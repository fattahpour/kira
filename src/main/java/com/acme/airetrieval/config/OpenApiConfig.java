package com.acme.airetrieval.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI kiraOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Kira API")
                .version("0.1.0")
                .description("Local AI retrieval service for code, documents, hybrid search, answer context, and code graph queries.")
                .contact(new Contact().name("Kira"))
                .license(new License().name("Internal")))
            .servers(List.of(new Server().url("http://localhost:8080").description("Local development")));
    }
}
