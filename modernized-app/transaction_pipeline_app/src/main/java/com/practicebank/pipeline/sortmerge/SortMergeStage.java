package com.practicebank.pipeline.sortmerge;

import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.Counters;
import com.practicebank.pipeline.core.ErrorCode;
import com.practicebank.pipeline.core.FatalPipelineException;
import com.practicebank.pipeline.core.PipelineStage;
import com.practicebank.pipeline.core.StageResult;
import com.practicebank.pipeline.core.TxnError;
import com.practicebank.pipeline.validate.ValidTxn;
import com.practicebank.pipeline.validate.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 11-txnsortmerge — SortMergeStage.
 *
 * <p>Sorts the valid transactions by payer account then sequence, reconciles
 * against the previous-day ready set (E050 duplicates), and asserts losslessness
 * (in count == out count + duplicates). A lossless violation is FATAL(16).
 */
@Component
public class SortMergeStage implements PipelineStage<ValidationResult, SortMergeResult> {

    private static final Logger log = LoggerFactory.getLogger(SortMergeStage.class);

    private static final Comparator<ValidTxn> ORDER =
            Comparator.comparing((ValidTxn v) -> v.payerAccount()).thenComparingLong(ValidTxn::seq);

    private final ReconRepository reconRepository;

    public SortMergeStage(ReconRepository reconRepository) {
        this.reconRepository = reconRepository;
    }

    @Override
    public StageResult<SortMergeResult> execute(ValidationResult input, BatchContext ctx) {
        List<ValidTxn> sorted = new ArrayList<>(input.valid());
        sorted.sort(ORDER);

        Set<String> existing = reconRepository.existingReadyKeys(ctx.batchId());

        List<ReadyTxn> ready = new ArrayList<>();
        List<TxnError> duplicates = new ArrayList<>();
        for (ValidTxn v : sorted) {
            String key = reconKey(ctx.batchId(), v);
            if (existing.contains(key)) {
                duplicates.add(TxnError.of(v.seq(), ErrorCode.E050));
            } else {
                ready.add(new ReadyTxn(v.txn()));
            }
        }

        // Lossless invariant: nothing may silently disappear.
        long in = sorted.size();
        long out = ready.size() + duplicates.size();
        if (in != out) {
            throw new FatalPipelineException(
                    "sortmerge lossless violation: in=" + in + " out=" + out);
        }

        SortMergeResult result = new SortMergeResult(ready, duplicates);
        Counters counters = new Counters(in, ready.size(), duplicates.size(), 0);

        if (!duplicates.isEmpty()) {
            log.warn("sortmerge: {} ready, {} duplicates (E050)", ready.size(), duplicates.size());
            return StageResult.partial(result, counters);
        }
        log.info("sortmerge: {} transactions ready", ready.size());
        return StageResult.ok(result, counters);
    }

    private static String reconKey(String batchId, ValidTxn v) {
        return batchId + ":" + v.seq();
    }
}
