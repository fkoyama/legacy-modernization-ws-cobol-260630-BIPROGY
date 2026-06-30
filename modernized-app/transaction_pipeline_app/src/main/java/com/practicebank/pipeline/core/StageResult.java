package com.practicebank.pipeline.core;

/**
 * Result of a pipeline stage: a COBOL-style {@link Status}, an optional value,
 * and {@link Counters}. Fatal statuses are surfaced via {@link #orThrowOnFatal()}.
 */
public record StageResult<T>(Status status, T value, Counters counters) {

    public static <T> StageResult<T> ok(T value, Counters counters) {
        return new StageResult<>(Status.OK, value, counters);
    }

    public static <T> StageResult<T> partial(T value, Counters counters) {
        return new StageResult<>(Status.PARTIAL, value, counters);
    }

    public static <T> StageResult<T> noInput() {
        return new StageResult<>(Status.NO_INPUT, null, Counters.empty());
    }

    public static <T> StageResult<T> of(Status status, T value, Counters counters) {
        return new StageResult<>(status, value, counters);
    }

    /**
     * Returns the value for OK/PARTIAL, or throws the matching pipeline exception
     * for INVALID/IO_FAIL/FATAL. NO_INPUT returns {@code null} (the caller should
     * have short-circuited before calling this).
     */
    public T orThrowOnFatal() {
        return switch (status) {
            case OK, PARTIAL, NO_INPUT -> value;
            case INVALID -> throw new InvalidInputException("stage returned INVALID(08)");
            case IO_FAIL -> throw new IoFailureException("stage returned IO_FAIL(12)");
            case FATAL -> throw new FatalPipelineException("stage returned FATAL(16)");
        };
    }
}
