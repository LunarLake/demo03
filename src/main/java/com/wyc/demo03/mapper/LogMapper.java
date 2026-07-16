package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.Log;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface LogMapper extends BaseMapper<Log> {
    // 统计访问来源（User-Agent）分布，返回列表包含访问来源名称和对应的访问次数，按访问次数降序排序
    @Select("SELECT url AS label, COUNT(*) AS count FROM t_log GROUP BY url ORDER BY count DESC, label ASC")
    List<Map<String, Object>> countByUrl();
    // 统计访问来源（User-Agent）分布，返回访问次数排名前 6 的访问来源名称和对应的访问次数，按访问次数降序排序
    @Select("SELECT url AS label, COUNT(*) AS count FROM t_log GROUP BY url ORDER BY count DESC, label ASC LIMIT 6")
    List<Map<String, Object>> countTop6ByUrl();
    // 统计访问用户分布，返回列表包含用户名和对应的访问次数，按访问次数降序排序
    @Select("SELECT username AS label, COUNT(*) AS count FROM t_log GROUP BY username ORDER BY count DESC, label ASC")
    List<Map<String, Object>> countByUsername();
    // 统计访问用户分布，返回访问次数排名前 6 的用户名和对应的访问次数，按访问次数降序排序
    @Select("SELECT username AS label, COUNT(*) AS count FROM t_log GROUP BY username ORDER BY count DESC, label ASC LIMIT 6")
    List<Map<String, Object>> countTop6ByUsername();
    // 统计访问时间分布，返回列表包含访问日期（格式为 "YYYY-MM-DD"）和对应的访问次数，按访问次数降序排序
    @Select("SELECT DATE_FORMAT(timestamp, '%Y-%m-%d') AS label, COUNT(*) AS count FROM t_log GROUP BY DATE_FORMAT(timestamp, '%Y-%m-%d') ORDER BY count DESC, label ASC")
    List<Map<String, Object>> countByDay();
}
