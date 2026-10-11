# -*- coding: utf-8 -*-
"""环节3：AI 概述 + AI 难度 + AI 分类建议（正向），产物由人工采纳回写素材。
DB 验证：summary/difficulty/domain_code/topic_name 落库。"""
import json, os, sys, sqlite3
sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
manifest = json.load(open(os.path.join(os.path.dirname(__file__), 'manifest.json'), encoding='utf-8'))
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']

# 领域主题映射（人工拍板表：基于素材内容给 AI 人工领域，AI 只在领域内建议主题）
DOMAIN_MAP = {
    5:  ('engineering', 'jianli'),            # 简历模板 -> 工程素养（无简历主题→AI 会建议或空）
    6:  ('engineering', None),                # Claude Code multi-agent -> 工程素养（AI 主题：CI/CD）
    7:  ('engineering', None),                # 选课策略 -> 工程素养
    8:  ('engineering', None),                # Harness 五要素 -> 工程素养
    9:  ('middleware', None),                 # RocketMQ -> 中间件与搜索/消息队列
    10: ('java', None),                       # Spring Bean 生命周期 -> Java/Spring 生态
    11: ('java', None),                       # Arrays 工具类 -> Java
    12: ('engineering', None),                # 后训练 vs 参数 -> 工程素养
    13: ('java', None),                       # 异常处理 -> Java
    14: ('engineering', None),                # Codex 使用经验 -> 工程素养
    15: ('engineering', None),                # 春招总结 -> 工程素养
    16: ('engineering', None),                # 大模型面试指南 -> 工程素养
    17: ('distributed', None),                # Hystrix 隔离 -> 分布式与架构
    18: ('database', None),                   # MySQL 索引 15 连问 -> 数据库
    19: ('distributed', None),                # Hystrix timeout -> 分布式与架构
    20: ('middleware', None),                 # ES 介绍 -> 中间件与搜索
    21: ('distributed', None),                # 电商详情页架构 -> 分布式与架构
    22: ('distributed', None),                # 分布式面试题 -> 分布式与架构
    23: ('middleware', None),                 # Kafka 40 问 -> 中间件与搜索
    24: ('distributed', None),                # 接口幂等 -> 分布式与架构
}

conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
results = []
errors = []

for m in manifest:
    sid = m['source_id']
    domain = DOMAIN_MAP[sid][0]
    # 取领域主题候选
    topics = ok('GET', f'/api/admin/sources/domain-topics?domainCode={domain}', token=token)['topics']
    # AI 概述
    code, s = call('POST', f'/api/admin/sources/{sid}/ai/summary', {}, token, timeout=180)
    if code != 200:
        errors.append(f"#{sid} summary: {code} {str(s)[:150]}"); continue
    # AI 难度
    code, d = call('POST', f'/api/admin/sources/{sid}/ai/difficulty', {}, token, timeout=180)
    if code != 200:
        errors.append(f"#{sid} difficulty: {code} {str(d)[:150]}"); continue
    # AI 分类建议（领域内）
    code, c = call('POST', f'/api/admin/sources/{sid}/ai/classify',
                   {'domainCode': domain, 'topics': topics}, token, timeout=180)
    if code != 200:
        errors.append(f"#{sid} classify: {code} {str(c)[:150]}"); continue
    topic = c.get('topicName') or ''
    if topic and topic not in topics:
        # 分类建议越界，服务端应已拦截返回空；双保险
        topic = ''
    # 人工拍板：AI 建议为空时取领域第一个主题（人工兜底）
    if not topic:
        topic = topics[0]
    # 回写（概述/难度/领域/主题）
    code, body = call('PUT', f'/api/admin/sources/{sid}',
                      {'summary': s['summary'], 'difficulty': d['difficulty'],
                       'domainCode': domain, 'topicName': topic}, token)
    if code != 200:
        errors.append(f"#{sid} update: {code} {str(body)[:150]}"); continue
    # DB 验证
    r = conn.execute("SELECT summary, difficulty, domain_code, topic_name FROM src_item WHERE id=?", (sid,)).fetchone()
    ok1 = r['summary'] == s['summary']
    ok2 = r['difficulty'] == d['difficulty']
    ok3 = r['domain_code'] == domain
    ok4 = r['topic_name'] == topic
    line_ok = ok1 and ok2 and ok3 and ok4
    if not line_ok:
        errors.append(f"#{sid} DB不匹配: summary={ok1} diff={ok2} domain={ok3} topic={ok4}")
    results.append({'source_id': sid, 'domain': domain, 'topic': topic,
                    'difficulty': d['difficulty'], 'summary_chars': len(s['summary'])})
    print(f"[{'OK' if line_ok else 'BAD'}] #{sid} {m['title']}: {domain}/{topic} 难度={d['difficulty']} 概述{len(s['summary'])}字")

conn.close()
json.dump(results, open(os.path.join(os.path.dirname(__file__), 'step3_result.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
if errors:
    print("\n[FAIL]")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"\n[DB-OK] 20 篇概述/难度/分类全部落库且与 API 返回一致")
