package com.zhiji.common.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.common.domain.SysConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SysConfigMapper extends BaseMapper<SysConfig> {

    @Update("UPDATE sys_config SET config_value = #{value}, updated_at = datetime('now') WHERE config_key = #{key}")
    int updateValue(@Param("key") String key, @Param("value") String value);
}
