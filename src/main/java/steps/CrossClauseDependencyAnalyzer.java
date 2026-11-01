package steps;

import ai.Router;
import ai.StepAi;
import config.AppConfig;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pipeline step 8: Cross-clause dependency analysis.
 *
 * <h3>Two-phase approach (cost-efficient)</h3>
 * <ol>
 *   <li><b>Phase 1 — Deterministic (free):</b> Scan all clause texts with regex to find
 *       shared numeric values (amounts, durations, percentages, dates) that appear in two
 *       or more clauses. These become "candidate pairs".</li>
 *   <li><b>Phase 2 — AI (one request per candidate group):</b> For each changed clause
 *       that has candidates, send ONLY those clause texts (not the entire contract) to the
 *       LLM and ask it to evaluate whether a real dependency exists and what the impact is
 *       if the trigger clause changed.</li>
 * </ol>
 *
 * <h3>Output shape (stored under {@code analyze.dependencies})</h3>
 * <pre>{@code
 * "dependencies": {
 *   "TR": [
 *     {
 *       "trigger":       "5",
 *       "triggerLabel":  "Madde 5",
 *       "triggerSummary":"Ödeme süresi 30 günden 45 güne çıktı",
 *       "affected": [
 *         {
 *           "clause":  "8",
 *           "label":   "Madde 8",
 *           "sharedValue": "30 gün",
 *           "relation": "Madde 8'deki gecikme cezası bu süreye referans veriyor.",
 *           "impact":   "Ödeme süresi uzadığından gecikme koşulu daha geç devreye girer.",
 *           "risk":     "MEDIUM"
 *         }
 *       ],
 *       "chain": ["5", "8", "12"],
 *       "confirmed": true
 *     }
 *   ]
 * }
 * }</pre>
 *
 * The step is skipped (no AI call, empty result) when {@code steps.crossClauseDependency}
 * is absent from the config or when there are no DIFFERENT/MISSING changed clauses.
 */
public final class CrossClauseDependencyAnalyzer {

    private static final Logger log = LogManager.getLogger();

    /**
     * Builds the system prompt for the dependency-analysis AI call.
     * All free-text fields must be bilingual JSON objects {"en":"...","tr":"..."} so the
     * report can switch display language in the browser without re-running the pipeline.
     */
    private static String buildSystem() {
        return "You are a senior contract lawyer. Analyze cross-clause dependencies in a contract. "
                + "Be precise: only confirm a dependency when a change in the trigger clause genuinely "
                + "and directly affects the meaning or enforceability of the other clause. "
                + "Write ALL free-text fields (triggerSummary, relation, impact, chainExplanation) "
                + "as a JSON object with BOTH keys \"en\" (English) and \"tr\" (Turkish), e.g. "
                + "{\"en\":\"Payment term increased.\",\"tr\":\"Ödeme süresi uzatıldı.\"}. "
                + "Output JSON only.";
    }

    // ── shared-value patterns (same patterns as ContractFacts but simplified to extract canonical tokens)
    private static final Pattern RE_AMOUNT = Pattern.compile(
            "(?i)([\\d][\\d.,]*)\\s*(TL|USD|EUR|GBP|TRY|gün(?:lük)?|ay(?:lık)?|yıl(?:lık)?|" +
            "day(?:s)?|month(?:s)?|year(?:s)?|%|puan|adet|ton|kg)\\b"
    );
    private static final Pattern RE_PERCENT = Pattern.compile(
            "(?i)%\\s*([\\d]+(?:[.,][\\d]+)?)|([\\d]+(?:[.,][\\d]+)?)\\s*%"
    );
    private static final Pattern RE_DATE = Pattern.compile(
            "\\b(\\d{1,2}[./]\\d{1,2}[./]\\d{4}|\\d{4}[-./]\\d{2}[-./]\\d{2})\\b"
    );

    private final AppConfig cfg;
    private final Router router;

    public CrossClauseDependencyAnalyzer(AppConfig cfg, Router router) {
        this.cfg    = cfg;
        this.router = router;
    }

    // ══════════════════════════════════════════════════════════════ PUBLIC API

