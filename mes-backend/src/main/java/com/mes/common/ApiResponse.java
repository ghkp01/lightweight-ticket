package com.mes.common;

public record ApiResponse(int code, String message, Object data) {

    public static ApiResponse ok(Object data) {
        return new ApiResponse(0, "ok", data);
    }

    public static ApiResponse ok() {
        return new ApiResponse(0, "ok", null);
    }

    public static ApiResponse error(int code, String message) {
        return new ApiResponse(code, message, null);
    }
}
