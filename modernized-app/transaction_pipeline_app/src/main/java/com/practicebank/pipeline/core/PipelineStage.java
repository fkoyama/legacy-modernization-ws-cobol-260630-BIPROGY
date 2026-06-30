package com.practicebank.pipeline.core;

/**
 * Core pipeline stage (19 -> 10 -> 11 -> 12): consumes the previous stage's
 * output and produces a typed value.
 */
public interface PipelineStage<I, O> {
    StageResult<O> execute(I input, BatchContext ctx);
}
