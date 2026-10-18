package com.commerceflow.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="AI_ENABLED",havingValue="true")
    RouterFunction<ServerResponse> aiRoutes(@Value("${AI_URL:http://localhost:8087}") String target) {
        return proxy("ai",target,"/api/v1/ai/**","/api/v1/admin/ai/**");
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
        var predicate =
                Arrays.stream(paths)
                        .map(RequestPredicates::path)
                        .reduce(RequestPredicate::or)
                        .orElseThrow();
        return route(name).route(predicate, http()).before(uri(target)).build();
    }
}
