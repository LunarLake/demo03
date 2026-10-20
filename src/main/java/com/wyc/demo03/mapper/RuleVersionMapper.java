package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.RuleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface RuleVersionMapper extends BaseMapper<RuleVersion> {

    /** 取当前生效的最新版本（{@code effective_from <= now} 中版本号最大者）。 */
    @Select("SELECT * FROM t_rule_version WHERE effective_from <= #{now} "
            + "ORDER BY version_no DESC LIMIT 1")
    RuleVersion findCurrent(LocalDateTime now);

    /** 当前最大版本号；空表时返回 null。 */
    @Select("SELECT MAX(version_no) FROM t_rule_version")
    Integer maxVersionNo();
}
