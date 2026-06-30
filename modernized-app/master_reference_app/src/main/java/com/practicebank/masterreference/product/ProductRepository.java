package com.practicebank.masterreference.product;

import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.practicebank.masterreference.product.dto.Product;

/**
 * products テーブル（db/migration V2）への参照アクセス。
 *
 * <p>マッピング: name=product_name, type=product_type, interestType=interest_eligible。
 * 既存スキーマに allowOverdraft / term_days / 有効期間 列が無いため、
 * allowOverdraft=false, termDays=0, effectiveFrom/To=null を返す。
 */
@Repository
public class ProductRepository {

    private static final RowMapper<Product> ROW_MAPPER = (rs, rowNum) -> new Product(
            trim(rs.getString("product_code")),
            rs.getString("product_name"),
            trim(rs.getString("product_type")),
            trim(rs.getString("interest_eligible")),
            false,
            0,
            null,
            null);

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Product> findByCode(String productCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT product_code, product_name, product_type, interest_eligible "
                            + "FROM products WHERE product_code = ?",
                    ROW_MAPPER, productCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
