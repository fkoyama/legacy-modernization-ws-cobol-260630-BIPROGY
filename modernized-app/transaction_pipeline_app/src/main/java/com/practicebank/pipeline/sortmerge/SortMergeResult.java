package com.practicebank.pipeline.sortmerge;

import com.practicebank.pipeline.core.TxnError;

import java.util.List;

/** Result of 11-txnsortmerge: ready transactions plus reconciliation duplicates. */
public record SortMergeResult(List<ReadyTxn> ready, List<TxnError> duplicates) {
}
