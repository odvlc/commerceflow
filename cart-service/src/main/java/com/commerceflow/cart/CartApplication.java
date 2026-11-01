/*
 * Starts the cart service with shared web support and Redis-backed carts.
 */
package com.commerceflow.cart;

import com.commerceflow.support.WebConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@Import(WebConfiguration.class)
public class CartApplication {

    public static void main(String[] args) {
        // Load shared web support in the cart service context.
        SpringApplication.run(CartApplication.class, args);
    }
}
