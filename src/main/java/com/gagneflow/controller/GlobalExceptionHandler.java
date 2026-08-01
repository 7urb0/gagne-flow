package com.gagneflow.controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private Map<String, Object> buildErrorBody(int status, String error, String message) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        return body;
    }

    @ExceptionHandler(value={GraphRunnerException.class})
    public ResponseEntity<Map<String, Object>> handleGraphRunner(GraphRunnerException e) {
        logger.error("Agent \u6267\u884c\u5931\u8d25", (Throwable)e);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Agent Error", e.getMessage()));
    }

    @ExceptionHandler(value={MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        logger.warn("@Valid \u6821\u9a8c\u5931\u8d25: {}", (Object)detail);
        return ResponseEntity.badRequest().body(this.buildErrorBody(400, "Validation Failed", detail));
    }

    @ExceptionHandler(value={IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("\u53c2\u6570\u6821\u9a8c\u5931\u8d25: {}", (Object)e.getMessage());
        return ResponseEntity.badRequest().body(this.buildErrorBody(400, "Bad Request", e.getMessage()));
    }

    @ExceptionHandler(value={SecurityException.class})
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        logger.warn("\u5b89\u5168\u6821\u9a8c\u5931\u8d25: {}", (Object)e.getMessage());
        return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body(this.buildErrorBody(403, "Forbidden", e.getMessage()));
    }

    @ExceptionHandler(value={IOException.class})
    public ResponseEntity<Map<String, Object>> handleIO(IOException e) {
        logger.error("IO \u5f02\u5e38: {}", (Object)e.getMessage());
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Internal Server Error", "\u6587\u4ef6\u64cd\u4f5c\u5931\u8d25: " + e.getMessage()));
    }

    @ExceptionHandler(value={RuntimeException.class})
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        logger.error("\u8fd0\u884c\u65f6\u5f02\u5e38", (Throwable)e);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Internal Server Error", e.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        logger.warn("JSR-303 校验失败: {}", detail);
        return ResponseEntity.badRequest()
                .body(this.buildErrorBody(400, "Constraint Violation", detail));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        logger.warn("请求体 JSON 解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(this.buildErrorBody(400, "Bad Request", "请求体格式错误，请检查 JSON 格式"));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        logger.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(this.buildErrorBody(400, "Bad Request", "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(value={Exception.class})
    public ResponseEntity<Map<String, Object>> handleAll(Exception e) {
        logger.error("\u672a\u6355\u83b7\u5f02\u5e38", (Throwable)e);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Internal Server Error", "\u670d\u52a1\u5668\u5185\u90e8\u9519\u8bef\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"));
    }
}
