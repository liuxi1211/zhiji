package com.zhiji.common.web;

import java.util.List;

/**
 * 分页响应的统一契约：items 为当前页数据，total 为当前筛选条件下的总行数；
 * 页码与页大小由调用方维护，不随响应回传。
 */
public record PageResult<T>(List<T> items, long total) {

    public static <T> PageResult<T> of(List<T> items, long total) {
        return new PageResult<>(items, total);
    }

    /** 把已就绪的全量列表按页窗口切片（kp/doc 两表合并列表的分页形态）。 */
    public static <T> List<T> slice(List<T> items, int page, int pageSize) {
        int from = Math.min((page - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return items.subList(from, to);
    }

    /** 页码与页大小的入参归一：page 从 1 起，pageSize 限 1~100（缺省 20）。 */
    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    public static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
