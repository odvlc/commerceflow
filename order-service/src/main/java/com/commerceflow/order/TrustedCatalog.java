package com.commerceflow.order;

import com.commerceflow.support.BusinessException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Component
public class TrustedCatalog {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(UUID id, BigDecimal price, String currency, boolean active) {

    }

    private final RestClient client;

    public TrustedCatalog(
            RestClient.Builder builder,
            @Value("${catalog.base-url:http://localhost:8081}") String baseUrl) {
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Quote quote(UUID productId, String correlation) {
        try {
            var quote =
                    client
                            .get()
                            .uri("/api/v1/products/{id}", productId)
                            .header("X-Correlation-ID", correlation)
                            .header("Cache-Control", "no-cache")
                            .retrieve()
                            .body(Quote.class);
            if (quote == null
                    || !productId.equals(quote.id())
                    || !quote.active()
                    || quote.price() == null
                    || quote.price().signum() < 0
                    || quote.price().scale() > 2
                    || quote.currency() == null
                    || !quote.currency().matches("USD|EUR|GBP"))
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "PRODUCT_UNAVAILABLE",
                        "Catalog returned an unavailable product");
            return quote;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_UNAVAILABLE", "Product unavailable");
        } catch (RestClientException e) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CATALOG_UNAVAILABLE",
                    "Cannot validate current product prices; retry later");
        }
    }
}
