package com.wyc.demo03.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 单元测试：API 请求返回错误信封，页面请求重定向首页。
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiRequestReturnsErrorEnvelope() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/room-schedule");

        // Act
        Object result = handler.handleException(new RuntimeException("db down"), request, response);

        // Assert
        ApiResponse<Object> envelope = (ApiResponse<Object>) result;
        assertFalse(envelope.success());
        assertNull(envelope.data());
        assertEquals("服务器内部错误，请稍后重试", envelope.message());
        verify(response, never()).sendRedirect(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pageRequestRedirectsHome() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/rooms");

        // Act
        Object result = handler.handleException(new RuntimeException("db down"), request, response);

        // Assert
        assertNull(result);
        verify(response).sendRedirect("/");
    }

    @Test
    void okEnvelopeCarriesData() {
        // Act
        ApiResponse<String> ok = ApiResponse.ok("payload");

        // Assert
        assertTrue(ok.success());
        assertEquals("payload", ok.data());
        assertNull(ok.message());
    }

    @Test
    void failEnvelopeCarriesMessage() {
        // Act
        ApiResponse<String> fail = ApiResponse.fail("错误");

        // Assert
        assertFalse(fail.success());
        assertNull(fail.data());
        assertEquals("错误", fail.message());
    }
}
