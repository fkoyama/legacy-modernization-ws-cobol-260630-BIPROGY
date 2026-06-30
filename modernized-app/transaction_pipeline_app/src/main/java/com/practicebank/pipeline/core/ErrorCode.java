package com.practicebank.pipeline.core;

/**
 * Reject / error reason codes used across the transaction pipeline.
 *
 * <p>The {@link #priority()} value encodes the AS-IS "Primary Error" precedence
 * (design 10-txnvalidate §2.2): when a single detail violates several rules, the
 * code with the lowest priority number is reported as the representative error.
 * Validation codes follow the documented order
 * {@code E001→E002→E003→E009→E013→E017→E012→E014→E015→E016→E007→E008→E010→E011→E018→E019→E099};
 * decode (19), sort/merge (11) and post (12) specific codes are ordered after them.
 */
public enum ErrorCode {

    // --- 10-txnvalidate (Primary-Error precedence) ---
    E001(1, "record invalid"),
    E002(2, "unknown transaction category"),
    E003(3, "payer account must be 13 numeric digits"),
    E009(4, "amount must not be zero"),
    E013(5, "currency must be JPY"),
    E017(6, "validation error"),
    E012(7, "business date is not a business day"),
    E014(8, "branch code not found"),
    E015(9, "product code not found"),
    E016(10, "product not effective on business date"),
    E007(11, "transfer/remittance requires a counter account"),
    E008(12, "self transfer is not allowed"),
    E010(13, "amount exceeds the limit"),
    E011(14, "validation error"),
    E018(15, "counter account set on a non-transfer category"),
    E019(16, "withdrawal from a time-deposit product"),
    E099(17, "unclassified validation error"),

    // --- 12-txnpost (posting checks I1/I3/I5 and prohibited operations) ---
    E004(20, "transaction data invalid"),
    E005(21, "operation on a closed account"),
    E006(22, "operation on a suspended account"),
    E020(23, "account does not exist"),
    E021(24, "insufficient balance"),
    E022(25, "dormant account (deferred)"),
    E023(26, "withdrawal-prohibited account"),
    E024(27, "already posted (idempotent skip)"),
    E025(28, "prohibited operation"),
    E026(29, "prohibited operation"),

    // --- 11-txnsortmerge (duplicate detection) ---
    E050(40, "duplicate transaction (resubmission)"),

    // --- 19-integrationin (decode-level rejects) ---
    E105(50, "unknown transaction category (decode)"),
    E106(51, "payer account format invalid (decode)");

    private final int priority;
    private final String description;

    ErrorCode(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    /** Primary-Error precedence (lower value = higher precedence). */
    public int priority() {
        return priority;
    }

    /** Human-readable reason text for reports and logs. */
    public String description() {
        return description;
    }
}
