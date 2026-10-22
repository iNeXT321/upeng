package com.yt.agent.controller;

import com.yt.agent.platform.llm.ModelUnavailableException;
import com.yt.agent.platform.runtime.UnsupportedAgentTypeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口。
 *
 * <p>原实现没有任何全局异常处理：参数错误、模型未配置、handler 缺失都会变成
 * Spring 默认的 500 白页，前端拿不到可读原因。这里把"可预期的配置/参数错误"
 * 映射为 4xx + 明确 message，未知异常记录日志并返回带 requestId 的 500，
 * 便于用户报障时直接提供定位信息。</p>
 *
 * <p>注意：SSE 接口（/ai/autoAgent）的异常不会走到这里——它已经在流内以 error 事件返回。</p>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedAgentTypeException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupported(UnsupportedAgentTypeException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AGENT_TYPE", e.getMessage(), request);
    }

    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleModel(ModelUnavailableException e, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleState(IllegalStateException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.getMessage(), request);
    }

    /** 请求方式不对（如对只支持 POST 的接口发 GET）——必须 405，不能被兜底成 500 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                e.getMessage() + "（请检查请求方式，接口清单见 GET /api/ai/health）", request);
    }

    /** 路径不存在——必须 404 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在: " + request.getRequestURI(), request);
    }

    /** 请求体不可读/参数缺失——必须 400 */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未处理异常 path={}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务内部错误，请稍后重试或联系管理员（path=" + request.getRequestURI() + "）", request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message,
                                                      HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? status.getReasonPhrase() : message);
        body.put("path", request.getRequestURI());
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(status).body(body);
    }
}
