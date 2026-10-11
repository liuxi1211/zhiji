import sqlite3, json, sys, os
sys.stdout.reconfigure(encoding='utf-8')
db_path = os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db')
conn = sqlite3.connect(db_path)
conn.row_factory = sqlite3.Row
cur = conn.cursor()
print("=== sys_config ===")
for r in cur.execute("SELECT * FROM sys_config"):
    d = dict(r)
    for k, v in d.items():
        s = str(v)
        print(f"  {k}: {s[:500]}")
    print("  ---")
conn.close()
