/*
 * ============================================================================
 *  TotpService - time-based one-time passwords (RFC 6238) for 2FA
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Generates a shared secret + otpauth URI for enrolling an authenticator app
 *  (Google Authenticator, Authy, ...), and verifies the 6-digit code at login.
 *
 *  Business use case
 *  -----------------
 *  A free second factor for a vault of sensitive documents. Authenticator apps run
 *  fully offline against a shared secret, so this needs NO external service and costs
 *  nothing (unlike SMS 2FA).
 *
 *  Solution architecture
 *  ---------------------
 *  Pure RFC 6238: HMAC-SHA1 over the 30-second time-step, dynamically truncated to 6
 *  digits. The secret is 20 random bytes, Base32-encoded (what authenticator apps
 *  expect). Verification accepts the current step +/- 1 (about 90s) to tolerate clock
 *  drift. No third-party library.
 *
 *  Reasoning & logic
 *  -----------------
 *  Example: secret "JBSWY3DPEHPK3PXP" at Unix time 1700000000 -> step 56666666 ->
 *  HMAC-SHA1 -> truncate -> "628109". An app showing "628109" in that 30s window
 *  verifies; the previous/next window also passes to cover a slightly wrong clock.
 * ============================================================================
 */
package com.trove.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int DRIFT_WINDOWS = 1;   // accept +/- one 30s step

    private final SecureRandom random = new SecureRandom();

    /** A fresh Base32 secret (160 bits) to store (encrypted) and show at enrollment. */
    public String newSecret() {
        byte[] buf = new byte[20];
        random.nextBytes(buf);
        return base32Encode(buf);
    }

    /** The otpauth:// URI an authenticator app scans / imports. */
    public String otpauthUri(String secret, String accountEmail) {
        String label = url("Trove:" + accountEmail);
        return "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=Trove&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** True if {@code code} matches the secret for the current 30s window (or +/- 1). */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String digits = code.trim().replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long step = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        byte[] key = base32Decode(secret);
        for (long w = -DRIFT_WINDOWS; w <= DRIFT_WINDOWS; w++) {
            if (generate(key, step + w).equals(digits)) {
                return true;
            }
        }
        return false;
    }

    /** The 6-digit code for a given key + time step (RFC 6238 / HOTP truncation). */
    private String generate(byte[] key, long step) {
        try {
            byte[] msg = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    // ── Base32 (RFC 4648, no padding) ────────────────────────────────────────
    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String url(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
