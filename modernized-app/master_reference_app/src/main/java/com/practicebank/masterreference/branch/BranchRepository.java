package com.practicebank.masterreference.branch;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.practicebank.masterreference.branch.dto.Branch;

/**
 * branches テーブル（db/migration V2）への参照アクセス。
 *
 * <p>注: 既存スキーマに region / status 列が無いため、region は branch_type を流用し、
 * status は既定値 "A" を返す。スキーマ拡張時に差し替える。
 */
@Repository
public class BranchRepository {

    private static final String STATUS_DEFAULT = "A";

    private static final RowMapper<Branch> ROW_MAPPER = (rs, rowNum) -> new Branch(
            trim(rs.getString("branch_code")),
            rs.getString("branch_name"),
            rs.getString("branch_name_kana"),
            trim(rs.getString("branch_type")),
            STATUS_DEFAULT);

    private final JdbcTemplate jdbc;

    public BranchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Branch> findByCode(String branchCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT branch_code, branch_name, branch_name_kana, branch_type "
                            + "FROM branches WHERE branch_code = ?",
                    ROW_MAPPER, branchCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Branch> findAll() {
        return jdbc.query(
                "SELECT branch_code, branch_name, branch_name_kana, branch_type "
                        + "FROM branches ORDER BY branch_code",
                ROW_MAPPER);
    }

    public List<Branch> findByRegion(String region) {
        return jdbc.query(
                "SELECT branch_code, branch_name, branch_name_kana, branch_type "
                        + "FROM branches WHERE branch_type = ? ORDER BY branch_code",
                ROW_MAPPER, region);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
