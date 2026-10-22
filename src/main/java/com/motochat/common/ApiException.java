package com.motochat.common;

/**
 * API 层统一异常，对应 Python 版的 core/responses.APIError。
 * 抛出后由 GlobalExceptionHandler 转成 {"success": false, "error": "..."}。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String detail;

    public ApiException(int status, String message) {
        this(status, message, null);
    }

    public ApiException(int status, String message, String detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}
