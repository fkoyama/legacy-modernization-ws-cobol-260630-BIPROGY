package com.practicebank.masterreference.customer;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.practicebank.masterreference.customer.dto.Customer;
import com.practicebank.masterreference.customer.dto.CustomerListResponse;
import com.practicebank.masterreference.customer.dto.CustomerStatusChangeRequest;

/** 03-customer /customers。 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerListResponse listCustomers(
            @RequestParam(required = false) String kana,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String startAfter,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        return service.list(kana, phone, startAfter, pageSize);
    }

    @GetMapping("/{customerId}")
    public Customer getCustomerById(@PathVariable String customerId) {
        return service.getById(customerId);
    }

    @PatchMapping("/{customerId}/status")
    public Customer changeCustomerStatus(
            @PathVariable String customerId,
            @Valid @RequestBody CustomerStatusChangeRequest request) {
        return service.changeStatus(customerId, request.status(), request.reason());
    }
}
