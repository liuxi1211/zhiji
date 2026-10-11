import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('data/zhiji.db')
conn.row_factory = sqlite3.Row
print('--- kb_review kp 7,9 ---')
for r in conn.execute("SELECT content_id, action, comment FROM kb_review WHERE content_type='kp' AND content_id IN (7,9) ORDER BY id"):
    print(dict(r))
print('--- kb_kp 7,9 ---')
for r in conn.execute('SELECT id, status FROM kb_kp WHERE id IN (7,9)'):
    print(dict(r))
