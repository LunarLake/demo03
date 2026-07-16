package com.wyc.demo03.common;

import com.wyc.demo03.entity.Log;
import com.wyc.demo03.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

@Component
public class LogInterceptor implements HandlerInterceptor {

    private final LogService logService;

    public LogInterceptor(LogService logService) {
        this.logService = logService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String username = (String) request.getSession().getAttribute("username");
        if (username == null) {
            username = "匿名";
        }

        Log sysLog = new Log();
        sysLog.setUsername(username);
        sysLog.setIp(request.getRemoteAddr());
        sysLog.setUrl(request.getRequestURI());
        sysLog.setTimestamp(LocalDateTime.now());

        logService.saveAsync(sysLog);

        return true;
    }
}
