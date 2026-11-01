/*
 * Starts the inventory service with shared support and its own persistence.
 */
package com.commerceflow.inventory;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceConfiguration.class)
public class InventoryApplication {

    public static void main(String[] args) {
        // Load shared support in the inventory service context.
        SpringApplication.run(InventoryApplication.class, args);
    }
}
