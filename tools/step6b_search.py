# -*- coding: utf-8 -*-
"""环节6补：搜索/目录/peers 验证（step6 前半已通过）。"""
import json, os, sys, sqlite3
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
mapping = json.load(open(os.path.join(os.path.dirname(__file__), 'step4_mapping.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
by_source = {m['source_id']: m for m in mapping}
kp4 = by_source[18]['kb_id']
spring_doc = by_source[10]
spring_kp_id = conn.execute("SELECT kp_id FROM kb_doc WHERE id=?", (spring_doc['kb_id'],)).fetchone()['kp_id']

code, hits = call('GET', '/api/admin/kb/search?keyword=索引', token=token)
kp_hits = [h for h in hits if h['contentType'] == 'kp' and h['id'] == kp4]
if not kp_hits:
    errors.append("搜索「索引」未命中 kp#4")
else:
    print(f"[OK] 搜索「索引」命中 kp#{kp4}")

code, hits = call('GET', '/api/admin/kb/search?keyword=生命周期&type=doc', token=token)
if not any(h['id'] == spring_doc['kb_id'] for h in hits):
    errors.append("搜索「生命周期」未命中 doc#6")
else:
    print(f"[OK] 搜索「生命周期」命中 doc#{spring_doc['kb_id']}")

topic_id = spring_doc['topic_id']
code, items = call('GET', f'/api/admin/kb/contents?topicId={topic_id}', token=token)
if code != 200 or not items:
    errors.append(f"目录主题 {topic_id} 内容为空")
else:
    print(f"[OK] 目录主题#{topic_id} 共 {len(items)} 条内容")

code, peers = call('GET', f'/api/admin/kb/peers?topicId={topic_id}', token=token)
ids = [p['id'] for p in peers if p['contentType'] == 'doc']
if spring_doc['kb_id'] not in ids:
    errors.append("peers 未包含 doc#6")
else:
    print(f"[OK] peers 含 doc#{spring_doc['kb_id']}（同位清单）")

conn.close()
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
json.dump({'spring_kp_id': spring_kp_id, 'kp4_id': kp4},
          open(os.path.join(os.path.dirname(__file__), 'step6_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
print("\n[DB-OK] 环节6 搜索/目录/peers 全部验证通过")
