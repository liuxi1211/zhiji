import json, sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('data/zhiji.db')
row = conn.execute("SELECT config_value FROM sys_config WHERE config_key='llm.route'").fetchone()
route = json.loads(row[0])
for purpose, cfg in sorted(route['purposes'].items()):
    print(f"{purpose:22s} -> {cfg.get('provider'):10s} {cfg.get('model')} maxTokens={cfg.get('maxTokens')}")
conn.close()
