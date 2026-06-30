package com.practicebank.pipeline.decode;

import java.time.LocalDate;

/** Decoded batch header (TXN-DECODED-HEADER). */
public record DecodedHeader(
        String batchId,
        LocalDate businessDate,
        String sourceSystem,
        long expectedCount,
        long amountSum) {
}
