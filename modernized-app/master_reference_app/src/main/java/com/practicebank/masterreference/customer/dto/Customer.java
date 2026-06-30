package com.practicebank.masterreference.customer.dto;

import java.time.LocalDate;

/** 顧客情報（OpenAPI Customer）。openedDate は単件取得時のみ、一覧/検索では null。 */
public record Customer(String customerId, String kana, String kanji, String phone,
                       String address, LocalDate openedDate, String status, String tier) {
}
