package com.practicebank.pipeline.core;

/** Maps to COBOL status 12 — I/O failure, operationally retryable. */
public class IoFailureException extends PipelineException {
    public IoFailureException(String message) {
        super(message);
    }
}
