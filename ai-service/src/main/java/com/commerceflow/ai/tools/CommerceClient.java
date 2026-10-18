package com.commerceflow.ai.tools;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.support.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CommerceClient {
  private final Map<String, RestClient> clients;
  private final JsonMapper json;

  public CommerceClient(
      @Value("${ai.catalog-url:http://127.0.0.1:8081}") String catalog,
      @Value("${ai.order-url:http://127.0.0.1:8082}") String order,
      @Value("${ai.cart-url:http://127.0.0.1:8085}") String cart,
      JsonMapper json) {
    this.json = json;
    clients = Map.of("catalog", client(catalog), "order", client(order), "cart", client(cart));
  }

  private RestClient client(String target) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().baseUrl(target).requestFactory(factory).build();
  }

  public JsonNode get(
      String service, String path, Map<String, ?> parameters, Actor actor, String correlation) {
    return request(service, HttpMethod.GET, path, parameters, null, actor, correlation, Map.of());
  }

  public JsonNode mutateCart(
      String operation,
      UUID product,
      int quantity,
      String revision,
      String key,
      Actor actor,
      String correlation) {
    String path = operation.equals("ADD") ? "/api/v1/cart/items" : "/api/v1/cart/items/" + product;
    HttpMethod method =
        switch (operation) {
          case "ADD" -> HttpMethod.POST;
          case "SET" -> HttpMethod.PATCH;
          case "DELETE" -> HttpMethod.DELETE;
          default -> throw new IllegalArgumentException("Unknown cart operation");
        };
    Object body =
        operation.equals("ADD")
            ? Map.of("productId", product, "quantity", quantity)
            : operation.equals("SET") ? Map.of("quantity", quantity) : null;
    return request(
        "cart",
        method,
        path,
        Map.of(),
        body,
        actor,
        correlation,
        Map.of("If-Match", revision, "Idempotency-Key", key));
  }

  private JsonNode request(
      String service,
      HttpMethod method,
      String path,
      Map<String, ?> parameters,
      Object body,
      Actor actor,
      String correlation,
      Map<String, String> extra) {
    if (!actor.valid())
      throw new BusinessException(
          HttpStatus.UNAUTHORIZED,
          "AI_AUTHENTICATION_REQUIRED",
          "A fresh access token is required");
    try {
      var request =
          clients
              .get(service)
              .method(method)
              .uri(
                  builder -> {
                    builder.path(path);
                    parameters.forEach((k, v) -> builder.queryParam(k, v));
                    return builder.build();
                  })
              .headers(
                  headers -> {
                    headers.setBearerAuth(actor.token());
                    headers.set("X-Correlation-ID", correlation);
                    extra.forEach(headers::set);
                  })
              .contentType(MediaType.APPLICATION_JSON);
      if (body != null) request.body(body);
      return json.readTree(request.retrieve().body(String.class));
    } catch (RestClientResponseException error) {
      int status = error.getStatusCode().value();
      if (status == 401)
        throw new BusinessException(
            HttpStatus.UNAUTHORIZED,
            "AI_AUTHENTICATION_REQUIRED",
            "A fresh access token is required");
      if (status == 404)
        throw new BusinessException(
            HttpStatus.NOT_FOUND, "AI_TOOL_RESOURCE_NOT_FOUND", "Resource not found");
      if (status == 403)
        throw new BusinessException(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "The current identity cannot perform this operation");
      if (status == 409 || status == 412)
        throw new BusinessException(
            HttpStatus.CONFLICT,
            "AI_APPROVAL_STALE",
            "The resource changed; refresh and review the action");
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AI_TOOL_UNAVAILABLE",
          "Commerce service operation unavailable");
    } catch (RestClientException error) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AI_TOOL_UNAVAILABLE",
          "Commerce service operation unavailable");
    }
  }
}
