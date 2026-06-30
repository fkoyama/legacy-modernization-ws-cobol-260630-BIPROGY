package com.practicebank.pipeline.decode.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InputDetailJson(
        Long seq,
        String category,
        Long amountJpy,
        String currency,
        String payerAccount,
        String payeeAccount,
        String branchCode,
        String productCode,
        String description,
        String sourceBank,
        String sourceBranch,
        Long originalSeq) {
}
