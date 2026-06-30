package com.practicebank.pipeline.decode;

/** Decoded batch trailer (TXN-DECODED-TRAILER). */
public record DecodedTrailer(long recordCount, long amountSum) {
}
