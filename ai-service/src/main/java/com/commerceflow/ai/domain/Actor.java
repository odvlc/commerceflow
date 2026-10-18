package com.commerceflow.ai.domain;

import com.commerceflow.support.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record Actor(UUID subject, String token, Set<String> roles, java.time.Instant expiresAt) {

    public static Actor from(Jwt jwt) {
        Map<String, Object> access = jwt.getClaimAsMap("realm_access");
        Set<String> roles = new HashSet<>();
        if (access != null && access.get("roles") instanceof Collection<?> values)
            values.forEach(v -> roles.add(v.toString()));
        return new Actor(
                UUID.fromString(jwt.getSubject()),
                jwt.getTokenValue(),
                Set.copyOf(roles),
                jwt.getExpiresAt());
    }

    public void requireMode(String mode) {
        boolean allowed =
                "CUSTOMER".equals(mode)
                        ? roles.contains("CUSTOMER") || roles.contains("ADMIN")
                        : "SUPPORT".equals(mode) && (roles.contains("SUPPORT") || roles.contains("ADMIN"));
        if (!allowed)
            throw new BusinessException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Conversation mode is not permitted");
    }

    public boolean valid() {
        return expiresAt != null && expiresAt.isAfter(java.time.Instant.now().plusSeconds(5));
    }

    @Override
    public String toString() {
        return "Actor[subject=" + subject + ",roles=" + roles + "]";
    }
}
