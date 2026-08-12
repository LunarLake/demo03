package com.wyc.demo03.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.SecureRandom;

/**
 * CSRF 防护拦截器（Synchronizer Token 模式）：
 * - 每个会话持有唯一 token（session 属性 _csrf），GET 渲染时通过 request attribute 暴露给模板
 * - POST 请求必须携带与 session 一致的 token（表单参数 _csrf 或请求头 X-CSRF-TOKEN），否则拒绝
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    public static final String SESSION_ATTR = "_csrf";
    public static final String REQUEST_ATTR = "_csrf";
    public static final String PARAM_NAME = "_csrf";
    public static final String HEADER_NAME = "X-CSRF-TOKEN";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);

        // 无会话的请求（如匿名直连 POST）：直接拒绝写操作
        if (session == null) {
            return "POST".equalsIgnoreCase(request.getMethod()) ? reject(response) : true;
        }

        String token = (String) session.getAttribute(SESSION_ATTR);
        // 会话尚无 token → 生成并存入（GET 建立 token，POST 不允许现场生成）
        if (token == null) {
            token = generateToken();
            session.setAttribute(SESSION_ATTR, token);
        }

        // 暴露给模板：<input type="hidden" name="_csrf" th:value="${_csrf}">
        request.setAttribute(REQUEST_ATTR, token);

        // 写操作校验 token
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String submitted = request.getParameter(PARAM_NAME);
            if (submitted == null) {
                submitted = request.getHeader(HEADER_NAME);
            }
            if (!token.equals(submitted)) {
                return reject(response);
            }
        }
        return true;
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.sendRedirect("/");
        return false;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
