package com.wyc.demo03.common;

/**
 * 统一 API 响应信封：{ success, data, message }。
 * 所有 /api/* JSON 接口均使用此结构，前端据此区分业务成功与系统错误。
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
