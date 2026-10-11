# -*- coding: utf-8 -*-
"""环节7b：补齐逆向验证（step7 因脚本假设错误中断的部分）。
1) 红线：deprecated kp#12 不可被 doc 挂载 / 不可作前置依赖 / 自引用被拒 / 不可参与合并
2) 追问层：删除既有层 id 被拒；停用 disabled 生效且 id 不变
3) 素材拆分：新建多主题长文 → AI 拆分建议 → split 1拆2 → 母discarded子pending →
   子素材补元数据提交入库 → 已入库素材不可编辑/删除 → 新 doc 放行
每步 DB 验证。
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
out = {}

# ============ 1) 红线验证（用 deprecated 的 kp#12） ============
st12 = conn.execute("SELECT status FROM kb_kp WHERE id=12").fetchone()['status']
assert st12 == 'deprecated', f"kp12 应为 deprecated，实际 {st12}"

code, resp = expect_fail('PUT', '/api/admin/kb/doc/6', {'kpId': 12}, token, keyword='下线')
print(f"[OK] R1 已下线 kp 不可被 doc 挂载: {resp.get('message')}")

code, resp = expect_fail('PUT', '/api/admin/kb/kp/4', {'prerequisites': [12]}, token, keyword='下线')
print(f"[OK] R2 已下线 kp 不可作前置依赖: {resp.get('message')}")
r = conn.execute("SELECT prerequisites FROM kb_kp WHERE id=4").fetchone()
if '12' in (r['prerequisites'] or ''):
    errors.append("R2 失败后 kp4.prerequisites 竟含 12")

code, resp = expect_fail('PUT', '/api/admin/kb/kp/4', {'prerequisites': [4]}, token, keyword='自身')
print(f"[OK] R3 前置依赖自引用被拒: {resp.get('message')}")

code, resp = expect_fail('POST', '/api/admin/kb/merge/kp/validate', {'ids': [4, 12]}, token, keyword='下线')
print(f"[OK] R4 已下线 kp 不可参与合并: {resp.get('message')}")

# ============ 2) 追问层红线 ============
kp4 = 4
kp = ok('GET', f'/api/admin/kb/kp/{kp4}', token=token)
fus = kp['followups']
assert len(fus) == 2, f"kp4 应有 2 个追问层，实际 {len(fus)}"
code, resp = expect_fail('PUT', f'/api/admin/kb/kp/{kp4}', {'followups': [fus[0]]}, token, keyword='不可删除')
print(f"[OK] R5 删除既有追问层被拒: {resp.get('message')}")
fus[1]['status'] = 'disabled'
code, body = call('PUT', f'/api/admin/kb/kp/{kp4}', {'followups': fus}, token)
f2 = json.loads(conn.execute("SELECT followups FROM kb_kp WHERE id=?", (kp4,)).fetchone()['followups'])
if code != 200 or f2[1]['status'] != 'disabled' or f2[1]['id'] != fus[1]['id'] or f2[0]['id'] != fus[0]['id']:
    errors.append(f"R6 停用追问层落库不符: {f2}")
else:
    print(f"[OK] R6 追问层停用生效，id 保持不变: {[f['id'][:10] for f in f2]}")
    # 恢复 active
    fus[1]['status'] = 'active'
    ok('PUT', f'/api/admin/kb/kp/{kp4}', {'followups': fus}, token=token)
    print(f"[OK] R7 追问层重新启用")

# ============ 3) 素材拆分全流程 ============
long_md = """# 多主题长文：Redis 缓存与 MySQL 索引

## Redis 缓存三大问题

缓存穿透指查询不存在的数据，缓存与数据库都不会命中，可用布隆过滤器与空值缓存缓解；缓存击穿指热点 key 过期瞬间大量请求打到数据库，可用互斥锁与逻辑过期；缓存雪崩指大量 key 同时过期，可错峰过期与多级缓存。

## MySQL 索引失效场景

