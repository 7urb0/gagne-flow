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
        logger.error("Agent 执行失败", (Throwable)e);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Agent Error", e.getMessage()));
    }

    @ExceptionHandler(value={MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        logger.warn("@Valid 校验失败: {}", (Object)detail);
        return ResponseEntity.badRequest().body(this.buildErrorBody(400, "Validation Failed", detail));
    }

    @ExceptionHandler(value={IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("参数校验失败: {}", (Object)e.getMessage());
        return ResponseEntity.badRequest().body(this.buildErrorBody(400, "Bad Request", e.getMessage()));
    }

    @ExceptionHandler(value={SecurityException.class})
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        logger.warn("安全校验失败: {}", (Object)e.getMessage());
        return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body(this.buildErrorBody(403, "Forbidden", e.getMessage()));
    }

    @ExceptionHandler(value={IOException.class})
    public ResponseEntity<Map<String, Object>> handleIO(IOException e) {
        logger.error("IO 异常: {}", (Object)e.getMessage());
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Internal Server Error", "文件操作失败: " + e.getMessage()));
    }

    @ExceptionHandler(value={org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        logger.warn("请求的资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(this.buildErrorBody(404, "Not Found", "请求的资源不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(value={RuntimeException.class})
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        logger.error("运行时异常", (Throwable)e);
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
        logger.error("未捕获异常", (Throwable)e);
        return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(this.buildErrorBody(500, "Internal Server Error", "服务器内部错误，请稍后重试"));
    }
}