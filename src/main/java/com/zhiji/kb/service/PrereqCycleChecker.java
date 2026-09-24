package com.zhiji.kb.service;

import com.zhiji.common.exception.BizException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前置关系防环：全量边（含本次新边）建邻接表，从 kpId 沿前置方向 DFS 回到自身即成环。
 * 自引用同处理（起点 == 目标）。只报含 kpId 自身的环，其他独立环不影响本知识点。
 */
public final class PrereqCycleChecker {

    private static final int TITLE_LIMIT = 24;

    private PrereqCycleChecker() {
    }

    public static void check(Map<Long, List<Long>> edges, Map<Long, String> titles, long kpId) {
        List<Long> path = new ArrayList<>();
        Set<Long> onPath = new HashSet<>();
        Set<Long> settled = new HashSet<>();
        List<Long> cycle = findCycle(edges, kpId, kpId, path, onPath, settled);
        if (cycle != null) {
            throw BizException.of(400, "KB_PREREQ_CYCLE", "前置关系成环：" + renderPath(cycle, titles));
        }
    }

    private static List<Long> findCycle(Map<Long, List<Long>> edges, long start, long current,
            List<Long> path, Set<Long> onPath, Set<Long> settled) {
        path.add(current);
        onPath.add(current);
        for (Long next : edges.getOrDefault(current, List.of())) {
            if (next == null) {
                continue;
            }
            if (next == start) {
                List<Long> cycle = new ArrayList<>(path);
                cycle.add(start);
                return cycle;
            }
            if (onPath.contains(next) || settled.contains(next)) {
                continue;
            }
            List<Long> found = findCycle(edges, start, next, path, onPath, settled);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(current);
        settled.add(current);
        return null;
    }

    private static String renderPath(List<Long> cycle, Map<Long, String> titles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            Long id = cycle.get(i);
            String title = titles.getOrDefault(id, "#" + id);
            if (title.length() > TITLE_LIMIT) {
                title = title.substring(0, TITLE_LIMIT) + "…";
            }
            sb.append(title);
        }
        return sb.toString();
    }
}
