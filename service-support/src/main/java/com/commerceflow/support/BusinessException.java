/*
 * Carries an intended HTTP status and stable public error code without exposing infrastructure
 * exception details.
 */
package com.commerceflow.support;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        // Keep the public status/code distinct from the diagnostic Java exception type.
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
