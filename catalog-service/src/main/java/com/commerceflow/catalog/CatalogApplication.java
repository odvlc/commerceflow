/*
 * Bootstraps the independently deployable catalog-service; shared helpers are imported without
 * sharing service persistence.
 */
package com.commerceflow.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.context.annotation.Import(com.commerceflow.support.ServiceConfiguration.class)
public class CatalogApplication {

    public static void main(String[] args) {
        // Start this service context only; its configuration supplies the owned database and enabled
        // infrastructure helpers.
        SpringApplication.run(CatalogApplication.class, args);
    }
}
