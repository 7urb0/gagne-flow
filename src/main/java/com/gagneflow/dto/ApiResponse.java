package com.gagneflow.dto;

/**
 * 统一 API 响应封装，供所有 Controller 共用。
 */
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T d) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = d;
        return r;
    }

    public static <T> ApiResponse<T> error(String m) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 500;
        r.message = m;
        return r;
    }

    public int getCode() {
        return this.code;
    }

    public String getMessage() {
        return this.message;
    }

    public T getData() {
        return this.data;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setData(T data) {
        this.data = data;
    }
}
