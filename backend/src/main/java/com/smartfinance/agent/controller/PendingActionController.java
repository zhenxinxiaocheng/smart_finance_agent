package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pending-actions")
public class PendingActionController {

    private final PendingActionService pendingActionService;

    public PendingActionController(PendingActionService pendingActionService) {
        this.pendingActionService = pendingActionService;
    }

    @GetMapping
    public Result<List<PendingAction>> list(@RequestAttribute Long userId) {
        return Result.success(pendingActionService.listPending(userId));
    }

    @PostMapping("/{id}/confirm")
    public Result<PendingAction> confirm(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(pendingActionService.confirm(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<PendingAction> cancel(@RequestAttribute Long userId, @PathVariable Long id) {
        return Result.success(pendingActionService.cancel(userId, id));
    }
}
