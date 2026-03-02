package io.github.drompincen.javaclawv1.gateway.test;

import org.springframework.context.annotation.Configuration;

@Configuration
public class TestMongoConfiguration {

    static {
        // Force x86_64 platform for Flapdoodle embedded MongoDB download.
        // Only on Windows ARM64 where WoW64 can emulate x86_64 binaries.
        // On Linux ARM64 (WSL2), let Flapdoodle download the native aarch64 binary.
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "");
        if (os.contains("win") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            System.setProperty("os.arch", "amd64");
        }
    }
}