    /**
     * Runs cross-clause dependency analysis and attaches {@code analyze.dependencies}.
     * Mutates and returns the same object.
     * Returns immediately (no AI call) if the step is not configured.
     */
    public JSONObject analyze(JSONObject analyze, Checkpoint cp) throws IOException {

        JSONObject stepCfg = cfg.step("crossClauseDependency");
        if (stepCfg == null) {
            log.info("Step crossClauseDependency not configured - dependency analysis skipped");
            return analyze;
        }

        List<String> languages = toList(analyze.optJSONArray("languages"));
        JSONObject allDeps = new JSONObject();
        boolean debug = cfg.isDebug();
        if (debug) log.info("[DEBUG] CrossClauseDependencyAnalyzer başlıyor — {} dil", languages.size());
        long t0 = System.currentTimeMillis();

        for (String lang : languages) {
            JSONObject langObj = analyze.optJSONObject(lang);
            if (langObj == null) continue;
            JSONObject clauses = langObj.optJSONObject("clauses");
            if (clauses == null) continue;

            String baseline = baselineOf(analyze, lang);
            if (debug) log.info("[DEBUG] CrossClauseDependencyAnalyzer [{}] — {} madde, baseline:{}",
                    lang, clauses.length(), baseline);
            JSONArray langDeps = analyzeLanguage(lang, clauses, baseline, analyze, cp);
            if (langDeps.length() > 0) allDeps.put(lang, langDeps);
        }

        analyze.put("dependencies", allDeps);
        log.info("CrossClauseDependencyAnalyzer: dependencies written for {} language(s)", allDeps.length());
        if (debug) log.info("[DEBUG] CrossClauseDependencyAnalyzer tamamlandı — {}ms", System.currentTimeMillis() - t0);
        return analyze;
    }

    // ══════════════════════════════════════════════════════════════ PHASE 1: candidate finder

    private JSONArray analyzeLanguage(String lang, JSONObject clauses, String baseline,
                                      JSONObject analyze, Checkpoint cp) throws IOException {

        // ── collect changed clauses (DIFFERENT or MISSING)
        List<String> changedClauses = new ArrayList<>();
        for (String no : clauses.keySet()) {
            JSONObject c = clauses.getJSONObject(no);
            JSONObject cmps = c.optJSONObject("comparisons");
            if (cmps == null) continue;
            for (String other : cmps.keySet()) {
                String st = cmps.getJSONObject(other).optString("status");
                if ("DIFFERENT".equals(st) || "MISSING_IN_OTHER".equals(st) || "MISSING_IN_BASELINE".equals(st)) {
                    if (!changedClauses.contains(no)) changedClauses.add(no);
                }
            }
        }

        if (changedClauses.isEmpty()) {
            log.info("Lang {}: no changed clauses, dependency analysis skipped", lang);
            return new JSONArray();
        }

        // ── extract shared tokens per clause (baseline text only)
        Map<String, Set<String>> clauseTokens = new LinkedHashMap<>();
        for (String no : clauses.keySet()) {
            JSONObject c     = clauses.getJSONObject(no);
            String    text   = c.optString(baseline + "_content", c.optString("content", ""));
            Set<String> toks = extractTokens(text);
            if (!toks.isEmpty()) clauseTokens.put(no, toks);
        }

        // ── find candidate pairs: (changed clause) × (any other clause) sharing ≥1 token
        // map: changed-clause → list of {candidate, sharedTokens}
        Map<String, List<Map<String, Object>>> candidateMap = new LinkedHashMap<>();

        for (String trigger : changedClauses) {
            Set<String> triggerToks = clauseTokens.getOrDefault(trigger, Set.of());
            if (triggerToks.isEmpty()) continue;

            List<Map<String, Object>> candidates = new ArrayList<>();
            for (String other : clauses.keySet()) {
                if (other.equals(trigger)) continue;
                Set<String> otherToks = clauseTokens.getOrDefault(other, Set.of());
                Set<String> shared = new LinkedHashSet<>(triggerToks);
                shared.retainAll(otherToks);
                if (!shared.isEmpty()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("clause",       other);
                    c.put("sharedTokens", new ArrayList<>(shared));
                    candidates.add(c);
                }
            }

            int maxCandidates = cfg.pipelineInt("dependencyMaxCandidates", 8);
            if (!candidates.isEmpty()) {
                // limit candidates to avoid huge prompts
                candidateMap.put(trigger, candidates.subList(0, Math.min(candidates.size(), maxCandidates)));
            }
        }

        if (candidateMap.isEmpty()) {
            log.info("Lang {}: no shared-value candidates found", lang);
            return new JSONArray();
        }

        log.info("Lang {}: {} trigger(s) with candidates, sending to AI", lang, candidateMap.size());

        // ── Phase 2: AI evaluation
        return aiEvaluate(lang, clauses, baseline, candidateMap, analyze, cp);
    }

    // ══════════════════════════════════════════════════════════════ PHASE 2: AI evaluation

