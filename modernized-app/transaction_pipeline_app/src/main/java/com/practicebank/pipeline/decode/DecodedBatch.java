package com.practicebank.pipeline.decode;

import com.practicebank.pipeline.core.TxnError;

import java.util.List;

/**
 * In-memory replacement for decoded.dat: header, the successfully decoded
 * details and the rejected details (TxnError list).
 */
public record DecodedBatch(
        DecodedHeader header,
        List<DecodedTxn> details,
        DecodedTrailer trailer,
        List<TxnError> rejected) {
}
