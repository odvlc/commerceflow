/*
 * Starts the notification service with shared support and its own persistence.
 */
package com.commerceflow.notification;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceConfiguration.class)
public class NotificationApplication {

    public static void main(String[] args) {
        // Load shared support in the notification service context.
        SpringApplication.run(NotificationApplication.class, args);
    }
}
