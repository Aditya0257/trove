/*
 * ============================================================================
 *  ApiNotice — the two-channel, user+developer feedback envelope (D23)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A single, serializable object that carries BOTH a calm human `userMessage` and a
 *  precise technical `devNote` for the same event, plus a machine `code`, a `level`,
 *  and a free-form `meta` map. Attached to error responses and to async outcomes
 *  (e.g. extraction fallbacks) so the UI never has to choose between "friendly" and
 *  "informative" — it shows the user line and offers the dev note underneath.
 *
 *  Business use case
 *  -----------------
 *  Trove's audience is the owner plus friends/family. The product philosophy (D23) is
 *  to DIGNIFY errors, not hide them: a reassuring line for everyone, the real "why"
 *  one tap/click away for the curious or the developer.
 *
 *  Solution architecture
 *  ---------------------
 *  Immutable record. Built server-side per situation (a quota fallback, a duplicate
 *  upload, an unexpected 500). Consumed identically by the Angular web client and the
 *  Flutter mobile client, which render a toast (user line + expandable dev note) and
 *  log a structured entry in their Developer surfaces.
 *
 *  Reasoning & logic
 *  -----------------
 *  `devNote` MUST NEVER contain secrets (keys, tokens, endpoints, bucket names,
 *  account ids). It describes behaviour — provider used, whether the chain fell back,
 *  latency, counts, request id — which is safe and genuinely useful. `meta` is for
 *  structured extras the drawer can render (e.g. the extraction attempt trail).
 * ============================================================================
 */
package com.trove.common;

import java.util.Map;

public record ApiNotice(
        NoticeLevel level,
        String code,
        String userMessage,
        String devNote,
        Map<String, Object> meta
) {
    public static ApiNotice of(NoticeLevel level, String code, String userMessage, String devNote) {
        return new ApiNotice(level, code, userMessage, devNote, null);
    }

    public static ApiNotice of(NoticeLevel level, String code, String userMessage,
                               String devNote, Map<String, Object> meta) {
        return new ApiNotice(level, code, userMessage, devNote, meta);
    }

    /** Convenience for the common "something failed" case. */
    public static ApiNotice error(String code, String userMessage, String devNote) {
        return of(NoticeLevel.ERROR, code, userMessage, devNote);
    }

    /** Convenience for a graceful-degradation notice (e.g. quota fallback). */
    public static ApiNotice warning(String code, String userMessage, String devNote) {
        return of(NoticeLevel.WARNING, code, userMessage, devNote);
    }
}
