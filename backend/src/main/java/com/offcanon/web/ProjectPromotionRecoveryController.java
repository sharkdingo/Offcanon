package com.offcanon.web;

import com.offcanon.application.ProjectApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.promotion.application.PromotionRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Project-scoped recovery endpoints. A journal blocks the whole project. */
@RestController
@RequestMapping("/api/projects")
public class ProjectPromotionRecoveryController {
    private final ProjectApplicationService projects;
    private final PromotionRecoveryService recovery;
    private final IdentityContext identity;

    @Autowired
    public ProjectPromotionRecoveryController(ProjectApplicationService projects,
                                              PromotionRecoveryService recovery,
                                              IdentityContext identity) {
        this.projects = projects;
        this.recovery = recovery;
        this.identity = identity;
    }

    @GetMapping("/{projectId}/promotion-recovery")
    public PromotionRecoveryService.ProjectRecoveryStatus status(@PathVariable UUID projectId,
                                                                  HttpServletRequest request) {
        projects.get(projectId, identity.ownerId(request));
        return recovery.status(projectId);
    }

    @PostMapping("/{projectId}/promotion-reconcile")
    public PromotionRecoveryService.ManualReconciliation reconcile(@PathVariable UUID projectId,
                                                                    HttpServletRequest request) {
        projects.get(projectId, identity.ownerId(request));
        return recovery.reconcileProject(projectId);
    }
}
