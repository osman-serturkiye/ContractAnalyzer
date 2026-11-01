package steps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-document view of the analysis: language → clause number → markdown (+ optional image).
 * Also carries the list of detected ink signatures (language-independent, signed PDF only).
 */
public final class DocAnalysis {
    public final String key;                        // "signed", "original1", ...
    public final List<String> languages = new ArrayList<>();
    public final Map<String, String> titles = new LinkedHashMap<>();
    public final Map<String, Map<String, String>> content = new HashMap<>();
    public final Map<String, Map<String, String>> image = new HashMap<>();
    /**
     * Detected ink signatures for this document. Each entry is a JSON object:
     * {@code {"page": 3, "index": 0, "label": "ABC Ltd.", "image": "<base64 PNG>"}}.
     * Only populated for the signed PDF; always empty for DOCX originals.
     */
    public final List<JSONObject> signatures = new ArrayList<>();

    public DocAnalysis(String key) {
        this.key = key;
    }

    /** docAnalyze = {languages:[..], <LANG>:{title, clauses:{no:{signed_content|content, signed_image}}}} */
    public static DocAnalysis from(String key, JSONObject docAnalyze) {
        DocAnalysis d = new DocAnalysis(key);
        JSONArray langs = docAnalyze.optJSONArray("languages");
        if (langs == null) {
            return d;
        }
        for (int i = 0; i < langs.length(); i++) {
            String lang = langs.getString(i);
            d.languages.add(lang);
            JSONObject al = docAnalyze.optJSONObject(lang);
            if (al == null) {
                continue;
            }
            d.titles.put(lang, al.optString("title", ""));
            Map<String, String> cm = new LinkedHashMap<>();
            Map<String, String> im = new LinkedHashMap<>();
            JSONObject clauses = al.optJSONObject("clauses");
            if (clauses != null) {
                for (String no : clauses.keySet()) {
                    JSONObject c = clauses.getJSONObject(no);
                    String text = c.optString("signed_content", c.optString("content", ""));
                    cm.put(no, text);
                    String img = c.optString("signed_image", "");
                    if (!img.isEmpty()) {
                        im.put(no, img);
                    }
                }
            }
            d.content.put(lang, cm);
            d.image.put(lang, im);
        }
        // signatures are language-independent; stored at the top-level of docAnalyze
        JSONArray sigs = docAnalyze.optJSONArray("signatures");
        if (sigs != null) {
            for (int i = 0; i < sigs.length(); i++) {
                JSONObject s = sigs.optJSONObject(i);
                if (s != null) {
                    d.signatures.add(s);
                }
            }
        }
        return d;
    }
}
