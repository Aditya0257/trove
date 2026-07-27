package com.trove.service;

import com.trove.entity.Merchant;

/** Service contract for MerchantService. */
public interface MerchantService {
    Merchant resolve(String rawName);
}
