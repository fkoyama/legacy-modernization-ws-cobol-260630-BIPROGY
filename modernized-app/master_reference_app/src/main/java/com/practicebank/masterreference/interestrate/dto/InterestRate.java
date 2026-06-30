package com.practicebank.masterreference.interestrate.dto;

import java.time.LocalDate;

/** 金利情報（OpenAPI InterestRate）。rateMicro は金利×1,000,000。 */
public record InterestRate(String productCode, int tier, long rateMicro,
                           LocalDate effectiveFrom, LocalDate effectiveTo) {
}
