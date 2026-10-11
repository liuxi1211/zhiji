package com.zhiji.llm.api;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmHealthService;
import com.zhiji.llm.service.LlmUsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端系统配置接口（/api/admin/settings，仅 admin——权限矩阵见基础架构PRD 3.3）。
 * 管理模型路由、限额与连通性自检；密钥不在本接口暴露面内（只走 yml / 环境变量）。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {

    private final LlmConfigService configService;
    private final LlmHealthService healthService;
    private final LlmUsageService usageService;
    private final LlmProperties properties;

    public SettingsController(LlmConfigService configService, LlmHealthService healthService,
            LlmUsageService usageService, LlmProperties properties) {
        this.configService = configService;
        this.healthService = healthService;
        this.usageService = usageService;
        this.properties = properties;
    }

    /** provider 模型清单（yml 事实源）：key=provider 名，value=该 key 支持的模型列表（设置页下拉数据源）。 */
    public record SettingsView(Map<String, LlmRouteConfig.RouteTarget> routes, LlmLimits limits,
            List<String> providers, Map<String, List<String>> providerModels) {
    }

    public record LimitsReq(
            @Min(value = 0, message = "判定限额不能为负") int judgeDaily,
            @Min(value = 0, message = "变体改写限额不能为负") int variantRewriteDaily,
            @Min(value = 0, message = "单档案合计限额不能为负") int perProfileDailyTotal,
            @Min(value = 0, message = "全站熔断阈值不能为负") int siteDailyTotal) {
    }

    /**
     * 用量行：站点计数与单档案峰值分开给——per-purpose 限额是「单档案」口径，
     * 拿全站合计去比会误报超限，因此 exceeded 以单档案当日峰值为准（核心模块定义 4.2 限额表）。
     * side 标注调用侧别（C 端用户触发 / B 端员工触发），B 端行不参与限额只有计量。
     */
    public record UsageRow(String purpose, String purposeName, String side, long callCount, long peakProfileCount,
            String limitKey, Integer limit, boolean soft, boolean exceeded) {
    }

    public record DailyPoint(String date, long callCount) {
    }

    public record UsageView(String date, List<UsageRow> purposes, long todayTotal, long siteDailyTotal,
            boolean circuitOpen, long peakProfileTotal, int perProfileDailyTotal, boolean perProfileExceeded,
            List<DailyPoint> recent7d) {
    }

    @GetMapping
    public SettingsView get() {
        Map<String, List<String>> providerModels = new LinkedHashMap<>();
        properties.getProviders().forEach((name, provider) -> providerModels.put(name, provider.getModels()));
        return new SettingsView(configService.routeConfig().purposes(), configService.limits(),
                healthService.knownProviders(), providerModels);
    }

    @PutMapping("/routes")
    public SettingsView updateRoutes(@Valid @RequestBody RouteUpdateReq req) {
        configService.saveRouteConfig(new LlmRouteConfig(validateRoutes(req.routes())));
        return get();
    }

    @PutMapping("/limits")
    public SettingsView updateLimits(@Valid @RequestBody LimitsReq req) {
        configService.saveLimits(new LlmLimits(req.judgeDaily(), req.variantRewriteDaily(),
                req.perProfileDailyTotal(), req.siteDailyTotal()));
        return get();
    }

    @PostMapping("/connectivity-check")
    public LlmConnectivityReport connectivityCheck() {
        return healthService.check();
    }

    @GetMapping("/usage")
    public UsageView usage() {
        LlmLimits limits = configService.limits();
        Map<String, Long> siteByPurpose = new LinkedHashMap<>();
        Map<String, Long> peakByPurpose = new LinkedHashMap<>();
        Map<Long, Long> totalByProfile = new LinkedHashMap<>();
        for (LlmUsageDaily row : usageService.todayRows()) {
            long count = row.getCallCount() == null ? 0L : row.getCallCount();
            long profileId = row.getProfileId() == null ? 0L : row.getProfileId();
            siteByPurpose.merge(row.getPurpose(), count, Long::sum);
            if (profileId != 0L) {
                peakByPurpose.merge(row.getPurpose(), count, Math::max);
                totalByProfile.merge(profileId, count, Long::sum);
            }
        }
        List<UsageRow> rows = new ArrayList<>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            Integer limit = limitFor(purpose, limits);
            long peak = peakByPurpose.getOrDefault(purpose.getCode(), 0L);
            rows.add(new UsageRow(purpose.getCode(), purpose.getName(), purpose.side().name(),
                    siteByPurpose.getOrDefault(purpose.getCode(), 0L), peak, purpose.limitKey().name(), limit,
                    purpose.limitKey() == LlmPurpose.LimitKey.JUDGE, limit != null && peak >= limit));
        }
        long todayTotal = usageService.todayTotal();
        long peakProfileTotal = totalByProfile.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        return new UsageView(usageService.today(), rows, todayTotal, limits.siteDailyTotal(),
                todayTotal >= limits.siteDailyTotal(), peakProfileTotal, limits.perProfileDailyTotal(),
                peakProfileTotal >= limits.perProfileDailyTotal(), recentDaily());
    }

    private Integer limitFor(LlmPurpose purpose, LlmLimits limits) {
        return switch (purpose.limitKey()) {
            case JUDGE -> limits.judgeDaily();
            case VARIANT -> limits.variantRewriteDaily();
            case NONE -> null;
        };
    }

    /** 未知入口 / 缺目标 / 未配置的 provider / 文本入口缺模型名 / 模型不在 provider 清单一律挡在落库前。 */
    private Map<String, RouteTarget> validateRoutes(Map<String, RouteTarget> routes) {
        Map<String, RouteTarget> validated = new LinkedHashMap<>();
        for (Map.Entry<String, RouteTarget> entry : routes.entrySet()) {
            LlmPurpose purpose = parsePurpose(entry.getKey());
            RouteTarget target = entry.getValue();
            if (target == null) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少路由目标：" + entry.getKey());
            }
            if (target.provider() == null || !healthService.knownProviders().contains(target.provider())) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "入口 " + entry.getKey() + " 指向未配置的 provider：" + target.provider());
            }
            if (!target.embedding() && (target.model() == null || target.model().isBlank())) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少模型名：" + entry.getKey());
            }
            // 模型必须在 provider 的 models 清单内（yml 事实源）：防止手输错模型名把错误留到真实调用才暴露
            if (!target.embedding()) {
                List<String> models = properties.getProviders().get(target.provider()).getModels();
                if (models != null && !models.isEmpty() && !models.contains(target.model())) {
                    throw BizException.of(400, "SYS_PARAM_INVALID",
                            "入口 " + entry.getKey() + " 的模型 " + target.model() + " 不在 provider "
                                    + target.provider() + " 的支持清单内");
                }
            }
            validated.put(entry.getKey(), target);
        }
        return validated;
    }

    private LlmPurpose parsePurpose(String code) {
        try {
            return LlmPurpose.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw BizException.of(400, "SYS_PARAM_INVALID", "未知 LLM 入口：" + code);
        }
    }

    private List<DailyPoint> recentDaily() {
        Map<String, Long> byDate = new LinkedHashMap<>();
        for (LlmUsageDaily row : usageService.recentRows(7)) {
            byDate.merge(row.getStatDate(), row.getCallCount() == null ? 0L : row.getCallCount(), Long::sum);
        }
        List<DailyPoint> points = new ArrayList<>();
        byDate.forEach((date, count) -> points.add(new DailyPoint(date, count)));
        return points;
    }

    public record RouteUpdateReq(@NotNull(message = "路由不能为空") Map<String, RouteTarget> routes) {
    }
}