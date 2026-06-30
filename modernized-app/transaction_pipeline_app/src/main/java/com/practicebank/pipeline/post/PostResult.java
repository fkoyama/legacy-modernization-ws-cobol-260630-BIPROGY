package com.practicebank.pipeline.post;

import com.practicebank.pipeline.core.ErrorCode;

/** Outcome of posting a single transaction (12-txnpost). */
public record PostResult(Kind kind, long seq, String txnId, ErrorCode error) {

    public enum Kind { POSTED, SKIPPED, REJECTED }

    public static PostResult posted(long seq, String txnId) {
        return new PostResult(Kind.POSTED, seq, txnId, null);
    }

    public static PostResult skipped(long seq, String txnId) {
        return new PostResult(Kind.SKIPPED, seq, txnId, ErrorCode.E024);
    }

    public static PostResult rejected(long seq, ErrorCode error) {
        return new PostResult(Kind.REJECTED, seq, null, error);
    }
}
