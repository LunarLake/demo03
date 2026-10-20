package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.RuleConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleConfigMapper extends BaseMapper<RuleConfig> {

    /** 某一版本的全部参数。 */
    @Select("SELECT * FROM t_rule_config WHERE version_id = #{versionId} ORDER BY param_key")
    List<RuleConfig> findByVersionId(@Param("versionId") Long versionId);
}
