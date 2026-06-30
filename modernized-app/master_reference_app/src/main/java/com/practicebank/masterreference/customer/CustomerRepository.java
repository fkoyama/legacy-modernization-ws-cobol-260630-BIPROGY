package com.practicebank.masterreference.customer;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.practicebank.masterreference.customer.dto.Customer;

/**
 * customers テーブル（db/migration V2）への参照・更新アクセス。
 *
 * <p>マッピング: kana=cust_name_kana, kanji=cust_name, status=cust_status。
 * 既存スキーマに opened_date 列が無いため openedDate は null を返す。
 */
@Repository
public class CustomerRepository {

    /** 一覧/検索用（openedDate は null）。 */
    private static final RowMapper<Customer> LIST_MAPPER = (rs, rowNum) -> new Customer(
            trim(rs.getString("cust_id")),
            rs.getString("cust_name_kana"),
            rs.getString("cust_name"),
            rs.getString("phone"),
            rs.getString("address"),
            null,
            trim(rs.getString("cust_status")),
            trim(rs.getString("tier")));

    private static final String SELECT_COLS =
            "cust_id, cust_name, cust_name_kana, cust_status, tier, phone, address";

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findById(String customerId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + SELECT_COLS + " FROM customers WHERE cust_id = ?",
                    LIST_MAPPER, customerId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 全件/カナ前方一致/電話完全一致を、cust_id カーソルで取得する。 */
    public List<Customer> search(String kana, String phone, String startAfter, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM customers WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (kana != null && !kana.isBlank()) {
            sql.append(" AND cust_name_kana LIKE ?");
            args.add(kana + "%");
        }
        if (phone != null && !phone.isBlank()) {
            sql.append(" AND phone = ?");
            args.add(phone);
        }
        if (startAfter != null && !startAfter.isBlank()) {
            sql.append(" AND cust_id > ?");
            args.add(startAfter);
        }
        sql.append(" ORDER BY cust_id LIMIT ?");
        args.add(pageSize);
        return jdbc.query(sql.toString(), LIST_MAPPER, args.toArray());
    }

    public int updateStatus(String customerId, String status) {
        return jdbc.update(
                "UPDATE customers SET cust_status = ?, updated_at = NOW() WHERE cust_id = ?",
                status, customerId);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
