package com.practicebank.masterreference.branch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.practicebank.masterreference.branch.dto.Branch;
import com.practicebank.masterreference.branch.dto.BranchListResponse;

/** 02-branch /branches。 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private final BranchService service;

    public BranchController(BranchService service) {
        this.service = service;
    }

    @GetMapping
    public BranchListResponse listBranches(
            @RequestParam(required = false) String region) {
        return service.list(region);
    }

    @GetMapping("/{branchCode}")
    public Branch getBranchByCode(@PathVariable String branchCode) {
        return service.getByCode(branchCode);
    }
}
