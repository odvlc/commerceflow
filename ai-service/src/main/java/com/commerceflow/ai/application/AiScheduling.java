package com.commerceflow.ai.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AiScheduling {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ai-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
