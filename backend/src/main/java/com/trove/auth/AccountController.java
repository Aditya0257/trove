/*
 * ============================================================================
 *  AccountController - authenticated account settings (TOTP 2FA)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Enroll, enable, disable and report status for authenticator-app two-factor.
 *
 *  Business use case
 *  -----------------
 *  A free, optional second factor for the account. Enrollment is two-step: /setup
 *  returns a secret to add to the app, then /enable turns it on only after the user
 *  proves they can generate a valid code - so a mistyped setup never locks them out.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/account (authenticated: identity from CurrentUser). The TOTP secret
 *  is AES-GCM encrypted at rest; disable requires a current code so a walk-up attacker
 *  on an open session can't silently remove 2FA.
 * ============================================================================
 */
package com.trove.auth;

import com.trove.common.error.NotFoundException;
import com.trove.common.error.UnauthorizedException;
import com.trove.common.security.CurrentUser;
import com.trove.common.security.EncryptionService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final CurrentUser currentUser;

    public AccountController(UserRepository userRepository, TotpService totpService,
                            EncryptionService encryptionService, CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.currentUser = currentUser;
    }

    /** Whether 2FA is currently on for this account. */
    @GetMapping("/2fa/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", me().isTotpEnabled());
    }

    /** Start enrollment: generate + store a fresh secret (not yet active), return it + the
     *  otpauth URI to add to an authenticator app. */
    @PostMapping("/2fa/setup")
    @Transactional
    public Map<String, String> setup() {
        User user = me();
        String secret = totpService.newSecret();
        user.setTotpSecretEnc(encryptionService.encrypt(secret));
        user.setTotpEnabled(false);   // stays off until a code is verified
        userRepository.save(user);
        return Map.of("secret", secret, "otpauthUri", totpService.otpauthUri(secret, user.getEmail()));
    }

    /** Turn 2FA on after the user proves a valid code from the setup secret. */
    @PostMapping("/2fa/enable")
    @Transactional
    public void enable(@RequestBody CodeRequest req) {
        User user = me();
        if (user.getTotpSecretEnc() == null) {
            throw new UnauthorizedException("Start setup first");
        }
        if (!totpService.verify(encryptionService.decrypt(user.getTotpSecretEnc()), req.code())) {
            throw new UnauthorizedException("That code didn't match. Check the app and try again.");
        }
        user.setTotpEnabled(true);
        userRepository.save(user);
    }

    /** Turn 2FA off; requires a current code so an open session can't silently disable it. */
    @PostMapping("/2fa/disable")
    @Transactional
    public void disable(@RequestBody CodeRequest req) {
        User user = me();
        if (user.isTotpEnabled()
                && !totpService.verify(encryptionService.decrypt(user.getTotpSecretEnc()), req.code())) {
            throw new UnauthorizedException("That code didn't match, so 2FA was left on.");
        }
        user.setTotpEnabled(false);
        user.setTotpSecretEnc(null);
        userRepository.save(user);
    }

    private User me() {
        UUID id = currentUser.requireUserId();
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("Account not found"));
    }

    public record CodeRequest(String code) {
    }
}
