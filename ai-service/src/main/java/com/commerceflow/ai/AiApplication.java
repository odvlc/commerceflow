/*
 * Bootstraps the independently deployable ai-service; shared helpers are imported without sharing
 * service persistence.
 */
package com.commerceflow.ai;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(ServiceConfiguration.class)
public class AiApplication {

    public static void main(String[] args) {
        // Start this service context only; its configuration supplies the owned database and enabled
        // infrastructure helpers.
        SpringApplication.run(AiApplication.class, args);
    }
}
