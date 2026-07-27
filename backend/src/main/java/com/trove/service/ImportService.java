package com.trove.service;

import com.trove.dto.RebuildSummary;

/** Service contract for ImportService. */
public interface ImportService {
    RebuildSummary importZip(byte[] zipBytes);
}
