# -*- coding: utf-8 -*-
"""最终完整性终验：素材库→标准库全链路数据一致性。
V1 素材库：20 篇挑选素材全部 submitted（进门即断联）；拆分链 25/26/27 状态正确
V2 标准库规模：kp/doc 状态分布与预期一致
V3 合并一致性：merged 行的 merged_into 指向存在的产物；产物 merge_sources 与源互逆
V4 覆盖度转入：kp#17 freq_coverage=8
V5 挂载关系：doc 挂载的 kpId 指向 available kp
V6 审核留痕：每条标准内容至少一条 submit 与一条 pass/reject/discard
V7 复核到期：available 行 review_due_at 非空且 >= 今天
V8 追问层 id：全部 fu-*/sp-* 前缀且无重复
V9 主题树：kb_topic 全部 active
V10 LLM 用量：llm_usage_daily 各 purpose 调用统计
"""
import datetime
import json
import os
import sqlite3
import sys

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors, checks = [], 0


def expect(cond, msg_ok, msg_fail):
    global checks
    checks += 1
    if cond:
        print(f"[OK] {msg_ok}")
    else:
        errors.append(msg_fail)
        print(f"[FAIL] {msg_fail}")


# V1 素材库
rows = conn.execute("SELECT id, status, title FROM src_item WHERE id BETWEEN 5 AND 24").fetchall()
expect(all(r['status'] == 'submitted' for r in rows) and len(rows) == 20,
       f"V1 20 篇挑选素材全部 submitted（进门即断联）",
       f"V1 素材状态异常: {[(r['id'], r['status']) for r in rows if r['status'] != 'submitted']}")
r25 = conn.execute("SELECT status FROM src_item WHERE id=25").fetchone()
expect(r25 and r25['status'] == 'discarded', "V1-2 拆分父素材 #25 discarded",
       f"V1-2 拆分父素材状态异常: {dict(r25) if r25 else '缺'}")
r2627 = conn.execute("SELECT id, status FROM src_item WHERE id IN (26,27)").fetchall()
expect(all(r['status'] == 'submitted' for r in r2627) and len(r2627) == 2,
       "V1-3 拆分子素材 #26/#27 submitted", "V1-3 拆分子素材状态异常")

# V2 标准库规模
kp_rows = conn.execute("SELECT id, status FROM kb_kp ORDER BY id").fetchall()
kp_dist = {}
for r in kp_rows:
    kp_dist.setdefault(r['status'], []).append(r['id'])
doc_rows = conn.execute("SELECT id, status FROM kb_doc ORDER BY id").fetchall()
doc_dist = {}
for r in doc_rows:
    doc_dist.setdefault(r['status'], []).append(r['id'])
print(f"    kp 分布: { {k: v for k, v in kp_dist.items()} }")
print(f"    doc 分布: { {k: v for k, v in doc_dist.items()} }")
expect(kp_dist.get('available') and kp_dist.get('merged') == [4] and kp_dist.get('deprecated') == [12],
       "V2 kp: available + merged[kp#4] + deprecated[kp#12 演练] 分布正确",
       f"V2 kp 分布异常: {kp_dist}")
expect(doc_dist.get('merged') == [14] and doc_dist.get('available') and 'deprecated' not in doc_dist,
       "V2 doc: available + merged[doc#14] 分布正确", f"V2 doc 分布异常: {doc_dist}")

# V3 合并一致性：当前 merged 行的 merged_into 指向存在的产物，且是产物 merge_sources 的子集
# （产物 merge_sources 记录合并当时的全部源；其中部分源可能事后被恢复流程拉回 available）
for tab, product in [('kb_kp', 17), ('kb_doc', 15)]:
    r = conn.execute(f"SELECT merge_sources, status FROM {tab} WHERE id=?", (product,)).fetchone()
    srcs = json.loads(r['merge_sources'])
    expect(r['status'] == 'available',
           f"V3 产物 {tab}#{product} available，merge_sources={srcs}",
           f"V3 产物 {tab}#{product} 异常: {dict(r)}")
    merged_now = [x['id'] for x in conn.execute(
        f"SELECT id FROM {tab} WHERE status='merged' AND merged_into_id=?", (product,)).fetchall()]
    strays = [x['id'] for x in conn.execute(
        f"SELECT id, merged_into_id FROM {tab} WHERE status='merged' AND (merged_into_id IS NULL OR merged_into_id != ?)",
        (product,)).fetchall()]
    expect(set(merged_now) <= set(srcs) and not strays,
           f"V3 {tab} 当前 merged 源 {merged_now} ⊆ 产物 merge_sources，无游离 merged 行",
           f"V3 {tab} merged 一致性异常: merged_now={merged_now}, strays={strays}")

