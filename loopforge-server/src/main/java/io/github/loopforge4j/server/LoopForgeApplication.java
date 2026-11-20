package io.github.loopforge4j.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** LoopForge4J server: REST/SSE API around the SOP engine. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LoopForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoopForgeApplication.class, args);
    }
}
