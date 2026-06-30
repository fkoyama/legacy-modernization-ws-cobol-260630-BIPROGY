package com.practicebank.masterreference.interestrate;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.practicebank.masterreference.common.NotFoundException;
import com.practicebank.masterreference.interestrate.dto.InterestRate;

/** 06-interestrate 金利マスタ参照。 */
@Service
public class InterestRateService {

    private final InterestRateRepository repository;

    public InterestRateService(InterestRateRepository repository) {
        this.repository = repository;
    }

    public InterestRate get(String productCode, int tier, LocalDate effectiveDate) {
        return repository.find(productCode, tier, effectiveDate)
                .orElseThrow(() -> new NotFoundException(
                        "interest rate not found: product=" + productCode
                                + ", tier=" + tier + ", date=" + effectiveDate));
    }
}
