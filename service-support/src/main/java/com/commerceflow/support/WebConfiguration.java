/*
 * Defines shared web behavior and API metadata while leaving service-specific endpoints in their
 * owning modules.
 */
package com.commerceflow.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
// Register correlation before shared error and security handling. Controllers remain in their service packages.
@Import({CorrelationFilter.class, ApiErrors.class, SecurityConfiguration.class})
public class WebConfiguration {

}
