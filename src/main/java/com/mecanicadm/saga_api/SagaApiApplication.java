package com.mecanicadm.saga_api;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableRabbit
@SpringBootApplication
public class SagaApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SagaApiApplication.class, args);
    }
}
