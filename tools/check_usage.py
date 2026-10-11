# -*- coding: utf-8 -*-
"""诊断：AI 合并产物为空（所有字段 null）。查看 llm_usage_daily 与后端日志线索。"""
import sqlite3, sys, os
sys.stdout.reconfigure(encoding='utf-8')
DB = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'data', 'zhiji.db'))
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
print('--- llm_usage_daily 今日 kb_merge/source_* ---')
for r in conn.execute("SELECT * FROM llm_usage_daily WHERE usage_date = date('now','localtime') AND purpose IN ('kb_merge','source_split','source_rewrite','source_summary')"):
    print(dict(r))
conn.close()
