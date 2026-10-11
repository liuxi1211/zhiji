# -*- coding: utf-8 -*-
"""HTTP 调用封装：登录、GET/POST/PUT/DELETE/Multipart。"""
import json, os, sys, urllib.request, urllib.parse, uuid

sys.stdout.reconfigure(encoding='utf-8')
BASE = 'http://localhost:8080'
TOKEN_FILE = os.path.join(os.path.dirname(__file__), '.token')

def enc_url(path):
    """对路径中非 ASCII 字符做百分号编码（保留 / ? = & 结构）。"""
    return urllib.parse.quote(path, safe="/?=&:.%-_~")

class HttpError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {str(body)[:300]}")
        self.status = status
        self.body = body

def login():
    if os.path.exists(TOKEN_FILE):
        token = open(TOKEN_FILE, encoding='utf-8').read().strip()
        if token:
            return token
    code, body = call('POST', '/api/auth/staff/login', {'username': 'admin', 'password': 'admin123'})
    if code != 200:
        raise HttpError(code, body)
    token = body['token']
    open(TOKEN_FILE, 'w', encoding='utf-8').write(token)
    return token

def call(method, path, body=None, token=None, timeout=300, files=None):
    req = urllib.request.Request(BASE + enc_url(path), method=method)
    if files:
        boundary = uuid.uuid4().hex
        payload = b''
        for name, (filename, content, ctype) in files.items():
            payload += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; '
                        f'filename="{filename}"\r\nContent-Type: {ctype}\r\n\r\n').encode('utf-8')
            payload += content + b'\r\n'
        payload += f'--{boundary}--\r\n'.encode('utf-8')
        req.add_header('Content-Type', f'multipart/form-data; boundary={boundary}')
        data = payload
    else:
        req.add_header('Content-Type', 'application/json')
        data = json.dumps(body).encode('utf-8') if body is not None else None
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            raw = resp.read().decode('utf-8')
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', errors='replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw

def ok(method, path, body=None, token=None, timeout=300, files=None, expect=200):
    code, resp = call(method, path, body, token, timeout, files)
    if code != expect:
        raise HttpError(code, resp)
    return resp

def expect_fail(method, path, body=None, token=None, timeout=300, keyword=None):
    """断言请求失败，且错误信息含 keyword。返回 (status, body)。"""
    code, resp = call(method, path, body, token, timeout)
    if 200 <= code < 300:
        raise AssertionError(f"期望失败但成功了: {method} {path} -> {code}")
    if keyword and keyword not in str(resp):
        raise AssertionError(f"错误信息未包含「{keyword}」: {str(resp)[:300]}")
    return code, resp
