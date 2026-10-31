/*
 * Validates or creates a correlation ID, propagates it to responses and clears thread logging
 * context after the request.
 */
package com.commerceflow.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader("X-Correlation-ID");
        // Restrict caller-controlled IDs before putting them in logs/headers to prevent multiline or
        // oversized values.
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}"))
            id = UUID.randomUUID().toString();
        request.setAttribute("correlationId", id);
        response.setHeader("X-Correlation-ID", id);
        // Close the MDC scope on both success and failure so request context cannot leak to later work.
        try (var ignored = MDC.putCloseable("correlationId", id)) {
            chain.doFilter(request, response);
        }
    }
}
