package com.zhiji.llm.repo;

import com.zhiji.llm.domain.AccountName;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 限流计数主体映射（SQL 层面关联 prf_profile / sys_customer，Java 层不依赖 profile 模块类型）：
 * C 端计数键为账号 id（sys_customer），档案 → 账号映射在此解析；用量列表取账号名。
 */
public interface LlmAccountMapper {

    @Select("SELECT customer_id FROM prf_profile WHERE id = #{profileId}")
    Long findCustomerIdByProfileId(@Param("profileId") long profileId);

    @Select("<script>SELECT id, username FROM sys_customer WHERE id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<AccountName> findNames(@Param("ids") List<Long> ids);
}
