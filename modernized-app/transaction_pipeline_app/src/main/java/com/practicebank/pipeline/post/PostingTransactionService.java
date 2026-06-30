package com.practicebank.pipeline.post;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practicebank.pipeline.core.BatchContext;
import com.practicebank.pipeline.core.FatalPipelineException;
import com.practicebank.pipeline.sortmerge.ReadyTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.practicebank.pipeline.core.ErrorCode;

/**
 * 12-txnpost — the SERIALIZABLE posting transaction for a single transaction.
 *
 * <p>This bean only carries the {@code @Transactional(SERIALIZABLE)} boundary;
 * the serialization retry is applied OUTSIDE this transaction by
 * {@link PostStage}. Balance rows are locked in ascending account order to keep
 * the lock acquisition order consistent and avoid deadlocks.
 */
@Service
public class PostingTransactionService {

    private static final Logger log = LoggerFactory.getLogger(PostingTransactionService.class);
    private static final DateTimeFormatter TXN_DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    /** System accounts (must be seeded; absence is FATAL). */
    static final String CASH = "0010010000001";
    static final String CLEARING = "0010010000002";

    private final PostingRepository repository;
    private final ObjectMapper objectMapper;

    public PostingTransactionService(PostingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PostResult postOneTx(ReadyTxn ready, BatchContext ctx) {
        var t = ready.txn();
        long seq = t.seq();
        long amount = Math.abs(t.amountJpy());
        String batchId = clip(ctx.batchId(), 14);

        // I1 — idempotency.
        if (repository.existsBySourceBatchSeq(batchId, seq)) {
            log.info("post: seq {} already posted, skipping (E024)", seq);
            return PostResult.skipped(seq, null);
        }

        DrCrPair pair = mapDrCr(t.category(), t.payerAccount(), t.payeeAccount());
        if (pair == null) {
            return PostResult.rejected(seq, ErrorCode.E002);
        }

        // Lock balances in ascending account order.
        Map<String, Long> balances = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(java.util.List.of(pair.drAccount(), pair.crAccount()))) {
            Long bal = repository.lockBalance(acct);
            if (bal == null) {
                if (isSystemAccount(acct)) {
                    throw new FatalPipelineException("system account missing in balances: " + acct);
                }
                log.warn("post: seq {} account not found {} (E020)", seq, acct);
                return PostResult.rejected(seq, ErrorCode.E020);
            }
            balances.put(acct, bal);
        }

        // I3 — debit account (non-system) must not go negative.
        if (!isSystemAccount(pair.drAccount())) {
            long residual = balances.get(pair.drAccount()) - amount;
            if (residual < 0) {
                log.warn("post: seq {} insufficient balance on {} (E021)", seq, pair.drAccount());
                return PostResult.rejected(seq, ErrorCode.E021);
            }
        }

        String txnId = genTxnId(ctx.businessDate(), seq);
        Timestamp now = Timestamp.from(Instant.now());

        repository.insertTransaction(txnId, ctx.businessDate(), now, t.category(),
                t.payerAccount(), t.payeeAccount(), amount, t.description(),
                clip(ctx.sourceSystem(), 20), batchId, seq);

        repository.insertPosting(txnId + "01", txnId, 1, pair.drAccount(), amount, 0, "DR",
                ctx.businessDate());
        repository.insertPosting(txnId + "02", txnId, 2, pair.crAccount(), 0, amount, "CR",
                ctx.businessDate());

        // CR side increases, DR side decreases (sum of deltas == 0).
        repository.applyBalanceDelta(pair.drAccount(), -amount, txnId, ctx.businessDate());
        repository.applyBalanceDelta(pair.crAccount(), amount, txnId, ctx.businessDate());

        repository.insertOutbox(ctx.businessDate(), txnId, payload(txnId, t.category(), amount,
                pair), eventKey(txnId));

        return PostResult.posted(seq, txnId);
    }

    /** MAP-DR-CR (design 12 §2.2). */
    DrCrPair mapDrCr(String category, String payer, String payee) {
        return switch (category) {
            case "10" -> new DrCrPair(CASH, payer);       // 入金: DR cash, CR payer
            case "20" -> new DrCrPair(payer, CASH);       // 出金: DR payer, CR cash
            case "30" -> new DrCrPair(payer, payee);      // 振替: DR payer, CR payee
            case "40" -> new DrCrPair(payer, CLEARING);   // 送金: DR payer, CR clearing
            case "50" -> new DrCrPair(CASH, payer);       // 利息: DR (interest) cash, CR payer
            case "60" -> new DrCrPair(payer, CASH);       // 手数料: DR payer, CR (fee) cash
            default -> null;
        };
    }

    private boolean isSystemAccount(String acct) {
        return CASH.equals(acct) || CLEARING.equals(acct);
    }

    private String genTxnId(LocalDate businessDate, long seq) {
        return businessDate.format(TXN_DATE) + String.format("%010d", seq); // 8 + 10 = 18
    }

    private String payload(String txnId, String category, long amount, DrCrPair pair) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txnId", txnId);
        m.put("category", category);
        m.put("amountJpy", amount);
        m.put("dr", pair.drAccount());
        m.put("cr", pair.crAccount());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{\"txnId\":\"" + txnId + "\"}";
        }
    }

    private static String eventKey(String txnId) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(txnId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return txnId;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
