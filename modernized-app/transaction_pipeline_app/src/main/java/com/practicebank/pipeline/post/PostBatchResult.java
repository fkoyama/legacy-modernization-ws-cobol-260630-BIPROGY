package com.practicebank.pipeline.post;

import com.practicebank.pipeline.core.TxnError;

import java.util.List;

/** Result of 12-txnpost: posted / skipped (idempotent) / rejected breakdown. */
public record PostBatchResult(long posted, long skipped, List<TxnError> rejected) {
}
