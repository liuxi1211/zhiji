# -*- coding: utf-8 -*-
"""
一次性批量脚本：清理测试脏数据 + 把题库源目录下所有文本文件灌进素材库。

做什么（只做这两件事，不走业务逻辑）：
  1. 备份当前 SQLite 库到 data/zhiji.db.bak-<时间戳>；
  2. 清空知识库（kb_*）与素材库（src_item），并连带清理硬依赖 kb_kp 的下游测试数据
     （judge_session / judge_answer / learn_state），避免留下悬空外键行；
  3. 遍历 docs/题库源，把所有 .md/.txt/.markdown 文本按「导入」口径写进 src_item：
     title=文件名(去扩展名)、source_md=原文、source_type=import、status=pending、created_by=1(admin)；
     正文里的本地图片引用同时改写为公共图片库地址 /api/assets/{sha256}.{ext}。

图片处理（与后端 MediaAssetService / SourceService 同口径）：
  - 图片按「内容寻址」写入 {data-dir}/assets/media/{sha256 前2位}/{sha256}{ext}（256 个分片桶，同图自动去重）；
  - 正文里指向本地图片的相对/绝对路径引用改写为公共地址（无鉴权、任何人可查）；
  - 外链（http/https）、data: 与非图片引用一律保持原样；
  - 引用在磁盘上找不到的，引用原样保留（缺图交给人工补，不静默删）。

图片是公共资源（与素材行解耦），所以清理时只清 assets/media 里的存量，不再按素材 id 建目录。

用法：
    python tools/import_all_sources.py
"""
import hashlib
import os
import re
import shutil
import sqlite3
import sys
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
SRC_ROOT = os.path.join(ROOT, 'docs', '题库源')
DATA_DIR = os.path.join(ROOT, 'data')
DB = os.path.join(DATA_DIR, 'zhiji.db')
MEDIA_DIR = os.path.join(DATA_DIR, 'assets', 'media')

TEXT_EXTS = ('.md', '.markdown', '.txt')
IMAGE_EXTS = ('.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg')
STAFF_ID = 1  # admin（sys_staff.id）
PUBLIC_PREFIX = '/api/assets/'

MD_IMAGE = re.compile(r'!\[([^\]]*)]\(([^)]+)\)')
HTML_IMG_SRC = re.compile(r'(<img\b[^>]*\bsrc\s*=\s*["\'])([^"\']+)(["\'])', re.IGNORECASE)

# 清理顺序：先子后父，避免外键约束报错
CLEAR_TABLES = [
    'judge_answer',    # 依赖 judge_session
    'learn_state',     # 依赖 judge_session / kb_kp
    'judge_session',   # 依赖 kb_kp
    'kb_review',       # 依赖 kb_kp / kb_doc
    'kb_kp',
    'kb_doc',
    'kb_topic',
    'src_item',        # 素材库
]


def backup_db():
    if not os.path.exists(DB):
        print(f'[FAIL] 找不到数据库：{DB}')
        sys.exit(1)
    stamp = datetime.now().strftime('%Y%m%d%H%M%S')
    bak = os.path.join(DATA_DIR, f'zhiji.db.bak-{stamp}')
    shutil.copy2(DB, bak)
    print(f'[备份] {bak}')
    return bak


def clear_data(conn):
    print('\n=== 清理脏数据 ===')
    for t in CLEAR_TABLES:
        try:
            n = conn.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0]
        except sqlite3.OperationalError:
            print(f'  {t}: 表不存在，跳过')
            continue
        conn.execute(f'DELETE FROM {t}')
        print(f'  {t}: 删除 {n} 行')
    conn.execute("DELETE FROM sqlite_sequence WHERE name IN (%s)"
                 % ','.join('?' * len(CLEAR_TABLES)), CLEAR_TABLES)
    conn.commit()


def clear_media():
    print('\n=== 清理公共图片库 ===')
    for rel in ('assets/media', 'assets/source', 'source-assets'):
        d = os.path.join(DATA_DIR, rel)
        if os.path.isdir(d):
            shutil.rmtree(d)
            print(f'  已删除 {d}')
        else:
            print(f'  {d} 不存在，跳过')


def build_image_index():
    """建立图片索引：basename → [绝对路径]，并统计磁盘图片总数。"""
    by_base = {}
    total = 0
    for dp, dn, fn in os.walk(SRC_ROOT):
        for f in fn:
            if f.lower().endswith(IMAGE_EXTS):
                total += 1
                by_base.setdefault(f, []).append(os.path.join(dp, f))
    print(f'图片索引：{total} 张（去重 basename {len(by_base)} 个）')
    return by_base


def common_prefix_len(a, b):
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def resolve_image(doc_path, ref, by_base):
    """把正文里的本地图片引用解析为磁盘文件；解析不到返回 None。"""
    ref_path = ref.split('#')[0].split('?')[0]
    base = os.path.basename(ref_path.replace('\\', '/'))
    if not base:
        return None
    doc_dir = os.path.dirname(doc_path)
    # 1) 相对文档目录
    cand = os.path.normpath(os.path.join(doc_dir, ref_path))
    if os.path.isfile(cand):
        return cand
    # 2) 相对题库源根目录（去掉前导 /）
    cand = os.path.normpath(os.path.join(SRC_ROOT, ref_path.lstrip('/\\')))
    if os.path.isfile(cand):
        return cand
    # 3) 全局 basename 兜底：同 basename 多份时取与文档路径公共前缀最长的一张
    hits = by_base.get(base)
    if not hits:
        return None
    if len(hits) == 1:
        return hits[0]
    return max(hits, key=lambda p: common_prefix_len(os.path.dirname(p), doc_dir))


