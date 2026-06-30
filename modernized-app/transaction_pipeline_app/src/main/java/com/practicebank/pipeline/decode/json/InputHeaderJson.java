package com.practicebank.pipeline.decode.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InputHeaderJson(
        String batchId,
        String businessDate,
        String sourceSystem,
        Long expectedCount,
        Long amountSum) {
}
