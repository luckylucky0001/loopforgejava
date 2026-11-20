package io.github.loopforge4j.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** LoopForge4J server: REST/SSE API around the SOP engine. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LoopForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoopForgeApplication.class, args);
    }
}
