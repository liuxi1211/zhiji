package com.zhiji.learn.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.domain.LearnStatus;
import com.zhiji.learn.repo.LearnStateMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识点学习状态服务（核心模块定义 4.2/6.5）：五态状态机的唯一写入口。
 *
 * 状态机：learning → quick|pass|weak；weak → quick|pass；abandoned → learning（恢复）；任意 → abandoned。
 * quick/pass/weak 均算已推进（progressed_at 落时间，T12 间隔重复起点）；progressed_at 仅首次设置，
 * 已推进的行再推进不刷新起点（除非被放弃清空后重新推进）。
 * 不存在的行视为 learning（getOwned 返回 null，不建行）——路径视图的 state 由此推导。
 */
@Service
public class LearnStateService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LearnStateMapper mapper;

    public LearnStateService(LearnStateMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取一行状态；无行返回 null（调用方视为 learning）。 */
    public LearnState getOwned(long profileId, long kpId) {
        return mapper.selectOne(new LambdaQueryWrapper<LearnState>()
                .eq(LearnState::getProfileId, profileId)
                .eq(LearnState::getKpId, kpId));
    }

    /**
     * 练习开局（R9）：建行或把已有行的 session_id 指向新会话，状态保持/回落到 learning。
     * 已 pass/quick 的行重练不降级（progressed_at 保留）；abandoned 的行被重新开练则复活为 learning。
     */
    @Transactional
    public LearnState upsertLearning(long profileId, long kpId, Long sessionId) {
        LearnState row = getOwned(profileId, kpId);
        String now = now();
        if (row == null) {
            row = new LearnState();
            row.setProfileId(profileId);
            row.setKpId(kpId);
            row.setStatus(LearnStatus.LEARNING.getCode());
            row.setSessionId(sessionId);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            mapper.insert(row);
            return row;
        }
        // 放弃的行重新开练即退出放弃清单，回到进行中
        if (LearnStatus.ABANDONED.getCode().equals(row.getStatus())) {
            row.setStatus(LearnStatus.LEARNING.getCode());
            row.setAbandonReason(null);
            row.setProgressedAt(null);
        }
        row.setSessionId(sessionId);
        row.setUpdatedAt(now);
        mapper.updateById(row);
        return row;
    }

    /**
     * 速览自评（R5）：known → quick（抽查范围）；unknown → weak（弱点加权）。两态均算已推进。
     * accepted 原 abandoned 行：先复活再落态。
     */
    @Transactional
    public LearnState markQuick(long profileId, long kpId, boolean known) {
        LearnState row = requireOrCreate(profileId, kpId);
        String now = now();
        row.setStatus(known ? LearnStatus.QUICK.getCode() : LearnStatus.WEAK.getCode());
        row.setAbandonReason(null);
        if (row.getProgressedAt() == null) {
            row.setProgressedAt(now);
        }
        row.setUpdatedAt(now);
        mapper.updateById(row);
        return row;
    }

    /**
     * 练习判定通过（R8）：close 且 grade ∈ {pass, good, excellent} 时回写。progressed_at 仅首次设置。
     * 已 quick/weak 的题被练习判定通过后升级为 pass（实心）。
     */
    @Transactional
    public LearnState markPassed(long profileId, long kpId, Long sessionId) {
        LearnState row = requireOrCreate(profileId, kpId);
        String now = now();
        row.setStatus(LearnStatus.PASS.getCode());
        row.setAbandonReason(null);
        if (sessionId != null) {
            row.setSessionId(sessionId);
        }
        if (row.getProgressedAt() == null) {
            row.setProgressedAt(now);
        }
        row.setUpdatedAt(now);
        mapper.updateById(row);
        return row;
    }

    /** 练习未通过（fail/未评级，R8）：保持 learning（可重练），不动 progressed_at。 */
    @Transactional
    public LearnState markLearning(long profileId, long kpId, Long sessionId) {
        LearnState row = requireOrCreate(profileId, kpId);
        String now = now();
        // 仅在尚未推进时回落 learning；已 pass/quick/weak 的行不因一次失败降级（进度只增不减）
        if (!LearnStatus.isProgressed(row.getStatus())) {
            row.setStatus(LearnStatus.LEARNING.getCode());
        }
        if (sessionId != null) {
            row.setSessionId(sessionId);
        }
        row.setUpdatedAt(now);
        mapper.updateById(row);
        return row;
    }

    /** 放弃（4.2 唯一人工裁剪例外）：移出单元、进放弃清单、可选填原因；progressed_at 清空。 */
    @Transactional
    public LearnState abandon(long profileId, long kpId, String reason) {
        LearnState row = requireOrCreate(profileId, kpId);
        String now = now();
        row.setStatus(LearnStatus.ABANDONED.getCode());
        row.setAbandonReason(reason == null || reason.isBlank() ? null : reason.strip());
        row.setProgressedAt(null);
        row.setUpdatedAt(now);
        mapper.updateById(row);
        return row;
    }

    /** 恢复：abandoned → learning，清原因与 progressed_at。非 abandoned 行幂等返回。 */
    @Transactional
    public LearnState restore(long profileId, long kpId) {
        LearnState row = requireOrCreate(profileId, kpId);
        if (LearnStatus.ABANDONED.getCode().equals(row.getStatus())) {
            String now = now();
            row.setStatus(LearnStatus.LEARNING.getCode());
            row.setAbandonReason(null);
            row.setProgressedAt(null);
            row.setUpdatedAt(now);
            mapper.updateById(row);
        }
        return row;
    }

    // —— 批量读（路径视图用） ——

    public Map<Long, LearnState> byProfile(long profileId) {
        return mapper.selectList(new LambdaQueryWrapper<LearnState>()
                        .eq(LearnState::getProfileId, profileId))
                .stream().collect(Collectors.toMap(LearnState::getKpId, row -> row, (a, b) -> a));
    }

    /** 已推进的 kp id 集合（quick/pass/weak），前置锁推导用（R6）。 */
    public Set<Long> progressedSet(long profileId) {
        return mapper.selectList(new LambdaQueryWrapper<LearnState>()
                        .eq(LearnState::getProfileId, profileId)
                        .in(LearnState::getStatus, List.of(LearnStatus.QUICK.getCode(),
                                LearnStatus.PASS.getCode(), LearnStatus.WEAK.getCode())))
                .stream().map(LearnState::getKpId).collect(Collectors.toSet());
    }

    /** 放弃清单（路径视图底部分区用）：只含 abandoned 行。 */
    public List<LearnState> listAbandoned(long profileId) {
        return mapper.selectList(new LambdaQueryWrapper<LearnState>()
                .eq(LearnState::getProfileId, profileId)
                .eq(LearnState::getStatus, LearnStatus.ABANDONED.getCode())
                .orderByDesc(LearnState::getUpdatedAt));
    }

    /** 级联清理（R14）：档案删除时清掉名下全部学习状态。 */
    public void deleteByProfile(long profileId) {
        mapper.delete(new LambdaQueryWrapper<LearnState>().eq(LearnState::getProfileId, profileId));
    }

    // —— 内部 ——

    private LearnState requireOrCreate(long profileId, long kpId) {
        LearnState row = getOwned(profileId, kpId);
        if (row != null) {
            return row;
        }
        LearnState fresh = new LearnState();
        fresh.setProfileId(profileId);
        fresh.setKpId(kpId);
        fresh.setStatus(LearnStatus.LEARNING.getCode());
        String now = now();
        fresh.setCreatedAt(now);
        fresh.setUpdatedAt(now);
        mapper.insert(fresh);
        return fresh;
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
