package com.zhiji.llm.domain;

import java.util.Map;

/**
 * 模型路由配置（存 sys_config 的 llm.route）：按用途（purpose code）映射到目标模型。
 * 业务只认用途、不认模型名——换服务商只改 provider 的 base-url/api-key（yml），换模型只改此处。
 */
public record LlmRouteConfig(Map<String, RouteTarget> purposes) {

    /**
     * 单个路由目标。provider 名对应 zhiji.llm.providers 的 key；
     * embedding=true 表示该用途走向量化接口（chat 与 embedding 是两条调用链）；
     * thinking=思考模式开关（Boolean 三态：null 跟随 provider 默认，true/false 经 extra_body.thinking 透传）。
     */
    public record RouteTarget(String provider, String model, Double temperature, Integer maxTokens,
            boolean embedding, Boolean thinking) {

        public RouteTarget(String provider, String model, Double temperature, Integer maxTokens, boolean embedding) {
            this(provider, model, temperature, maxTokens, embedding, null);
        }
    }

    public RouteTarget target(String purposeCode) {
        return purposes == null ? null : purposes.get(purposeCode);
    }
}