    private JSONArray aiEvaluate(String lang, JSONObject clauses, String baseline,
                                 Map<String, List<Map<String, Object>>> candidateMap,
                                 JSONObject analyze, Checkpoint cp) throws IOException {

        StepAi ai = router.get("crossClauseDependency");
        JSONArray results = new JSONArray();
        boolean debug = cfg.isDebug();

        // Maximum characters kept per clause text in the prompt.
        // Keeps individual prompts small enough that the model can finish its JSON response
        // within its output-token budget (finish_reason=length protection).
        // Lower values mean more truncation but safer prompt sizes. Tune via
        // pipeline.dependencyMaxCharsPerClause in config.
        int maxCharsPerClause = cfg.pipelineInt("dependencyMaxCharsPerClause", 600);

        // Maximum candidates per AI batch call.
        // When a trigger has more candidates than this, they are split into multiple calls
        // and their results are merged, so no single response needs to enumerate every clause.
        // Tune via pipeline.dependencyBatchSize in config.
        int batchSize = cfg.pipelineInt("dependencyBatchSize", 2);

        for (Map.Entry<String, List<Map<String, Object>>> entry : candidateMap.entrySet()) {
            String trigger = entry.getKey();
            List<Map<String, Object>> candidates = entry.getValue();

            if (debug) log.info("[DEBUG] CrossClauseDependencyAnalyzer [{}] trigger:{} — {} aday madde (batchSize={})",
                    lang, trigger, candidates.size(), batchSize);

            // collect trigger's diff summary from existing AI compare results
            String triggerChangeSummary = getTriggerSummary(clauses, trigger, baseline);
            String triggerBaseText  = truncate(getText(clauses, trigger, baseline + "_content"), maxCharsPerClause);
            String triggerOtherText = truncate(getOtherText(clauses, trigger), maxCharsPerClause);

            // Split candidates into batches to avoid oversized output responses.
            List<List<Map<String, Object>>> batches = partition(candidates, batchSize);
            if (debug && batches.size() > 1) {
                log.info("[DEBUG] CrossClauseDependencyAnalyzer [{}] trigger:{} — {} aday {} batch'e bölündü",
                        lang, trigger, candidates.size(), batches.size());
            }

            // Collect affected entries across all batches for this trigger.
            String triggerSummaryText = null;
            JSONArray mergedAffected  = new JSONArray();

            for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
                List<Map<String, Object>> batch = batches.get(batchIdx);

                // build prompt
                StringBuilder sb = new StringBuilder();
                sb.append("Contract language: ").append(lang).append(".\n");
                sb.append("IMPORTANT: Write ALL free-text fields (triggerSummary, relation, impact, chainExplanation)")
                  .append(" as bilingual JSON objects with keys \"en\" (English) and \"tr\" (Turkish).\n\n");
                sb.append("=== TRIGGER CLAUSE (changed) ===\n");
                sb.append("Clause ").append(trigger).append(":\n");
                sb.append("[Original]:\n<<<\n").append(triggerBaseText).append("\n>>>\n");
                sb.append("[Modified]:\n<<<\n").append(triggerOtherText).append("\n>>>\n");
                if (!triggerChangeSummary.isEmpty()) {
                    sb.append("Change summary: ").append(triggerChangeSummary).append("\n");
                }
                sb.append("\n=== CANDIDATE CLAUSES (shared numeric/date values) ===\n");

                for (Map<String, Object> cand : batch) {
                    String no    = (String) cand.get("clause");
                    @SuppressWarnings("unchecked")
                    List<String> shared = (List<String>) cand.get("sharedTokens");
                    String text = truncate(getText(clauses, no, baseline + "_content"), maxCharsPerClause);
                    sb.append("\nClause ").append(no)
                      .append(" (shared values: ").append(String.join(", ", shared)).append("):\n")
                      .append("<<<\n").append(text).append("\n>>>\n");
                }

                sb.append("\nFor each candidate clause, determine:\n");
                sb.append("1. Is there a real dependency? (the trigger change directly affects this clause's meaning/enforceability)\n");
                sb.append("2. What is the nature of the relation?\n");
                sb.append("3. What is the impact of the trigger change on this clause?\n");
                sb.append("4. Risk level: LOW | MEDIUM | HIGH\n\n");

                sb.append("Also identify any dependency CHAIN (e.g. trigger→clauseA→clauseB).\n\n");

                sb.append("Return JSON only:\n");
                sb.append("{\n");
                sb.append("  \"triggerSummary\": {\"en\":\"one sentence in English\",\"tr\":\"bir cümle Türkçe\"},\n");
                sb.append("  \"affected\": [\n");
                sb.append("    {\n");
                sb.append("      \"clause\": \"<no>\",\n");
                sb.append("      \"confirmed\": true|false,\n");
                sb.append("      \"sharedValue\": \"<the shared numeric/date value>\",\n");
                sb.append("      \"relation\": {\"en\":\"how they are related (English)\",\"tr\":\"nasıl ilişkili (Türkçe)\"},\n");
                sb.append("      \"impact\": {\"en\":\"impact in English\",\"tr\":\"etki Türkçe\"},\n");
                sb.append("      \"risk\": \"LOW|MEDIUM|HIGH\"\n");
                sb.append("    }\n");
                sb.append("  ],\n");
                sb.append("  \"chain\": [\"").append(trigger).append("\", ...],\n");
                sb.append("  \"chainExplanation\": {\"en\":\"why this chain matters (English)\",\"tr\":\"bu zincir neden önemli (Türkçe)\"}\n");
                sb.append("}\n");

                // checkpoint key: dep.{lang}.{trigger}[.{batchIdx}]
                final String promptFinal = sb.toString();
                final String cpKey = batches.size() == 1
                        ? "dep." + lang + "." + trigger
                        : "dep." + lang + "." + trigger + "." + batchIdx;

                JSONObject aiResult;
                try {
                    aiResult = cp.obj(cpKey, () -> {
                        String raw = ai.chat(buildSystem(), promptFinal, List.of());
                        try {
                            return Json.parseLoose(raw);
                        } catch (IllegalArgumentException parseEx) {
                            // Truncated response: no closing brace.
                            // Return empty object so this batch is skipped rather than failing the step.
                            log.warn("CrossClauseDependencyAnalyzer [{}] trigger:{} batch:{} — JSON parse failed ({}), skipping batch",
                                    lang, trigger, cpKey, parseEx.getMessage());
                            return new JSONObject();
                        }
                    });
                } catch (IOException ioEx) {
                    // The model returned empty content (finish_reason=length or content_filter).
                    // Skip this batch and continue — a partial dependency result is better than
                    // aborting the entire pipeline step.
                    log.warn("CrossClauseDependencyAnalyzer [{}] trigger:{} batch:{} — AI call failed ({}), skipping batch",
                            lang, trigger, cpKey, ioEx.getMessage());
                    continue;
                }

                // Keep the trigger summary from the first batch only.
                if (triggerSummaryText == null) {
                    Object ts = aiResult.opt("triggerSummary");
                    triggerSummaryText = ts == null ? "" : ts.toString();
                }

                // Merge affected entries from this batch into the combined list.
                JSONArray batchAffected = aiResult.optJSONArray("affected");
                if (batchAffected != null) {
                    for (int i = 0; i < batchAffected.length(); i++) {
                        mergedAffected.put(batchAffected.get(i));
                    }
                }
            }

            // Re-read triggerSummary as a proper i18n object from the first batch result.
            // We stored the raw toString() above just for a null-check; re-parse here.
            String triggerSummaryRaw = triggerSummaryText;
            JSONObject triggerSummaryObj;
            try {
                triggerSummaryObj = triggerSummaryRaw == null || triggerSummaryRaw.isBlank()
                        ? new JSONObject().put("en", "").put("tr", "")
                        : toI18nObj(new JSONObject(triggerSummaryRaw));
            } catch (Exception ex) {
                triggerSummaryObj = toI18nObj(triggerSummaryRaw);
            }

            // build output entry
            JSONObject depEntry = new JSONObject();
            depEntry.put("trigger",       trigger);
            depEntry.put("triggerLabel",  clauseLabel(trigger));
            depEntry.put("triggerSummary", triggerSummaryObj);

            // filter only confirmed affected clauses
            JSONArray affectedOut  = new JSONArray();
            int confirmedCount = 0;
            for (int i = 0; i < mergedAffected.length(); i++) {
                JSONObject a = mergedAffected.optJSONObject(i);
                if (a == null) continue;
                if (!a.optBoolean("confirmed", false)) continue;
                JSONObject aOut = new JSONObject();
                aOut.put("clause",      a.optString("clause",     ""));
                aOut.put("label",       clauseLabel(a.optString("clause", "")));
                aOut.put("sharedValue", a.optString("sharedValue",""));
                aOut.put("relation",    toI18nObj(a.opt("relation")));
                aOut.put("impact",      toI18nObj(a.opt("impact")));
                aOut.put("risk",        pickRisk(a.optString("risk", "MEDIUM")));
                affectedOut.put(aOut);
                confirmedCount++;
            }
            depEntry.put("affected", affectedOut);
            depEntry.put("confirmedCount", confirmedCount);

            // chain: derive from confirmed affected clauses (batch merging makes a combined chain)
            JSONArray chain = new JSONArray();
            chain.put(trigger);
            for (int i = 0; i < affectedOut.length(); i++) {
                chain.put(affectedOut.getJSONObject(i).optString("clause"));
            }
            depEntry.put("chain",            chain);
            depEntry.put("chainExplanation", new JSONObject().put("en", "").put("tr", ""));
            depEntry.put("confirmed",        confirmedCount > 0);

            if (confirmedCount > 0) {
                if (debug) log.info("[DEBUG] CrossClauseDependencyAnalyzer [{}] trigger:{} — {} onaylı bağımlılık",
                        lang, trigger, confirmedCount);
                results.put(depEntry);
            } else if (debug) {
                log.info("[DEBUG] CrossClauseDependencyAnalyzer [{}] trigger:{} — onaylı bağımlılık yok", lang, trigger);
            }
        }

