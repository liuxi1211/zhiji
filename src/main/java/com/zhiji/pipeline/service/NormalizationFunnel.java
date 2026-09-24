package com.zhiji.pipeline.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三层归一漏斗纯计算（知识库PRD 九-3 / 决策清单 D21）：
 * ①结构信号免费先行（同源同章节同题面 = 强命中）→ ②题面向量进程内暴力余弦召回（万级毫秒级）
 * → ③中间相似带对交 LLM 精判——向量只做召回不做事判定（「HashMap 扩容机制」与「HashMap 树化阈值」很相似但不是同一题）。
 * 无 Spring 依赖：输入不可变记录 + 判定回调，输出归并组；阈值集中本类，调参 = 发版（B端管理台PRD 十「改 prompt 是发版行为」同口径）。
 *
 * 置信分层与 canonical_status 的对应：exact(3)/structural 强命中(2) → confirmed；
 * llm 精判(1)/结构灰带(1，余弦不足但跨源章节路径投票一致) → pending 待人工确认。
 */
final class NormalizationFunnel {

    /** 极高相似：跳过 LLM 直接归并 / 挂靠既有归一题 / 映射已有知识点。 */
    static final double EXACT_SIMILARITY = 0.92;
    /** 中间带下沿：[0.82, 0.92) 送 LLM 精判。 */
    static final double SUGGEST_SIMILARITY = 0.82;
    /** 灰带下沿：[0.60, 0.82) 且跨源章节路径末段投票一致才归并（结构信号兜粗簇）。 */
    static final double GRAY_SIMILARITY = 0.60;
    /** 语料簇聚类阈值（canonical 题面间挂靠既有簇 / 聚新簇同口径）。 */
    static final double CLUSTER_SIMILARITY = 0.60;
    /** 单次 run 的 LLM 精判对数上限（超限截断并告警，B 端成本护栏）。 */
    static final int MAX_JUDGE_PAIRS = 400;

    private NormalizationFunnel() {
    }

    /** 散题输入项。 */
    record Item(long id, long docId, String title, String sectionPath) {
    }

    /** 既有归一题（吸收目标）。 */
    record Existing(long id, String title) {
    }

    /**
     * 归并组：canonicalId 为空表示需新建归一题（status 按组置信推导）；
     * 非空表示整组吸收挂靠到既有归一题（其 status 不变）。
     */
    record Group(String basis, String status, List<Item> items, Long canonicalId) {
    }

    /** 中间带判定回调（实现方走 CLUSTER_DEDUP 批量 LLM 精判）。 */
    interface Judge {

        /** 批量精判散题对，返回与入参等长的 same 数组。 */
        boolean[] judgePairs(List<Pair> pairs);

        /** 批量精判散题与既有归一题，返回与入参等长的 same 数组。 */
        boolean[] judgeCanonPairs(List<CanonPair> pairs);

        record Pair(Item a, Item b) {
        }

        record CanonPair(Item item, Existing canonical) {
        }
    }

    /** 归一结果：值得处理的组 + 中间带对数（超 LLM 上限截断时用于告警口径）。 */
    record GroupResult(List<Group> groups, int middlePairCount) {
    }

