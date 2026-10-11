# -*- coding: utf-8 -*-
"""环节5：审核环节。先逆向（校验缺失拒放行、错误状态机操作被拒），再正向放行全部 20 条。
DB 验证：available + review_due_at=+90天 + pass 留痕含四项 checks。"""
import json, os, sys, sqlite3, datetime
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok, expect_fail

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
mapping = json.load(open(os.path.join(os.path.dirname(__file__), 'step4_mapping.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []

GOOD_CHECKS_KP = {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass', 'notes': '自动流程抽检通过'}
GOOD_CHECKS_DOC = {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass', 'notes': '自动流程抽检通过'}

# ---- 逆向1：四项校验缺一不可（kp 缺 judgeable）----
first_kp = next(m for m in mapping if m['content_type'] == 'kp')
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/kp/{first_kp['kb_id']}/pass",
                         {'checks': {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}},
                         token, keyword='可判定性')
print(f"[逆向1 OK] kp 缺 judgeable 被拒: {str(resp)[:80]}")

# ---- 逆向2：存在 fail 项不能放行 ----
first_doc = next(m for m in mapping if m['content_type'] == 'doc')
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/doc/{first_doc['kb_id']}/pass",
                         {'checks': {'correctness': 'fail', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}},
                         token, keyword='未通过')
print(f"[逆向2 OK] correctness=fail 被拒: {str(resp)[:80]}")

# ---- 逆向3：doc 线 judgeable 必须 na ----
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/doc/{first_doc['kb_id']}/pass",
                         {'checks': {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}},
                         token, keyword='na')
print(f"[逆向3 OK] doc judgeable=pass 被拒: {str(resp)[:80]}")

# ---- 逆向4：对 available 内容重复 pass 被拒（先放行一条再试）----
code, _ = call('POST', f"/api/admin/kb/reviews/doc/{first_doc['kb_id']}/pass",
               {'checks': GOOD_CHECKS_DOC}, token)
assert code == 200, f"正向放行失败 {code}"
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/doc/{first_doc['kb_id']}/pass",
                         {'checks': GOOD_CHECKS_DOC}, token, keyword='审核队列')
print(f"[逆向4 OK] 重复放行被拒: {str(resp)[:80]}")

# ---- 逆向5：reject 意见必填 ----
target = next(m for m in mapping if m['content_type'] == 'kp' and m['kb_id'] != first_kp['kb_id'])
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/kp/{target['kb_id']}/reject", {'comment': ''}, token, keyword='审核意见')
print(f"[逆向5 OK] 退回空意见被拒: {str(resp)[:80]}")

# ---- 逆向6：对 available 内容 reject 被拒（状态机）----
code, resp = expect_fail('POST', f"/api/admin/kb/reviews/doc/{first_doc['kb_id']}/reject", {'comment': 'x'}, token, keyword='审核队列')
print(f"[逆向6 OK] available 不可退回: {str(resp)[:80]}")

# ---- 正向：放行剩余全部 ----
today = datetime.date.today()
due_expect = (today + datetime.timedelta(days=90)).isoformat()
passed = []
for m in mapping:
    kb_id = m['kb_id']
    row = conn.execute(f"SELECT status FROM {'kb_doc' if m['content_type']=='doc' else 'kb_kp'} WHERE id=?", (kb_id,)).fetchone()
    if row['status'] == 'available':
        passed.append(m)  # 逆向4 已放行的
        continue
    checks = GOOD_CHECKS_DOC if m['content_type'] == 'doc' else GOOD_CHECKS_KP
    code, body = call('POST', f"/api/admin/kb/reviews/{m['content_type']}/{kb_id}/pass",
                      {'checks': checks, 'comment': '批量放行', 'dueDays': 90}, token)
    if code != 200:
        errors.append(f"{m['content_type']}#{kb_id} pass: {code} {str(body)[:150]}")
        continue
    passed.append(m)

# ---- DB 验证 ----
for m in passed:
    table = 'kb_doc' if m['content_type'] == 'doc' else 'kb_kp'
    row = conn.execute(f"SELECT status, review_due_at FROM {table} WHERE id=?", (m['kb_id'],)).fetchone()
    if row['status'] != 'available':
        errors.append(f"{table}#{m['kb_id']} 应 available，实际 {row['status']}")
    if row['review_due_at'] != due_expect:
        errors.append(f"{table}#{m['kb_id']} review_due_at 应 {due_expect}，实际 {row['review_due_at']}")
    rev = conn.execute(f"SELECT action, source, checks FROM kb_review WHERE content_type=? AND content_id=? ORDER BY id",
                       (m['content_type'], m['kb_id'])).fetchall()
    passes = [r for r in rev if r['action'] == 'pass']
    if not passes:
        errors.append(f"{table}#{m['kb_id']} 无 pass 留痕")
    else:
        c = json.loads(passes[-1]['checks'])
        if c.get('correctness') != 'pass' or c.get('infringement') != 'pass' or c.get('quality') != 'pass':
            errors.append(f"{table}#{m['kb_id']} pass 留痕 checks 不完整")

conn.close()
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"\n[DB-OK] {len(passed)}/20 条内容放行：available + review_due_at(+90天={due_expect}) + pass 留痕完整")
