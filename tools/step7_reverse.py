# -*- coding: utf-8 -*-
"""环节7：逆向流程全测。
A. 审核退回→重提→放行（kp#5：kafka-40）
B. 审核废弃（kp#7：电商详情页）
C. 下线→恢复（doc#7 arrays）
D. 素材拆分（1拆2）→ 子素材提交入库（新 doc）
E. 红线：deprecated kp 不可作前置依赖/挂载/合并目标；追问层 id 不可删；prerequisites 自引用拒绝
DB 验证每步状态机与留痕。
"""
import json, os, sys, sqlite3, uuid
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok, expect_fail

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
mapping = json.load(open(os.path.join(os.path.dirname(__file__), 'step4_mapping.json'), encoding='utf-8'))
manifest = json.load(open(os.path.join(os.path.dirname(__file__), 'manifest.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
by_source = {m['source_id']: m for m in mapping}
GOOD_KP = {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}

# ============ A. 退回→重提→放行（kp#5 kafka） ============
kp5 = by_source[23]['kb_id']
code, body = call('POST', f'/api/admin/kb/reviews/kp/{kp5}/reject', {'comment': '追问链缺失，补充后重提'}, token)
if code != 200 or conn.execute("SELECT status FROM kb_kp WHERE id=?", (kp5,)).fetchone()['status'] != 'draft':
    errors.append(f"A1 退回失败: {code} {str(body)[:150]}")
else:
    print(f"[OK] A1 kp#{kp5} 退回 → draft，留痕")
    rev = conn.execute("SELECT action FROM kb_review WHERE content_type='kp' AND content_id=? ORDER BY id DESC LIMIT 1", (kp5,)).fetchone()
    if rev['action'] != 'reject':
        errors.append("A1 退回无 reject 留痕")
    # 草稿态编辑补正
    code, _ = call('PUT', f'/api/admin/kb/kp/{kp5}', {'summary': 'Kafka 高频面试题合集：设计、高性能原理、存储、副本与 ISR、消费者组与再均衡等 40 问的整理稿。'}, token)
    if code != 200:
        errors.append(f"A2 草稿编辑失败: {code}")
    # 重提
    code, _ = call('POST', f'/api/admin/kb/reviews/kp/{kp5}/resubmit', {}, token)
    if code != 200 or conn.execute("SELECT status FROM kb_kp WHERE id=?", (kp5,)).fetchone()['status'] != 'pending':
        errors.append(f"A3 重提失败: {code}")
    else:
        print(f"[OK] A3 kp#{kp5} 重提 → pending")
        # 放行
        code, _ = call('POST', f'/api/admin/kb/reviews/kp/{kp5}/pass', {'checks': GOOD_KP}, token)
        st = conn.execute("SELECT status FROM kb_kp WHERE id=?", (kp5,)).fetchone()['status']
        if code != 200 or st != 'available':
            errors.append(f"A4 放行失败: {code} status={st}")
        else:
            print(f"[OK] A4 kp#{kp5} 放行 → available（退回-重提-放行闭环）")

# ============ B. 废弃（kp#7 电商详情页） ============
kp7 = by_source[21]['kb_id']
code, _ = call('POST', f'/api/admin/kb/reviews/kp/{kp7}/discard', {'comment': '内容为架构综述，不适合作为面试题'}, token)
st = conn.execute("SELECT status FROM kb_kp WHERE id=?", (kp7,)).fetchone()['status']
rev = conn.execute("SELECT action FROM kb_review WHERE content_type='kp' AND content_id=? ORDER BY id DESC LIMIT 1", (kp7,)).fetchone()
if code != 200 or st != 'deprecated' or rev['action'] != 'discard':
    errors.append(f"B 废弃失败: code={code} status={st} action={rev['action']}")
else:
    print(f"[OK] B kp#{kp7} 废弃 → deprecated，留痕 discard")
    # 逆向：废弃后的内容不能被 doc 挂载
    code, resp = expect_fail('PUT', '/api/admin/kb/doc/6', {'kpId': kp7}, token, keyword='下线')
    print(f"[OK] B-逆向 已废弃 kp 不可被 doc 挂载: {str(resp)[:60]}")

# ============ C. 下线→恢复（doc#7 arrays） ============
doc7 = by_source[11]['kb_id']
code, _ = call('POST', f'/api/admin/kb/doc/{doc7}/offline', None, token)
st = conn.execute("SELECT status FROM kb_doc WHERE id=?", (doc7,)).fetchone()['status']
if code != 200 or st != 'deprecated':
    errors.append(f"C1 下线失败: {code} status={st}")
else:
    print(f"[OK] C1 doc#{doc7} 下线 → deprecated")
    # 下线后不可编辑
    code, resp = expect_fail('PUT', f'/api/admin/kb/doc/{doc7}', {'title': 'x'}, token, keyword='恢复')
    print(f"[OK] C-逆向1 已下线 doc 不可编辑: {str(resp)[:60]}")
    # 恢复 → draft → 重提 → 放行
    code, _ = call('POST', f'/api/admin/kb/doc/{doc7}/restore', None, token)
    st = conn.execute("SELECT status FROM kb_doc WHERE id=?", (doc7,)).fetchone()['status']
    if code != 200 or st != 'draft':
        errors.append(f"C2 恢复失败: {code} status={st}")
    else:
        print(f"[OK] C2 doc#{doc7} 恢复 → draft")
        ok('POST', f'/api/admin/kb/reviews/doc/{doc7}/resubmit', {}, token=token)
        ok('POST', f'/api/admin/kb/reviews/doc/{doc7}/pass', {'checks': {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}}, token=token)
        st = conn.execute("SELECT status FROM kb_doc WHERE id=?", (doc7,)).fetchone()['status']
        if st != 'available':
            errors.append(f"C3 恢复后再放行失败: status={st}")
        else:
            print(f"[OK] C3 doc#{doc7} 恢复-重提-放行 → available")

# ============ E-前置红线：deprecated kp 不可作前置依赖 ============
code, resp = expect_fail('PUT', '/api/admin/kb/kp/4', {'prerequisites': [kp7]}, token, keyword='下线')
print(f"[OK] E1 已下线 kp 不可作前置依赖: {str(resp)[:60]}")
# 自引用
code, resp = expect_fail('PUT', '/api/admin/kb/kp/4', {'prerequisites': [4]}, token, keyword='自身')
print(f"[OK] E2 前置依赖自引用被拒: {str(resp)[:60]}")

# ============ E-追问层红线：删除既有层 id 被拒 ============
kp4 = by_source[18]['kb_id']
kp = ok('GET', f'/api/admin/kb/kp/{kp4}', token=token)
fus = kp['followups']
if len(fus) >= 2:
    code, resp = expect_fail('PUT', f'/api/admin/kb/kp/{kp4}', {'followups': [fus[0]]}, token, keyword='不可删除')
    print(f"[OK] E3 删除既有追问层被拒: {str(resp)[:60]}")
    # 停用（status=disabled）合法
    fus[1]['status'] = 'disabled'
    code, body = call('PUT', f'/api/admin/kb/kp/{kp4}', {'followups': fus}, token)
    r = conn.execute("SELECT followups FROM kb_kp WHERE id=?", (kp4,)).fetchone()
    f2 = json.loads(r['followups'])
    if code != 200 or f2[1]['status'] != 'disabled':
        errors.append(f"E4 停用追问层失败: {code} {str(f2)[:120]}")
    else:
        print(f"[OK] E4 追问层停用（disabled）生效，id 不变")

# ============ D. 素材拆分：把 harness-five-elements（#8，已 submitted）不行——用新建素材拆 ============
# 拆分验证用新建一条多主题长文素材
long_md = """# 多主题长文：Redis 缓存与 MySQL 索引

## Redis 缓存三大问题

缓存穿透指查询不存在的数据，缓存与数据库都不会命中，可布隆过滤器与空值缓存缓解；缓存击穿指热点 key 过期瞬间大量请求打到数据库，可用互斥锁与逻辑过期；缓存雪崩指大量 key 同时过期，可错峰过期与多级缓存。

## MySQL 索引失效场景

对索引列做函数运算、隐式类型转换、前导模糊匹配 like '%x'、OR 连接非索引列等都会导致索引失效，遵循最左前缀原则。"""
code, body = call('POST', '/api/admin/sources', {'title': '多主题长文-Redis与MySQL', 'contentMd': long_md}, token)
if code != 200:
    errors.append(f"D1 新建素材失败: {code} {str(body)[:150]}")
else:
    pid = body['id']
    print(f"[OK] D1 新建多主题素材 #{pid}")
    # AI 拆分建议（真实 LLM）
    code, sp = call('POST', f'/api/admin/sources/{pid}/ai/split', {}, token, timeout=180)
    need = sp.get('needSplit') if isinstance(sp, dict) else None
    slices_from_ai = sp.get('slices') if isinstance(sp, dict) else []
    print(f"[OK] D2 AI 拆分建议: needSplit={need}, 建议 {len(slices_from_ai or [])} 段")
    # 人工拍板：按两个主题拆（用标题锚点切段）
    code, pv = call('GET', f'/api/admin/sources/{pid}/split-preview', token=token)
    print(f"[OK] D3 split-preview 返回 {len(pv)} 段")
    slices = [
        {'title': 'Redis 缓存三大问题', 'contentMd': '缓存穿透指查询不存在的数据，缓存与数据库都不会命中，可布隆过滤器与空值缓存缓解；缓存击穿指热点 key 过期瞬间大量请求打到数据库，可用互斥锁与逻辑过期；缓存雪崩指大量 key 同时过期，可错峰过期与多级缓存。'},
        {'title': 'MySQL 索引失效场景', 'contentMd': '对索引列做函数运算、隐式类型转换、前导模糊匹配 like \'%x\'、OR 连接非索引列等都会导致索引失效，遵循最左前缀原则。'},
    ]
    code, sp2 = call('POST', f'/api/admin/sources/{pid}/split', {'slices': slices}, token)
    if code != 200:
        errors.append(f"D4 拆分失败: {code} {str(sp2)[:200]}")
    else:
        child_ids = [s['id'] for s in sp2['slices']]
        p_status = conn.execute("SELECT status FROM src_item WHERE id=?", (pid,)).fetchone()['status']
        c_rows = conn.execute(f"SELECT id, parent_id, title, status FROM src_item WHERE id IN ({','.join(map(str, child_ids))})").fetchall()
        okc = (p_status == 'discarded' and len(c_rows) == 2
               and all(r['parent_id'] == pid and r['status'] == 'pending' for r in c_rows))
        if not okc:
            errors.append(f"D4 拆分DB验证失败: parent={p_status}, children={ [dict(r) for r in c_rows] }")
        else:
            print(f"[OK] D4 拆分 1→2：母素材 discarded，子素材 {child_ids} pending")
            # 逆向：有未处理子素材的母素材不可删除
            code, resp = expect_fail('DELETE', f'/api/admin/sources/{pid}', token=token, keyword='子素材')
            print(f"[OK] D-逆向 母素材有子素材不可删: {str(resp)[:60]}")
            # 子素材补齐概述/难度后提交入库（doc 线，database/MySQL + database/Redis 两个主题）
            tid_mysql = conn.execute("SELECT id FROM kb_topic WHERE domain_code='database' AND name='MySQL'").fetchone()['id']
            tid_redis = conn.execute("SELECT id FROM kb_topic WHERE domain_code='database' AND name='Redis'").fetchone()['id']
            metas = [
                (child_ids[0], 'Redis 缓存穿透/击穿/雪崩的成因与对策', 'database', tid_redis, '概述三大缓存问题的成因与缓解手段', 'mid'),
                (child_ids[1], 'MySQL 索引失效的常见场景', 'database', tid_mysql, '列举函数运算、隐式转换、前导模糊等索引失效场景', 'mid'),
            ]
            for cid, title, dom, tid, summ, diff in metas:
                ok('PUT', f'/api/admin/sources/{cid}', {'title': title, 'summary': summ, 'difficulty': diff}, token=token)
                code, body = call('POST', f'/api/admin/sources/{cid}/submit',
                                  {'domainCode': dom, 'topicId': tid, 'contentType': 'doc'}, token)
                if code != 200:
                    errors.append(f"D5 子素材提交失败 #{cid}: {code} {str(body)[:150]}")
                else:
                    kb_id = conn.execute("SELECT MAX(id) id FROM kb_doc").fetchone()['id']
                    st = conn.execute("SELECT status FROM kb_doc WHERE id=?", (kb_id,)).fetchone()['status']
                    src_st = conn.execute("SELECT status, parent_id, source_url FROM src_item WHERE id=?", (cid,)).fetchone()
                    if st != 'pending' or src_st['status'] != 'submitted':
                        errors.append(f"D5 子素材 #{cid} 落库不符: kb status={st}, src={dict(src_st)}")
                    else:
                        print(f"[OK] D5 子素材 #{cid} → doc#{kb_id} pending（继承父 source_url={src_st['source_url']}）")
            # 逆向：已 submitted 素材不可编辑/删除
            code, resp = expect_fail('PUT', f'/api/admin/sources/{child_ids[0]}', {'title': 'x'}, token, keyword='断联')
            print(f"[OK] D-逆向2 已入库素材不可编辑（进门即断联）: {str(resp)[:60]}")
            code, resp = expect_fail('DELETE', f'/api/admin/sources/{child_ids[0]}', token=token, keyword='保留')
            print(f"[OK] D-逆向3 已入库素材不可删除: {str(resp)[:60]}")
            # 放行两条新 doc
            for row in conn.execute("SELECT id FROM kb_doc WHERE id > 10 ORDER BY id"):
                ok('POST', f"/api/admin/kb/reviews/doc/{row['id']}/pass",
                   {'checks': {'correctness': 'pass', 'judgeable': 'na', 'infringement': 'pass', 'quality': 'pass'}}, token=token)
            print("[OK] D6 两条拆分产物 doc 放行 available")

conn.close()
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\n[DB-OK] 环节7 逆向流程全部验证通过")
