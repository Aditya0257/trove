/*
 * ============================================================================
 *  MerchantService — normalizes a raw merchant string to a canonical merchant
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Given a raw name from extraction, return the canonical Merchant: match a known
 *  alias, else match an existing canonical name, else create a new merchant (and an
 *  alias for the raw form).
 *
 *  Business use case
 *  -----------------
 *  Vendors appear under many spellings in OCR. Collapsing them to one canonical
 *  merchant is what makes "all Nike purchases" and per-vendor spend work.
 *
 *  Solution architecture
 *  ---------------------
 *  Called by the ExtractionWorker after a provider returns a raw merchant name.
 *  Backed by merchant + merchant_alias (DESIGN.md §2).
 *
 *  Reasoning & logic
 *  -----------------
 *  Learning loop: the first time we see a raw name we create the merchant AND record
 *  the raw form as an alias, so the next identical spelling resolves by alias
 *  instantly. Blank/unknown names return null (merchant is optional on a document).
 * ============================================================================
 */
package com.trove.merchant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantAliasRepository aliasRepository;

    public MerchantService(MerchantRepository merchantRepository, MerchantAliasRepository aliasRepository) {
        this.merchantRepository = merchantRepository;
        this.aliasRepository = aliasRepository;
    }

    /**
     * Resolves (or learns) the canonical merchant for a raw name. Returns null when
     * the raw name is blank — merchant is an optional field on a document.
     */
    @Transactional
    public Merchant resolve(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();

        // 1) Known alias?
        var alias = aliasRepository.findByAliasIgnoreCase(name);
        if (alias.isPresent()) {
            return merchantRepository.findById(alias.get().getMerchantId()).orElse(null);
        }

        // 2) Existing canonical name?
        var existing = merchantRepository.findByCanonicalNameIgnoreCase(name);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 3) First sighting — create the merchant and record this spelling as an alias.
        Merchant created = merchantRepository.save(new Merchant(name));
        aliasRepository.save(new MerchantAlias(created.getId(), name));
        return created;
    }
}
