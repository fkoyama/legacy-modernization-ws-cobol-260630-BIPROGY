package com.practicebank.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Transaction Pipeline App (subsystems 19 -> 10 -> 11 -> 12).
 *
 * <p>Spring Boot console application. The console (Controller layer) parses a
 * command and arguments, the orchestrator (Service layer) runs the stages, and
 * the repositories (Repository layer) talk to PostgreSQL and the Master
 * Reference App via REST. See doc/design/specs-tobe/99-pipeline-orchestration-design.md.
 */
@SpringBootApplication
@EnableRetry
public class PipelineApplication {

    public static void main(String[] args) {
        // web-application-type=none (configured in application.yml) — no web server.
        System.exit(SpringApplication.exit(SpringApplication.run(PipelineApplication.class, args)));
    }
}
