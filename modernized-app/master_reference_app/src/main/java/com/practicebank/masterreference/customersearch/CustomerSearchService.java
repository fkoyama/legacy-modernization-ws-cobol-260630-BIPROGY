package com.practicebank.masterreference.customersearch;

import java.util.List;

import org.springframework.stereotype.Service;

import com.practicebank.masterreference.customersearch.dto.CustomerSearchListResponse;
import com.practicebank.masterreference.customersearch.dto.CustomerSearchMatch;

/** 04-customersearch 顧客複合検索。 */
@Service
public class CustomerSearchService {

    private final CustomerSearchRepository repository;

    public CustomerSearchService(CustomerSearchRepository repository) {
        this.repository = repository;
    }

    public CustomerSearchListResponse search(String kanaPrefix, String phonePrefix,
                                             String addressSubstr, String startAfter, int pageSize) {
        List<CustomerSearchMatch> rows =
                repository.search(kanaPrefix, phonePrefix, addressSubstr, startAfter, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<CustomerSearchMatch> items = hasMore ? rows.subList(0, pageSize) : rows;
        String lastId = items.isEmpty() ? null : items.get(items.size() - 1).customerId();
        return new CustomerSearchListResponse(items, items.size(), hasMore, lastId);
    }
}
