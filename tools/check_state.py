import sqlite3, sys
sys.stdout.reconfigure(encoding='utf-8')
conn = sqlite3.connect('data/zhiji.db')
conn.row_factory = sqlite3.Row
print('--- kb_kp all ---')
for r in conn.execute('SELECT id, title, status, review_due_at FROM kb_kp ORDER BY id'):
    print(dict(r))
print('--- kb_doc all ---')
for r in conn.execute('SELECT id, title, status FROM kb_doc ORDER BY id'):
    print(dict(r))
print('--- kb_review last per content ---')
for r in conn.execute("""
    SELECT content_type, content_id, action, comment FROM kb_review
    WHERE id IN (SELECT MAX(id) FROM kb_review GROUP BY content_type, content_id)
    ORDER BY content_type, content_id"""):
    print(dict(r))
