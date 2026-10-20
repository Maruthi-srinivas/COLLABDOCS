package com.collabdocs.collab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.collabdocs.collab", "com.collabdocs.persistence"})
public class CollabApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollabApplication.class, args);
    }
}
