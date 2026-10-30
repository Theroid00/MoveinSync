package com.moveinsync.rostersync.roster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Two worker threads for reconciliation runs; one_active_run keeps a tenant source to one run at a time. */
@Configuration(proxyBeanMethods = false)
public class ReconciliationExecutorConfig {

    @Bean
    ThreadPoolTaskExecutor reconciliationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("recon-");
        executor.initialize();
        return executor;
    }
}
