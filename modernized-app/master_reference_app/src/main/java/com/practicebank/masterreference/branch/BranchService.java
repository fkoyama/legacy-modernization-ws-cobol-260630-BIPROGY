package com.practicebank.masterreference.branch;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.practicebank.masterreference.branch.dto.Branch;
import com.practicebank.masterreference.branch.dto.BranchListResponse;
import com.practicebank.masterreference.common.NotFoundException;

/** 02-branch 支店マスタ参照。 */
@Service
public class BranchService {

    private final BranchRepository repository;

    public BranchService(BranchRepository repository) {
        this.repository = repository;
    }

    public BranchListResponse list(String region) {
        List<Branch> items = StringUtils.hasText(region)
                ? repository.findByRegion(region)
                : repository.findAll();
        return new BranchListResponse(items, items.size());
    }

    public Branch getByCode(String branchCode) {
        return repository.findByCode(branchCode)
                .orElseThrow(() -> new NotFoundException("branch not found: " + branchCode));
    }
}
