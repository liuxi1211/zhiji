"""输出 20 篇挑中素材的头部与结构（标题层级数），供人工拍板领域主题。"""
import json, sys, os, re
sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'docs', '题库源'))
data = json.load(open(os.path.join(os.path.dirname(__file__), 'picked.json'), encoding='utf-8'))

def head(rel, n=600):
    with open(os.path.join(ROOT, rel), encoding='utf-8', errors='ignore') as f:
        return f.read(n).replace('\n', ' | ')[:n]

def structure(rel):
    with open(os.path.join(ROOT, rel), encoding='utf-8', errors='ignore') as f:
        text = f.read()
    h2 = re.findall(r'^##\s+(.+)$', text, re.M)
    return f"{len(text)}字, h2数={len(h2)}, 前5个h2: {h2[:5]}"

for kind in ['picked_articles', 'picked_questions']:
    print(f"\n======== {kind} ========")
    for i, it in enumerate(data[kind]):
        print(f"\n[{i+1}] {it['path']}")
        print(f"    头部: {head(it['path'], 220)}")
        print(f"    结构: {structure(it['path'])}")
