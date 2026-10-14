package com.zhiji.llm.domain;

import java.util.Map;

/**
 * 模型路由配置（存 sys_config 的 llm.route）：按用途（purpose code）映射到目标渠道与模型。
 * 业务只认用途、不认模型名——换服务商只改渠道库（llm.channel），换模型只改此处。
 */
public record LlmRouteConfig(Map<String, RouteTarget> purposes) {

    /**
     * 单个路由目标。provider 名对应 llm.channel 的渠道名；
     * embedding=true 表示该用途走向量化接口（chat 与 embedding 是两条调用链）；
     * thinking=思考模式开关（Boolean 三态：null 跟随 provider 默认，true/false 经 extra_body.thinking 透传）。
     * 温度与最大 Token 不做路由管理：请求未覆盖时不传，直接用模型的默认值
     * （Anthropic 协议 max_tokens 必填，由传输层给协议兜底值）。
     */
    public record RouteTarget(String provider, String model, boolean embedding, Boolean thinking) {

        public RouteTarget(String provider, String model, boolean embedding) {
            this(provider, model, embedding, null);
        }
    }

    public RouteTarget target(String purposeCode) {
        return purposes == null ? null : purposes.get(purposeCode);
    }
}
