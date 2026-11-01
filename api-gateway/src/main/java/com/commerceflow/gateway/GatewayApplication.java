/*
 * Starts the gateway with shared web support and no service database.
 */
package com.commerceflow.gateway;

import com.commerceflow.support.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@Import(WebConfiguration.class)
public class GatewayApplication {

    public static void main(String[] args) {
        // Load shared web support in the gateway context.
        SpringApplication.run(GatewayApplication.class, args);
    }
}
