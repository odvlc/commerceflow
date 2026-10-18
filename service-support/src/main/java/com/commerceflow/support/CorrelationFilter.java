package com.commerceflow.support;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader("X-Correlation-ID");
    if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) id = UUID.randomUUID().toString();
    request.setAttribute("correlationId", id);
    response.setHeader("X-Correlation-ID", id);
    try (var ignored = MDC.putCloseable("correlationId", id)) {
      chain.doFilter(request, response);
    }
  }
}
