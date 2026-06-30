package com.practicebank.masterreference.product.dto;

import java.time.LocalDate;

/** 商品情報（OpenAPI Product）。 */
public record Product(String productCode, String name, String type, String interestType,
                      boolean allowOverdraft, int termDays,
                      LocalDate effectiveFrom, LocalDate effectiveTo) {
}
