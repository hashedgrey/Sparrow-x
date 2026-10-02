package com.sparrowx.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(
        scanBasePackages = {"com.sparrowx.agentic", "buildingblocks"}
)
@ConfigurationPropertiesScan
public class AgenticApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticApplication.class, args);
    }
}