    /**
     * 归一主流程。vectors 键为候选 id，canonicalVectors 键为既有 canonical id。
     * 返回值得处理的组（≥2 题的组，或挂靠了既有归一题的单题）；无挂靠的单题留在候选池散题不返回。
     */
    static GroupResult group(List<Item> items, List<Existing> existing,
            Map<Long, float[]> vectors, Map<Long, float[]> canonicalVectors, Judge judge) {
        int n = items.size();
        if (n == 0) {
            return new GroupResult(List.of(), 0);
        }
        int middlePairCount = 0;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        record Edge(int a, int b, int rank, String basis) {
        }
        List<Edge> edges = new ArrayList<>();
        List<Judge.Pair> middlePairs = new ArrayList<>();
        Map<Long, float[]> normalizedVectors = new HashMap<>();
        String[] leaves = new String[n];
        for (int i = 0; i < n; i++) {
            Item item = items.get(i);
            leaves[i] = leafToken(item.sectionPath());
            float[] vector = vectors.get(item.id());
            if (vector != null) {
                normalizedVectors.put(item.id(), vector);
            }
        }

        for (int i = 0; i < n; i++) {
            Item a = items.get(i);
            for (int j = i + 1; j < n; j++) {
                Item b = items.get(j);
                float[] va = normalizedVectors.get(a.id());
                float[] vb = normalizedVectors.get(b.id());
                double sim = va == null || vb == null ? -1 : cosine(va, vb);
                if (isStrongStructural(a, b)) {
                    edges.add(new Edge(i, j, 2, "structural"));
                } else if (sim >= EXACT_SIMILARITY) {
                    edges.add(new Edge(i, j, 3, "exact"));
                } else if (sim >= SUGGEST_SIMILARITY) {
                    middlePairCount++;
                    if (middlePairs.size() < MAX_JUDGE_PAIRS) {
                        middlePairs.add(new Judge.Pair(a, b));
                    }
                } else if (sim >= GRAY_SIMILARITY && !leaves[i].isEmpty() && leaves[i].equals(leaves[j])) {
                    edges.add(new Edge(i, j, 1, "structural"));
                }
            }
        }
        for (Edge edge : edges) {
            union(parent, edge.a(), edge.b());
        }

        if (!middlePairs.isEmpty() && judge != null) {
            boolean[] verdicts = judge.judgePairs(middlePairs);
            for (int k = 0; k < middlePairs.size() && k < verdicts.length; k++) {
                if (verdicts[k]) {
                    int a = indexOf(items, middlePairs.get(k).a());
                    int b = indexOf(items, middlePairs.get(k).b());
                    edges.add(new Edge(a, b, 1, "llm"));
                    union(parent, a, b);
                }
            }
        }

        // 挂靠判定：按 item 记录目标（exact 直接记、中间带 LLM 精判后记），组件级再聚合投票
        Map<Long, Long> itemAttach = new HashMap<>();
        Map<Long, Double> itemAttachSim = new HashMap<>();
        Map<Long, String> itemAttachBasis = new HashMap<>();
        if (!existing.isEmpty()) {
            List<Judge.CanonPair> canonPairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Item item = items.get(i);
                float[] vi = normalizedVectors.get(item.id());
                if (vi == null) {
                    continue;
                }
                Long best = null;
                double bestSim = -1;
                for (Existing canonical : existing) {
                    float[] vc = canonicalVectors.get(canonical.id());
                    if (vc == null) {
                        continue;
                    }
                    double sim = cosine(vi, vc);
                    if (sim > bestSim) {
                        bestSim = sim;
                        best = canonical.id();
                    }
                }
                if (best == null) {
                    continue;
                }
                if (bestSim >= EXACT_SIMILARITY) {
                    itemAttach.put(item.id(), best);
                    itemAttachSim.put(item.id(), bestSim);
                    itemAttachBasis.put(item.id(), "exact");
                } else if (bestSim >= SUGGEST_SIMILARITY && canonPairs.size() < MAX_JUDGE_PAIRS) {
                    long bestId = best;
                    canonPairs.add(new Judge.CanonPair(item, existing.stream()
                            .filter(c -> c.id() == bestId).findFirst().orElseThrow()));
                }
            }
            if (!canonPairs.isEmpty() && judge != null) {
                boolean[] verdicts = judge.judgeCanonPairs(canonPairs);
                for (int k = 0; k < canonPairs.size() && k < verdicts.length; k++) {
                    if (verdicts[k]) {
                        Item item = canonPairs.get(k).item();
                        itemAttach.put(item.id(), canonPairs.get(k).canonical().id());
                        itemAttachSim.put(item.id(), -1.0);
                        itemAttachBasis.put(item.id(), "llm");
                    }
                }
            }
        }

