package com.practicebank.pipeline.decode.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InputTrailerJson(Long recordCount, Long amountSum) {
}
