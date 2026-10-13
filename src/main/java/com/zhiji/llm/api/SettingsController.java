package com.zhiji.llm.api;

import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmHealthService;
import com.zhiji.llm.service.LlmUsageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 运维接口（/api/admin/settings，仅 admin——权限矩阵见基础架构PRD 3.3）。
 * 只承载「操作与监控」：连通性自检、用量对照；模型路由与限额的读写已迁往通用配置接口
 * /api/admin/configs（llm.route / llm.limits 两个配置域）。密钥不在本接口暴露面内（只走 yml / 环境变量）。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {

    private final LlmConfigService configService;
    private final LlmHealthService healthService;
    private final LlmUsageService usageService;

    public SettingsController(LlmConfigService configService, LlmHealthService healthService,
            LlmUsageService usageService) {
        this.configService = configService;
        this.healthService = healthService;
        this.usageService = usageService;
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

    private List<DailyPoint> recentDaily() {
        Map<String, Long> byDate = new LinkedHashMap<>();
        for (LlmUsageDaily row : usageService.recentRows(7)) {
            byDate.merge(row.getStatDate(), row.getCallCount() == null ? 0L : row.getCallCount(), Long::sum);
        }
        List<DailyPoint> points = new ArrayList<>();
        byDate.forEach((date, count) -> points.add(new DailyPoint(date, count)));
        return points;
    }
}
