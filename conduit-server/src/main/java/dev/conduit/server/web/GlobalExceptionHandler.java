package dev.conduit.server.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * REST 接口的统一异常处理：把业务异常与数据库约束冲突转换为 RFC 7807 problem+json 响应；
 * 继承 ResponseEntityExceptionHandler，其余 Spring MVC 标准异常（如参数校验失败）沿用其默认映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 按异常自带的状态码与消息返回；异常带稳定错误码时写入 problem 的 code 属性。 */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        if (e.code() != null) {
            problem.setProperty("code", e.code());
        }
        return ResponseEntity.status(e.status()).body(problem);
    }

    /** 批量校验失败：400，并在 problem 的 errors 属性中列出逐项错误。 */
    @ExceptionHandler(ItemErrorsException.class)
    ResponseEntity<ProblemDetail> handleItemErrors(ItemErrorsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("errors", e.errors());
        return ResponseEntity.badRequest().body(problem);
    }

    /** 数据库完整性约束冲突（如外键被引用）：409，不向客户端暴露底层 SQL 错误。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "操作与现有数据冲突"));
    }

    /** 唯一键冲突：409「资源已存在」。DuplicateKeyException 是 DataIntegrityViolationException 的子类，匹配更具体的处理器。 */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "资源已存在"));
    }
}
