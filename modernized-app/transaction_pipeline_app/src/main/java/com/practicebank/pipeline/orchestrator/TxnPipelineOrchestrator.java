package com.practicebank.pipeline.orchestrator;

import com.practicebank.pipeline.console.PipelineCommand;
import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.StageResult;
import com.practicebank.pipeline.core.Status;
import com.practicebank.pipeline.decode.DecodeRequest;
import com.practicebank.pipeline.decode.DecodeStage;
import com.practicebank.pipeline.decode.DecodedBatch;
import com.practicebank.pipeline.post.PostBatchResult;
import com.practicebank.pipeline.post.PostStage;
import com.practicebank.pipeline.sortmerge.SortMergeResult;
import com.practicebank.pipeline.sortmerge.SortMergeStage;
import com.practicebank.pipeline.validate.ValidateStage;
import com.practicebank.pipeline.validate.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service-layer orchestrator for the core pipeline: 19 decode -> 10 validate ->
 * 11 sortmerge -> 12 post. The overall status is the most severe stage status;
 * a stage that returns INVALID/IO_FAIL/FATAL throws and aborts the run.
 */
@Service
public class TxnPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TxnPipelineOrchestrator.class);

    private final DecodeStage decodeStage;
    private final ValidateStage validateStage;
    private final SortMergeStage sortMergeStage;
    private final PostStage postStage;
    private final int rejectThresholdPct;

    public TxnPipelineOrchestrator(DecodeStage decodeStage,
                                   ValidateStage validateStage,
                                   SortMergeStage sortMergeStage,
                                   PostStage postStage,
                                   @Value("${pipeline.decode.reject-threshold-pct:100}") int rejectThresholdPct) {
        this.decodeStage = decodeStage;
        this.validateStage = validateStage;
        this.sortMergeStage = sortMergeStage;
        this.postStage = postStage;
        this.rejectThresholdPct = rejectThresholdPct;
    }

    /** Runs the core pipeline and returns the overall status. */
    public Status runCore(PipelineCommand cmd) {
        BatchContext ctx = new BatchContext(cmd.batchId(), cmd.businessDate(), cmd.sourceSystem());
        log.info("pipeline start: batchId={} businessDate={} input={}",
                ctx.batchId(), ctx.businessDate(), cmd.inputPath());

        // 19 decode
        StageResult<DecodedBatch> decode =
                decodeStage.execute(new DecodeRequest(cmd.inputPath(), rejectThresholdPct), ctx);
        if (decode.status() == Status.NO_INPUT) {
            log.info("pipeline end: NO_INPUT (no-op)");
            return Status.NO_INPUT;
        }
        DecodedBatch batch = decode.orThrowOnFatal();
        Status worst = decode.status();

        // 10 validate
        StageResult<ValidationResult> validate = validateStage.execute(batch, ctx);
        ValidationResult vr = validate.orThrowOnFatal();
        worst = worse(worst, validate.status());

        // 11 sortmerge
        StageResult<SortMergeResult> sort = sortMergeStage.execute(vr, ctx);
        SortMergeResult sm = sort.orThrowOnFatal();
        worst = worse(worst, sort.status());

        // 12 post
        StageResult<PostBatchResult> post = postStage.execute(sm, ctx);
        post.orThrowOnFatal();
        worst = worse(worst, post.status());

        log.info("pipeline end: status={}", worst);
        return worst;
    }

    private static Status worse(Status a, Status b) {
        return a.exitCode() >= b.exitCode() ? a : b;
    }
}
