/*
 * ============================================================================
 *  EncryptionService — AES-256-GCM encryption at rest
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Symmetric authenticated encryption for secrets/PII stored by the app: encrypt a
 *  string to a self-contained token, and decrypt it back.
 *
 *  Business use case
 *  -----------------
 *  Two needs converge here: (1) the Google Drive refresh token must be stored
 *  encrypted at rest, and (2) the brief requires vital documents (passport/Aadhaar/
 *  PAN/policies) to be encrypted at rest. This is the single crypto primitive both
 *  reuse — the "encryption seam" the design calls for.
 *
 *  Solution architecture
 *  ---------------------
 *  AES-256-GCM (authenticated: tamper-evident). The key is derived by SHA-256 over
 *  the configured secret (TROVE_ENCRYPTION_KEY), so any passphrase yields a valid
 *  32-byte key. Output = base64(iv ‖ ciphertext‖tag); a fresh random 12-byte IV per
 *  call means identical plaintexts encrypt differently.
 *
 *  Reasoning & logic
 *  -----------------
 *  GCM gives confidentiality + integrity in one pass (no separate MAC). Keeping the
 *  IV with the ciphertext makes each value self-describing to decrypt. TODO: unify
 *  vital-document file encryption onto this same service (currently used for the
 *  Drive refresh token). See DECISIONS.md → D18.
 * ============================================================================
 */
package com.trove.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${trove.security.encryption-key}") String secret) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize encryption key", e);
        }
    }

    /** Encrypts a string → base64(iv ‖ ciphertext‖tag). */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Encrypts raw bytes → iv ‖ ciphertext‖tag (used for vital file bytes at rest). */
    public byte[] encryptBytes(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Decrypts bytes produced by {@link #encryptBytes(byte[])}. */
    public byte[] decryptBytes(byte[] data) {
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(data, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /** Decrypts a token produced by {@link #encrypt(String)}. */
    public String decrypt(String token) {
        try {
            byte[] all = Base64.getDecoder().decode(token);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
