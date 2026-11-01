/*
 * Starts the order service with shared support and its own persistence.
 */
package com.commerceflow.order;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceConfiguration.class)
public class OrderApplication {

    public static void main(String[] args) {
        // Load shared support in the order service context.
        SpringApplication.run(OrderApplication.class, args);
    }
}
