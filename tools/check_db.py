import sqlite3, json, sys, os

sys.stdout.reconfigure(encoding='utf-8')
db_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db')
conn = sqlite3.connect(db_path)
conn.row_factory = sqlite3.Row
cur = conn.cursor()

print("=== TABLES ===")
for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
    print(r['name'])

print("\n=== ROW COUNTS ===")
for t in ['src_item', 'kb_topic', 'kb_kp', 'kb_doc', 'kb_review', 'sys_staff', 'sys_config']:
    try:
        n = cur.execute(f"SELECT COUNT(*) c FROM {t}").fetchone()['c']
        print(f"{t}: {n}")
    except Exception as e:
        print(f"{t}: ERR {e}")

print("\n=== src_item status 分布 ===")
try:
    for r in cur.execute("SELECT status, source_type, COUNT(*) c FROM src_item GROUP BY status, source_type"):
        print(dict(r))
except Exception as e:
    print("ERR", e)

print("\n=== kb_kp 状态分布 ===")
try:
    for r in cur.execute("SELECT status, COUNT(*) c FROM kb_kp GROUP BY status"):
        print(dict(r))
except Exception as e:
    print("ERR", e)

print("\n=== kb_doc 状态分布 ===")
try:
    for r in cur.execute("SELECT status, COUNT(*) c FROM kb_doc GROUP BY status"):
        print(dict(r))
except Exception as e:
    print("ERR", e)

print("\n=== kb_topic ===")
try:
    for r in cur.execute("SELECT id, domain_code, name, status FROM kb_topic ORDER BY domain_code, id LIMIT 100"):
        print(dict(r))
except Exception as e:
    print("ERR", e)

print("\n=== sys_staff ===")
try:
    for r in cur.execute("SELECT id, username, role, status FROM sys_staff"):
        print(dict(r))
except Exception as e:
    print("ERR", e)

conn.close()
