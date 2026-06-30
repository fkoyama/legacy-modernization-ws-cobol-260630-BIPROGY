package com.practicebank.pipeline.decode;

/**
 * A decoded transaction detail (19 output, canonical detail that flows through
 * 10/11/12). Field set follows shared/copy/ws-txn-decoded-record.cpy.
 */
public record DecodedTxn(
        long seq,
        String category,
        long amountJpy,
        String currency,
        String payerAccount,
        String payeeAccount,
        String branchCode,
        String productCode,
        String description,
        String sourceBank,
        String sourceBranch,
        long originalSeq) {
}
