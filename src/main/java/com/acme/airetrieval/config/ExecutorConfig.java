package com.acme.airetrieval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class ExecutorConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService indexingExecutor(ApplicationProps props) {
        return Executors.newFixedThreadPool(Math.max(1, props.executor().indexThreads()));
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kira-sync-");
        scheduler.initialize();
        return scheduler;
    }
}
