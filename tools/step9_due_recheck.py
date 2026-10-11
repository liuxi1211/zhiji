# -*- coding: utf-8 -*-
"""环节9：复核到期（5.2：放行时设置到期时间，到期回审核队列复检）。
1) SQL 把 kp#9 / doc#5 的 review_due_at 改为过去（模拟时间流逝，应用无时间机器接口）
2) GET /reviews/due → 恰好包含这两条（其余 2027 到期的不应出现）
3) recheck kp#9 → pending + kb_review action=recheck source=recheck
4) pass kp#9（dueDays=30 显式）→ available + review_due_at=今天+30
5) doc#5 同样 recheck → pass（默认 90 天）
6) due 列表清空（两条都续期）
DB 逐步验证。
"""
import datetime
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
GOOD = {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}
GOOD_DOC = {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}
PAST = '2026-01-01'
today = datetime.date.today()

# ---- 1) 模拟到期 ----
conn.execute("UPDATE kb_kp SET review_due_at=? WHERE id=9", (PAST,))
conn.execute("UPDATE kb_doc SET review_due_at=? WHERE id=5", (PAST,))
conn.commit()
print(f"[OK] 1 模拟到期: kp#9 / doc#5 review_due_at={PAST}")

# ---- 2) due 列表 ----
code, due = call('GET', '/api/admin/kb/reviews/due', token=token)
if code != 200:
    print(f"[FAIL] due: {code} {str(due)[:200]}"); sys.exit(1)
pairs = {(d['contentType'], d['id']) for d in due}
if ('kp', 9) not in pairs or ('doc', 5) not in pairs:
    errors.append(f"2 due 列表缺 kp#9/doc#5: {sorted(pairs)}")
elif any(i in {(1, 9), ('doc', 5)} and False for _ in []):
    pass
else:
    # 未到期的（2027-01-09）不应出现
    unexpected = [p for p in pairs if p not in {('kp', 9), ('doc', 5)}]
    if unexpected:
        errors.append(f"2 due 列表混入未到期项: {unexpected}")
    else:
        print(f"[OK] 2 due 列表恰好 = {sorted(pairs)}（未到期项不出现）")

# ---- 3) recheck kp#9 ----
code, resp = call('POST', '/api/admin/kb/reviews/kp/9/recheck', {}, token)
if code != 200:
    print(f"[FAIL] recheck: {code} {str(resp)[:200]}"); sys.exit(1)
row = conn.execute("SELECT status, review_due_at FROM kb_kp WHERE id=9").fetchone()
if row['status'] != 'pending':
    errors.append(f"3 recheck 后 kp#9 应 pending，实际 {dict(row)}")
else:
    print(f"[OK] 3 kp#9 送复审 → pending（due={row['review_due_at']}，pass 时刷新）")
rev = conn.execute("SELECT action, source FROM kb_review WHERE content_type='kp' AND content_id=9 ORDER BY id DESC LIMIT 1").fetchone()
if rev['action'] != 'recheck' or rev['source'] != 'recheck':
    errors.append(f"3 留痕异常: {dict(rev)}")
else:
    print("[OK] 3-验证 审核留痕 action=recheck source=recheck")

# 逆向：recheck 只对 available；pending 再 recheck 应被拒
code, resp = call('POST', '/api/admin/kb/reviews/kp/9/recheck', {}, token)
if code == 200:
    errors.append("3-逆向 pending 内容再送复审应被拒")
else:
    print(f"[OK] 3-逆向 pending 再送复审被拒: {resp.get('message')}")

# ---- 4) pass kp#9 dueDays=30 ----
ok('POST', '/api/admin/kb/reviews/kp/9/pass', {'checks': GOOD, 'comment': '到期复审通过', 'dueDays': 30}, token=token)
row = conn.execute("SELECT status, review_due_at FROM kb_kp WHERE id=9").fetchone()
expect_due = (today + datetime.timedelta(days=30)).isoformat()
if row['status'] != 'available' or row['review_due_at'] != expect_due:
    errors.append(f"4 kp#9 复审放行不符: {dict(row)}，期望 due={expect_due}")
else:
    print(f"[OK] 4 kp#9 复审放行 → available，review_due_at={row['review_due_at']}（显式 30 天）")

# ---- 5) doc#5 recheck → pass（默认 90 天） ----
ok('POST', '/api/admin/kb/reviews/doc/5/recheck', {}, token)
ok('POST', '/api/admin/kb/reviews/doc/5/pass', {'checks': GOOD_DOC, 'comment': '到期复审通过'}, token)
row = conn.execute("SELECT status, review_due_at FROM kb_doc WHERE id=5").fetchone()
expect_due = (today + datetime.timedelta(days=90)).isoformat()
if row['status'] != 'available' or row['review_due_at'] != expect_due:
    errors.append(f"5 doc#5 复审放行不符: {dict(row)}，期望 due={expect_due}")
else:
    print(f"[OK] 5 doc#5 复审放行 → available，review_due_at={row['review_due_at']}（默认 90 天）")

# ---- 6) due 清空 ----
code, due = call('GET', '/api/admin/kb/reviews/due', token=token)
if any(d['id'] in (9,) and d['contentType'] == 'kp' for d in due) or any(d['id'] == 5 and d['contentType'] == 'doc' for d in due):
    errors.append(f"6 续期后 due 仍包含 kp#9/doc#5: {[(d['contentType'], d['id']) for d in due]}")
else:
    print(f"[OK] 6 续期后 due 列表 = {[(d['contentType'], d['id']) for d in due]}（kp#9/doc#5 已退出）")

conn.close()
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 环节9 复核到期全流程验证通过")
