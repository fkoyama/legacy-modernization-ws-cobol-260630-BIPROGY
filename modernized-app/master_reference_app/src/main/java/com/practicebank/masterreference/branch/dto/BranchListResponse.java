package com.practicebank.masterreference.branch.dto;

import java.util.List;

/** 支店一覧レスポンス（OpenAPI BranchListResponse）。 */
public record BranchListResponse(List<Branch> items, int totalCount) {
}
