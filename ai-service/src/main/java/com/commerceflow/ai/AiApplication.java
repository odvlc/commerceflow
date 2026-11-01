/*
 * Starts the AI service with shared support and its own persistence.
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
        // Load shared support in the AI service's own application context.
        SpringApplication.run(AiApplication.class, args);
    }
}
