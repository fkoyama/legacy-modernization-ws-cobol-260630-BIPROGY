package com.practicebank.pipeline.post;

/** Debit/credit account pair resolved by MAP-DR-CR for a transaction category. */
public record DrCrPair(String drAccount, String crAccount) {
}
