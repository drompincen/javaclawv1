package io.github.drompincen.javaclawv1.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication(
        scanBasePackages = "io.github.drompincen.javaclawv1",
        exclude = {
                org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
        }
)
@EnableMongoRepositories(basePackages = "io.github.drompincen.javaclawv1.persistence.repository")
public class JavaClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaClawApplication.class, args);
    }
}
