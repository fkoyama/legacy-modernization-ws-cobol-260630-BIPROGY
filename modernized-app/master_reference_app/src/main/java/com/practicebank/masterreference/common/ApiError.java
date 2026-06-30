package com.practicebank.masterreference.common;

/**
 * 共通エラーレスポンス（OpenAPI ErrorResponse 準拠）。
 * error: invalid_input / not_found / io_failure / fatal
 */
public record ApiError(String error, String detail) {
}
