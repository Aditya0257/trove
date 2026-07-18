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
            + "travel, food, rent, subscription, tax, other";

    public static final String INSTRUCTION = """
            You are a meticulous document-understanding assistant for a personal document vault.
            Read the attached document (a bill, receipt, invoice, policy, ticket, or ID) and extract
            its key fields. Respond with a SINGLE JSON object and NOTHING else — no markdown, no code
            fences, no commentary.

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
              "rawText": string,                       // all text you can read from the document
              "extra": object,                         // any useful type-specific fields you find
              "confidence": number                     // 0..1, how sure you are overall
            }

            Rules:
            - Pick the single best categoryCode from the allowed list; use "other" if none fit.
            - Never invent values. If unsure of a number or date, use null and lower the confidence.
            - amount and lineItems[].amount must be plain numbers (e.g. 1840.50), not "₹1,840.50".
            """.formatted(CATEGORY_CODES);
}
