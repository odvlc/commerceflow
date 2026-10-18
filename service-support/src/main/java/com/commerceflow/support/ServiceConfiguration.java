package com.commerceflow.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Infrastructure helpers only: every repository and transaction uses the importing service's
 * database.
 */
@Configuration(proxyBeanMethods = false)
@Import({WebConfiguration.class, OutboxStore.class, MessagingConfiguration.class})
public class ServiceConfiguration {}
