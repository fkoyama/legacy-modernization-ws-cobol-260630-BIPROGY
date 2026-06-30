package com.practicebank.pipeline.master.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 02-branch — GET /branches/{branchCode}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Branch(
        String branchCode,
        String nameKanji,
        String nameKana,
        String region,
        String status) {
}