对索引列做函数运算、隐式类型转换、前导模糊匹配 like '%x'、OR 连接非索引列等都会导致索引失效，遵循最左前缀原则。"""
code, body = call('POST', '/api/admin/sources', {'title': '多主题长文-Redis与MySQL', 'contentMd': long_md}, token)
if code != 200:
    print(f"[FAIL] D1 新建素材: {code} {str(body)[:200]}"); sys.exit(1)
pid = body['id']
print(f"[OK] D1 新建多主题素材 #{pid}")

code, sp = call('POST', f'/api/admin/sources/{pid}/ai/split', {}, token, timeout=180)
if code != 200:
    print(f"[FAIL] D2 ai/split: {code} {str(sp)[:200]}"); sys.exit(1)
print(f"[OK] D2 AI 拆分建议: needSplit={sp.get('needSplit')}, 建议 {len(sp.get('slices') or [])} 段: "
      f"{[s.get('title') for s in (sp.get('slices') or [])]}")

code, pv = call('GET', f'/api/admin/sources/{pid}/split-preview', token=token)
print(f"[OK] D3 split-preview 返回 {len(pv)} 段锚点")

slices = [
    {'title': 'Redis 缓存穿透/击穿/雪崩', 'contentMd': '缓存穿透指查询不存在的数据，缓存与数据库都不会命中，可用布隆过滤器与空值缓存缓解；缓存击穿指热点 key 过期瞬间大量请求打到数据库，可用互斥锁与逻辑过期；缓存雪崩指大量 key 同时过期，可错峰过期与多级缓存。'},
    {'title': 'MySQL 索引失效场景', 'contentMd': "对索引列做函数运算、隐式类型转换、前导模糊匹配 like '%x'、OR 连接非索引列等都会导致索引失效，遵循最左前缀原则。"},
]
code, sp2 = call('POST', f'/api/admin/sources/{pid}/split', {'slices': slices}, token)
if code != 200:
    print(f"[FAIL] D4 split: {code} {str(sp2)[:200]}"); sys.exit(1)
child_ids = [s['id'] for s in sp2['slices']]
p = conn.execute("SELECT status FROM src_item WHERE id=?", (pid,)).fetchone()
cs = conn.execute(f"SELECT id, parent_id, title, status, source_type FROM src_item WHERE id IN ({','.join(map(str, child_ids))})").fetchall()
okc = (p['status'] == 'discarded' and len(cs) == 2
       and all(r['parent_id'] == pid and r['status'] == 'pending' for r in cs))
if not okc:
    errors.append(f"D4 拆分DB验证失败: parent={dict(p)}, children={[dict(r) for r in cs]}")
else:
    print(f"[OK] D4 拆分 1→2：母素材 #{pid} discarded，子素材 {child_ids} pending")

code, resp = expect_fail('DELETE', f'/api/admin/sources/{pid}', token=token, keyword='子素材')
print(f"[OK] D-逆向1 母素材有未处理子素材不可删: {resp.get('message')}")

tid_mysql = conn.execute("SELECT id FROM kb_topic WHERE domain_code='database' AND name='MySQL'").fetchone()['id']
tid_redis = conn.execute("SELECT id FROM kb_topic WHERE domain_code='database' AND name='Redis'").fetchone()['id']
metas = [
    (child_ids[0], 'Redis 缓存穿透、击穿、雪崩的成因与对策', 'database', tid_redis,
     '概述缓存三大问题的成因与缓解手段：布隆过滤器、互斥锁、逻辑过期、错峰过期与多级缓存。', 'mid'),
    (child_ids[1], 'MySQL 索引失效的常见场景', 'database', tid_mysql,
     '列举函数运算、隐式类型转换、前导模糊匹配、OR 非索引列等索引失效场景与最左前缀原则。', 'mid'),
]
new_docs = []
for cid, title, dom, tid, summ, diff in metas:
    ok('PUT', f'/api/admin/sources/{cid}', {'title': title, 'summary': summ, 'difficulty': diff}, token=token)
    code, body = call('POST', f'/api/admin/sources/{cid}/submit',
                      {'domainCode': dom, 'topicId': tid, 'contentType': 'doc'}, token)
    if code != 200:
        errors.append(f"D5 子素材 #{cid} 提交失败: {code} {str(body)[:150]}")
        continue
    kb_id = conn.execute("SELECT MAX(id) id FROM kb_doc").fetchone()['id']
    st = conn.execute("SELECT status FROM kb_doc WHERE id=?", (kb_id,)).fetchone()['status']
    src = conn.execute("SELECT status, parent_id, source_url, topic_name, domain_code FROM src_item WHERE id=?", (cid,)).fetchone()
    if st != 'pending' or src['status'] != 'submitted':
        errors.append(f"D5 子素材 #{cid} 落库不符: kb={st}, src={dict(src)}")
    else:
        new_docs.append(kb_id)
        print(f"[OK] D5 子素材 #{cid} → doc#{kb_id} pending（素材回填 topic={src['topic_name']}，source_url={src['source_url']}）")

code, resp = expect_fail('PUT', f'/api/admin/sources/{child_ids[0]}', {'title': 'x'}, token, keyword='断联')
print(f"[OK] D-逆向2 已入库素材不可编辑: {resp.get('message')}")
code, resp = expect_fail('DELETE', f'/api/admin/sources/{child_ids[0]}', token=token, keyword='保留')
print(f"[OK] D-逆向3 已入库素材不可删除: {resp.get('message')}")

for kb_id in new_docs:
    ok('POST', f'/api/admin/kb/reviews/doc/{kb_id}/pass',
       {'checks': {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'},
        'comment': '拆分子素材放行'}, token=token)
    st = conn.execute("SELECT status, review_due_at FROM kb_doc WHERE id=?", (kb_id,)).fetchone()
    if st['status'] != 'available' or not st['review_due_at']:
        errors.append(f"D6 doc#{kb_id} 放行落库不符: {dict(st)}")
    else:
        print(f"[OK] D6 doc#{kb_id} 放行 available，复核到期 {st['review_due_at']}")

conn.close()
out['new_docs'] = new_docs
json.dump(out, open(os.path.join(os.path.dirname(__file__), 'step7b_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 环节7b 红线/追问层/拆分 全部验证通过")
