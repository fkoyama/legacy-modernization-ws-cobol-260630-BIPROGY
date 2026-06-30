package com.practicebank.pipeline.decode.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson binding for the JSON input file consumed by 19-integrationin.
 * EBCDIC fixed-length decoding is out of scope (the upstream system produces
 * this JSON) — see design 99 §6-D.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InputBatchJson(
        InputHeaderJson header,
        List<InputDetailJson> details,
        InputTrailerJson trailer) {
}
