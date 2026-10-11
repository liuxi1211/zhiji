import sqlite3
import json

conn = sqlite3.connect("data/zhiji.db")
row = conn.execute(
    "select config_value from sys_config where config_key='llm.route'"
).fetchone()
conn.close()
data = json.loads(row[0])
print(json.dumps(data, ensure_ascii=False, indent=1))
