package steps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges signed + original documents into the analyze JSON:
 * { documents, baseline, languages, document_languages, title:{doc:{LANG:..}},
 *   LANG:{clauses:{no:{signed_image, signed_content, original1_content, ...}}} }
 * The first document is the baseline (signed if present, otherwise original1).
 */
public final class AnalyzeMerger {
    private static final Logger log = LogManager.getLogger(AnalyzeMerger.class);
    private final double renumberThreshold;

    public AnalyzeMerger(double renumberThreshold) {
        this.renumberThreshold = renumberThreshold;
    }

    public JSONObject merge(List<DocAnalysis> docs) {
        JSONObject analyze = new JSONObject();
        DocAnalysis base = docs.get(0);

        JSONArray keys = new JSONArray();
        JSONObject docLangs = new JSONObject();
        JSONObject titles = new JSONObject();
        Set<String> langs = new LinkedHashSet<>();
        for (DocAnalysis d : docs) {
            keys.put(d.key);
            docLangs.put(d.key, new JSONArray(d.languages));
            titles.put(d.key, new JSONObject(d.titles));
            langs.addAll(d.languages);
        }

        // "languages" represents the canonical language set of the analysis.
        // Only languages that are present in at least one *original* document (original1, original2, …)
        // are included. A language detected exclusively in the signed PDF is NOT added — the signed
        // document is compared against the originals, so a language without an original baseline
        // has nothing to compare against and must not appear as a report tab/section.
        // If no originals are present (signed-only run), fall back to the full union.
        Set<String> originalLangs = new LinkedHashSet<>();
        for (DocAnalysis d : docs) {
            if (d.key.startsWith("original")) {
                originalLangs.addAll(d.languages);
            }
        }
        Set<String> reportLangs = originalLangs.isEmpty() ? langs : originalLangs;
        analyze.put("documents", keys);
        analyze.put("baseline", base.key);
        // "languages" = original document languages only (used by ReportBuilder for tabs/sections).
        // "languages_all" = full union across all docs (used internally for merge/compare loops).
        analyze.put("languages", new JSONArray(reportLangs));
        analyze.put("languages_all", new JSONArray(langs));
        analyze.put("document_languages", docLangs);
        analyze.put("title", titles);

        // Carry signatures from any document that has them (in practice: the signed PDF only).
        // Stored at the top level so ReportBuilder can access them independently of language sections.
        JSONArray mergedSigs = new JSONArray();
        for (DocAnalysis d : docs) {
            for (JSONObject sig : d.signatures) {
                mergedSigs.put(sig);
            }
        }
        if (mergedSigs.length() > 0) {
            analyze.put("signatures", mergedSigs);
        }

        JSONObject baselines = new JSONObject();
        for (String lang : langs) {
            // baseline of a language = first document (signed first) that contains that language
            DocAnalysis lb = baselineFor(docs, lang);
            baselines.put(lang, lb.key);
            // re-align renumbered clauses of the other docs onto the baseline numbering
            Map<String, Map<String, String>> renumbered = new LinkedHashMap<>();
            for (DocAnalysis d : docs) {
                if (d != lb && d.content.containsKey(lang)) {
                    renumbered.put(d.key, realign(lb.content.get(lang), d.content.get(lang), d.image.get(lang)));
                }
            }
            Set<String> numbers = new TreeSet<>();
            for (DocAnalysis d : docs) {
                if (d.content.containsKey(lang)) {
                    numbers.addAll(d.content.get(lang).keySet());
                }
            }
            JSONObject clauses = new JSONObject();
            for (String no : numbers) {
                JSONObject c = new JSONObject();
                for (DocAnalysis d : docs) {
                    Map<String, String> cm = d.content.get(lang);
                    if (cm == null || !cm.containsKey(no)) {
                        continue;
                    }
                    c.put(d.key + "_content", cm.get(no));
                    String img = d.image.get(lang) == null ? null : d.image.get(lang).get(no);
                    if (img != null) {
                        c.put(d.key + "_image", img);
                    }
                    Map<String, String> rn = renumbered.get(d.key);
                    if (rn != null && rn.containsKey(no)) {
                        c.put(d.key + "_renumbered_from", rn.get(no));
                    }
                }
                clauses.put(no, c);
            }

            // ── S# segment removal ───────────────────────────────────────────────────
            // S#-prefixed keys (e.g. "S6#2", "S8#1", "S11#2") come from the signed PDF's
            // deterministic supplement opening new segments for tables/annexes/specs.
            // These segments originate from non-contract pages (equipment specs, notary
            // text, signature blocks) and their "clause numbers" are false positives —
            // numbers found in table cells, ID numbers, prices, etc.
            //
            // Previously these were absorbed into the last normal clause, but this caused
            // garbage content (e.g. "10 gün", "2 yıl", "POMPAYI KONTROL EDEN TEK") to be
            // appended to real clauses — a fraud detection false positive.
            //
            // Now: S# segments are DROPPED entirely. If the signed PDF has genuine
            // additional clauses (annexes), the LLM segmentation finds them with real
            // clause numbers — the deterministic supplement's S# segments are always noise.
            List<String> sKeysToRemove = new ArrayList<>();
            for (String no : clauses.keySet()) {
                if (no.contains("#")) {
                    sKeysToRemove.add(no);
                    log.info("Merge [{}]: S# segment key '{}' dropped (non-contract page content)", lang, no);
                }
            }
            for (String k : sKeysToRemove) {
                clauses.remove(k);
            }

            analyze.put(lang, new JSONObject().put("clauses", clauses));
        }
        analyze.put("baselines", baselines);
        return analyze;
    }

