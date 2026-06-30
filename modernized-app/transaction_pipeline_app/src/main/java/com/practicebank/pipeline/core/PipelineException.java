package com.practicebank.pipeline.core;

/** Base type for pipeline aborts. */
public abstract class PipelineException extends RuntimeException {
    protected PipelineException(String message) {
        super(message);
    }
}
