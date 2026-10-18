package com.commerceflow.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(BusinessException.class)
  public ProblemDetail business(BusinessException e, HttpServletRequest r) {
    return problem(e.status(), e.code(), e.getMessage(), r);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    NumberFormatException.class
  })
  public ProblemDetail invalid(Exception e, HttpServletRequest r) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request fields, parameters or headers",
        r);
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    OptimisticLockingFailureException.class
  })
  public ProblemDetail conflict(Exception e, HttpServletRequest r) {
    return problem(
        HttpStatus.CONFLICT,
        "DATA_CONFLICT",
        "A unique value or resource version conflicts with current data",
        r);
  }

  private ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(code);
    problem.setProperty("code", code);
    problem.setProperty("correlationId", request.getAttribute("correlationId"));
    return problem;
  }
}
