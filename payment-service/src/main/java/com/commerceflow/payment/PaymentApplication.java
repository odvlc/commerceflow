/*
 * Starts the payment service with shared support and its own persistence.
 */
package com.commerceflow.payment;

import com.commerceflow.support.ServiceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ServiceConfiguration.class)
public class PaymentApplication {

    public static void main(String[] args) {
        // Load shared support in the payment service context.
        SpringApplication.run(PaymentApplication.class, args);
    }
}
