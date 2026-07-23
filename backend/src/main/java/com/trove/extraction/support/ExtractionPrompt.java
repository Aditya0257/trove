/*
 * ============================================================================
 *  ExtractionPrompt — the instruction every vision model receives
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  A single, shared prompt that tells any vision LLM to read a document image/PDF
 *  and return ONLY the strict JSON shape Trove expects.
 *
 *  Business use case
 *  -----------------
 *  Consistent extraction quality across providers depends on a consistent prompt.
 *  Centralizing it means Gemini, Ollama, Cloudflare, etc. all ask for the exact
 *  same schema, so their outputs are interchangeable in the fallback chain.
 *
 *  Solution architecture
 *  ---------------------
 *  Used by every real ExtractionProvider; the model's text reply is parsed by
 *  ExtractionResponseParser. The schema mirrors ExtractionResult / DESIGN.md §6.1.
 *
 *  Reasoning & logic
 *  -----------------
 *  The prompt lists the known category codes so models pick a stable code, demands
 *  raw JSON (no prose/markdown), and asks for a calibrated confidence so the engine
 *  can gate on it. Numbers are requested unformatted (no currency symbols/commas).
 * ============================================================================
 */
package com.trove.extraction.support;

public final class ExtractionPrompt {

    private ExtractionPrompt() {
    }

    /** Known category codes (must match the seeded taxonomy). */
    public static final String CATEGORY_CODES =
            "electricity, water, gas, internet, mobile, shopping, insurance, medical, "
            + "travel, food, rent, subscription, tax, bank, other";

    public static final String INSTRUCTION = """
            You are a meticulous document-understanding assistant for a personal document vault.
            Read the attached document (a bill, receipt, invoice, policy, ticket, or ID) and extract
            its key fields. The image is often a PHOTO taken by hand — it may be at an angle, held in
            fingers, on a cluttered surface, folded, faint (thermal paper), or slightly blurred. Read
            whatever you can regardless; ignore fingers, background and glare.

            OUTPUT FORMAT — THIS IS STRICT:
            Return EXACTLY ONE JSON object and nothing else. Your entire reply MUST begin with the
            character { and end with the character }.
            Do NOT reply in any of these ways:
              - NOT prose, sentences, explanations, commentary, or reasoning
              - NOT markdown of any kind — no **bold**, no # or ## headings, no bullet or numbered lists
              - NOT code fences or backticks (no ```json, no ``` at all)
              - NOT HTML or XML tags
              - NOT plain text, and NOT a label such as "Document Details", "Here is", or "Answer:"
                before or after the JSON
              - NOT an apology or refusal such as "I'm sorry" or "I cannot read this"
            Even if the image is blurry or unreadable, you MUST STILL return the JSON object: set the
            fields you cannot read to null and lower "confidence". A single JSON object is the ONLY
            acceptable reply, in every case.

            Use exactly this JSON shape (use null when a field is not present):
            {
              "categoryCode": one of [%s],
              "merchantName": string or null,        // the vendor/biller/issuer name as printed
              "docDate": "YYYY-MM-DD" or null,        // the document/invoice date
              "amount": number or null,               // the total amount, digits only (no symbols/commas)
              "currency": string or null,             // ISO code if determinable, e.g. "INR"
              "dueDate": "YYYY-MM-DD" or null,         // payment due date if any
              "lineItems": [                           // may be empty
                { "description": string, "quantity": number or null, "amount": number or null }
              ],
              "rawText": string,                       // ALL text you can read, top to bottom
              "extra": object,                         // every other useful field you find (see rules)
              "confidence": number                     // 0..1, how sure you are overall
            }

            Rules:
            - Pick the single best categoryCode from the allowed list; use "other" if none fit.
            - Never invent values. If unsure of a number or date, use null and lower the confidence.
            - amount and lineItems[].amount must be plain numbers (e.g. 1840.50), not "₹1,840.50".
            - "rawText" must contain everything legible on the document — do not summarise or omit.
            - Fill "extra" generously with any labelled fields present, using clear camelCase keys,
              e.g. invoiceNumber, accountNumber, taxAmount, subtotal, billingPeriod, statementDate,
              paymentStatus, address, phone, email, gstin, policyNumber, referenceNumber. Include
              anything else useful; omit what isn't present. This is where the document's real detail
              is captured, so be thorough.

            Reminder: reply with the JSON object ONLY. It must start with { and end with }. Output no
            other characters before or after it.
            """.formatted(CATEGORY_CODES);
}
