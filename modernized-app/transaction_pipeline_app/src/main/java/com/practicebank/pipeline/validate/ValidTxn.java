package com.practicebank.pipeline.validate;

import com.practicebank.pipeline.decode.DecodedTxn;

/** A transaction that passed 10-txnvalidate. */
public record ValidTxn(DecodedTxn txn) {

    public long seq() {
        return txn.seq();
    }

    public String payerAccount() {
        return txn.payerAccount();
    }
}
