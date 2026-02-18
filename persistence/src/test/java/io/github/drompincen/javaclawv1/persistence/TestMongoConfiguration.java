package io.github.drompincen.javaclawv1.persistence;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@EnableAutoConfiguration
@EnableMongoRepositories(basePackages = "io.github.drompincen.javaclawv1.persistence.repository")
public class TestMongoConfiguration {

    static {
        // Force x86_64 platform for Flapdoodle embedded MongoDB download.
        // Windows ARM64 can run x86_64 MongoDB binary via WoW64 emulation.
        // On Linux x86_64 (CI), this is already the correct value and has no effect.
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            System.setProperty("os.arch", "amd64");
        }
    }
}
