# -*- coding: utf-8 -*-
"""运行时冒烟：登录 + 关键只读接口 + 修复后行为抽查（恢复清空 merged_into 已由 step8b 验证）。"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from api_helper import call, ok

sys.stdout.reconfigure(encoding='utf-8')
token = ok('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})['token']
print("[OK] 登录")

code, due = call('GET', '/api/admin/kb/reviews/due', token=token)
assert code == 200 and due == [], f"due 异常: {due}"
print(f"[OK] 复核到期列表为空: {due}")

code, items = call('GET', '/api/admin/kb/contents?topicId=5&type=kp', token=token)
assert code == 200, f"contents 异常: {code}"
ids = {(i['id'], i['status']) for i in items}
assert (17, 'available') in ids and (4, 'merged') in ids, f"MySQL 主题 kp 状态异常: {ids}"
print(f"[OK] MySQL 主题 kp: {sorted(ids)}（合并产物 available、源 merged）")

code, kp = call('GET', '/api/admin/kb/kp/17', token=token)
assert code == 200 and kp['freqCoverage'] == 8 and len(kp['followups']) > 0, f"kp#17 异常"
print(f"[OK] 合并产物 kp#17: 覆盖度={kp['freqCoverage']}, 追问={len(kp['followups'])} 层, 变体={len(kp['variants'])} 条")
print("\n[SMOKE-OK] 后端运行时以修复后代码正常服务")
