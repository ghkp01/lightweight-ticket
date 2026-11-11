package com.mes.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse handleBiz(BizException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse handleValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("参数校验失败");
        return ApiResponse.error(1001, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse handleUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResponse.error(1002, "附件超过 20MB 上限，请压缩后再上传");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("data integrity violation", ex);
        return ApiResponse.error(1409, "数据被其他单据引用或存在重复编码，操作已取消");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error(403, "没有该操作的权限");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse handleNotFound(NoResourceFoundException ex) {
        return ApiResponse.error(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse handleOther(Exception ex) {
        log.error("unexpected error", ex);
        return ApiResponse.error(5000, "系统异常：" + ex.getMessage());
    }
}
