package io.github.drompincen.javaclawv1.runtime.agent.llm;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableAutoConfiguration
@EnableMongoRepositories(basePackages = "io.github.drompincen.javaclawv1.persistence.repository")
public class TestMongoConfiguration {

    static {
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            System.setProperty("os.arch", "amd64");
        }
    }
}
