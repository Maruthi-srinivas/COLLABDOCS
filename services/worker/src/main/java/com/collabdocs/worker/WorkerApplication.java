package com.collabdocs.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.collabdocs.worker", "com.collabdocs.persistence"})
@EnableScheduling
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
