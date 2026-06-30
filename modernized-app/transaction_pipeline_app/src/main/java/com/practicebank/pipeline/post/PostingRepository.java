package com.practicebank.pipeline.post;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * JDBC access for 12-txnpost. All methods are intended to run inside the caller's
 * SERIALIZABLE transaction. Balance rows are locked with SELECT ... FOR UPDATE.
 */
@Repository
public class PostingRepository {

    private final JdbcTemplate jdbc;

    public PostingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** I1 — idempotency: has this (batch, seq) already been posted? */
    public boolean existsBySourceBatchSeq(String batchId, long seq) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE source_batch_id = ? AND source_seq = ?",
                Integer.class, batchId, (int) seq);
        return n != null && n > 0;
    }

    /** Locks a balance row (FOR UPDATE) and returns its current balance, or null if absent. */
    public Long lockBalance(String account) {
        List<Long> rows = jdbc.query(
                "SELECT balance_jpy FROM balances WHERE account_number = ? FOR UPDATE",
                (rs, i) -> rs.getLong("balance_jpy"), account);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertTransaction(String txnId, LocalDate businessDate, Timestamp systemTs,
                                  String category, String account, String counterAccount,
                                  long amountJpy, String description, String sourceSystem,
                                  String batchId, long seq) {
        jdbc.update(
                "INSERT INTO transactions (txn_id, business_date, system_ts, category, "
                        + "account_number, counter_account_number, amount_jpy, currency, description, "
                        + "source_system, source_batch_id, source_seq, status, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'JPY', ?, ?, ?, ?, 'PT', 'PIPELINE')",
                txnId, businessDate, systemTs, category, account, counterAccount, amountJpy,
                description, sourceSystem, batchId, (int) seq);
    }

    public void insertPosting(String postingId, String txnId, int lineNo, String account,
                              long debitJpy, long creditJpy, String role, LocalDate businessDate) {
        jdbc.update(
                "INSERT INTO postings (posting_id, txn_id, line_no, account_number, debit_jpy, "
                        + "credit_jpy, posting_role, business_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                postingId, txnId, lineNo, account, debitJpy, creditJpy, role, businessDate);
    }

    public void applyBalanceDelta(String account, long delta, String lastTxnId, LocalDate businessDate) {
        jdbc.update(
                "UPDATE balances SET balance_jpy = balance_jpy + ?, available_jpy = available_jpy + ?, "
                        + "last_txn_id = ?, last_business_date = ?, updated_ts = NOW() "
                        + "WHERE account_number = ?",
                delta, delta, lastTxnId, businessDate, account);
    }

    public void insertOutbox(LocalDate businessDate, String txnId, String payloadJson, String eventKey) {
        jdbc.update(
                "INSERT INTO audit_outbox (business_date, subsystem, action, actor, target_type, "
                        + "target_id, payload_json, severity, event_key) "
                        + "VALUES (?, '12-txnpost', 'TXN_POSTED', 'PIPELINE', 'TXN', ?, CAST(? AS JSONB), 'I', ?)",
                businessDate, txnId, payloadJson, eventKey);
    }
}
