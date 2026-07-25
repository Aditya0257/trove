/*
 * ============================================================================
 *  MailController - the Mail page's paginated thread list
 * ============================================================================
 *  Purpose:        GET /api/mail returns one page of email threads plus the
 *                  add-form autocomplete facets; the total thread count rides in
 *                  the X-Total-Count header so the client can build a pager.
 *  Business use:    lets the Mail page show threads a page at a time instead of
 *                  pulling every filed email into the browser to group there.
 *  Design:         thin controller over MailService; space defaults to the caller's
 *                  personal space, mirroring DocumentController.
 * ============================================================================
 */
package com.trove.mail;

import com.trove.common.security.CurrentUser;
import com.trove.space.SpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/mail")
public class MailController {

    private final MailService mailService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public MailController(MailService mailService, SpaceService spaceService, CurrentUser currentUser) {
        this.mailService = mailService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** One page of email threads (defaults to the personal space). size = 0 means "all". */
    @GetMapping
    public ResponseEntity<MailService.MailPage> list(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "0") int size) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        MailService.MailPage result = mailService.bundles(space, user, page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.total()))
                .body(result);
    }
}
