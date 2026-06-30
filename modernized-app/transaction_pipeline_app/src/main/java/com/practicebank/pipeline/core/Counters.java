package com.practicebank.pipeline.core;

/**
 * Generic stage counters (processed / ok / rejected / skipped).
 */
public record Counters(long processed, long ok, long rejected, long skipped) {

    public static Counters of(long processed, long ok, long rejected) {
        return new Counters(processed, ok, rejected, 0);
    }

    public static Counters empty() {
        return new Counters(0, 0, 0, 0);
    }
}
