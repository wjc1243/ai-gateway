package com.example.gatewayaimvc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@Slf4j
@ConfigurationPropertiesScan
@SpringBootApplication
public class GatewayAiMvcApplication {

	public static void main(String[] args) {
        log.info("REDIS_HOST = {}", System.getenv("REDIS_HOST"));
		SpringApplication.run(GatewayAiMvcApplication.class, args);
	}

}
