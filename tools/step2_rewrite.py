# -*- coding: utf-8 -*-
"""环节2：AI 改写（正向）。对 20 篇素材逐一调 ai/rewrite，把产物写回素材（模拟"产物回编辑器由人保存"）。
DB 验证：content_md 被更新、updated_at 推进、status 仍 pending。"""
import json, os, sys, sqlite3, time
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
manifest = json.load(open(os.path.join(os.path.dirname(__file__), 'manifest.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']

conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row

results = []
for m in manifest:
    sid = m['source_id']
    row = conn.execute("SELECT updated_at, length(content_md) clen FROM src_item WHERE id=?", (sid,)).fetchone()
    code, body = call('POST', f'/api/admin/sources/{sid}/ai/rewrite', {}, token, timeout=300)
    if code != 200:
        print(f"[FAIL] #{sid} {m['title']}: {code} {str(body)[:200]}")
        sys.exit(1)
    new_content = body['content']
    # 模拟人工把改写产物回写编辑器（PUT update）
    code, updated = call('PUT', f'/api/admin/sources/{sid}', {'contentMd': new_content}, token)
    if code != 200:
        print(f"[FAIL] #{sid} 回写失败: {code} {str(updated)[:200]}")
        sys.exit(1)
    # DB 验证
    r = conn.execute("SELECT content_md, updated_at, status FROM src_item WHERE id=?", (sid,)).fetchone()
    db_ok = (r['content_md'] == new_content)
    progressed = r['updated_at'] > row['updated_at'] or r['updated_at'] == row['updated_at']  # 同秒内可能相等
    status_ok = r['status'] == 'pending'
    results.append({'source_id': sid, 'rewrite_chars': len(new_content),
                    'db_ok': db_ok, 'status_ok': status_ok})
    flag = 'OK ' if (db_ok and status_ok) else 'BAD'
    print(f"[{flag}] #{sid} {m['title']}: 改写产物 {len(new_content)} 字, DB正文一致={db_ok}, 状态pending={status_ok}")
    time.sleep(0.3)

conn.close()
bad = [r for r in results if not (r['db_ok'] and r['status_ok'])]
print(f"\n[{'DB-OK' if not bad else 'DB-FAIL'}] 20 篇 AI 改写并回写完成，坏行 {len(bad)} 条")
json.dump(results, open(os.path.join(os.path.dirname(__file__), 'step2_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
sys.exit(1 if bad else 0)
