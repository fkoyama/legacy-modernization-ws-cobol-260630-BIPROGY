package com.practicebank.pipeline.master.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** 05-product — GET /products/{productCode}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
        String productCode,
        String name,
        String type,
        String interestType,
        Boolean allowOverdraft,
        Integer termDays,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    /** Whether {@code date} is within [effectiveFrom, effectiveTo]. */
    public boolean isEffectiveOn(LocalDate date) {
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }

    /** Type T = time deposit (定期). */
    public boolean isTimeDeposit() {
        return "T".equals(type);
    }
}
