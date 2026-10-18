package com.commerceflow.ai.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerceflow.ai.application.CartActions;
import com.commerceflow.ai.retrieval.KnowledgeSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolArgumentsTest {
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void onlyAnAbsentOptionalReadPriceCanBeCanonicalized() throws Exception {
    var commerce = mock(CommerceClient.class);
    var knowledge = mock(KnowledgeSearch.class);
    var actions = mock(CartActions.class);
    try (var registry =
        new ToolRegistry(commerce, knowledge, json, new SimpleMeterRegistry(), actions)) {
      for (String body :
          List.of(
              "{\"query\":\"lamp\",\"maxPrice\":null}",
              "{\"query\":\"lamp\",\"maxPrice\":\"null\"}",
              "{\"query\":\"lamp\"}"))
        assertThat(
                registry.validatedArguments("searchProducts", json.readTree(body)).has("maxPrice"))
            .isFalse();
      for (String value : List.of("USD", "undefined", "-1", "9999999999.99"))
        assertThatThrownBy(
                () ->
                    registry.validatedArguments(
                        "searchProducts",
                        json.valueToTree(Map.of("query", "lamp", "maxPrice", value))))
            .hasMessageContaining("registered schema");
      assertThatThrownBy(
              () ->
                  registry.validatedArguments("getProduct", json.readTree("{\"productId\":null}")))
          .hasMessageContaining("registered schema");
      assertThatThrownBy(
              () ->
                  registry.validatedArguments(
                      "proposeCartChange",
                      json.readTree(
                          "{\"operation\":\"ADD\",\"productId\":\""
                              + UUID.randomUUID()
                              + "\",\"quantity\":null}")))
          .hasMessageContaining("registered schema");
      assertThatThrownBy(
              () ->
                  registry.validatedArguments(
                      "searchProducts",
                      json.readTree("{\"query\":\"lamp\",\"url\":\"http://localhost\"}")))
          .hasMessageContaining("registered schema");
      verifyNoInteractions(commerce, knowledge, actions);
    }
  }

  @Test
  void integralOverflowCannotWrapIntoAnAllowedCartQuantity() throws Exception {
    var commerce = mock(CommerceClient.class);
    var actions = mock(CartActions.class);
    try (var registry =
        new ToolRegistry(
            commerce, mock(KnowledgeSearch.class), json, new SimpleMeterRegistry(), actions)) {
      for (String quantity :
          List.of("18446744073709551617", "2147483649", "1001", "-1", "1.1", "\"1\"")) {
        var arguments =
            json.readTree(
                "{\"operation\":\"ADD\",\"productId\":\""
                    + UUID.randomUUID()
                    + "\",\"quantity\":"
                    + quantity
                    + "}");
        assertThatThrownBy(() -> registry.validatedArguments("proposeCartChange", arguments))
            .hasMessageContaining("registered schema");
      }
      verifyNoInteractions(commerce, actions);
    }
  }

  @Test
  void staleIndexPriceAndCurrencyCannotOverrideAuthoritativeCatalogFacts() throws Exception {
    var commerce = mock(CommerceClient.class);
    var id = UUID.randomUUID();
    when(commerce.get(eq("catalog"), eq("/api/v1/products/search"), anyMap(), any(), anyString()))
        .thenReturn(
            json.valueToTree(Map.of("items", List.of(Map.of("id", id)), "totalElements", 1)));
    var fresh =
        json.valueToTree(
            Map.of(
                "id",
                id,
                "name",
                "Keyboard",
                "price",
                "30.00",
                "currency",
                "USD",
                "category",
                "Workspace",
                "version",
                2));
    when(commerce.get(eq("catalog"), eq("/api/v1/products/" + id), anyMap(), any(), anyString()))
        .thenReturn(fresh);
    var actor =
        new com.commerceflow.ai.domain.Actor(
            UUID.randomUUID(),
            "not-forwarded-by-mocked-transport",
            Set.of("CUSTOMER"),
            java.time.Instant.now().plusSeconds(300));
    try (var registry =
        new ToolRegistry(
            commerce,
            mock(KnowledgeSearch.class),
            json,
            new SimpleMeterRegistry(),
            mock(CartActions.class))) {
      var result =
          registry.invoke(
              UUID.randomUUID(),
              actor,
              "CUSTOMER",
              "searchProducts",
              json.valueToTree(Map.of("query", "keyboard", "maxPrice", "20.00")));
      assertThat(result.evidence()).isEmpty();
      assertThat(result.data().path("items").isEmpty()).isTrue();
      when(commerce.get(eq("catalog"), eq("/api/v1/products/" + id), anyMap(), any(), anyString()))
          .thenReturn(json.valueToTree(Map.of("id", id, "price", "10.00", "currency", "EUR")));
      assertThat(
              registry
                  .invoke(
                      UUID.randomUUID(),
                      actor,
                      "CUSTOMER",
                      "searchProducts",
                      json.valueToTree(Map.of("query", "keyboard", "maxPrice", "20.00")))
                  .evidence())
          .isEmpty();
    }
  }
}
