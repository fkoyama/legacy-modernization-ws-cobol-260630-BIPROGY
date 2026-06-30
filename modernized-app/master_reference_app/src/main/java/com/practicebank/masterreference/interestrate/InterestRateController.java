package com.practicebank.masterreference.interestrate;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.practicebank.masterreference.interestrate.dto.InterestRate;

/** 06-interestrate /interest-rates。 */
@RestController
@RequestMapping("/api/v1/interest-rates")
public class InterestRateController {

    private final InterestRateService service;

    public InterestRateController(InterestRateService service) {
        this.service = service;
    }

    @GetMapping
    public InterestRate getInterestRate(
            @RequestParam String productCode,
            @RequestParam int tier,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return service.get(productCode, tier, effectiveDate);
    }
}
