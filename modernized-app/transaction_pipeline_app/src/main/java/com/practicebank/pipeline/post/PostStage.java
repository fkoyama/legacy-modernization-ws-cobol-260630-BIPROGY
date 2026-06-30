package com.practicebank.pipeline.post;

import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.Counters;
import com.practicebank.pipeline.core.PipelineStage;
import com.practicebank.pipeline.core.StageResult;
import com.practicebank.pipeline.core.TxnError;
import com.practicebank.pipeline.sortmerge.ReadyTxn;
import com.practicebank.pipeline.sortmerge.SortMergeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 12-txnpost — PostStage.
 *
 * <p>Posts each ready transaction through the retry/transaction boundary and
 * aggregates the outcome. Per-transaction business rejections (E020/E021/E024)
 * yield PARTIAL; a {@code FatalPipelineException} thrown by the posting service
 * (e.g. missing system account) aborts the whole pipeline.
 */
@Component
public class PostStage implements PipelineStage<SortMergeResult, PostBatchResult> {

    private static final Logger log = LoggerFactory.getLogger(PostStage.class);

    private final RetryablePoster poster;

    public PostStage(RetryablePoster poster) {
        this.poster = poster;
    }

    @Override
    public StageResult<PostBatchResult> execute(SortMergeResult input, BatchContext ctx) {
        long posted = 0;
        long skipped = 0;
        List<TxnError> rejected = new ArrayList<>();

        for (ReadyTxn ready : input.ready()) {
            PostResult r = poster.postOne(ready, ctx);
            switch (r.kind()) {
                case POSTED -> posted++;
                case SKIPPED -> skipped++;
                case REJECTED -> rejected.add(TxnError.of(r.seq(), r.error()));
            }
        }

        PostBatchResult result = new PostBatchResult(posted, skipped, rejected);
        Counters counters = new Counters(input.ready().size(), posted, rejected.size(), skipped);

        if (!rejected.isEmpty()) {
            log.warn("post: {} posted, {} skipped, {} rejected", posted, skipped, rejected.size());
            return StageResult.partial(result, counters);
        }
        log.info("post: {} posted, {} skipped", posted, skipped);
        return StageResult.ok(result, counters);
    }
}
