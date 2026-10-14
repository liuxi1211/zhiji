package com.zhiji.llm.api;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.config.LlmPromptSection;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmPromptConfig;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 运维接口（/api/admin/settings，仅 admin——权限矩阵见基础架构PRD 3.3）。
 * 只承载「操作与监控」与提示词默认值参照：连通性自检、用量对照、prompt 恢复默认取数；
 * 渠道（llm.channel）/ 模型路由（llm.route）/ 限额（llm.limits）/ 提示词（llm.prompt）的读写
 * 均走通用配置接口 /api/admin/configs，渠道密钥经脱敏下发（掩码 ****abcd，真实值不出后端）。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {

    private final LlmConfigService configService;
    private final LlmHealthService healthService;
    private final LlmUsageService usageService;
    private final ConfigRegistry registry;

    public SettingsController(LlmConfigService configService, LlmHealthService healthService,
            LlmUsageService usageService, ConfigRegistry registry) {
        this.configService = configService;
        this.healthService = healthService;
        this.usageService = usageService;
        this.registry = registry;
    }

    /**
     * 用量视图（成本治理 2026-10 统一规划）：
     * C 端列表按账号聚合（日/周/月计数 + 对应限额的超限标记），B 端列表按入口给当日明细
     * 并附全站三窗口汇总；全站熔断状态与近 7 天趋势保留。计数主体键见 LlmUsageService。
     */
    public record CustomerUsageRow(long accountId, String username, long daily, long weekly, long monthly,
            boolean dailyExceeded, boolean weeklyExceeded, boolean monthlyExceeded) {
    }

    public record StaffUsageRow(String purpose, String purposeName, long today) {
    }

    public record DailyPoint(String date, long callCount) {
    }

    public record UsageView(String date, List<CustomerUsageRow> customers, long customerDailyLimit,
            long customerWeeklyLimit, long customerMonthlyLimit, List<StaffUsageRow> staff,
            long staffTodayTotal, long staffDaily, long staffWeekly, long staffMonthly,
            long todayTotal, long siteDailyTotal, boolean circuitOpen, List<DailyPoint> recent7d) {
    }

    @PostMapping("/connectivity-check")
    public LlmConnectivityReport connectivityCheck() {
        return healthService.check();
    }

    /** 提示词「恢复默认」参照：classpath 文件版模板（code → 文本），LLM 管理页提示词 tab 取用。 */
    @GetMapping("/prompt-defaults")
    public Map<String, String> promptDefaults() {
        LlmPromptConfig defaults = registry.defaults(LlmPromptSection.KEY);
        return defaults.templates();
    }

    /** 渠道级连通测试请求体。 */
    public record ChannelCheckReq(String channel) {
    }

    /** 单渠道连通测试（渠道卡「测试连通」按钮）：对该渠道已保存配置发起一次最小真实请求。 */
    @PostMapping("/channel-check")
    public LlmHealthService.ChannelProbe channelCheck(@RequestBody ChannelCheckReq req) {
        if (req == null || req.channel() == null || req.channel().isBlank()) {
            throw BizException.validation("渠道名不能为空");
        }
        return healthService.probeChannel(req.channel());
    }

    @GetMapping("/usage")
    public UsageView usage() {
        LlmLimits limits = configService.limits();

        // C 端：本月窗口内按账号聚合（日/周/月），账号名补齐展示
        Map<Long, LlmUsageService.WindowCounts> byAccount = usageService.customerWindowCounts();
        Map<Long, String> names = usageService.accountNames(byAccount.keySet());
        List<CustomerUsageRow> customers = byAccount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    LlmUsageService.WindowCounts counts = entry.getValue();
                    return new CustomerUsageRow(entry.getKey(),
                            names.getOrDefault(entry.getKey(), "—"),
                            counts.daily(), counts.weekly(), counts.monthly(),
                            counts.daily() >= limits.customerDaily(),
                            counts.weekly() >= limits.customerWeekly(),
                            counts.monthly() >= limits.customerMonthly());
                })
                .toList();

        // B 端：全站三窗口汇总 + 按入口的当日明细（入口细分仅用于观测，限流在总体口径）
        LlmUsageService.WindowCounts staffCounts = usageService.staffWindowCounts();
        Map<String, Long> staffTodayByPurpose = new LinkedHashMap<>();
        for (LlmUsageDaily row : usageService.todayRows()) {
            if (LlmPurpose.isValid(row.getPurpose())
                    && LlmPurpose.fromCode(row.getPurpose()).side() == LlmPurpose.Side.B) {
                long count = row.getCallCount() == null ? 0L : row.getCallCount();
                staffTodayByPurpose.merge(row.getPurpose(), count, Long::sum);
            }
        }
        List<StaffUsageRow> staff = new ArrayList<>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            if (purpose.side() == LlmPurpose.Side.B) {
                staff.add(new StaffUsageRow(purpose.getCode(), purpose.getName(),
                        staffTodayByPurpose.getOrDefault(purpose.getCode(), 0L)));
            }
        }

        long todayTotal = usageService.todayTotal();
        return new UsageView(usageService.today(), customers, limits.customerDaily(),
                limits.customerWeekly(), limits.customerMonthly(), staff,
                staffTodayByPurpose.values().stream().mapToLong(Long::longValue).sum(),
                limits.staffDaily(), limits.staffWeekly(), limits.staffMonthly(),
                todayTotal, limits.siteDailyTotal(), todayTotal >= limits.siteDailyTotal(),
                recentDaily());
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
