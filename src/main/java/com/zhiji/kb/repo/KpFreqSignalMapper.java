package com.zhiji.kb.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.kb.domain.KpFreqSignal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface KpFreqSignalMapper extends BaseMapper<KpFreqSignal> {

    @Select("SELECT * FROM kp_freq_signal WHERE kp_id = #{kpId} ORDER BY created_at DESC, id DESC")
    List<KpFreqSignal> selectByKpId(@Param("kpId") long kpId);

    @Select("SELECT * FROM kp_freq_signal WHERE kp_id = #{kpId} AND source = 'manual' ORDER BY id LIMIT 1")
    KpFreqSignal selectManual(@Param("kpId") long kpId);

    @Update("UPDATE kp_freq_signal SET signal_level = #{signalLevel}, note = #{note} WHERE id = #{id}")
    int updateManual(@Param("id") long id, @Param("signalLevel") String signalLevel,
            @Param("note") String note);
}
