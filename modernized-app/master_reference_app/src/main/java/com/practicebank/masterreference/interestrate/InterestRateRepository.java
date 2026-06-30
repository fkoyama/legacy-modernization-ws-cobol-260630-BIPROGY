package com.practicebank.masterreference.interestrate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.practicebank.masterreference.interestrate.dto.InterestRate;

/**
 * interest_rates テーブル（db/migration V2）への参照アクセス。
 *
 * <p>マッピング: rateMicro = annual_rate × 1,000,000、effectiveFrom = effective_date。
 * 既存スキーマに tier / effective_to 列が無いため、tier は入力値をエコーし、
 * effectiveTo は既定値（2099-12-31）を返す。適用日以前で最新の effective_date を採用する。
 */
@Repository
public class InterestRateRepository {

    private static final LocalDate DEFAULT_EFFECTIVE_TO = LocalDate.of(2099, 12, 31);
    private static final BigDecimal MICRO = new BigDecimal("1000000");

    private final JdbcTemplate jdbc;

    public InterestRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InterestRate> find(String productCode, int tier, LocalDate effectiveDate) {
        RowMapper<InterestRate> mapper = (rs, rowNum) -> new InterestRate(
                trim(rs.getString("product_code")),
                tier,
                rs.getBigDecimal("annual_rate").multiply(MICRO).longValueExact(),
                rs.getObject("effective_date", LocalDate.class),
                DEFAULT_EFFECTIVE_TO);
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT product_code, effective_date, annual_rate FROM interest_rates "
                            + "WHERE product_code = ? AND effective_date <= ? "
                            + "ORDER BY effective_date DESC LIMIT 1",
                    mapper, productCode, effectiveDate));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
