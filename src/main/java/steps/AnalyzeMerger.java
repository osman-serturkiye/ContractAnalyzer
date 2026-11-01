package steps;

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
     * Clauses that exist only in one of the two documents are matched by text similarity; if similar enough the
     * "other" clause is re-keyed to the baseline number. Returns newNo -> oldNo.
     */
    Map<String, String> realign(Map<String, String> base, Map<String, String> other, Map<String, String> otherImages) {
        Map<String, String> moved = new LinkedHashMap<>();
        List<String> onlyOther = new ArrayList<>();
        for (String k : other.keySet()) {
            if (!base.containsKey(k)) {
                onlyOther.add(k);
            }
        }
        List<String> onlyBase = new ArrayList<>();
        for (String k : base.keySet()) {
            if (!other.containsKey(k)) {
                onlyBase.add(k);
            }
        }
        for (String o : onlyOther) {
            String best = null;
            double bestSim = renumberThreshold;
            for (String b : onlyBase) {
                double sim = ClauseDiffer.similarity(base.get(b), other.get(o));
                if (sim >= bestSim) {
                    bestSim = sim;
                    best = b;
                }
            }
            if (best != null) {
                other.put(best, other.remove(o));
                if (otherImages != null && otherImages.containsKey(o)) {
                    otherImages.put(best, otherImages.remove(o));
                }
                onlyBase.remove(best);
                moved.put(best, o);
            }
        }
        return moved;
    }
}
