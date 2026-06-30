package com.practicebank.pipeline.validate;

import com.practicebank.pipeline.core.ErrorCode;
import com.practicebank.pipeline.core.TxnError;

import java.util.List;
import java.util.Map;

/**
 * Result of 10-txnvalidate: the valid transactions, the per-detail primary
 * errors and an occurrence map (count per ErrorCode) for the batch report.
 */
public record ValidationResult(
        List<ValidTxn> valid,
        List<TxnError> errors,
        Map<ErrorCode, Long> occurrence) {
}
