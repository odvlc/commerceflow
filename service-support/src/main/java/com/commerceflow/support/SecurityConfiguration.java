/*
 * Validates JWT issuer, audience and UUID subject, then applies role/scope rules; resource
 * ownership remains an application check.
 */
package com.commerceflow.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${security.issuer:http://localhost:8180/realms/commerceflow}") String issuer,
            @Value(
                    "${security.jwk-set-uri:http://localhost:8180/realms/commerceflow/protocol/openid-connect/certs}")
            String jwks,
            @Value("${security.audience:commerceflow-api}") String audience) {
        // Internal JWKS routing can differ from the browser issuer URL; validate the public issuer anyway.
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> audienceValidator =
                token ->
                        token.getAudience().contains(audience)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Required audience missing", null));
        OAuth2TokenValidator<Jwt> subjectValidator =
                token -> {
                    try {
                        // Reject non-UUID subjects before controllers convert them into resource-owner
                        // IDs.
                        UUID.fromString(token.getSubject());
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException error) {
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "UUID subject required", null));
                    }
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, subjectValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, tools.jackson.databind.json.JsonMapper json)
            throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    List<GrantedAuthority> roles = new ArrayList<>();
                    Map<String, Object> access = jwt.getClaimAsMap("realm_access");
                    if (access != null && access.get("roles") instanceof Collection<?> values)
                        values.stream()
                                .map(Object::toString)
                                .filter(Set.of("ADMIN", "CUSTOMER", "SUPPORT")::contains)
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .forEach(roles::add);
                    // Service scopes and realm roles are distinct authorities; possessing a customer
                    // role does not grant internal write access.
                    String scope = jwt.getClaimAsString("scope");
                    if (scope != null)
                        Arrays.stream(scope.split(" "))
                                .filter("commerceflow.internal"::equals)
                                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                                .forEach(roles::add);
                    return roles;
                });
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/v1/products",
                                                "/api/v1/products/**",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/v3/api-docs/**",
                                                "/docs",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET, "/api/v1/admin/orders", "/api/v1/admin/orders/*/explanation")
                                        .hasAnyRole("ADMIN", "SUPPORT")
                                        .requestMatchers("/api/v1/admin/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/internal/**")
                                        .hasAuthority("SCOPE_commerceflow.internal")
                                        .requestMatchers("/actuator/prometheus")
                                        .hasAuthority("SCOPE_commerceflow.internal")
                                        .requestMatchers(
                                                "/api/v1/orders", "/api/v1/orders/**", "/api/v1/cart", "/api/v1/cart/**")
                                        .hasAnyRole("CUSTOMER", "ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.accessDeniedHandler(
                                        (request, response, error) ->
                                                securityProblem(
                                                        request,
                                                        response,
                                                        json,
                                                        403,
                                                        "ACCESS_DENIED",
                                                        "Insufficient permissions")))
                .oauth2ResourceServer(
                        oauth ->
                                oauth
                                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(
                                                (request, response, error) ->
                                                        securityProblem(
                                                                request,
                                                                response,
                                                                json,
                                                                401,
                                                                "AUTHENTICATION_REQUIRED",
                                                                "A valid access token is required"))
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        securityProblem(
                                                                request,
                                                                response,
                                                                json,
                                                                403,
                                                                "ACCESS_DENIED",
                                                                "Insufficient permissions")))
                .build();
    }

    private void securityProblem(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            tools.jackson.databind.json.JsonMapper json,
            int status,
            String code,
            String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response
                .getWriter()
                .write(
                        json.writeValueAsString(
                                Map.of(
                                        "type",
                                        "about:blank",
                                        "title",
                                        code,
                                        "status",
                                        status,
                                        "detail",
                                        message,
                                        "code",
                                        code,
                                        "correlationId",
                                        String.valueOf(request.getAttribute("correlationId")))));
    }
}
