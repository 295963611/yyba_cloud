package com.yyba.cloud.exception;

import com.yyba.cloud.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return jsonError(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.error("Illegal state", e);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return jsonError(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({CannotAcquireLockException.class, UncategorizedSQLException.class})
    public ResponseEntity<ApiResponse<Void>> handleDatabaseBusy(Exception e) {
        if (isSqliteBusy(e)) {
            log.warn("SQLite busy: {}", e.getMessage());
            return jsonError(HttpStatus.SERVICE_UNAVAILABLE, "数据库繁忙，请稍后重试");
        }
        log.error("Database error", e);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "数据库错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        if (isSqliteBusy(e)) {
            return handleDatabaseBusy(e);
        }
        if (isClientAbort(e)) {
            log.debug("Client disconnected during transfer: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.OK).build();
        }
        log.error("Unhandled exception", e);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
    }

    private boolean isClientAbort(Throwable e) {
        while (e != null) {
            if (e instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String className = e.getClass().getName();
            if (className.contains("ClientAbortException")) {
                return true;
            }
            String message = e.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connection reset by peer")
                        || lower.contains("broken pipe")
                        || lower.contains("connection aborted")) {
                    return true;
                }
            }
            e = e.getCause();
        }
        return false;
    }

    private boolean isSqliteBusy(Throwable e) {
        while (e != null) {
            if (e instanceof SQLiteException sqlite && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_BUSY) {
                return true;
            }
            String message = e.getMessage();
            if (message != null && message.contains("SQLITE_BUSY")) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private ResponseEntity<ApiResponse<Void>> jsonError(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(message));
    }
}