        return results;
    }

    // ══════════════════════════════════════════════════════════════ HELPERS

    /**
     * Truncates a clause text to at most {@code maxChars} characters so that
     * prompts stay within the model's output-token budget.
     * When truncation occurs, a short marker is appended so the model knows the
     * text was cut (avoids silent hallucinations about the missing tail).
     */
    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... truncated ...]";
    }

    /**
     * Splits a list into consecutive sub-lists of at most {@code size} elements.
     * The last sub-list may be smaller.
     */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    /** Extracts normalised numeric/date tokens from clause text for overlap detection. */
    private static Set<String> extractTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return tokens;

        Matcher m1 = RE_AMOUNT.matcher(text);
        while (m1.find()) {
            String num  = m1.group(1).replaceAll("\\.", "").replace(",", ".");
            String unit = m1.group(2).toLowerCase().trim();
            tokens.add(num + " " + unit);
        }
        Matcher m2 = RE_PERCENT.matcher(text);
        while (m2.find()) {
            String val = (m2.group(1) != null ? m2.group(1) : m2.group(2))
                          .replace(",", ".");
            tokens.add(val + "%");
        }
        Matcher m3 = RE_DATE.matcher(text);
        while (m3.find()) {
            tokens.add(m3.group(1).trim());
        }
        return tokens;
    }

    private static String getText(JSONObject clauses, String no, String key) {
        JSONObject c = clauses.optJSONObject(no);
        if (c == null) return "";
        String t = c.optString(key, "");
        return t.isEmpty() ? c.optString("content", "") : t;
    }

    private static String getOtherText(JSONObject clauses, String no) {
        JSONObject c = clauses.optJSONObject(no);
        if (c == null) return "";
        // find first *_content that is NOT the baseline
        JSONObject cmps = c.optJSONObject("comparisons");
        if (cmps != null) {
            for (String other : cmps.keySet()) {
                String t = c.optString(other + "_content", "");
                if (!t.isEmpty()) return t;
            }
        }
        return "";
    }

    private static String getTriggerSummary(JSONObject clauses, String trigger, String baseline) {
        JSONObject c = clauses.optJSONObject(trigger);
        if (c == null) return "";
        JSONObject cmps = c.optJSONObject("comparisons");
        if (cmps == null) return "";
        for (String other : cmps.keySet()) {
            JSONObject cmp = cmps.getJSONObject(other);
            JSONObject ai  = cmp.optJSONObject("ai");
            if (ai != null) {
                String s = ai.optString("summary", "");
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private static String baselineOf(JSONObject analyze, String lang) {
        JSONObject b = analyze.optJSONObject("baselines");
        return b == null ? analyze.optString("baseline", "original1")
                         : b.optString(lang, analyze.optString("baseline", "original1"));
    }

    private static String clauseLabel(String no) {
        // Stored as a neutral clause identifier; ReportBuilder resolves display label per language
        return no;
    }

    /**
     * Normalises a possibly-bilingual AI response field to a {"en":"...","tr":"..."} JSONObject.
     * If the AI returned a plain string (fallback / old checkpoint), both slots get the same value.
     */
    private static JSONObject toI18nObj(Object raw) {
        if (raw instanceof JSONObject) {
            JSONObject j = (JSONObject) raw;
            String en = j.optString("en", j.optString("tr", ""));
            String tr = j.optString("tr", j.optString("en", ""));
            return new JSONObject().put("en", en).put("tr", tr);
        }
        String s = raw == null ? "" : raw.toString();
        return new JSONObject().put("en", s).put("tr", s);
    }

    private static String pickRisk(String v) {
        if (v == null) return "MEDIUM";
        switch (v.trim().toUpperCase()) {
            case "HIGH": return "HIGH";
            case "LOW":  return "LOW";
            default:     return "MEDIUM";
        }
    }

    private static List<String> toList(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        return l;
    }
}
