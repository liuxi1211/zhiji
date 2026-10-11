"""登录 + 连通性自检 + 一次真实 AI 概述调用，验证 LLM 链路端到端可用。"""
import json, sys, urllib.request

sys.stdout.reconfigure(encoding='utf-8')
BASE = 'http://localhost:8080'

def call(method, path, body=None, token=None, timeout=120):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')[:500]

# 1. 登录
code, body = call('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})
assert code == 200, f"登录失败: {code} {body}"
token = body['token']
print(f"[1] 登录成功 role={body.get('role')}")

# 2. 创建一条测试素材
code, body = call('POST', '/api/admin/sources',
                  {'title': 'LLM连通性测试-HashMap', 'contentMd': '# HashMap 底层实现\n\nHashMap 基于数组+链表+红黑树，JDK8 引入树化阈值 8。'}, token)
assert code == 200, f"创建素材失败: {code} {body}"
sid = body['id']
print(f"[2] 创建测试素材 id={sid}")

# 3. AI 概述（真实 LLM 调用）
code, body = call('POST', f'/api/admin/sources/{sid}/ai/summary', {}, token, timeout=180)
print(f"[3] AI 概述: {code} {json.dumps(body, ensure_ascii=False)[:300] if isinstance(body, dict) else body}")

# 4. 清理测试素材
code, _ = call('DELETE', f'/api/admin/sources/{sid}', None, token)
print(f"[4] 清理测试素材: {code}")
