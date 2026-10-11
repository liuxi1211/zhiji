"""扫描题库源，统计所有 markdown 文件，按主题归类并随机挑选 10 篇文章 + 10 篇面试题。"""
import os, sys, json, random, re

sys.stdout.reconfigure(encoding='utf-8')

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'docs', '题库源'))
SKIP_DIRS = {'images', '_media', 'public', 'pictures', 'JavaFamily(全是指向性链接,暂时不用)'}
articles = []   # 文章（讲解类）
questions = []  # 面试题（面试/interview/mianjing/面经类）

for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        if not fn.lower().endswith('.md'):
            continue
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, ROOT).replace('\\', '/')
        # 排除导航类文件
        base = fn.lower()
        if base in ('readme.md', 'index.html', '_sidebar.md', '_coverpage.md', 'line.md', '.nojekyll'):
            continue
        if base in ('home.md', 'blog.md', 'contactme.md', 'faq.md', 'history.md', 'intro.md', 'use-suggestion.md'):
            continue
        if rel.startswith('JavaGuide/snippets/') or rel.startswith('JavaGuide/roadmap/'):
            continue
        size = os.path.getsize(full)
        if size < 1500:  # 太小的多为导航/占位
            continue
        try:
            with open(full, 'r', encoding='utf-8', errors='ignore') as f:
                head = f.read(4000)
        except Exception:
            continue
        rel_l = rel.lower()
        is_q = ('interview' in rel_l) or ('mianjing' in rel_l) or ('面' in fn and '试' in fn)
        item = {'path': rel, 'size': size, 'title_guess': os.path.splitext(fn)[0]}
        # 从首个 # 标题取真实标题
        m = re.search(r'^#\s+(.+)$', head, re.M)
        if m:
            item['h1'] = m.group(1).strip()[:80]
        (questions if is_q else articles).append(item)

random.seed(20261011)
random.shuffle(articles)
random.shuffle(questions)

picked_a = articles[:10]
picked_q = questions[:10]

out = {'all_articles': len(articles), 'all_questions': len(questions),
       'picked_articles': picked_a, 'picked_questions': picked_q}
with open(os.path.join(os.path.dirname(__file__), 'picked.json'), 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=2)

print(f"文章候选总数: {len(articles)}, 面试题候选总数: {len(questions)}")
print("\n=== 随机挑选的 10 篇文章 ===")
for it in picked_a:
    print(f"  [{it['size']:>7}B] {it['path']}  | h1: {it.get('h1','-')}")
print("\n=== 随机挑选的 10 篇面试题 ===")
for it in picked_q:
    print(f"  [{it['size']:>7}B] {it['path']}  | h1: {it.get('h1','-')}")