def store_image(path):
    """图片内容寻址入库，返回公共文件名（幂等）；非法扩展名/超限返回 None。

    落盘按内容哈希前 2 位分片：{data-dir}/assets/media/{前2位}/{sha256}{ext}，
    与后端 MediaAssetService 同口径（对外地址仍是 /api/assets/{sha256}{ext}）。
    """
    ext = os.path.splitext(path)[1].lower()
    if ext not in IMAGE_EXTS:
        return None
    data = open(path, 'rb').read()
    if not data or len(data) > 10 * 1024 * 1024:
        return None
    sha = hashlib.sha256(data).hexdigest()
    name = sha + ext
    target = os.path.join(MEDIA_DIR, sha[:2], name)
    if not os.path.exists(target):
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as f:
            f.write(data)
    return name


def rewrite_refs(text, doc_path, by_base, stats):
    """改写正文里的本地图片引用为公共地址（外链/未命中保持原样）。"""

    def convert(ref):
        ref = ref.strip()
        lower = ref.lower()
        if (not ref or lower.startswith(('http://', 'https://', 'data:', 'asset:', PUBLIC_PREFIX))):
            return None
        # Markdown 可能带标题部分，如  x.png "标题"
        ref = ref.split(' ')[0]
        resolved = resolve_image(doc_path, ref, by_base)
        if not resolved:
            stats['missing'] += 1
            return None
        name = store_image(resolved)
        if not name:
            return None
        stats['rewritten'] += 1
        return PUBLIC_PREFIX + name

    def replace_md(m):
        url = convert(m.group(2))
        return None if url is None else f'![{m.group(1)}]({url})'

    def replace_html(m):
        url = convert(m.group(2))
        return None if url is None else m.group(1) + url + m.group(3)

    text = MD_IMAGE.sub(lambda m: replace_md(m) or m.group(0), text)
    text = HTML_IMG_SRC.sub(lambda m: replace_html(m) or m.group(0), text)
    return text


def collect_text_files():
    files = []
    for dirpath, dirnames, filenames in os.walk(SRC_ROOT):
        for fn in filenames:
            if fn.lower().endswith(TEXT_EXTS):
                files.append(os.path.join(dirpath, fn))
    files.sort()
    return files


def import_files(conn, by_base):
    files = collect_text_files()
    print(f'\n=== 导入素材：{len(files)} 个文本文件 ===')
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    stats = {'rewritten': 0, 'missing': 0}
    rows = []
    for full in files:
        title = os.path.splitext(os.path.basename(full))[0]
        try:
            with open(full, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
        except Exception as e:
            print(f'[跳过] 读取失败 {full}: {e}')
            continue
        content = rewrite_refs(content, full, by_base, stats)
        rows.append((title, content, 'import', 'pending', STAFF_ID, now, now))
    conn.executemany(
        'INSERT INTO src_item '
        '(title, content_md, source_type, status, created_by, created_at, updated_at) '
        'VALUES (?, ?, ?, ?, ?, ?, ?)',
        rows,
    )
    conn.commit()
    print(f'[完成] 写入 {len(rows)} 行；图片引用改写 {stats["rewritten"]} 处，'
          f'未命中(引用保留) {stats["missing"]} 处')
    return len(rows)


def main():
    print(f'题库源目录：{SRC_ROOT}')
    print(f'数据库：{DB}')
    backup_db()

    by_base = build_image_index()
    conn = sqlite3.connect(DB)
    try:
        clear_data(conn)
        clear_media()
        written = import_files(conn, by_base)

        print('\n=== 校验 ===')
        total = conn.execute('SELECT COUNT(*) FROM src_item').fetchone()[0]
        print(f'src_item 总数：{total}（本次写入 {written}）')
        for t in ('kb_topic', 'kb_kp', 'kb_doc', 'kb_review',
                  'judge_session', 'judge_answer', 'learn_state'):
            n = conn.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0]
            print(f'{t}: {n}')
        for st, status, c in conn.execute(
                'SELECT source_type, status, COUNT(*) FROM src_item GROUP BY source_type, status'):
            print(f'  {st} / {status}: {c}')
        with_img = conn.execute(
            "SELECT COUNT(*) FROM src_item WHERE content_md LIKE '%/api/assets/%'").fetchone()[0]
        print(f'含公共图片引用的素材：{with_img} 条')
        media_count = sum(len(fs) for _, _, fs in os.walk(MEDIA_DIR)) if os.path.isdir(MEDIA_DIR) else 0
        shards = len(os.listdir(MEDIA_DIR)) if os.path.isdir(MEDIA_DIR) else 0
        print(f'公共图片库文件数：{media_count}（分片目录 {shards} 个）')
        conn.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    finally:
        conn.close()

    print('\n[OK] 清理 + 导入完成。素材库已就绪，可在系统里走后续流程。')


if __name__ == '__main__':
    main()
