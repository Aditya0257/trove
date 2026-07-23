/*
 * ============================================================================
 *  ExtractionResponseParser — turns a model's JSON reply into ExtractionResult
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Parses the (sometimes messy) JSON text a vision model returns into a strict
 *  ExtractionResult, tolerating code fences and stray prose around the JSON.
 *
 *  Business use case
 *  -----------------
 *  Different models wrap JSON differently (```json fences, leading text). One
 *  robust parser keeps every provider's output interchangeable in the chain and
 *  turns malformed replies into a clean transient failure the engine can fall past.
 *
 *  Solution architecture
 *  ---------------------
 *  Shared by all real providers. Field-for-field aligned with ExtractionResult and
 *  the prompt schema. Numbers are coerced defensively (strings, symbols stripped).
 *
 *  Reasoning & logic
 *  -----------------
 *  We extract the outermost {...} span before parsing, so a model that adds a
 *  sentence before/after the JSON still works. Unparseable input throws
 *  IllegalArgumentException, which the provider maps to a transient ExtractionException.
 * ============================================================================
 */
package com.trove.extraction.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.extraction.ExtractionResult;
import com.trove.extraction.LineItemDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtractionResponseParser {

    private ExtractionResponseParser() {
    }

    /** Parses model text into an ExtractionResult, or throws IllegalArgumentException. */
    public static ExtractionResult parse(String modelText, ObjectMapper mapper) {
        if (modelText == null || modelText.isBlank()) {
            throw new IllegalArgumentException("Empty model response");
        }
        String json = extractJsonObject(modelText);
        try {
            return build(readLenient(json, mapper), mapper);
        } catch (Exception first) {
            // Salvage: the long, free-form rawText often contains unescaped quotes or
            // newlines that break strict JSON. Drop rawText (it's requested last for
            // exactly this reason) and parse the structured fields that precede it, so a
            // messy rawText never loses the merchant/amount/date/line-items.
            String salvaged = dropRawText(json);
            if (salvaged != null) {
                try {
                    return build(readLenient(salvaged, mapper), mapper);
                } catch (Exception ignored) {
                    // fall through to the original error
                }
            }
            if (first instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("Could not parse model JSON: " + first.getMessage(), first);
        }
    }

    /** Reads JSON tolerantly — allows unescaped control chars and single quotes, common
     *  model slips that would otherwise sink an otherwise-fine object. */
    private static JsonNode readLenient(String json, ObjectMapper mapper) throws Exception {
        return mapper.reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .readTree(json);
    }

    /** Cuts the JSON just before "rawText" and closes the object, dropping rawText (and
     *  anything after it) so the structured fields before it can still be parsed. */
    private static String dropRawText(String json) {
        int i = json.indexOf("\"rawText\"");
        if (i <= 0) {
            return null;
        }
        String head = json.substring(0, i).stripTrailing();
        if (head.endsWith(",")) {
            head = head.substring(0, head.length() - 1);
        }
        return head + "}";
    }

    private static ExtractionResult build(JsonNode root, ObjectMapper mapper) {
        return new ExtractionResult(
                text(root, "categoryCode"),
                text(root, "merchantName"),
                date(root, "docDate"),
                number(root, "amount"),
                text(root, "currency"),
                date(root, "dueDate"),
                lineItems(root.get("lineItems")),
                text(root, "rawText"),
                extra(mapper, root.get("extra")),
                confidence(root.get("confidence"))
        );
    }

    /** Returns the outermost {...} substring so surrounding prose/fences don't break parsing. */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found in model response");
        }
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static LocalDate date(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null; // unparseable date → null rather than failing the whole extraction
        }
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return toNumber(v);
    }

    private static BigDecimal toNumber(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        String s = v.asText("").replaceAll("[^0-9.\\-]", "");
        if (s.isBlank() || s.equals("-") || s.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<LineItemDto> lineItems(JsonNode arr) {
        List<LineItemDto> items = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode li : arr) {
                String desc = text(li, "description");
                BigDecimal qty = toNumber(li.get("quantity"));
                BigDecimal amt = toNumber(li.get("amount"));
                if (desc != null || qty != null || amt != null) {
                    items.add(new LineItemDto(desc, qty, amt));
                }
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extra(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(node, Map.class);
    }

    private static BigDecimal confidence(JsonNode node) {
        BigDecimal c = toNumber(node);
        if (c == null) {
            return BigDecimal.ZERO;
        }
        // Clamp to [0,1] so a stray 95 (meaning 0.95) or negative can't skew the gate.
        if (c.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        if (c.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return c;
    }
}
