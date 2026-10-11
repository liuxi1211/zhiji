# -*- coding: utf-8 -*-
"""环节8补充：修复验证。
Bug #4：MyBatis-Plus updateById 默认跳过 null 字段 → restore() 的 setMergedIntoId(null)
被静默丢弃，恢复后 merged_into_id 残留。修复：KbKp/KbDoc.mergedIntoId 加
@TableField(updateStrategy = FieldStrategy.ALWAYS)。

验证项：
R1 kp 恢复：merged → draft 且 merged_into_id 清空（kp#16，上轮 M4 已标 merged）
R2 kp 恢复后重提放行 → available
D1 doc 同位合并（人工参照手写产物，不耗 LLM）：validate 逆向（单份/跨主题被拒）
D2 doc 产物 pending + merge_sources + source=merge 留痕
D3 doc 产物放行 → 2 源标 merged、merged_into 指向产物
D4 doc 源恢复 → draft 且 merged_into_id 清空（doc 侧同款修复验证）
D5 doc 源恢复后重提放行 → available
"""
import json, os, sys, sqlite3
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok, expect_fail

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
GOOD = {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}
GOOD_DOC = {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}

# ---- R1/R2 kp 恢复（bug #4 修复验证） ----
r = conn.execute("SELECT status, merged_into_id FROM kb_kp WHERE id=16").fetchone()
if r['status'] != 'merged':
    print(f"[SKIP] kp#16 状态 {r['status']}，非 merged（上轮 M4 应已标 merged）")
    sys.exit(1)
ok('POST', '/api/admin/kb/kp/16/restore', None, token=token)
r = conn.execute("SELECT status, merged_into_id FROM kb_kp WHERE id=16").fetchone()
if r['status'] != 'draft' or r['merged_into_id'] is not None:
    errors.append(f"R1 kp#16 恢复后应为 draft/NULL，实际 {dict(r)}")
else:
    print("[OK] R1 kp#16 恢复 → draft，merged_into_id 已清空（Bug#4 修复生效）")
ok('POST', '/api/admin/kb/reviews/kp/16/resubmit', {}, token=token)
ok('POST', '/api/admin/kb/reviews/kp/16/pass', {'checks': GOOD, 'comment': '恢复后重审'}, token=token)
r = conn.execute("SELECT status FROM kb_kp WHERE id=16").fetchone()
if r['status'] != 'available':
    errors.append(f"R2 kp#16 应 available，实际 {r['status']}")
else:
    print("[OK] R2 kp#16 恢复-重提-放行 → available")

# ---- D1-D5 doc 同位合并（人工产物）+ 恢复 ----
tid = conn.execute("SELECT id FROM kb_topic WHERE domain_code='engineering' AND name='CI/CD'").fetchone()['id']
docs = []
for title, body in [
    ('【流程验证】CI 流水线的阶段划分与失败快反',
     '# CI 流水线\n\n提交即构建：lint、单测、打包三阶段串行，失败即刻阻断合并并通知责任人，快反是 CI 的核心价值。'),
    ('【流程验证】CD 的发布策略：滚动/蓝绿/金丝雀怎么选',
     '# CD 发布策略\n\n滚动省资源但回滚慢；蓝绿切换快但双倍资源；金丝雀按流量渐进放量，兼顾风险与成本，是多数团队默认解。'),
]:
    exist = conn.execute("SELECT id FROM kb_doc WHERE title=? AND status IN ('available','merged') ORDER BY id DESC LIMIT 1", (title,)).fetchone()
    if exist:
        docs.append(exist['id'])
        continue
    code, resp = call('POST', '/api/admin/kb/direct-submit', {
        'contentType': 'doc', 'domainCode': 'engineering', 'topicId': tid,
        'title': title, 'summary': title, 'contentMd': body, 'difficulty': 'mid'}, token)
    if code != 200:
        print(f"[FAIL] doc 直提: {code} {str(resp)[:200]}"); sys.exit(1)
    did = resp['id']
    ok('POST', f'/api/admin/kb/reviews/doc/{did}/pass', {'checks': GOOD_DOC, 'comment': '测试文章放行'}, token=token)
    docs.append(did)
doc_a, doc_b = docs
print(f"[OK] D0 doc 语料: doc#{doc_a}, doc#{doc_b}")

