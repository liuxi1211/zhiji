package com.zhiji.llm.api;

import java.util.List;

/**
 * 连通性自检结果（B 端系统配置页「配置改完当场可验证」）。
 * items 按路由去重后的每个 (provider, model) 一项，失败项给出可读原因。
 */
public record LlmConnectivityReport(boolean allOk, List<Item> items) {

    public record Item(String purpose, String provider, String model, boolean embedding, boolean ok, String detail) {
    }
}
