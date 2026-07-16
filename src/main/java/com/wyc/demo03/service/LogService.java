package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.Log;

import java.util.List;
import java.util.Map;
// 还没用到，先写着
public interface LogService extends IService<Log> {
    List<Map<String, Object>> countByUrl();
    List<Map<String, Object>> countTop6ByUrl();
    List<Map<String, Object>> countByUsername();
    List<Map<String, Object>> countTop6ByUsername();
    List<Map<String, Object>> countByDay();
    // 记录日志，异步执行
    void saveAsync(com.wyc.demo03.entity.Log log);
}
