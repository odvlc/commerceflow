/*
 * Defines explicit service routes and optional AI routing; downstream services still validate
 * identity and ownership.
 */
package com.commerceflow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "AI_ENABLED", havingValue = "true")
    RouterFunction<ServerResponse> aiRoutes(@Value("${AI_URL:http://localhost:8087}") String target) {
        // With the feature flag absent, no AI route is registered; the commerce routes remain
        // available.
        return proxy("ai", target, "/api/v1/ai/**", "/api/v1/admin/ai/**");
    }

    @Bean
    RouterFunction<ServerResponse> catalogRoutes(
            @Value("${CATALOG_URL:http://localhost:8081}") String target) {
        return proxy(
                "catalog",
                target,
                "/api/v1/products/**",
                "/api/v1/admin/products/**",
                "/api/v1/admin/search/**");
    }

    @Bean
    RouterFunction<ServerResponse> orderRoutes(
            @Value("${ORDER_URL:http://localhost:8082}") String target) {
        return proxy("orders", target, "/api/v1/orders/**", "/api/v1/admin/orders/**");
    }

    @Bean
    RouterFunction<ServerResponse> inventoryRoutes(
            @Value("${INVENTORY_URL:http://localhost:8083}") String target) {
        return proxy("inventory", target, "/api/v1/admin/inventory/**");
    }

    @Bean
    RouterFunction<ServerResponse> paymentRoutes(
            @Value("${PAYMENT_URL:http://localhost:8084}") String target) {
        return proxy("payment", target, "/api/v1/admin/payments/**");
    }

    @Bean
    RouterFunction<ServerResponse> cartRoutes(
            @Value("${CART_URL:http://localhost:8085}") String target) {
        return proxy("cart", target, "/api/v1/cart/**");
    }

    @Bean
    RouterFunction<ServerResponse> notificationRoutes(
            @Value("${NOTIFICATION_URL:http://localhost:8086}") String target) {
        return proxy("notifications", target, "/api/v1/notifications/**");
    }

    private RouterFunction<ServerResponse> proxy(String name, String target, String... paths) {
        // Combine only declared path patterns; service selection never comes from a caller-supplied
        // URL.
        var predicate =
                Arrays.stream(paths)
                        .map(RequestPredicates::path)
                        .reduce(RequestPredicate::or)
                        .orElseThrow();
        return route(name).route(predicate, http()).before(uri(target)).build();
    }
}
