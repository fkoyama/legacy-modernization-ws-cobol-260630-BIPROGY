package com.practicebank.pipeline.validate;

import com.practicebank.pipeline.master.MasterReferenceClient;
import com.practicebank.pipeline.master.dto.Branch;
import com.practicebank.pipeline.master.dto.BusinessCalendarDay;
import com.practicebank.pipeline.master.dto.Product;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-batch master cache: the business-day calendar entry is fetched once, and
 * branch / product lookups are memoised so each distinct code hits the Master
 * Reference App at most once.
 */
public class MasterCache {

    private final MasterReferenceClient client;
    private final Map<String, Optional<Branch>> branches = new HashMap<>();
    private final Map<String, Optional<Product>> products = new HashMap<>();
    private Optional<BusinessCalendarDay> calendar;
    private LocalDate calendarDate;

    public MasterCache(MasterReferenceClient client) {
        this.client = client;
    }

    public Optional<BusinessCalendarDay> calendar(LocalDate date) {
        if (calendar == null || !date.equals(calendarDate)) {
            calendarDate = date;
            calendar = client.getBusinessCalendar(date);
        }
        return calendar;
    }

    public Optional<Branch> branch(String code) {
        return branches.computeIfAbsent(code, client::getBranch);
    }

    public Optional<Product> product(String code) {
        return products.computeIfAbsent(code, client::getProduct);
    }
}
