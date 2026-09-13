package com.smartfinance.agent.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.ExperimentException;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentRepository.ExperimentNotFoundException;
import com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentTransportException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ExperimentException.class)
    public ResponseEntity<Result<Map<String, String>>> handleExperiment(ExperimentException e) {
        int status = switch (e.code()) {
            case "EXPERIMENT_REQUEST_CONFLICT" -> 409;
            case "SOURCE_BACKTEST_NOT_FOUND" -> 404;
            default -> 400;
        };
        String safeMessage = e.safeMessage();
        if (safeMessage == null || safeMessage.isBlank()) safeMessage = switch (e.code()) {
            case "EXPERIMENT_REQUEST_CONFLICT" -> "Idempotency-Key 已用于其他参数敏感性请求";
            case "SOURCE_BACKTEST_NOT_FOUND" -> "来源回测不存在或无权访问";
            case "CURRENT_RUNTIME_CONFIG_INCOMPATIBLE" -> "来源回测与当前运行环境的研究合同不兼容，请重新执行正式回测。";
            default -> "参数敏感性请求无法完成";
        };
        var data = new LinkedHashMap<String, String>();
        data.put("errorCode", e.code());
        if (e.reasonCode() != null && !e.reasonCode().isBlank()) data.put("reasonCode", e.reasonCode());
        var body = Result.<Map<String, String>>error(status, safeMessage);
        body.setData(data);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ExperimentNotFoundException.class)
    public ResponseEntity<Result<Map<String, String>>> handleExperimentNotFound(ExperimentNotFoundException e) {
        var body = Result.<Map<String, String>>error(404, "参数敏感性实验不存在或无权访问");
        body.setData(Map.of("errorCode", "EXPERIMENT_NOT_FOUND"));
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(ExperimentTransportException.class)
    public ResponseEntity<Result<Map<String, String>>> handleExperimentTransport(ExperimentTransportException e) {
        var body = Result.<Map<String, String>>error(503, "分析服务暂时不可用，请稍后重试");
        body.setData(Map.of("errorCode", "ANALYSIS_SERVICE_UNAVAILABLE"));
        return ResponseEntity.status(503).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatus(ResponseStatusException e) {
        String message = e.getReason() == null ? "请求无法完成" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Result.error(e.getStatusCode().value(), message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验异常: {}", e.getMessage());
        return Result.badRequest(e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数绑定异常: {}", message);
        return Result.badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("约束违反异常: {}", e.getMessage());
        return Result.badRequest(e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getMessage());
        return Result.badRequest("缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.badRequest("请求数据格式错误");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("数据完整性异常: {}", e.getMessage());
        return Result.badRequest("数据操作冲突，请检查输入");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("服务器内部错误", e);
        return Result.serverError("服务器繁忙，请稍后重试");
    }
}
