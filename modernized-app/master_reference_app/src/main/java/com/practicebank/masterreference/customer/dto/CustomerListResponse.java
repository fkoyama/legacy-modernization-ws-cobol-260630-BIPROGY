package com.practicebank.masterreference.customer.dto;

import java.util.List;

/** 顧客一覧レスポンス（OpenAPI CustomerListResponse）。 */
public record CustomerListResponse(List<Customer> items, int totalCount,
                                   boolean hasMore, String lastId) {
}
