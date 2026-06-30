package com.practicebank.masterreference.branch.dto;

/** 支店情報（OpenAPI Branch）。 */
public record Branch(String branchCode, String nameKanji, String nameKana,
                     String region, String status) {
}
