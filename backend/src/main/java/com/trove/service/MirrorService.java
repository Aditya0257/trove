package com.trove.service;

import com.trove.dto.MirrorSummary;
import java.util.Set;

/** Service contract for MirrorService. */
public interface MirrorService {
    boolean isEnabled();
    MirrorSummary mirror();
    Set<String> listMirrorKeys();
}
