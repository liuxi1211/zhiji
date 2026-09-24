package com.zhiji.pipeline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhiji.pipeline.service.NormalizationFunnel.ClusterNode;
import com.zhiji.pipeline.service.NormalizationFunnel.Existing;
import com.zhiji.pipeline.service.NormalizationFunnel.Group;
import com.zhiji.pipeline.service.NormalizationFunnel.Item;
import com.zhiji.pipeline.service.NormalizationFunnel.Judge;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 归一漏斗纯单测（知识库PRD 九-3 三层漏斗的计算口径）：
 * 结构强命中 / exact 分带 / 中间带 LLM 精判 / 灰带路径投票 / 挂靠既有归一题 / 语料簇分配。
 */
class NormalizationFunnelTest {

    private static final Judge NEVER_SAME = new Judge() {
        @Override
        public boolean[] judgePairs(List<Pair> pairs) {
            return new boolean[pairs.size()];
        }

        @Override
        public boolean[] judgeCanonPairs(List<CanonPair> pairs) {
            return new boolean[pairs.size()];
        }
    };

    private static Item item(long id, long docId, String title, String sectionPath) {
        return new Item(id, docId, title, sectionPath);
    }

    /** 归一化单位向量（测试用 8 维，与 ScriptedLlmTransport 的伪向量同维）。 */
    private static float[] vec(double... components) {
        float[] v = new float[8];
        double norm = 0;
        for (int i = 0; i < components.length; i++) {
            v[i] = (float) components[i];
            norm += v[i] * v[i];
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / Math.sqrt(norm));
        }
        return v;
    }

    @Test
    void normalizeTitleStripsPunctuationWidthAndCase() {
        assertEquals("hashmap底层实现", NormalizationFunnel.normalizeTitle("HashMap 底层实现！"));
        assertEquals("hashmap", NormalizationFunnel.normalizeTitle("ＨａｓｈＭａｐ"));
        assertEquals("", NormalizationFunnel.normalizeTitle("？？？"));
    }

    @Test
    void leafAndFirstSegmentExtractPathTokens() {
        assertEquals("hashmap", NormalizationFunnel.leafToken("Java集合 > HashMap"));
        assertEquals("java集合", NormalizationFunnel.firstSegment("Java集合 > HashMap"));
        assertEquals("", NormalizationFunnel.leafToken(null));
        assertEquals("", NormalizationFunnel.firstSegment(""));
    }

    @Test
    void cosineMatchesBasicGeometry() {
        assertEquals(1.0, NormalizationFunnel.cosine(vec(1, 2), vec(2, 4)), 1e-6);
        assertEquals(0.0, NormalizationFunnel.cosine(vec(1, 0), vec(0, 1)), 1e-6);
        assertEquals(-1, NormalizationFunnel.cosine(new float[] {1}, new float[] {1, 0}));
        assertEquals(-1, NormalizationFunnel.cosine(vec(1), null));
    }

    @Test
    void strongStructuralMergesSameDocSameSectionSameTitle() {
        List<Item> items = List.of(
                item(1, 100, "HashMap 的底层实现是什么", "HashMap 的底层实现是什么"),
                item(2, 100, "HashMap 的底层实现是什么", "HashMap 的底层实现是什么"),
                item(3, 100, "无关问题", "无关问题"));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                Map.of(), Map.of(), NEVER_SAME);
        assertEquals(1, result.groups().size());
        Group group = result.groups().get(0);
        assertEquals("confirmed", group.status());
        assertEquals("structural", group.basis());
        assertEquals(2, group.items().size());
        assertEquals(0, result.middlePairCount());
    }

    @Test
    void exactVectorMergeSkipsLlm() {
        List<Item> items = List.of(
                item(1, 100, "HashMap 的底层实现是什么", null),
                item(2, 200, "HashMap 底层实现", null));
        Map<Long, float[]> vectors = Map.of(1L, vec(1, 0), 2L, vec(2, 0));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                vectors, Map.of(), NEVER_SAME);
        assertEquals(1, result.groups().size());
        assertEquals("exact", result.groups().get(0).basis());
        assertEquals("confirmed", result.groups().get(0).status());
    }

    @Test
    void middleBandSameByLlmStaysPending() {
        List<Item> items = List.of(
                item(1, 100, "HashMap 的底层实现是什么", null),
                item(2, 200, "HashMap 底层是如何实现的", null));
        Map<Long, float[]> vectors = Map.of(1L, vec(1, 0), 2L, vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));
        Judge sameOnce = new Judge() {
            @Override
            public boolean[] judgePairs(List<Pair> pairs) {
                return new boolean[] {true};
            }

            @Override
            public boolean[] judgeCanonPairs(List<CanonPair> pairs) {
                return new boolean[pairs.size()];
            }
        };
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                vectors, Map.of(), sameOnce);
        assertEquals(1, result.groups().size());
        assertEquals("llm", result.groups().get(0).basis());
        assertEquals("pending", result.groups().get(0).status());
        assertEquals(1, result.middlePairCount());
    }

    @Test
    void middleBandJudgedDifferentStaysLoose() {
        List<Item> items = List.of(
                item(1, 100, "HashMap 的底层实现是什么", null),
                item(2, 200, "HashMap 树化阈值是多少", null));
        Map<Long, float[]> vectors = Map.of(1L, vec(1, 0), 2L, vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                vectors, Map.of(), NEVER_SAME);
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void grayBandMergesOnlyWithMatchingPathVote() {
        Item a = item(1, 100, "HashMap 的底层实现是什么", "Java集合 > HashMap");
        Item b = item(2, 200, "HashMap 底层结构说明", "Java集合 > HashMap");
        Item c = item(3, 300, "HashMap 底层结构说明", "并发编程 > 线程安全");
        double sqrt = Math.sqrt(1 - 0.49);
        Map<Long, float[]> vectors = Map.of(
                1L, vec(1, 0),
                2L, vec(0.7, sqrt),
                3L, vec(0.7, -sqrt));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(List.of(a, b, c), List.of(),
                vectors, Map.of(), NEVER_SAME);
        // a+b 路径投票一致 → 灰带归并；c 路径不同 → 留散题
        assertEquals(1, result.groups().size());
        assertEquals(2, result.groups().get(0).items().size());
        assertEquals("structural", result.groups().get(0).basis());
        assertEquals("pending", result.groups().get(0).status());
    }

    @Test
    void exactMatchAbsorbsIntoExistingCanonical() {
        List<Item> items = List.of(item(9, 300, "HashMap 底层实现", null));
        Map<Long, float[]> itemVectors = Map.of(9L, vec(1, 0));
        Map<Long, float[]> canonicalVectors = Map.of(77L, vec(1, 0));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items,
                List.of(new Existing(77, "HashMap 的底层实现是什么")), itemVectors, canonicalVectors, NEVER_SAME);
        assertEquals(1, result.groups().size());
        assertEquals(77L, result.groups().get(0).canonicalId());
        assertEquals("exact", result.groups().get(0).basis());
    }

    @Test
    void singletonWithoutMatchStaysLoose() {
        List<Item> items = List.of(item(1, 100, "孤题", null));
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                Map.of(), Map.of(), NEVER_SAME);
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void clusterAssignmentAttachesToExistingThenGroupsRest() {
        ClusterNode attach = new ClusterNode(1, "HashMap 底层", vec(1, 0), "java集合");
        ClusterNode sameSegment = new ClusterNode(2, "HashMap 扩容", vec(0, 1), "java集合");
        ClusterNode lonely = new ClusterNode(3, "Redis 持久化", vec(0, 0, 1), "redis");
        NormalizationFunnel.ClusterOutcome outcome = NormalizationFunnel.assignClusters(
                List.of(attach, sameSegment, lonely),
                Map.of(500L, List.of(vec(1, 0))));
        assertEquals(500L, outcome.attached().get(1L));
        // 剩余按首段聚组：sameSegment + lonely 无同首段、向量正交 → 各自成组
        assertEquals(2, outcome.newGroups().size());
    }

    @Test
    void clusterCosineJoinMergesWithoutSegmentVote() {
        ClusterNode a = new ClusterNode(1, "缓存穿透怎么解决", null, "");
        ClusterNode b = new ClusterNode(2, "缓存击穿和穿透的区别", null, "");
        Map<Long, List<float[]>> existing = Map.of();
        NormalizationFunnel.ClusterOutcome outcome = NormalizationFunnel.assignClusters(List.of(a, b), existing);
        // 无向量节点不参与余弦、无首段投票 → 各自成组
        assertEquals(0, outcome.attached().size());
        assertEquals(2, outcome.newGroups().size());
    }

    @Test
    void groupBasisPrefersStrongestEdge() {
        // 三题链：1-2 exact 强边，2-3 中间带 LLM 边 → 组 basis 取 strongest = exact，confirmed
        List<Item> items = List.of(
                item(1, 100, "甲", null),
                item(2, 200, "乙", null),
                item(3, 300, "丙", null));
        Map<Long, float[]> vectors = new HashMap<>();
        vectors.put(1L, vec(1, 0));
        vectors.put(2L, vec(1, 0));
        vectors.put(3L, vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));
        Judge sameOnce = new Judge() {
            @Override
            public boolean[] judgePairs(List<Pair> pairs) {
                return new boolean[] {true};
            }

            @Override
            public boolean[] judgeCanonPairs(List<CanonPair> pairs) {
                return new boolean[pairs.size()];
            }
        };
        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(items, List.of(),
                vectors, Map.of(), sameOnce);
        assertEquals(1, result.groups().size());
        assertEquals("exact", result.groups().get(0).basis());
        assertEquals("confirmed", result.groups().get(0).status());
        assertEquals(3, result.groups().get(0).items().size());
    }
}
