package com.practicebank.masterreference.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 顧客状態変更リクエスト（OpenAPI CustomerStatusChangeRequest）。 */
public record CustomerStatusChangeRequest(
        @NotBlank @Size(max = 1) String status,
        @Size(max = 200) String reason) {
}
