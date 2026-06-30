package com.practicebank.masterreference.customersearch;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.practicebank.masterreference.customersearch.dto.CustomerSearchMatch;

/**
 * 04-customersearch の検索アクセス。自前テーブルは持たず customers を検索する。
 */
@Repository
public class CustomerSearchRepository {

    private static final RowMapper<CustomerSearchMatch> ROW_MAPPER = (rs, rowNum) ->
            new CustomerSearchMatch(
                    trim(rs.getString("cust_id")),
                    rs.getString("cust_name_kana"),
                    rs.getString("cust_name"),
                    rs.getString("phone"),
                    rs.getString("address"));

    private static final String SELECT_COLS =
            "cust_id, cust_name, cust_name_kana, phone, address";

    private final JdbcTemplate jdbc;

    public CustomerSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CustomerSearchMatch> search(String kanaPrefix, String phonePrefix,
                                            String addressSubstr, String startAfter, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM customers WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (kanaPrefix != null && !kanaPrefix.isBlank()) {
            sql.append(" AND cust_name_kana LIKE ?");
            args.add(kanaPrefix + "%");
        }
        if (phonePrefix != null && !phonePrefix.isBlank()) {
            sql.append(" AND phone LIKE ?");
            args.add(phonePrefix + "%");
        }
        if (addressSubstr != null && !addressSubstr.isBlank()) {
            sql.append(" AND address LIKE ?");
            args.add("%" + addressSubstr + "%");
        }
        if (startAfter != null && !startAfter.isBlank()) {
            sql.append(" AND cust_id > ?");
            args.add(startAfter);
        }
        sql.append(" ORDER BY cust_id LIMIT ?");
        args.add(pageSize);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
