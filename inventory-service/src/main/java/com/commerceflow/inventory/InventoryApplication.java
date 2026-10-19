/*
 * Bootstraps the independently deployable inventory-service; shared helpers are imported without
 * sharing service persistence.
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
        // Start this service context only; its configuration supplies the owned database and enabled
        // infrastructure helpers.
        SpringApplication.run(InventoryApplication.class, args);
    }
}
