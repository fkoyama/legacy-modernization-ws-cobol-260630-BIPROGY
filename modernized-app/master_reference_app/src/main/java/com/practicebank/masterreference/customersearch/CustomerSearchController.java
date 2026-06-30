package com.practicebank.masterreference.customersearch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.practicebank.masterreference.customersearch.dto.CustomerSearchListResponse;

/** 04-customersearch /customer-search。 */
@RestController
@RequestMapping("/api/v1/customer-search")
public class CustomerSearchController {

    private final CustomerSearchService service;

    public CustomerSearchController(CustomerSearchService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerSearchListResponse searchCustomers(
            @RequestParam(required = false) String kanaPrefix,
            @RequestParam(required = false) String phonePrefix,
            @RequestParam(required = false) String addressSubstr,
            @RequestParam(required = false) String startAfter,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        return service.search(kanaPrefix, phonePrefix, addressSubstr, startAfter, pageSize);
    }
}
