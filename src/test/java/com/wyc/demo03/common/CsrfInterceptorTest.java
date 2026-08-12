package com.wyc.demo03.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CsrfInterceptor 单元测试：覆盖 GET 生成 token、POST 校验通过/失败、无会话拒绝。
 */
@ExtendWith(MockitoExtension.class)
class CsrfInterceptorTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;

    private CsrfInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CsrfInterceptor();
    }

    @Test
    void getRequestGeneratesTokenAndExposesItToView() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("GET");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn(null);

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        // token 已写入 session 并通过 request attribute 暴露给模板
        verify(session).setAttribute(org.mockito.ArgumentMatchers.eq(CsrfInterceptor.SESSION_ATTR), anyString());
        verify(request).setAttribute(org.mockito.ArgumentMatchers.eq(CsrfInterceptor.REQUEST_ATTR), anyString());
    }

    @Test
    void postWithMatchingFormTokenPasses() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn("token123");
        when(request.getParameter(CsrfInterceptor.PARAM_NAME)).thenReturn("token123");

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void postWithMatchingHeaderTokenPasses() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn("token123");
        when(request.getParameter(CsrfInterceptor.PARAM_NAME)).thenReturn(null);
        when(request.getHeader(CsrfInterceptor.HEADER_NAME)).thenReturn("token123");

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
    }

    @Test
    void postWithWrongTokenIsRejected() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn("token123");
        when(request.getParameter(CsrfInterceptor.PARAM_NAME)).thenReturn("forged");

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
        verify(response).sendRedirect("/");
    }

    @Test
    void postWithoutTokenIsRejected() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn("token123");
        when(request.getParameter(CsrfInterceptor.PARAM_NAME)).thenReturn(null);
        when(request.getHeader(CsrfInterceptor.HEADER_NAME)).thenReturn(null);

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
    }

    @Test
    void postWithoutSessionIsRejected() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");
        when(request.getSession(false)).thenReturn(null);

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
        verify(response).sendRedirect("/");
    }

    @Test
    void getWithoutSessionDoesNotCreateOne() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("GET");
        when(request.getSession(false)).thenReturn(null);

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        verify(request, never()).setAttribute(anyString(), anyString());
    }

    @Test
    void existingTokenIsReusedOnGet() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("GET");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CsrfInterceptor.SESSION_ATTR)).thenReturn("existing");

        // Act
        boolean result = interceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        verify(session, never()).setAttribute(org.mockito.ArgumentMatchers.eq(CsrfInterceptor.SESSION_ATTR), anyString());
        verify(request).setAttribute(CsrfInterceptor.REQUEST_ATTR, "existing");
    }
}
