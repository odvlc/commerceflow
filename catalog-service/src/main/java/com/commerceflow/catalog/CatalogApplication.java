/*
 * Starts the catalog service with shared support and its own persistence.
 */
package com.commerceflow.catalog;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceConfiguration.class)
public class CatalogApplication {

    public static void main(String[] args) {
        // Load shared support in the catalog service context.
        SpringApplication.run(CatalogApplication.class, args);
    }
}
