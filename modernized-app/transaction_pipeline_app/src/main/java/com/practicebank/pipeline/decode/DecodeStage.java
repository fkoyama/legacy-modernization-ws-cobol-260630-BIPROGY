package com.practicebank.pipeline.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.Counters;
import com.practicebank.pipeline.core.ErrorCode;
import com.practicebank.pipeline.core.PipelineStage;
import com.practicebank.pipeline.core.StageResult;
import com.practicebank.pipeline.core.Status;
import com.practicebank.pipeline.core.TxnError;
import com.practicebank.pipeline.decode.json.InputBatchJson;
import com.practicebank.pipeline.decode.json.InputDetailJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 19-integrationin — DecodeStage.
 *
 * <p>Reads the JSON batch file, validates the H/D/T structure, applies
 * decode-level field checks and produces a {@link DecodedBatch}. EBCDIC/CP930
 * decoding is intentionally NOT performed (out of scope, design 99 §6-D): the
 * upstream system delivers JSON.
 */
@Component
public class DecodeStage implements PipelineStage<DecodeRequest, DecodedBatch> {

    private static final Logger log = LoggerFactory.getLogger(DecodeStage.class);
    private static final Set<String> KNOWN_CATEGORIES = Set.of("10", "20", "30", "40", "50", "60");

    private final ObjectMapper objectMapper;

    public DecodeStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public StageResult<DecodedBatch> execute(DecodeRequest req, BatchContext ctx) {
        Path inputPath = Path.of(req.inputPath());

        // 01 — input not available (no-op end), mirrors INTI sentinel check.
        if (!Files.exists(inputPath)) {
            log.warn("decode: input file not found, treating as NO_INPUT(01): {}", inputPath);
            return StageResult.noInput();
        }

        InputBatchJson in;
        try {
            in = objectMapper.readValue(Files.readAllBytes(inputPath), InputBatchJson.class);
        } catch (IOException e) {
            log.error("decode: failed to read/parse JSON input {}", inputPath, e);
            return StageResult.of(Status.INVALID, null, Counters.empty());
        }

        // 08 — header/trailer/details missing.
        if (in == null || in.header() == null || in.trailer() == null || in.details() == null) {
            log.error("decode: missing header/details/trailer in {}", inputPath);
            return StageResult.of(Status.INVALID, null, Counters.empty());
        }

        DecodedHeader header;
        try {
            header = toHeader(in);
        } catch (DateTimeParseException e) {
            log.error("decode: invalid header businessDate", e);
            return StageResult.of(Status.INVALID, null, Counters.empty());
        }

        List<DecodedTxn> decoded = new ArrayList<>();
        List<TxnError> rejected = new ArrayList<>();
        long amountSum = 0;
        for (InputDetailJson d : in.details()) {
            long seq = d.seq() == null ? 0 : d.seq();
            ErrorCode reject = decodeCheck(d);
            if (reject != null) {
                rejected.add(TxnError.of(seq, reject));
                continue;
            }
            DecodedTxn t = toDecodedTxn(d);
            decoded.add(t);
            amountSum += t.amountJpy();
        }

        long totalDetails = in.details().size();

        // Trailer record-count verification (VERIFY-TRAILER).
        if (in.trailer().recordCount() != null && in.trailer().recordCount() != totalDetails) {
            log.error("decode: trailer recordCount {} != detail count {}",
                    in.trailer().recordCount(), totalDetails);
            return StageResult.of(Status.INVALID, null, Counters.empty());
        }

        DecodedTrailer trailer = new DecodedTrailer(totalDetails, amountSum);
        DecodedBatch batch = new DecodedBatch(header, decoded, trailer, rejected);
        Counters counters = new Counters(totalDetails, decoded.size(), rejected.size(), 0);

        int rejectPct = totalDetails == 0 ? 0 : (int) (rejected.size() * 100 / totalDetails);
        if (!rejected.isEmpty()) {
            log.warn("decode: {} rejected of {} ({}%), threshold {}%",
                    rejected.size(), totalDetails, rejectPct, req.rejectThresholdPct());
            return StageResult.partial(batch, counters);
        }
        log.info("decode: {} details decoded from {}", decoded.size(), inputPath);
        return StageResult.ok(batch, counters);
    }

    private ErrorCode decodeCheck(InputDetailJson d) {
        if (d.category() == null || !KNOWN_CATEGORIES.contains(d.category())) {
            return ErrorCode.E105;
        }
        if (d.amountJpy() == null || d.amountJpy() == 0L) {
            // VALIDATE-AMOUNT rejects zero amounts at decode time.
            return ErrorCode.E009;
        }
        if (d.payerAccount() == null || !d.payerAccount().matches("\\d{13}")) {
            return ErrorCode.E106;
        }
        return null;
    }

    private DecodedHeader toHeader(InputBatchJson in) {
        return new DecodedHeader(
                trim(in.header().batchId()),
                LocalDate.parse(in.header().businessDate()),
                in.header().sourceSystem() == null ? "JSON_BATCH" : in.header().sourceSystem(),
                in.header().expectedCount() == null ? in.details().size() : in.header().expectedCount(),
                in.header().amountSum() == null ? 0 : in.header().amountSum());
    }

    private DecodedTxn toDecodedTxn(InputDetailJson d) {
        return new DecodedTxn(
                d.seq() == null ? 0 : d.seq(),
                d.category(),
                d.amountJpy(),
                d.currency() == null ? "JPY" : d.currency(),
                d.payerAccount(),
                emptyToNull(d.payeeAccount()),
                d.branchCode(),
                d.productCode(),
                d.description(),
                d.sourceBank(),
                d.sourceBranch(),
                d.originalSeq() == null ? 0 : d.originalSeq());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
