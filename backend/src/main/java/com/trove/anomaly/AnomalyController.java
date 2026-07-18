/*
 * ============================================================================
 *  AnomalyController — list documents flagged as spending anomalies
 * ============================================================================
 *  Purpose:        GET /api/anomalies — confirmed documents whose amount was flagged
 *                  "higher than usual" for their category.
 *  Business use:    the client's "review these unusual bills" view.
 *  Design:         authenticated; space defaults to the caller's personal space;
 *                  membership enforced in DocumentService. Detection itself happens
 *                  at confirm time (AnomalyService); this just surfaces the results.
 * ============================================================================
 */
package com.trove.anomaly;

import com.trove.common.security.CurrentUser;
import com.trove.document.DocumentService;
import com.trove.document.dto.DocumentResponse;
import com.trove.space.SpaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {

    private final DocumentService documentService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public AnomalyController(DocumentService documentService, SpaceService spaceService,
                            CurrentUser currentUser) {
        this.documentService = documentService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** List flagged documents in a space (defaults to the caller's personal space). */
    @GetMapping
    public List<DocumentResponse> list(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        return documentService.listAnomalies(space, user);
    }
}