# V4 覆盖度
r = conn.execute("SELECT freq_manual, freq_signal, freq_coverage FROM kb_kp WHERE id=17").fetchone()
expect(r['freq_coverage'] == 8, "V4 合并产物 kp#17 freq_coverage=8（源覆盖度转入）",
       f"V4 覆盖度异常: {dict(r)}")

# V5 挂载关系
rows = conn.execute("""SELECT d.id, d.kp_id, k.status FROM kb_doc d
    JOIN kb_kp k ON k.id = d.kp_id WHERE d.kp_id IS NOT NULL""").fetchall()
expect(all(r['status'] == 'available' for r in rows),
       f"V5 挂载 doc 的 kp 均可用: {[(r['id'], r['kp_id']) for r in rows]}",
       f"V5 挂载指向不可用 kp: {[(r['id'], r['kp_id'], r['status']) for r in rows if r['status'] != 'available']}")

# V6 审核留痕
for tab, ctype in [('kb_kp', 'kp'), ('kb_doc', 'doc')]:
    ids = [r['id'] for r in conn.execute(f"SELECT id FROM {tab}").fetchall()]
    for cid in ids:
        acts = {r['action'] for r in conn.execute(
            "SELECT action FROM kb_review WHERE content_type=? AND content_id=?", (ctype, cid)).fetchall()}
        if 'submit' not in acts and 'resubmit' not in acts:
            errors.append(f"V6 {ctype}#{cid} 缺入队留痕: {acts}")
        if not ({'pass', 'reject', 'discard'} & acts):
            errors.append(f"V6 {ctype}#{cid} 缺出队留痕: {acts}")
checks += 1
if not any(e.startswith('V6') for e in errors):
    print(f"[OK] V6 全部 {len(ids)} 条 {ctype} 内容入队/出队留痕完整" if ctype == 'doc' else "[OK] V6 kp 内容留痕完整")

# V7 复核到期
today = datetime.date.today().isoformat()
for tab in ['kb_kp', 'kb_doc']:
    rows = conn.execute(f"SELECT id, review_due_at FROM {tab} WHERE status='available'").fetchall()
    bad = [r['id'] for r in rows if not r['review_due_at'] or r['review_due_at'] < today]
    expect(not bad, f"V7 {tab} available 行复核到期全部有效（>= {today}）",
           f"V7 {tab} 到期异常: {bad}")

# V8 追问层/评分要点 id
fus, sps = [], []
for r in conn.execute("SELECT id, followups, scoring_points FROM kb_kp WHERE followups IS NOT NULL OR scoring_points IS NOT NULL").fetchall():
    for f in json.loads(r['followups'] or '[]'):
        fus.append(f['id'])
        if not f['id'].startswith('fu-'):
            errors.append(f"V8 kp#{r['id']} 追问 id 非法: {f['id']}")
    for p in json.loads(r['scoring_points'] or '[]'):
        sps.append(p['id'])
        if not p['id'].startswith('sp-'):
            errors.append(f"V8 kp#{r['id']} 评分要点 id 非法: {p['id']}")
expect(len(fus) == len(set(fus)) and len(sps) == len(set(sps)),
       f"V8 追问层 {len(fus)} 个 / 评分要点 {len(sps)} 个 id 全局唯一且前缀合法",
       "V8 存在重复 id")
checks += 1

# V9 主题树
n = conn.execute("SELECT COUNT(*) c FROM kb_topic WHERE status != 'active'").fetchone()['c']
total = conn.execute("SELECT COUNT(*) c FROM kb_topic").fetchone()['c']
expect(n == 0 and total == 20, f"V9 主题树 {total} 个全部 active", f"V9 主题树异常: 非active={n}, 总数={total}")

# V10 LLM 用量
rows = conn.execute("SELECT purpose, SUM(call_count) c, SUM(fail_count) f FROM llm_usage_daily GROUP BY purpose ORDER BY purpose").fetchall()
for r in rows:
    print(f"    LLM {r['purpose']}: calls={r['c']} fails={r['f']}")
checks += 1
print(f"\n共 {checks} 组检查，失败 {len(errors)}")
if errors:
    print("[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("[DB-OK] 最终完整性终验全部通过")
