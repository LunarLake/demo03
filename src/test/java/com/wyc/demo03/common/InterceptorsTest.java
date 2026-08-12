package com.wyc.demo03.common;

import com.wyc.demo03.entity.Log;
import com.wyc.demo03.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RoleInterceptor / AdminInterceptor / LogInterceptor 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class InterceptorsTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;
    @Mock
    private LogService logService;

    private RoleInterceptor roleInterceptor;
    private AdminInterceptor adminInterceptor;
    private LogInterceptor logInterceptor;

    @BeforeEach
    void setUp() {
        roleInterceptor = new RoleInterceptor();
        adminInterceptor = new AdminInterceptor();
        logInterceptor = new LogInterceptor(logService);
        when(request.getSession()).thenReturn(session);
    }

    // ============ RoleInterceptor ============

    @Test
    void roleInterceptorRedirectsToLoginWhenNotAuthenticated() throws Exception {
        // Arrange
        when(session.getAttribute("username")).thenReturn(null);

        // Act
        boolean result = roleInterceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
        verify(response).sendRedirect("/login");
    }

    @Test
    void roleInterceptorAllowsAuthenticatedUser() throws Exception {
        // Arrange
        when(session.getAttribute("username")).thenReturn("student01");

        // Act
        boolean result = roleInterceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        verify(response, never()).sendRedirect(anyString());
    }

    // ============ AdminInterceptor ============

    @Test
    void adminInterceptorRedirectsStudent() throws Exception {
        // Arrange
        when(session.getAttribute("role")).thenReturn("STUDENT");

        // Act
        boolean result = adminInterceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
        verify(response).sendRedirect("/");
    }

    @Test
    void adminInterceptorRedirectsTeacher() throws Exception {
        // Arrange
        when(session.getAttribute("role")).thenReturn("TEACHER");

        // Act
        boolean result = adminInterceptor.preHandle(request, response, null);

        // Assert
        assertFalse(result);
    }

    @Test
    void adminInterceptorAllowsAdmin() throws Exception {
        // Arrange
        when(session.getAttribute("role")).thenReturn("ADMIN");

        // Act
        boolean result = adminInterceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        verify(response, never()).sendRedirect(anyString());
    }

    // ============ LogInterceptor ============

    @Test
    void logInterceptorRecordsAnonymousWhenNotLoggedIn() throws Exception {
        // Arrange
        when(session.getAttribute("username")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRequestURI()).thenReturn("/rooms");

        // Act
        boolean result = logInterceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        ArgumentCaptor<Log> captor = ArgumentCaptor.forClass(Log.class);
        verify(logService).saveAsync(captor.capture());
        assertEquals("匿名", captor.getValue().getUsername());
        assertEquals("127.0.0.1", captor.getValue().getIp());
        assertEquals("/rooms", captor.getValue().getUrl());
        assertNotNull(captor.getValue().getTimestamp());
    }

    @Test
    void logInterceptorRecordsUsernameWhenLoggedIn() throws Exception {
        // Arrange
        when(session.getAttribute("username")).thenReturn("admin");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getRequestURI()).thenReturn("/admin/logs");

        // Act
        boolean result = logInterceptor.preHandle(request, response, null);

        // Assert
        assertTrue(result);
        ArgumentCaptor<Log> captor = ArgumentCaptor.forClass(Log.class);
        verify(logService).saveAsync(captor.capture());
        assertEquals("admin", captor.getValue().getUsername());
    }
}
