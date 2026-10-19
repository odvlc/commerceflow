/*
 * Defines shared web behavior and API metadata while leaving service-specific endpoints in their
 * owning modules.
 */
package com.commerceflow.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
// Install correlation before shared error/security handling; service controllers stay in their own
// packages.
@Import({CorrelationFilter.class, ApiErrors.class, SecurityConfiguration.class})
public class WebConfiguration {

}
