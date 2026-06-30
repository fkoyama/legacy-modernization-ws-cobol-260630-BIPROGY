package com.practicebank.masterreference.common;

/** 入力不正（COBOL status=08 → HTTP 400）。 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
