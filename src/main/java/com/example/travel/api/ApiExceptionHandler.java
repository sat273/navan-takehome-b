package com.example.travel.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> handle(ApiException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.code());
    body.put("message", e.getMessage());
    body.putAll(e.details());
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<Map<String, Object>> handleMalformed(Exception e) {
    return handle(ApiException.badRequest("Malformed request: " + e.getClass().getSimpleName()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
    log.error("Unhandled error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "internal_error", "message", "Unexpected error"));
  }
}
