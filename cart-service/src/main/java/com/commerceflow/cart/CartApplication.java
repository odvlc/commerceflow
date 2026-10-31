/*
 * Bootstraps the independently deployable cart-service; shared helpers are imported without
 * sharing service persistence.
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
        // Start this service context only; its configuration supplies the owned database and enabled
        // infrastructure helpers.
        SpringApplication.run(CartApplication.class, args);
    }
}
