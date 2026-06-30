package com.practicebank.masterreference.customer;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.practicebank.masterreference.common.NotFoundException;
import com.practicebank.masterreference.customer.dto.Customer;
import com.practicebank.masterreference.customer.dto.CustomerListResponse;

/** 03-customer 顧客マスタ参照・状態変更。 */
@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    public CustomerListResponse list(String kana, String phone, String startAfter, int pageSize) {
        List<Customer> rows = repository.search(kana, phone, startAfter, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Customer> items = hasMore ? rows.subList(0, pageSize) : rows;
        String lastId = items.isEmpty() ? null : items.get(items.size() - 1).customerId();
        return new CustomerListResponse(items, items.size(), hasMore, lastId);
    }

    public Customer getById(String customerId) {
        return repository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("customer not found: " + customerId));
    }

    @Transactional
    public Customer changeStatus(String customerId, String status, String reason) {
        int updated = repository.updateStatus(customerId, status);
        if (updated == 0) {
            throw new NotFoundException("customer not found: " + customerId);
        }
        // 監査記録（21-audit）はハッカソン版では最小実装（将来 audit_log へ INSERT）。
        return getById(customerId);
    }
}
