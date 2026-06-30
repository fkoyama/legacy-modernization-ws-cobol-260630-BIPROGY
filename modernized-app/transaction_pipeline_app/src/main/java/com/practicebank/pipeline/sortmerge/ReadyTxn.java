package com.practicebank.pipeline.sortmerge;

import com.practicebank.pipeline.decode.DecodedTxn;

/** A transaction sorted and reconciled, ready for 12-txnpost. */
public record ReadyTxn(DecodedTxn txn) {

    public long seq() {
        return txn.seq();
    }

    public String payerAccount() {
        return txn.payerAccount();
    }
}
