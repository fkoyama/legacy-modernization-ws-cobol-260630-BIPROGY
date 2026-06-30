package com.practicebank.pipeline.core;

/** Maps to COBOL status 08 — invalid input, not retryable. */
public class InvalidInputException extends PipelineException {
    public InvalidInputException(String message) {
        super(message);
    }
}
