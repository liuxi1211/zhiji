import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('data/zhiji.db')
conn.row_factory = sqlite3.Row
print('columns:', [d[1] for d in conn.execute('PRAGMA table_info(llm_usage_daily)')])
for r in conn.execute("SELECT * FROM llm_usage_daily WHERE purpose='kb_merge' ORDER BY id DESC LIMIT 5"):
    print(dict(r))
