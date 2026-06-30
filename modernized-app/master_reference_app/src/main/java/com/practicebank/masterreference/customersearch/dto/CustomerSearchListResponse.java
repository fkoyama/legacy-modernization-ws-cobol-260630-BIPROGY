package com.practicebank.masterreference.customersearch.dto;

import java.util.List;

/** 顧客検索一覧レスポンス（OpenAPI CustomerSearchListResponse）。 */
public record CustomerSearchListResponse(List<CustomerSearchMatch> items, int totalCount,
                                         boolean hasMore, String lastId) {
}
