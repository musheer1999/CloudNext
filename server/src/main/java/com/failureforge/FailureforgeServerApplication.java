package com.failureforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. In Phase 1 one process runs both the REST API and the background worker (D-018).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FailureforgeServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FailureforgeServerApplication.class, args);
	}

}