    private static DocAnalysis baselineFor(List<DocAnalysis> docs, String lang) {
        for (DocAnalysis d : docs) {
            if (d.content.containsKey(lang)) {
                return d;
            }
        }
        return docs.get(0);
    }

    /**
     * Clauses that exist only in one of the two documents are matched by text similarity;
     * if similar enough the "other" clause is re-keyed to the baseline number.
     * Returns newNo -> oldNo (baseline number → original other-doc number).
     *
     * <p>Threshold default: 0.92 (raised from 0.85 to reduce false renumber matches
     * between similar-looking standard clauses that are actually distinct).
     *
     * <p>Side effect: mutates {@code other} and {@code otherImages} in-place
     * (defensive copies are taken so the caller's maps are not affected).
     */
    Map<String, String> realign(Map<String, String> base, Map<String, String> other, Map<String, String> otherImages) {
        // Defensive copies — do not mutate caller's maps directly
        Map<String, String> otherCopy  = new java.util.LinkedHashMap<>(other);
        Map<String, String> imagesCopy = otherImages != null
                ? new java.util.LinkedHashMap<>(otherImages) : null;

        Map<String, String> moved = new LinkedHashMap<>();
        List<String> onlyOther = new ArrayList<>();
        for (String k : otherCopy.keySet()) {
            if (!base.containsKey(k)) onlyOther.add(k);
        }
        List<String> onlyBase = new ArrayList<>();
        for (String k : base.keySet()) {
            if (!otherCopy.containsKey(k)) onlyBase.add(k);
        }
        for (String o : onlyOther) {
            String best = null;
            double bestSim = renumberThreshold;
            for (String b : onlyBase) {
                double sim = ClauseDiffer.similarity(base.get(b), otherCopy.get(o));
                if (sim >= bestSim) {
                    bestSim = sim;
                    best = b;
                }
            }
            if (best != null) {
                otherCopy.put(best, otherCopy.remove(o));
                if (imagesCopy != null && imagesCopy.containsKey(o)) {
                    imagesCopy.put(best, imagesCopy.remove(o));
                }
                onlyBase.remove(best);
                moved.put(best, o);
                log.warn("AnalyzeMerger realign: madde '{}' (diğer dok.) → '{}' (baseline) olarak eşleştirildi " +
                        "(benzerlik={}) — gerçek madde numarası değişikliği olabilir, raporda kontrol edin.",
                        o, best, String.format("%.3f", bestSim));
            }
        }
        // Apply mutations back to the caller's maps
        other.clear();
        other.putAll(otherCopy);
        if (otherImages != null && imagesCopy != null) {
            otherImages.clear();
            otherImages.putAll(imagesCopy);
        }
        return moved;
    }
}
