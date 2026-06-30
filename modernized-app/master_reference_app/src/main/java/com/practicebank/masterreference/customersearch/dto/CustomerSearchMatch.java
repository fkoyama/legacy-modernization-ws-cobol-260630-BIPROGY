package com.practicebank.masterreference.customersearch.dto;

/** 顧客検索の一致レコード（OpenAPI CustomerSearchMatch）。 */
public record CustomerSearchMatch(String customerId, String kana, String kanji,
                                  String phone, String address) {
}
