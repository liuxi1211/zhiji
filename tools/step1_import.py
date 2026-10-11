# -*- coding: utf-8 -*-
"""环节1：批量导入20篇素材（md导入入口）。导入后立即在DB验证行数与字段。"""
import json, os, sys, sqlite3
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, login

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'docs', '题库源'))
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))

token = login()

picked = json.load(open(os.path.join(os.path.dirname(__file__), 'picked.json'), encoding='utf-8'))
manifest = []

for kind in ['picked_articles', 'picked_questions']:
    for it in picked[kind]:
        full = os.path.join(ROOT, it['path'])
        with open(full, encoding='utf-8', errors='ignore') as f:
            content = f.read()
        # 导入文件名作为标题（title 取文件名，与 importFile 语义一致）
        title = os.path.splitext(os.path.basename(it['path']))[0]
        code, body = call('POST', '/api/admin/sources/import', token=token, files={
            'file': (title + '.md', content.encode('utf-8'), 'text/markdown')})
        if code != 200:
            print(f"[FAIL] 导入 {it['path']}: {code} {str(body)[:200]}")
            sys.exit(1)
        manifest.append({
            'path': it['path'], 'kind': 'article' if kind == 'picked_articles' else 'question',
            'source_id': body['id'], 'title': body['title'],
            'orig_chars': len(content),
        })
        print(f"[OK] 导入 #{body['id']}: {body['title']} ({len(content)} 字) <- {it['path']}")

json.dump(manifest, open(os.path.join(os.path.dirname(__file__), 'manifest.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)

# ---- DB 验证 ----
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
ids = [str(m['source_id']) for m in manifest]
rows = conn.execute(
    f"SELECT id, title, source_type, status, length(content_md) AS clen FROM src_item WHERE id IN ({','.join(ids)})"
).fetchall()
assert len(rows) == 20, f"DB行数应为20，实际 {len(rows)}"
errors = []
for m in manifest:
    row = next((r for r in rows if r['id'] == m['source_id']), None)
    if row is None:
        errors.append(f"缺行 #{m['source_id']}")
        continue
    if row['source_type'] != 'import':
        errors.append(f"#{row['id']} source_type 应为 import，实际 {row['source_type']}")
    if row['status'] != 'pending':
        errors.append(f"#{row['id']} status 应为 pending，实际 {row['status']}")
    # SQLite length() 对 TEXT 计字符数；与 Python len(content) 对比
    if abs(row['clen'] - m['orig_chars']) > 2:
        errors.append(f"#{row['id']} 正文长度 {row['clen']} 与导入内容 {m['orig_chars']} 不符")
    if not row['title']:
        errors.append(f"#{row['id']} 标题为空")
conn.close()

if errors:
    print("[DB-FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"\n[DB-OK] 20 篇素材全部落库：source_type=import、status=pending、正文长度一致")
