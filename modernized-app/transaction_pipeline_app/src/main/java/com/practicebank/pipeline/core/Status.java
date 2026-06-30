package com.practicebank.pipeline.core;

/**
 * Stage outcome status, mirroring the COBOL return codes used across the
 * transaction pipeline (00/01/04/08/12/16).
 */
public enum Status {
    /** 00 — success. */
    OK(0),
    /** 01 — no input available (19 only); pipeline ends normally as a no-op. */
    NO_INPUT(0),
    /** 04 — partial: some records rejected/deferred, valid ones continue. */
    PARTIAL(4),
    /** 08 — invalid input (abort, not retryable). */
    INVALID(8),
    /** 12 — I/O failure (abort, operationally retryable). */
    IO_FAIL(12),
    /** 16 — fatal (abort, needs investigation). */
    FATAL(16);

    private final int exitCode;

    Status(int exitCode) {
        this.exitCode = exitCode;
    }

    /** Process exit code mapped from the COBOL status. */
    public int exitCode() {
        return exitCode;
    }

    /** Whether this status must abort the pipeline. */
    public boolean isFatalForPipeline() {
        return this == INVALID || this == IO_FAIL || this == FATAL;
    }
}
