/*
 * Gives background AI jobs their own scheduler so one slow indexing poll does not block every
 * scheduled task.
 */
package com.commerceflow.ai.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AiScheduling {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Allow short lease/expiry jobs to run while a slow index operation occupies another scheduler
        // thread.
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ai-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
