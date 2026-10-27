package dev.conduit.server.web;

import org.springframework.http.HttpStatus;

/**
 * 业务层抛出的、需要映射为特定 HTTP 状态码的异常，由 {@link GlobalExceptionHandler} 转成 problem+json。
 * 优先使用 badRequest / unauthorized / notFound / conflict 工厂方法。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, null);
    }

    /**
     * 带稳定错误码的异常：code 会写入 ProblemDetail 的 code 属性，供前端按码区分处理，
     * 而不是匹配可能变动的中文提示。
     */
    public ApiException(HttpStatus status, String detail, String code) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** 稳定错误码；没有时返回 null。 */
    public String code() {
        return code;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
