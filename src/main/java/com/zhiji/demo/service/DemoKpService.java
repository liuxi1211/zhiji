package com.zhiji.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhiji.demo.domain.DemoKp;
import com.zhiji.demo.repo.DemoKpMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 演示服务：一条写入路径 + 一条带本地缓存的读取路径。
 * dbHitCount 用于证明缓存真的命中了（第二次读不查库）。
 */
@Service
public class DemoKpService {

    public static final String CACHE = "demoKp";

    private final DemoKpMapper mapper;
    private final AtomicInteger dbHitCount = new AtomicInteger();

    public DemoKpService(DemoKpMapper mapper) {
        this.mapper = mapper;
    }

    public DemoKp save(DemoKp kp) {
        mapper.insert(kp);
        clearCache();
        return kp;
    }

    @Cacheable(value = CACHE, key = "#bizId")
    public DemoKp findByBizId(String bizId) {
        dbHitCount.incrementAndGet();
        return mapper.selectOne(new QueryWrapper<DemoKp>().eq("biz_id", bizId));
    }

    public List<DemoKp> listAll() {
        return mapper.selectList(null);
    }

    @CacheEvict(value = CACHE, allEntries = true)
    public void clearCache() {
        // 注解负责清缓存，方法体故意留空
    }

    /** 真实落库次数，用于验证缓存命中（应远小于接口调用次数）。 */
    public int dbHitCount() {
        return dbHitCount.get();
    }
}