code, resp = expect_fail('POST', '/api/admin/kb/merge/doc/validate', {'ids': [doc_a]}, token, keyword='两份')
print(f"[OK] D1-逆向1 doc 单份被拒: {resp.get('message')}")
code, resp = expect_fail('POST', '/api/admin/kb/merge/doc/validate', {'ids': [doc_a, 12]}, token, keyword='主题')
print(f"[OK] D1-逆向2 doc 跨主题被拒: {resp.get('message')}")

product = {
    'title': '【流程验证·合并产物】CI/CD 流水线与发布策略',
    'summary': '合并产物：CI 阶段划分失败快反 + CD 三种发布策略选型。',
    'contentMd': '# CI/CD\n\nCI：lint-单测-打包串行，失败阻断。CD：滚动/蓝绿/金丝雀，按资源与回滚诉求选型。',
    'difficulty': 'mid',
}
code, resp = call('POST', '/api/admin/kb/merge/doc/submit', {'ids': [doc_a, doc_b], 'product': product}, token)
if code != 200:
    print(f"[FAIL] submitDocMerge: {code} {str(resp)[:300]}"); sys.exit(1)
pid = resp['id']
row = conn.execute("SELECT status, merge_sources FROM kb_doc WHERE id=?", (pid,)).fetchone()
src_ids = json.loads(row['merge_sources'])
if row['status'] != 'pending' or sorted(src_ids) != sorted([doc_a, doc_b]):
    errors.append(f"D2 doc 产物落库不符: status={row['status']}, merge_sources={src_ids}")
else:
    print(f"[OK] D2 doc 产物 doc#{pid} pending，merge_sources={src_ids}")
rev = conn.execute("SELECT action, source FROM kb_review WHERE content_type='doc' AND content_id=? ORDER BY id", (pid,)).fetchall()
if not any(x['action'] == 'submit' and x['source'] == 'merge' for x in rev):
    errors.append(f"D2 doc 产物审核留痕缺 submit/merge: {[dict(x) for x in rev]}")
else:
    print("[OK] D2-验证 审核留痕 action=submit source=merge")

ok('POST', f'/api/admin/kb/reviews/doc/{pid}/pass', {'checks': GOOD_DOC, 'comment': 'doc 合并产物放行'}, token=token)
for i in [doc_a, doc_b]:
    r = conn.execute("SELECT status, merged_into_id FROM kb_doc WHERE id=?", (i,)).fetchone()
    if r['status'] != 'merged' or r['merged_into_id'] != pid:
        errors.append(f"D3 源 doc#{i} 应 merged→{pid}，实际 {dict(r)}")
r = conn.execute("SELECT status FROM kb_doc WHERE id=?", (pid,)).fetchone()
if r['status'] != 'available':
    errors.append(f"D3 产物 doc#{pid} 应 available，实际 {r['status']}")
if not errors or all(not e.startswith('D3') for e in errors):
    print(f"[OK] D3 放行后 2 源标 merged→doc#{pid}，产物 available")

ok('POST', f'/api/admin/kb/doc/{doc_a}/restore', None, token=token)
r = conn.execute("SELECT status, merged_into_id FROM kb_doc WHERE id=?", (doc_a,)).fetchone()
if r['status'] != 'draft' or r['merged_into_id'] is not None:
    errors.append(f"D4 doc#{doc_a} 恢复后应为 draft/NULL，实际 {dict(r)}")
else:
    print("[OK] D4 doc#%d 恢复 → draft，merged_into_id 已清空（doc 侧修复生效）" % doc_a)
ok('POST', f'/api/admin/kb/reviews/doc/{doc_a}/resubmit', {}, token=token)
ok('POST', f'/api/admin/kb/reviews/doc/{doc_a}/pass', {'checks': GOOD_DOC, 'comment': '恢复后重审'}, token=token)
r = conn.execute("SELECT status FROM kb_doc WHERE id=?", (doc_a,)).fetchone()
if r['status'] != 'available':
    errors.append(f"D5 doc#{doc_a} 应 available，实际 {r['status']}")
else:
    print(f"[OK] D5 doc#{doc_a} 恢复-重提-放行 → available")

conn.close()
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 环节8补充验证通过（kp/doc 恢复清空 merged_into_id + doc 人工合并全流程）")
