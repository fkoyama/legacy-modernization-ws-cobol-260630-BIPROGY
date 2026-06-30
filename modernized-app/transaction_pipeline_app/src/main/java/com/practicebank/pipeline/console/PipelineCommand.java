package com.practicebank.pipeline.console;

import java.time.LocalDate;

/** Parsed console command (Controller-layer DTO). */
public record PipelineCommand(
        String command,
        String batchId,
        LocalDate businessDate,
        String sourceSystem,
        String inputPath) {
}
