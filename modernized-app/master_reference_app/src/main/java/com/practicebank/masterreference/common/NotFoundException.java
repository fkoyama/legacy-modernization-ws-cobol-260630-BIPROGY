package com.practicebank.masterreference.common;

/** 該当なし（COBOL status=04 → HTTP 404）。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
