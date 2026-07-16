package com.wyc.demo03.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.entity.Log;
import com.wyc.demo03.mapper.LogMapper;
import com.wyc.demo03.service.LogService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class LogServiceImpl extends ServiceImpl<LogMapper, Log> implements LogService {

    @Override
    public List<Map<String, Object>> countByUrl() {
        return baseMapper.countByUrl();
    }

    @Override
    public List<Map<String, Object>> countTop6ByUrl() {
        return baseMapper.countTop6ByUrl();
    }

    @Override
    public List<Map<String, Object>> countByUsername() {
        return baseMapper.countByUsername();
    }

    @Override
    public List<Map<String, Object>> countTop6ByUsername() {
        return baseMapper.countTop6ByUsername();
    }

    @Override
    public List<Map<String, Object>> countByDay() {
        return baseMapper.countByDay();
    }

    @Override
    @Async
    public void saveAsync(Log log) {
        super.save(log);
    }
}
