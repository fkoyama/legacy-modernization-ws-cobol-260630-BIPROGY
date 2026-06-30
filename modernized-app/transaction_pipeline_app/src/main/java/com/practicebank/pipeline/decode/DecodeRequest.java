package com.practicebank.pipeline.decode;

/** Request for the decode stage: path of the JSON input file. */
public record DecodeRequest(String inputPath, int rejectThresholdPct) {
}
