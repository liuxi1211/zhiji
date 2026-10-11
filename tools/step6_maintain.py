# -*- coding: utf-8 -*-
"""环节6：标准库本体维护（知识库目录线，available 直接生效不重审）。
1) 编辑 kp#4（MySQL索引）：补全变体/追问链/评分要点/三档样例/职级规则/前置依赖/频率标定 —— 全结构
2) 编辑 kp#3（Hystrix隔离）：改题面+难度
3) doc#6（spring-bean-lifecycle）挂载 kp（Spring相关kp——用 kp#3 不合适；先给 kp#2 补充后挂载）
   实际挂载：doc#6 -> kp#2（大模型面试指南→不合适）。挂载验证用 doc#6 -> kp#4 也不合适。
   设计：把 doc#6（Spring Bean 生命周期，来自文章）挂到 kp#2？kp#2 是 ai-interview-guide。
   更合理：直接用直达新建建一条"Spring Bean 的生命周期"kp，再把 doc#6 挂上去。
4) 搜索/目录/peers 验证
DB 验证：JSON 列结构与提交一致、追问层 id 不变、updated_at 推进、挂载落库。
"""
import json, os, sys, sqlite3, uuid
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok, expect_fail

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
mapping = json.load(open(os.path.join(os.path.dirname(__file__), 'step4_mapping.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
errors = []
by_source = {m['source_id']: m for m in mapping}

# ============ 1) kp#4（mysql-suoyin-15，doc/kp id=4）全结构编辑 ============
kp4 = by_source[18]['kb_id']
kp = ok('GET', f'/api/admin/kb/kp/{kp4}', token=token)
fu1 = f'fu-{uuid.uuid4().hex[:12]}'
fu2 = f'fu-{uuid.uuid4().hex[:12]}'
sp1 = f'sp-{uuid.uuid4().hex[:12]}'
sp2 = f'sp-{uuid.uuid4().hex[:12]}'
sp3 = f'sp-{uuid.uuid4().hex[:12]}'
update = {
    'variants': [{'text': '什么是索引？'}, {'text': 'MySQL 索引有哪些数据结构？'}, {'text': '为什么用 B+ 树不用 B 树？'}],
    'followups': [
        {'id': fu1, 'layer': 1, 'direction': '深入', 'content': 'B+ 树和 B 树的区别是什么？为什么 MySQL 选择 B+ 树？', 'status': 'active'},
        {'id': fu2, 'layer': 2, 'direction': '深入', 'content': '聚簇索引和非聚簇索引在存储上的差异？回表是什么？', 'status': 'active'},
    ],
    'scoringPoints': [
        {'id': sp1, 'level': 'pass', 'content': '能说出索引是帮助 MySQL 高效获取数据的有序数据结构'},
        {'id': sp2, 'level': 'good', 'content': '能对比 B+ 树与哈希/B 树的优劣并解释为什么选 B+ 树'},
        {'id': sp3, 'level': 'excellent', 'content': '能讲清聚簇/非聚簇、回表、覆盖索引与最左前缀的工程权衡'},
    ],
    'answerSamples': [
        {'level': 'pass', 'content': '索引是数据库中帮助快速查找的数据结构，MySQL 常用 B+ 树实现，叶子节点存放数据或主键。'},
        {'level': 'good', 'content': '索引本质是有序数据结构。B+ 树矮胖、叶子成链，适合范围查询与磁盘 IO；哈希不支持范围，B 树非叶子也存数据导致扇出变小。'},
        {'level': 'excellent', 'content': '从磁盘 IO 与局部性原理讲起：B+ 树非叶子只存键使扇出更大、树更矮；叶子链表利于范围扫描；聚簇索引叶子存整行，二级索引存主键需要回表，覆盖索引可避免回表；再谈最左前缀与索引下推。'},
    ],
    'levelRules': [
        {'level': 'junior', 'required': True, 'stopLayer': 0},
        {'level': 'mid', 'required': True, 'stopLayer': 1},
        {'level': 'senior', 'required': True, 'stopLayer': 2},
        {'level': 'expert', 'required': False, 'stopLayer': 2},
    ],
    'freqManual': 8,
}
code, body = call('PUT', f'/api/admin/kb/kp/{kp4}', update, token)
if code != 200:
    print(f"[FAIL] kp4 编辑: {code} {str(body)[:300]}"); sys.exit(1)
# DB 验证
row = conn.execute("SELECT variants, followups, scoring_points, answer_samples, level_rules, freq_manual, status, updated_at FROM kb_kp WHERE id=?", (kp4,)).fetchone()
v = json.loads(row['variants']); f = json.loads(row['followups'])
sp = json.loads(row['scoring_points']); asamp = json.loads(row['answer_samples']); lr = json.loads(row['level_rules'])
checks = {
    'variants=3': len(v) == 3,
    'followups=2且id正确': len(f) == 2 and f[0]['id'] == fu1 and f[1]['id'] == fu2,
    'scoringPoints=3且档位': len(sp) == 3 and {p['level'] for p in sp} == {'pass', 'good', 'excellent'},
    'answerSamples=3档': len(asamp) == 3 and {a['level'] for a in asamp} == {'pass', 'good', 'excellent'},
    'levelRules=4档': len(lr) == 4 and lr[2]['stopLayer'] == 2,
    'freq_manual=8': row['freq_manual'] == 8,
    '编辑不重审仍available': row['status'] == 'available',
}
bad = [k for k, val in checks.items() if not val]
if bad:
    errors.append(f"kp#{kp4} 全结构编辑验证失败: {bad}")
else:
    print(f"[DB-OK] kp#{kp4} 全结构编辑落库：3变体/2追问/3评分要点/3样例/4职级规则/频率8，仍 available")
kp4_followups = [fu1, fu2]
kp4_sp = [sp1, sp2, sp3]

# ============ 2) kp#3 改题面+难度 ============
kp3 = by_source[17]['kb_id']
old = ok('GET', f'/api/admin/kb/kp/{kp3}', token=token)
code, body = call('PUT', f'/api/admin/kb/kp/{kp3}', {'title': 'Hystrix 线程池隔离与信号量隔离的区别与选型', 'difficulty': 'mid'}, token)
if code != 200:
    errors.append(f"kp#{kp3} 编辑失败: {code} {str(body)[:150]}")
else:
    r = conn.execute("SELECT title, difficulty, status FROM kb_kp WHERE id=?", (kp3,)).fetchone()
    if r['title'] == 'Hystrix 线程池隔离与信号量隔离的区别与选型' and r['difficulty'] == 'mid' and r['status'] == 'available':
        print(f"[DB-OK] kp#{kp3} 题面/难度编辑生效，仍 available")
    else:
        errors.append(f"kp#{kp3} 编辑落库不符: {dict(r)}")

# ============ 3) 直达新建一条 Spring kp，再把 doc#6 挂载上去 ============
spring_doc = by_source[10]  # doc#6 spring-bean-lifecycle
code, resp = call('POST', '/api/admin/kb/direct-submit', {
    'contentType': 'kp', 'domainCode': 'java', 'topicId': spring_doc['topic_id'],
    'title': 'Spring Bean 的生命周期有哪些阶段？',
    'summary': '考察 Spring Bean 从实例化到销毁的完整生命周期：实例化、属性填充、Aware 回调、BeanPostProcessor 前后处理、初始化、使用与销毁。',
    'contentMd': '# Spring Bean 生命周期\n\n实例化 → 属性填充 → Aware 回调 → BeanPostProcessor#before → InitializingBean/自定义 init → BeanPostProcessor#after → 使用 → 销毁回调。',
    'difficulty': 'mid'}, token)
if code != 200:
    errors.append(f"直达新建失败: {code} {str(resp)[:200]}")
else:
    spring_kp_id = resp['id']
    print(f"[OK] 直达新建 kp#{spring_kp_id}（Spring Bean 生命周期）pending")
    r = conn.execute("SELECT status, domain_code, topic_id FROM kb_kp WHERE id=?", (spring_kp_id,)).fetchone()
    if not (r['status'] == 'pending' and r['domain_code'] == 'java'):
        errors.append(f"kp#{spring_kp_id} 直提落库不符: {dict(r)}")
    # doc#6 挂载到这条 kp
    code, body = call('PUT', f"/api/admin/kb/doc/{spring_doc['kb_id']}", {'kpId': spring_kp_id}, token)
    if code != 200:
        errors.append(f"doc 挂载失败: {code} {str(body)[:150]}")
    else:
        r = conn.execute("SELECT kp_id FROM kb_doc WHERE id=?", (spring_doc['kb_id'],)).fetchone()
        if r['kp_id'] == spring_kp_id:
            print(f"[DB-OK] doc#{spring_doc['kb_id']} 挂载到 kp#{spring_kp_id}")
        else:
            errors.append(f"doc#{spring_doc['kb_id']} 挂载落库不符: kp_id={r['kp_id']}")
    # 直提的 kp 也要走审核放行（后面环节用）
    code, _ = call('POST', f'/api/admin/kb/reviews/kp/{spring_kp_id}/pass',
                   {'checks': {'correctness': 'pass', 'judgeable': 'pass', 'infringement': 'pass', 'quality': 'pass'}}, token)
    if code != 200:
        errors.append(f"kp#{spring_kp_id} 审核放行失败: {code}")

# ============ 4) 搜索/目录/peers ============
# 搜索：按关键词命中 kp
code, hits = call('GET', '/api/admin/kb/search?keyword=索引', token=token)
kp_hits = [h for h in hits if h['contentType'] == 'kp' and h['id'] == kp4]
if not kp_hits:
    errors.append("搜索「索引」未命中 kp#4")
else:
    print(f"[OK] 搜索「索引」命中 kp#{kp4}")
# 搜索：doc
code, hits = call('GET', '/api/admin/kb/search?keyword=生命周期&type=doc', token=token)
if not any(h['id'] == spring_doc['kb_id'] for h in hits):
    errors.append("搜索「生命周期」未命中 doc#6")
else:
    print(f"[OK] 搜索「生命周期」命中 doc#{spring_doc['kb_id']}")
# 目录：按主题列出
topic_id = spring_doc['topic_id']
code, items = call('GET', f'/api/admin/kb/contents?topicId={topic_id}', token=token)
if code != 200 or not items:
    errors.append(f"目录主题 {topic_id} 内容为空")
else:
    print(f"[OK] 目录主题#{topic_id} 共 {len(items)} 条内容")
# peers：同主题未下线
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
json.dump({'kp4_followups': kp4_followups, 'kp4_sp': kp4_sp, 'spring_kp_id': spring_kp_id,
           'kp3_id': kp3}, open(os.path.join(os.path.dirname(__file__), 'step6_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
print("\n[DB-OK] 环节6 标准库本体维护全部验证通过")
