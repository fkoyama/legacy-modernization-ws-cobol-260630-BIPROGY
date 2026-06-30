package com.practicebank.pipeline.validate;

import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.Counters;
import com.practicebank.pipeline.core.ErrorCode;
import com.practicebank.pipeline.core.PipelineStage;
import com.practicebank.pipeline.core.StageResult;
import com.practicebank.pipeline.core.TxnError;
import com.practicebank.pipeline.decode.DecodedBatch;
import com.practicebank.pipeline.decode.DecodedTxn;
import com.practicebank.pipeline.master.MasterReferenceClient;
import com.practicebank.pipeline.master.dto.Branch;
import com.practicebank.pipeline.master.dto.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 10-txnvalidate — ValidateStage.
 *
 * <p>Applies field rules and master cross-checks. For each detail the first
 * failing rule (in AS-IS Primary-Error priority order via {@link ErrorCode#priority()})
 * is recorded as the representative error; valid details flow on.
 */
@Component
public class ValidateStage implements PipelineStage<DecodedBatch, ValidationResult> {

    private static final Logger log = LoggerFactory.getLogger(ValidateStage.class);
    private static final Set<String> CATEGORIES = Set.of("10", "20", "30", "40", "50", "60");
    private static final Set<String> TRANSFER_CATEGORIES = Set.of("30", "40");
    private static final long AMOUNT_LIMIT = 100_000_000L;

    private final MasterReferenceClient masterClient;

    public ValidateStage(MasterReferenceClient masterClient) {
        this.masterClient = masterClient;
    }

    @Override
    public StageResult<ValidationResult> execute(DecodedBatch batch, BatchContext ctx) {
        MasterCache cache = new MasterCache(masterClient);
        LocalDate businessDate = ctx.businessDate();

        List<ValidTxn> valid = new ArrayList<>();
        List<TxnError> errors = new ArrayList<>();
        Map<ErrorCode, Long> occurrence = new EnumMap<>(ErrorCode.class);

        for (DecodedTxn t : batch.details()) {
            ErrorCode primary = firstError(t, businessDate, cache);
            if (primary == null) {
                valid.add(new ValidTxn(t));
            } else {
                errors.add(TxnError.of(t.seq(), primary));
                occurrence.merge(primary, 1L, Long::sum);
            }
        }

        ValidationResult result = new ValidationResult(valid, errors, occurrence);
        Counters counters = new Counters(batch.details().size(), valid.size(), errors.size(), 0);

        if (!errors.isEmpty()) {
            log.warn("validate: {} valid, {} rejected (occurrence={})",
                    valid.size(), errors.size(), occurrence);
            return StageResult.partial(result, counters);
        }
        log.info("validate: all {} details valid", valid.size());
        return StageResult.ok(result, counters);
    }

    /**
     * Returns the highest-priority error for the detail, or {@code null} if valid.
     * Checks are evaluated in priority order.
     */
    private ErrorCode firstError(DecodedTxn t, LocalDate businessDate, MasterCache cache) {
        // E002 — known category
        if (t.category() == null || !CATEGORIES.contains(t.category())) {
            return ErrorCode.E002;
        }
        // E003 — payer 13 numeric digits
        if (t.payerAccount() == null || !t.payerAccount().matches("\\d{13}")) {
            return ErrorCode.E003;
        }
        // E009 — amount not zero
        if (t.amountJpy() == 0L) {
            return ErrorCode.E009;
        }
        // E013 — currency JPY
        if (!"JPY".equals(t.currency())) {
            return ErrorCode.E013;
        }
        // E012 — business day
        if (!cache.calendar(businessDate).map(c -> c.isBusinessDay()).orElse(false)) {
            return ErrorCode.E012;
        }
        // E014 — branch exists
        Optional<Branch> branch = cache.branch(t.branchCode());
        if (branch.isEmpty()) {
            return ErrorCode.E014;
        }
        // E015 / E016 — product exists and effective
        Optional<Product> product = cache.product(t.productCode());
        if (product.isEmpty()) {
            return ErrorCode.E015;
        }
        if (!product.get().isEffectiveOn(businessDate)) {
            return ErrorCode.E016;
        }
        boolean transfer = TRANSFER_CATEGORIES.contains(t.category());
        boolean hasPayee = t.payeeAccount() != null && !t.payeeAccount().isBlank();
        // E007 — transfer/remit requires a counter account
        if (transfer && !hasPayee) {
            return ErrorCode.E007;
        }
        // E008 — self transfer
        if (transfer && t.payerAccount().equals(t.payeeAccount())) {
            return ErrorCode.E008;
        }
        // E010 — amount limit
        if (Math.abs(t.amountJpy()) >= AMOUNT_LIMIT) {
            return ErrorCode.E010;
        }
        // E018 — counter account on a non-transfer category
        if (!transfer && hasPayee) {
            return ErrorCode.E018;
        }
        // E019 — withdrawal (20) from a time-deposit product
        if ("20".equals(t.category()) && product.get().isTimeDeposit()) {
            return ErrorCode.E019;
        }
        return null;
    }
}
