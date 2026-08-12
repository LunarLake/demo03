package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.Log;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface LogService extends IService<Log> {
    List<Map<String, Object>> countByUrl();
    List<Map<String, Object>> countTop6ByUrl();
    List<Map<String, Object>> countByUsername();
    List<Map<String, Object>> countTop6ByUsername();
    List<Map<String, Object>> countByDay();
    // 记录日志，异步执行
    void saveAsync(Log log);
    // 删除指定时间之前的日志（定期清理用），返回删除行数
    int deleteBefore(LocalDateTime cutoff);
}
