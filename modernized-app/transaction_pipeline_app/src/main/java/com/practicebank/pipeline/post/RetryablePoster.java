package com.practicebank.pipeline.post;

import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.sortmerge.ReadyTxn;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Serialization-retry boundary for 12-txnpost. The {@code @Retryable} advice wraps
 * the call OUTSIDE the {@link PostingTransactionService} SERIALIZABLE transaction,
 * so a 40001 serialization failure rolls back and re-runs the whole posting.
 *
 * <p>Kept as a separate bean so the retry proxy is actually applied (self-invocation
 * from {@link PostStage} would bypass it).
 */
@Component
public class RetryablePoster {

    private final PostingTransactionService postingService;

    public RetryablePoster(PostingTransactionService postingService) {
        this.postingService = postingService;
    }

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttemptsExpression = "${pipeline.post.retry.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${pipeline.post.retry.delay-ms:10}",
                    multiplierExpression = "${pipeline.post.retry.multiplier:2}",
                    maxDelayExpression = "${pipeline.post.retry.max-delay-ms:2000}"))
    public PostResult postOne(ReadyTxn ready, BatchContext ctx) {
        return postingService.postOneTx(ready, ctx);
    }
}
