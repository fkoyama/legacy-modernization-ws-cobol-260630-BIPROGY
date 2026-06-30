package com.practicebank.pipeline.core;

import java.time.LocalDate;

/**
 * Per-run context shared by all stages: batch id, business date and source system.
 */
public record BatchContext(String batchId, LocalDate businessDate, String sourceSystem) {
}
