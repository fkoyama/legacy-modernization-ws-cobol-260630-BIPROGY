package com.practicebank.pipeline.core;

/**
 * A per-detail reject record: the source sequence number and the reason code.
 *
 * <p>Replaces the AS-IS {@code error.dat} / reject-file rows
 * ({@code TEF-ORIG-SEQ} + {@code TEF-REASON-CODE}) with an in-memory object.
 */
public record TxnError(long seq, ErrorCode code) {

    public static TxnError of(long seq, ErrorCode code) {
        return new TxnError(seq, code);
    }
}
