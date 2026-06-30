package com.practicebank.pipeline.core;

/** Maps to COBOL status 16 — fatal, needs investigation. */
public class FatalPipelineException extends PipelineException {
    public FatalPipelineException(String message) {
        super(message);
    }
}
