package com.trove.service;

/** Service contract for TotpService. */
public interface TotpService {
    String newSecret();
    String otpauthUri(String secret, String accountEmail);
    boolean verify(String secret, String code);
}
