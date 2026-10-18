package com.commerceflow.gateway;

import com.commerceflow.support.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(
        exclude = {org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class})
@Import(WebConfiguration.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
