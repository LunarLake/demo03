package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 7 天滚动清理（文档 7.2），返回删除行数。 */
    @Delete("DELETE FROM t_audit_log WHERE created_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
