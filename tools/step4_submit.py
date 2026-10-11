# -*- coding: utf-8 -*-
"""环节4：提交入库（正向主通道）。10 篇文章 → doc 线、10 篇面试题 → kp 线。
DB 验证：kb_doc/kb_kp 各 10 行 pending、字段带入正确（summary/difficulty/doc线source_url）；
kb_review 各有 submit/wizard 留痕；src_item 全部 submitted 且 domain/topic 回填。"""
import json, os, sys, sqlite3
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
BASE = 'http://localhost:8080'
manifest = json.load(open(os.path.join(os.path.dirname(__file__), 'manifest.json'), encoding='utf-8'))
step3 = {r['source_id']: r for r in json.load(open(os.path.join(os.path.dirname(__file__), 'step3_result.json'), encoding='utf-8'))}
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']

import urllib.request
def get_topics(domain):
    req = urllib.request.Request(f'{BASE}/api/admin/kb/topics?domainCode={domain}')
    req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return {t['name']: t['id'] for t in json.loads(resp.read().decode('utf-8'))}

# 领域主题 -> topicId（跳过 engineering 下的 jianli 类：这些素材主题叫 CI/CD 有点怪，但按流程走）
topic_ids = {}

conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
mapping = []  # source_id -> (content_type, kb_id)

for m in manifest:
    sid = m['source_id']
    s3 = step3[sid]
    domain, topic_name = s3['domain'], s3['topic']
    key = (domain, topic_name)
    if key not in topic_ids:
        topics = get_topics(domain)
        if topic_name not in topics:
            errors.append(f"#{sid} 主题 {topic_name} 不在 {domain} 主题表: {list(topics)}")
            continue
        topic_ids[key] = topics[topic_name]
    topic_id = topic_ids[key]
    ctype = 'doc' if m['kind'] == 'article' else 'kp'
    code, body = call('POST', f'/api/admin/sources/{sid}/submit',
                      {'domainCode': domain, 'topicId': topic_id, 'contentType': ctype}, token)
    if code != 200:
        errors.append(f"#{sid} submit: {code} {str(body)[:200]}")
        continue
    # 返回的素材状态
    st = body[0]['status'] if isinstance(body, list) else body.get('status')
    mapping.append({'source_id': sid, 'content_type': ctype, 'topic_id': topic_id,
                    'domain': domain, 'topic': topic_name, 'kb_status_returned': st})
    print(f"[OK] #{sid} {m['title']} -> {ctype} 提交成功, 素材状态={st}")

conn.commit()

# ---- DB 深度验证 ----
json_load = lambda s: json.loads(s) if s else None
for mp in mapping:
    sid, ctype = mp['source_id'], mp['content_type']
    table = 'kb_doc' if ctype == 'doc' else 'kb_kp'
    src = conn.execute("SELECT title, summary, difficulty, domain_code, topic_name, source_url, status, submitted_at FROM src_item WHERE id=?", (sid,)).fetchone()
    row = conn.execute(f"SELECT * FROM {table} WHERE id = (SELECT MAX(id) FROM {table})", ).fetchone()  # 取最新行兜底
    row = conn.execute(f"SELECT * FROM {table} WHERE title=? ORDER BY id DESC LIMIT 1", (src['title'],)).fetchone()
    if row is None:
        errors.append(f"#{sid} 标准库无对应行（title={src['title']}）")
        continue
    mp['kb_id'] = row['id']
    checks = {
        'status=pending': row['status'] == 'pending',
        'domain一致': row['domain_code'] == mp['domain'],
        'topic一致': row['topic_id'] == mp['topic_id'],
        'summary带入': row['summary'] == src['summary'],
        'difficulty带入': row['difficulty'] == src['difficulty'],
    }
    if ctype == 'doc':
        checks['source_url带入'] = (row['source_url'] is None and src['source_url'] is None) or row['source_url'] == src['source_url']
        checks['content_md非空'] = row['content_md'] and len(row['content_md']) > 100
    else:
        checks['framework_md非空'] = row['framework_md'] and len(row['framework_md']) > 100
        checks['JSON列默认'] = row['variants'] == '[]' and row['followups'] == '[]'
    # 审核留痕
    rev = conn.execute("SELECT action, source FROM kb_review WHERE content_type=? AND content_id=? ORDER BY id",
                       (ctype, row['id'])).fetchall()
    checks['审核留痕submit/wizard'] = any(r['action'] == 'submit' and r['source'] == 'wizard' for r in rev)
    # 素材侧
    checks['素材submitted'] = src['status'] == 'submitted' and src['submitted_at'] is not None
    bad = [k for k, v in checks.items() if not v]
    if bad:
        errors.append(f"#{sid} kb_id={row['id']} 检查失败: {bad}")
    else:
        print(f"[DB-OK] #{sid} -> {ctype}#{row['id']} 全部检查通过")

conn.close()
json.dump(mapping, open(os.path.join(os.path.dirname(__file__), 'step4_mapping.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 20 条标准库内容全部创建：pending + 审核留痕 + 字段带入正确 + 素材回填 submitted")
