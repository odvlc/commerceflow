/*
 * Imports infrastructure helpers into each application without sharing databases or entity
 * repositories across services.
 */
package com.commerceflow.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Infrastructure helpers only: every repository and transaction uses the importing service's
 * database.
 */
@Configuration(proxyBeanMethods = false)
// Imported helpers operate against the importing application context and its own DataSource.
@Import({WebConfiguration.class, OutboxStore.class, MessagingConfiguration.class})
public class ServiceConfiguration {

}
