package com.practicebank.pipeline.sortmerge;

import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * Reconciliation source for 11-txnsortmerge (E050 duplicate detection against the
 * previous-day "ready" set).
 *
 * <p>The {@code daily_ready} table (and its Flyway migration) is not yet defined,
 * so this default implementation is a pass-through that returns an empty set. When
 * the table is introduced, replace the body with a JdbcTemplate query.
 */
@Repository
public class ReconRepository {

    /** Returns the set of already-ready transaction keys for the batch (none by default). */
    public Set<String> existingReadyKeys(String batchId) {
        return Set.of();
    }
}
