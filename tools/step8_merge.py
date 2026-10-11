# -*- coding: utf-8 -*-
"""环节8：同位合并全流程。
1) 直达新建 2 条同位 kp（同 MySQL 主题）：凑齐 3 份
2) merge/validate（含逆向：1份被拒、4份被拒、跨主题被拒）
3) merge/ai（真实 LLM 一键合并 → MergeResp 产物不落库）
4) merge/kp/submit（产物 pending 新行 + merge_sources + source=merge 留痕）
5) 审核放行 → 源标 merged + merged_into 指向产物 + freq_coverage 转入
6) peers 不再显示源；恢复源 → draft（merged 可恢复）
DB 逐步验证。
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
GOOD_KP = {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}

tid_mysql = conn.execute("SELECT id FROM kb_topic WHERE domain_code='database' AND name='MySQL'").fetchone()['id']
kp4 = 4  # mysql-suoyin-15（已有，available，freq_manual=8）

# 给 kp4 加频率标定（合并覆盖度转入的验证基础）
ok('PUT', f'/api/admin/kb/kp/{kp4}', {'freqManual': 8}, token=token)

# ---- 1) 直达新建 2 条同位（幂等：已存在同标题可用 kp 直接复用） ----
made = []
for title, summ, body_md, diff in [
    ('MySQL 为什么选择 B+ 树作为索引结构',
     '对比 B 树、哈希与 B+ 树，从磁盘 IO、扇出、范围查询与叶子链表角度解释 MySQL 索引选型 B+ 树的原因。',
     '# MySQL 为什么选择 B+ 树\n\nB+ 树非叶子节点只存键，扇出更大树更矮，减少磁盘 IO；叶子节点成有序链表，范围查询与排序友好；哈希不支持范围，B 树数据分散在各层。',
     'mid'),
    ('MySQL 索引什么时候会失效',
     '总结函数运算、隐式类型转换、前导模糊、OR 与非索引列组合等导致索引失效的常见场景与最左前缀原则。',
     "# MySQL 索引失效\n\n对索引列用函数或运算、隐式类型转换、like '%x' 前导模糊、OR 连接非索引列、违反最左前缀组合都会让优化器放弃走索引。",
     'mid'),
]:
    exist = conn.execute(
        "SELECT id FROM kb_kp WHERE title=? AND status='available' AND merge_sources IS NULL ORDER BY id DESC LIMIT 1",
        (title,)).fetchone()
    if exist:
        made.append(exist['id'])
        print(f"[OK] 同位 kp#{exist['id']} 已存在，直接复用: {title}")
        continue
    code, resp = call('POST', '/api/admin/kb/direct-submit', {
        'contentType': 'kp', 'domainCode': 'database', 'topicId': tid_mysql,
        'title': title, 'summary': summ, 'contentMd': body_md, 'difficulty': diff}, token)
    if code != 200:
        print(f"[FAIL] 直提: {code} {str(resp)[:200]}"); sys.exit(1)
    kid = resp['id']
    ok('POST', f'/api/admin/kb/reviews/kp/{kid}/pass', {'checks': GOOD_KP}, token=token)
    made.append(kid)
    print(f"[OK] 同位 kp#{kid} 创建并放行: {title}")

kp_a, kp_b = kp4, made[0]
kp_c = made[1]

# ---- 2) validate 逆向 ----
code, resp = expect_fail('POST', '/api/admin/kb/merge/kp/validate', {'ids': [kp_a]}, token, keyword='两份')
print(f"[OK] M-逆向1 单份不可合并: {resp.get('message')}")
code, resp = expect_fail('POST', '/api/admin/kb/merge/kp/validate', {'ids': [kp_a, kp_b, kp_c, 11]}, token, keyword='三份')
print(f"[OK] M-逆向2 超三份被拒: {resp.get('message')}")
# 跨主题：kp11 在 java/集合框架
code, resp = expect_fail('POST', '/api/admin/kb/merge/kp/validate', {'ids': [kp_a, 11]}, token, keyword='主题')
print(f"[OK] M-逆向3 跨主题被拒: {resp.get('message')}")

# ---- 3) AI 一键合并 ----
code, val = call('POST', '/api/admin/kb/merge/kp/validate', {'ids': [kp_a, kp_b, kp_c]}, token)
if code != 200:
    print(f"[FAIL] validate: {code} {str(val)[:200]}"); sys.exit(1)
print(f"[OK] M1 validate 3 份语料: {[v['title'] for v in val]}")

code, product = call('POST', f'/api/admin/kb/merge/ai?type=kp', {'ids': [kp_a, kp_b, kp_c]}, token, timeout=300)
if code != 200:
    print(f"[FAIL] aiMerge: {code} {str(product)[:300]}"); sys.exit(1)
print(f"[OK] M2 AI 合并产物: title={product.get('title')}")
print(f"     变体 {len(product.get('variants') or [])} 条, 追问 {len(product.get('followups') or [])} 层, "
      f"评分要点 {len(product.get('scoringPoints') or [])} 条, 样例 {len(product.get('answerSamples') or [])} 档, "
      f"难度={product.get('difficulty')}, 矛盾点 {len(product.get('conflicts') or [])} 条")
# 产物不落库验证
row = conn.execute("SELECT COUNT(*) c FROM kb_kp WHERE title=?", (product.get('title'),)).fetchone()
if row['c'] > 0:
    errors.append("M2 AI 合并产物竟已落库（应回人手里）")
else:
    print("[OK] M2-验证 AI 合并产物未落库（回人手里）")
# 人工拍板难度（AI 建议可能为空）
if not product.get('difficulty'):
    product['difficulty'] = 'mid'
    print("[OK] M2-人工 产物难度人工拍板为 mid")

# ---- 4) 产物提交（pending 新行 + merge_sources） ----
code, resp = call('POST', '/api/admin/kb/merge/kp/submit', {'ids': [kp_a, kp_b, kp_c], 'product': product}, token)
if code != 200:
    print(f"[FAIL] submitKpMerge: {code} {str(resp)[:300]}"); sys.exit(1)
product_id = resp['id']
row = conn.execute("SELECT status, merge_sources, summary FROM kb_kp WHERE id=?", (product_id,)).fetchone()
src_ids = json.loads(row['merge_sources'])
if row['status'] != 'pending' or sorted(src_ids) != sorted([kp_a, kp_b, kp_c]):
    errors.append(f"M3 产物落库不符: status={row['status']}, merge_sources={src_ids}")
else:
    print(f"[OK] M3 产物 kp#{product_id} pending，merge_sources={src_ids}")
rev = conn.execute("SELECT action, source FROM kb_review WHERE content_type='kp' AND content_id=? ORDER BY id", (product_id,)).fetchall()
if not any(r['action'] == 'submit' and r['source'] == 'merge' for r in rev):
    errors.append(f"M3 产物审核留痕缺 submit/merge: {[dict(r) for r in rev]}")
else:
    print("[OK] M3-验证 审核留痕 action=submit source=merge")

# 追问层 id 已由服务端分配
row = conn.execute("SELECT followups, scoring_points FROM kb_kp WHERE id=?", (product_id,)).fetchone()
fus = json.loads(row['followups'])
sps = json.loads(row['scoring_points'])
if fus and all(f['id'].startswith('fu-') for f in fus) and sps and all(p['id'].startswith('sp-') for p in sps):
    print(f"[OK] M3-验证 追问层/评分要点服务端分配稳定 id（fu-*/sp-*）")
else:
    errors.append(f"M3 稳定 id 分配异常: fus={[f.get('id') for f in fus]}")

# ---- 5) 放行 → 源标 merged + 覆盖度转入 ----
ok('POST', f'/api/admin/kb/reviews/kp/{product_id}/pass', {'checks': GOOD_KP, 'comment': '合并产物放行'}, token=token)
rows = {i: conn.execute("SELECT status, merged_into_id, freq_manual, freq_signal, freq_coverage FROM kb_kp WHERE id=?", (i,)).fetchone()
        for i in [kp_a, kp_b, kp_c, product_id]}
prod = rows[product_id]
expect_cov = (rows[kp_a]['freq_manual'] + rows[kp_a]['freq_signal'] + rows[kp_a]['freq_coverage']
              + rows[kp_b]['freq_manual'] + rows[kp_b]['freq_signal'] + rows[kp_b]['freq_coverage']
              + rows[kp_c]['freq_manual'] + rows[kp_c]['freq_signal'] + rows[kp_c]['freq_coverage'])
okall = True
for i in [kp_a, kp_b, kp_c]:
    r = rows[i]
    if r['status'] != 'merged' or r['merged_into_id'] != product_id:
        errors.append(f"M4 源 kp#{i} 应 merged→{product_id}，实际 {r['status']}/{r['merged_into_id']}")
        okall = False
if prod['freq_coverage'] != expect_cov:
    errors.append(f"M4 覆盖度转入不符: 期望 {expect_cov}，实际 {prod['freq_coverage']}")
    okall = False
if okall:
    print(f"[OK] M4 放行后 3 源标 merged→{product_id}，覆盖度转入 freq_coverage={prod['freq_coverage']}（8+0+0 ×3源）")

# peers 不再显示源
code, peers = call('GET', f'/api/admin/kb/peers?topicId={tid_mysql}&type=kp', token=token)
peer_ids = [p['id'] for p in peers]
if any(i in peer_ids for i in [kp_a, kp_b, kp_c]) or product_id not in peer_ids:
    errors.append(f"M5 peers 不符: {peer_ids}")
else:
    print(f"[OK] M5 同位清单只剩产物 kp#{product_id}，源已退出（{peer_ids}）")

# ---- 6) 恢复源（merged → draft） ----
ok('POST', f'/api/admin/kb/kp/{kp_b}/restore', None, token=token)
r = conn.execute("SELECT status, merged_into_id FROM kb_kp WHERE id=?", (kp_b,)).fetchone()
if r['status'] != 'draft' or r['merged_into_id'] is not None:
    errors.append(f"M6 恢复不符: {dict(r)}")
else:
    print(f"[OK] M6 源 kp#{kp_b} 恢复 → draft（merged_into 清空），需重审")
# 恢复的 draft 重新放行回可用（保持数据整洁）
ok('POST', f'/api/admin/kb/reviews/kp/{kp_b}/resubmit', {}, token=token)
ok('POST', f'/api/admin/kb/reviews/kp/{kp_b}/pass', {'checks': GOOD_KP, 'comment': '恢复后重审'}, token=token)
print(f"[OK] M7 源 kp#{kp_b} 恢复-重提-放行 → available")

conn.close()
json.dump({'product_id': product_id, 'sources': [kp_a, kp_b, kp_c]},
          open(os.path.join(os.path.dirname(__file__), 'step8_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 环节8 同位合并全流程验证通过")
