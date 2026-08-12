package com.wyc.demo03.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 全局异常处理：
 * - /api/* JSON 接口 → 返回统一错误信封 { success:false, message }
 * - 页面请求 → 服务端记录详细日志，浏览器重定向回首页（不向用户暴露堆栈）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 服务端记录完整堆栈，便于排查；对外只给通用提示，不泄露内部细节
        log.error("请求处理异常: {} {}", request.getMethod(), request.getRequestURI(), e);

        if (request.getRequestURI().startsWith("/api/")) {
            return ApiResponse.fail("服务器内部错误，请稍后重试");
        }
        response.sendRedirect("/");
        return null;
    }
}
