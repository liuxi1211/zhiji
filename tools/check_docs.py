# -*- coding: utf-8 -*-
import os, sqlite3, sys

sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
rows = conn.execute("""
    SELECT d.id, d.title, d.status, d.topic_id, t.domain_code, t.name
    FROM kb_doc d JOIN kb_topic t ON t.id = d.topic_id
    ORDER BY d.topic_id, d.id
""").fetchall()
for r in rows:
    print(dict(r))
conn.close()
