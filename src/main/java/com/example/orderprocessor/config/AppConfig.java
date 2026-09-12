package com.example.orderprocessor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    // Clock is already defined in RabbitMQConfig, but it's good practice to have it here
    // if other parts of the application need it.
    // For this project, we'll use the one from RabbitMQConfig.
    // @Bean
    // public Clock clock() {
    //     return Clock.systemUTC();
    // }
}
