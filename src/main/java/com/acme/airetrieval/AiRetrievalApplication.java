package com.acme.airetrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiRetrievalApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiRetrievalApplication.class, args);
    }
}
