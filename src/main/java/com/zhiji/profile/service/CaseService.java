package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.profile.domain.PrfCase;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.repo.PrfCaseMapper;
import com.zhiji.profile.service.ProfileService.CaseView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目素材服务（核心模块定义 4.1）：建档时沉淀项目细节（背景、数字、冲突、结果），
 * 供项目叙事练习与模拟考追问使用（消费方 T11+）。
 */
@Service
public class CaseService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final int MAX_NAME_CHARS = 80;
    public static final int MAX_FIELD_CHARS = 2_000;

    /** 每档案项目素材上限（D4 防滥用） */
    public static final int MAX_CASES = 20;

    private final PrfCaseMapper caseMapper;

    public CaseService(PrfCaseMapper caseMapper) {
        this.caseMapper = caseMapper;
    }

    public record CaseReq(String projectName, String background, String metrics, String conflict, String result) {
    }

    public List<CaseView> listByProfile(long profileId) {
        return caseMapper.selectList(new LambdaQueryWrapper<PrfCase>()
                .eq(PrfCase::getProfileId, profileId)
                .orderByAsc(PrfCase::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public CaseView add(PrfProfile profile, CaseReq req) {
        long count = caseMapper.selectCount(new LambdaQueryWrapper<PrfCase>()
                .eq(PrfCase::getProfileId, profile.getId()));
        if (count >= MAX_CASES) {
            throw BizException.conflict("PRF_CASE_LIMIT", "每份档案最多 " + MAX_CASES + " 条项目素材");
        }
        PrfCase item = new PrfCase();
        item.setProfileId(profile.getId());
        applyReq(item, req);
        String now = LocalDateTime.now().format(TS);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        caseMapper.insert(item);
        return toView(item);
    }

    @Transactional
    public CaseView update(PrfProfile profile, long caseId, CaseReq req) {
        PrfCase item = requireOwnedCase(profile, caseId);
        applyReq(item, req);
        item.setUpdatedAt(LocalDateTime.now().format(TS));
        caseMapper.updateById(item);
        return toView(item);
    }

    @Transactional
    public void delete(PrfProfile profile, long caseId) {
        PrfCase item = requireOwnedCase(profile, caseId);
        caseMapper.deleteById(item.getId());
    }

    // —— 内部 ——

    private void applyReq(PrfCase item, CaseReq req) {
        if (req.projectName() == null || req.projectName().isBlank()) {
            throw BizException.validation("项目名不能为空");
        }
        String name = req.projectName().strip();
        if (name.length() > MAX_NAME_CHARS) {
            throw BizException.validation("项目名长度 " + MAX_NAME_CHARS + " 字以内");
        }
        item.setProjectName(name);
        item.setBackground(requireField(req.background(), "背景"));
        item.setMetrics(requireField(req.metrics(), "数字"));
        item.setConflict(requireField(req.conflict(), "冲突"));
        item.setResult(requireField(req.result(), "结果"));
    }

    private String requireField(String value, String label) {
        String text = value == null ? "" : value.strip();
        if (text.length() > MAX_FIELD_CHARS) {
            throw BizException.validation(label + "长度 " + MAX_FIELD_CHARS + " 字以内");
        }
        return text;
    }

    private PrfCase requireOwnedCase(PrfProfile profile, long caseId) {
        PrfCase item = caseMapper.selectById(caseId);
        if (item == null || item.getProfileId() == null || item.getProfileId() != profile.getId().longValue()) {
            throw BizException.notFound("项目素材不存在");
        }
        return item;
    }

    private CaseView toView(PrfCase item) {
        return new CaseView(item.getId(), item.getProjectName(), item.getBackground(), item.getMetrics(),
                item.getConflict(), item.getResult(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
