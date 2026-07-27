/*
 * ============================================================================
 *  ModelRouter — picks the answer model per question (cost-aware LLM routing)
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Decides which chat model answers a given question: a cheap model for simple
 *  single-fact lookups, a capable model for reasoning/aggregation/comparison.
 *
 *  Business use case
 *  -----------------
 *  Most vault questions are lookups ("when does my passport expire?") that a small model
 *  answers perfectly for ~4× fewer neurons. Spending the big model only where it earns
 *  its cost keeps the whole app inside the free tier for far more users.
 *
 *  Solution architecture
 *  ---------------------
 *  A tiny classifier model (llama-3.2-1b, ~0.4 neurons) labels the question simple vs
 *  complex — staying fully LLM-driven, not rule-based. Two guards wrap it: routing can be
 *  switched off (always standard), and when the SHARED daily budget is mostly spent the
 *  router forces the light model so the free tier stretches across more users.
 *
 *  Reasoning & logic
 *  -----------------
 *  When the classifier is unclear or errors, it defaults UP to the standard model — a
 *  wrong "simple" hurts answer quality, a wrong "complex" only costs a few neurons.
 * ============================================================================
 */
package com.trove.integration;
import com.trove.config.ChatProperties;

import com.trove.service.impl.AiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatProperties props;
    private final CloudflareChatClient chat;
    private final AiUsageTracker usage;

    public ModelRouter(ChatProperties props, CloudflareChatClient chat, AiUsageTracker usage) {
        this.props = props;
        this.chat = chat;
        this.usage = usage;
    }

    /** Which model answers, the tier label, and why (for logging/showcase). */
    public record Decision(String model, String tier, String reason) {
    }

    public Decision pick(String question, UUID userId) {
        if (!props.isRoutingEnabled()) {
            return new Decision(props.getStandardModel(), "standard", "routing off");
        }
        // Budget-aware: once the SHARED daily pool is mostly spent, conserve with the light
        // model so the free tier stretches across more users. This looks ONLY at the global
        // total + the global 10k/day limit — never an individual user's usage, so one heavy
        // user is never singled out for a downgrade.
        double limit = props.getBudgetDowngradeFraction() * usage.dailyNeuronLimit();
        if (limit > 0 && usage.globalToday().neurons() >= limit) {
            return new Decision(props.getLightModel(), "light", "budget conservation (shared pool)");
        }
        try {
            String verdict = chat.chat(props.getRouterModel(), classifyPrompt(question), 5, 0,
                    props.getTimeoutSeconds(), userId).toLowerCase();
            if (verdict.contains("complex")) {
                return new Decision(props.getStandardModel(), "standard", "classified complex");
            }
            if (verdict.contains("simple")) {
                return new Decision(props.getLightModel(), "light", "classified simple");
            }
            return new Decision(props.getStandardModel(), "standard", "classifier unclear → default up");
        } catch (Exception e) {
            log.warn("Model classifier failed - defaulting to standard: {}", e.getMessage());
            return new Decision(props.getStandardModel(), "standard", "classifier error → default up");
        }
    }

    private String classifyPrompt(String question) {
        return """
                Classify this question about someone's saved documents as ONE word.
                simple  = a single-fact lookup from one document (when/what/how much is my X, find my Y).
                complex = needs reasoning across documents: totals, averages, counts, comparisons,
                          filtering or exclusions ("not/except"), or "list all".
                Reply with only: simple OR complex.
                Question: %s""".formatted(question.trim());
    }
}