        Map<Integer, List<Item>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), root -> new ArrayList<>()).add(items.get(i));
        }
        List<Group> groups = new ArrayList<>();
        for (List<Item> members : components.values()) {
            // 组件内聚合挂靠投票：目标 canonical 取组内成员投票（票数优先、同票取最高相似）
            Map<Long, Integer> votes = new HashMap<>();
            Map<Long, Double> sims = new HashMap<>();
            Map<Long, String> bases = new HashMap<>();
            for (Item member : members) {
                Long canonicalId = itemAttach.get(member.id());
                if (canonicalId == null) {
                    continue;
                }
                votes.merge(canonicalId, 1, Integer::sum);
                sims.merge(canonicalId, itemAttachSim.getOrDefault(member.id(), -1.0), Math::max);
                bases.merge(canonicalId, itemAttachBasis.get(member.id()), NormalizationFunnel::preferLlm);
            }
            Long target = null;
            double targetSim = -1;
            for (Map.Entry<Long, Integer> vote : votes.entrySet()) {
                double sim = sims.getOrDefault(vote.getKey(), -1.0);
                Integer currentVotes = target == null ? null : votes.get(target);
                if (currentVotes == null || vote.getValue() > currentVotes
                        || (vote.getValue().intValue() == currentVotes.intValue() && sim > targetSim)) {
                    target = vote.getKey();
                    targetSim = sim;
                }
            }
            if (members.size() < 2 && target == null) {
                continue;
            }
            int maxRank = 0;
            String basis = null;
            for (Edge edge : edges) {
                if (containsItem(members, items.get(edge.a())) && containsItem(members, items.get(edge.b()))) {
                    if (edge.rank() > maxRank || (edge.rank() == maxRank && preferLlm(basis, edge.basis()).equals(edge.basis()))) {
                        maxRank = edge.rank();
                        basis = edge.basis();
                    }
                }
            }
            if (target != null && bases.containsKey(target)) {
                String attach = bases.get(target);
                int attachRank = "exact".equals(attach) ? 3 : 1;
                if (attachRank > maxRank) {
                    maxRank = attachRank;
                    basis = attach;
                }
            }
            if (target != null) {
                groups.add(new Group(basis, null, members, target));
            } else {
                groups.add(new Group(basis, maxRank >= 2 ? "confirmed" : "pending", members, null));
            }
        }
        return new GroupResult(groups, middlePairCount);
    }

    /**
     * 语料簇分配（增量）：先按成员题面余弦挂靠既有簇，剩余节点按「章节路径首段投票 + 余弦 ≥ 阈值」聚新组。
     * attached 返回 canonicalId → 既有簇 id；newGroups 返回待建新簇的 canonicalId 分组（单题组也成簇，人工可再合并）。
     */
    record ClusterNode(long canonicalId, String title, float[] vector, String firstSegment) {
    }

    record ClusterOutcome(Map<Long, Long> attached, List<List<Long>> newGroups) {
    }

    static ClusterOutcome assignClusters(List<ClusterNode> nodes, Map<Long, List<float[]>> existingClusterVectors) {
        Map<Long, Long> attached = new LinkedHashMap<>();
        List<ClusterNode> rest = new ArrayList<>();
        for (ClusterNode node : nodes) {
            if (node.vector() == null) {
                rest.add(node);
                continue;
            }
            Long bestCluster = null;
            double bestSim = -1;
            for (Map.Entry<Long, List<float[]>> entry : existingClusterVectors.entrySet()) {
                for (float[] vector : entry.getValue()) {
                    double sim = cosine(node.vector(), vector);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestCluster = entry.getKey();
                    }
                }
            }
            if (bestCluster != null && bestSim >= CLUSTER_SIMILARITY) {
                attached.put(node.canonicalId(), bestCluster);
            } else {
                rest.add(node);
            }
        }
        int m = rest.size();
        int[] parent = new int[m];
        for (int i = 0; i < m; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                boolean sameSegment = !rest.get(i).firstSegment().isEmpty()
                        && rest.get(i).firstSegment().equals(rest.get(j).firstSegment());
                double sim = rest.get(i).vector() == null || rest.get(j).vector() == null ? -1
                        : cosine(rest.get(i).vector(), rest.get(j).vector());
                if (sameSegment || sim >= CLUSTER_SIMILARITY) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Long>> components = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            components.computeIfAbsent(find(parent, i), root -> new ArrayList<>()).add(rest.get(i).canonicalId());
        }
        return new ClusterOutcome(attached, new ArrayList<>(components.values()));
    }

    /** 标题归一化：全角转半角、去标点空白、小写——结构强命中比较用。 */
    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(title.length());
        for (char c : title.toCharArray()) {
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);
            }
            if (Character.isLetterOrDigit(c) || c > 127) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 章节路径末段 token（路径投票的投票键），如 "Java集合 > HashMap" → "hashmap"。 */
    static String leafToken(String sectionPath) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return "";
        }
        String[] segments = sectionPath.split("[>/]|→");
        for (int i = segments.length - 1; i >= 0; i--) {
            String token = normalizeTitle(segments[i]);
            if (!token.isEmpty()) {
                return token;
            }
        }
        return "";
    }

    /** 章节路径首段（语料簇聚类的投票键）。 */
    static String firstSegment(String sectionPath) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return "";
        }
        for (String segment : sectionPath.split("[>/]|→")) {
            String token = normalizeTitle(segment);
            if (!token.isEmpty()) {
                return token;
            }
        }
        return "";
    }

    /** 暴力余弦（进程内，万级毫秒级——PRD 九-3 既定口径）。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return -1;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 结构强命中：同文档 + 同章节路径 + 归一化题面相等（同源标题结构）。 */
    private static boolean isStrongStructural(Item a, Item b) {
        return a.docId() == b.docId()
                && a.sectionPath() != null && a.sectionPath().equals(b.sectionPath())
                && normalizeTitle(a.title()).equals(normalizeTitle(b.title()))
                && !normalizeTitle(a.title()).isEmpty();
    }

    private static String preferLlm(String current, String candidate) {
        if (current == null) {
            return candidate;
        }
        return "llm".equals(candidate) ? "llm" : current;
    }

    private static int indexOf(List<Item> items, Item target) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == target.id()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsItem(List<Item> items, Item target) {
        for (Item item : items) {
            if (item.id() == target.id()) {
                return true;
            }
        }
        return false;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}
