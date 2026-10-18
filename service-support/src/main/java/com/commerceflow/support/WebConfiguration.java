package com.commerceflow.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({CorrelationFilter.class, ApiErrors.class, SecurityConfiguration.class})
public class WebConfiguration {

}
