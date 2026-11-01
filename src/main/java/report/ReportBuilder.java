package report;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.context.MapValueResolver;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import config.AppConfig;
import util.ClauseNumber;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * analyze JSON -> view model -> Handlebars template (XHTML) -> HTML (screen) / PDF (print).
 *
 * <p>Design: an executive summary first (KPIs, risk distribution, key findings sorted by risk), then per language ONLY
 * the clauses that differ, as cards (AI summary, change table, side-by-side text with highlighted changes). Identical
 * clauses are collapsed into a number list. The scan / formatted markdown text sits in a collapsed block on screen.
 *
 * <p>Markup, CSS and JS are assets ({@code report-template.hbs.html}, {@code report.css}, {@code report.js}, optional {@code logo.svg}):
 * read exclusively from {@code config.json -> report.assets} (base64-encoded; update with {@code gradle updateConfigAssets}).
 */
public final class ReportBuilder {

    private static final Logger log = LogManager.getLogger();

    private static final String TEMPLATE = "report-template.hbs.html";
    private static final String CSS      = "report.css";
    private static final String JS       = "report.js";

    private static final String[] REGULAR_FONTS = {
            "C:/Windows/Fonts/arial.ttf",
            "C:/Windows/Fonts/calibri.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf"
    };

    private static final String[] BOLD_FONTS = {
            "C:/Windows/Fonts/arialbd.ttf",
            "C:/Windows/Fonts/calibrib.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
    };

    private static final String C_HIGH = "#dc2626";
    private static final String C_MISSING = "#7c3aed";
    private static final String C_MEDIUM = "#ea580c";
    private static final String C_LOW = "#ca8a04";
    private static final String C_OK = "#16a34a";

    private final AppConfig cfg;
    private final boolean turkish;
    private final Parser parser;
    private final HtmlRenderer renderer;
    /** Like {@link #renderer} but with {@code escapeHtml(false)} so that pre-generated
     *  {@code <del>}/{@code <ins>} spans inside Markdown-annotated diff text pass through. */
    private final HtmlRenderer diffRenderer;
    private final Handlebars handlebars;
    private final ReportAssets assets;
    private final JSONObject clauseTagLabels;
    private final JSONObject clauseUnitLabels;

    public ReportBuilder(AppConfig cfg) {
        this.cfg = cfg;
        this.turkish = "tr".equalsIgnoreCase(cfg.defaultLang());

        /*
         * Report assetleri sadece:
         *
         * config.json -> report.assets
         *
         * üzerinden okunur.
         */
        this.assets = ReportAssets.load(cfg);

        // Load tag label map from steps.aiClauseCompare.clauseTagLabels
        JSONObject step = cfg.step("aiClauseCompare");
        this.clauseTagLabels =
                (step != null && step.has("clauseTagLabels"))
                        ? step.getJSONObject("clauseTagLabels")
                        : new JSONObject();

        // Load unit label map from steps.aiClauseCompare.clauseUnitLabels
        this.clauseUnitLabels =
                (step != null && step.has("clauseUnitLabels"))
                        ? step.getJSONObject("clauseUnitLabels")
                        : new JSONObject();

        List<Extension> ext = List.of(
                TablesExtension.create()
        );

        this.parser = Parser.builder()
                .extensions(ext)
                .build();

        this.renderer = HtmlRenderer.builder()
                .extensions(ext)
                .escapeHtml(true)
                .build();

        this.diffRenderer = HtmlRenderer.builder()
                .extensions(ext)
                .escapeHtml(false)   // allow pre-generated <del>/<ins> spans to pass through
                .build();

        // ------------------------------------------------------------
        // Handlebars
        // ------------------------------------------------------------

        this.handlebars = new Handlebars();

        /*
         * {{L "English" "Türkçe"}}
         *
         * Screen mode: emits a <span class="i18n" data-en="..." data-tr="...">
         * so the browser-side language switcher can swap labels without a page reload.
         * Print mode: emits plain text in the configured language (no JS in PDF).
         *
         * The "print" flag is resolved by walking up the context chain to the root
         * map, because the helper may be called from inside nested #each blocks.
         */
        this.handlebars.registerHelper(
                "L",
                (Helper<String>) (en, options) -> {
                    String tr = options.param(0, en);

                    // Walk up context chain to find the root "print" flag
                    boolean isPrint = false;
                    Context ctx = options.context;
                    while (ctx != null) {
                        Object model = ctx.model();
                        if (model instanceof Map) {
                            Object flag = ((Map<?, ?>) model).get("print");
                            if (flag instanceof Boolean) {
                                isPrint = (Boolean) flag;
                                break;
                            }
                        }
                        ctx = ctx.parent();
                    }

                    if (isPrint) {
                        return turkish ? tr : en;
                    }
                    // Escape for safe use inside HTML attributes
                    String safeEn = en.replace("&", "&amp;").replace("\"", "&#34;").replace("<", "&lt;");
                    String safeTr = tr.replace("&", "&amp;").replace("\"", "&#34;").replace("<", "&lt;");
                    String current = turkish ? tr : en;
                    return new com.github.jknack.handlebars.Handlebars.SafeString(
                            "<span class=\"i18n\" data-en=\"" + safeEn + "\" data-tr=\"" + safeTr + "\">" + current + "</span>"
                    );
                }
        );

        /*
         * {{#if (eq side "both")}}
         * {{#if (eq side "base")}}
         * {{#if (eq side "other")}}
         *
         * ConditionalHelpers.eq yerine doğrudan helper register ediyoruz.
         *
         * Böylece runtime'da ConditionalHelpers sınıfının farklı Handlebars
         * versiyonundan gelmesi / classloader problemi olması durumunda
         * eq helper'ı etkilenmez.
         */
        this.handlebars.registerHelper(
                "eq",
                (Helper<Object>) (value, options) -> {

                    Object other = options.param(0, null);

                    if (value == null || other == null) {
                        return value == other;
                    }

                    return value.toString().equals(other.toString());
                }
        );
    }

    private String L(String en, String tr) {
        return turkish ? tr : en;
    }

    /**
     * Returns a bilingual label map: {"en": en, "tr": tr}.
     * Used to enrich analyze-model.json so that consumers can render
     * labels in any supported language without re-running the pipeline.
     */
    private static Map<String, Object> i18n(String en, String tr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("en", en);
        m.put("tr", tr);
        return m;
    }

    /**
     * Reads a possibly-bilingual field (plain String OR JSONObject {"en","tr"})
     * and returns the value for the current report language.
     * Falls back gracefully: en→tr→raw string→"".
     */
    private String resolveI18nString(Object raw) {
        if (raw instanceof org.json.JSONObject) {
            org.json.JSONObject j = (org.json.JSONObject) raw;
            String val = turkish
                    ? j.optString("tr", j.optString("en", ""))
                    : j.optString("en", j.optString("tr", ""));
            return val;
        }
        return raw == null ? "" : raw.toString();
    }

    /**
     * Converts a possibly-bilingual field to a {"en":"...","tr":"..."} Map for the view model.
     * If the raw value is a plain string it is placed in both language slots.
     */
    private static Map<String, Object> toI18nMap(Object raw) {
        if (raw instanceof org.json.JSONObject) {
            org.json.JSONObject j = (org.json.JSONObject) raw;
            return i18n(j.optString("en", j.optString("tr", "")),
                        j.optString("tr", j.optString("en", "")));
        }
        String s = raw == null ? "" : raw.toString();
        return i18n(s, s);
    }

    /**
     * Builds an i18n-aware display string for free-text AI content:
     * - print mode  → plain text in the current language
     * - screen mode → &lt;span class="i18n" data-en="..." data-tr="..."&gt; so the
     *   browser language-switcher can swap it without a page reload.
     *
     * The returned string is already HTML-safe; use triple-brace {{{field}}} in the template.
     */
    private String i18nSpan(Object raw, boolean print) {
        String en = "";
        String tr = "";
        if (raw instanceof org.json.JSONObject) {
            org.json.JSONObject j = (org.json.JSONObject) raw;
            en = j.optString("en", j.optString("tr", ""));
            tr = j.optString("tr", j.optString("en", ""));
        } else if (raw != null) {
            en = raw.toString();
            tr = raw.toString();
        }
        if (print) {
            return turkish ? escHtml(tr) : escHtml(en);
        }
        return "<span class=\"i18n\" data-en=\"" + escAttr(en)
                + "\" data-tr=\"" + escAttr(tr) + "\">"
                + escHtml(turkish ? tr : en)
                + "</span>";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&#34;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Builds an i18n-aware display string for a static bilingual label pair.
     * Identical semantics to {@link #i18nSpan(Object, boolean)} but takes two
     * explicit strings instead of a JSONObject — used for hard-coded label pairs
     * like sev/verdict/doc labels so the browser language-switcher can swap them.
     *
     * Use triple-brace {{{field}}} in Handlebars to avoid double-escaping.
     */
    private String labelSpan(String en, String tr, boolean print) {
        if (print) {
            return turkish ? escHtml(tr) : escHtml(en);
        }
        return "<span class=\"i18n\" data-en=\"" + escAttr(en)
                + "\" data-tr=\"" + escAttr(tr) + "\">"
                + escHtml(turkish ? tr : en)
                + "</span>";
    }

    /**
     * Resolves a tag key (e.g. "intellectual_property")
     * to its display label in the report language.
     */
    private String tagLabel(String key) {
        JSONObject entry = clauseTagLabels.optJSONObject(key);

        if (entry == null) {
            return key;
        }

        String label = turkish
                ? entry.optString("tr", "")
                : entry.optString("en", "");

        return label.isEmpty()
                ? entry.optString("en", key)
                : label;
    }

    /** Returns {"en":"...","tr":"..."} for a tag key — used for i18n-aware tag display. */
    private Map<String, Object> tagLabelI18n(String key) {
        JSONObject entry = clauseTagLabels.optJSONObject(key);
        if (entry == null) return i18n(key, key);
        String en = entry.optString("en", key);
        String tr = entry.optString("tr", en);
        return i18n(en, tr);
    }

    /**
     * Resolves a unit key (e.g. "LEGAL")
     * to its display label in the report language.
     */
    private String unitLabel(String key) {
        if (key == null || key.isEmpty()) return key;
        JSONObject entry = clauseUnitLabels.optJSONObject(key);
        if (entry == null) return key;
        String label = turkish
                ? entry.optString("tr", "")
                : entry.optString("en", "");
        return label.isEmpty() ? entry.optString("en", key) : label;
    }

    /** Returns {"en":"...","tr":"..."} for a unit key — used for i18n-aware unit display. */
    private Map<String, Object> unitLabelI18n(String key) {
        if (key == null || key.isEmpty()) return i18n("", "");
        JSONObject entry = clauseUnitLabels.optJSONObject(key);
        if (entry == null) return i18n(key, key);
        String en = entry.optString("en", key);
        String tr = entry.optString("tr", en);
        return i18n(en, tr);
    }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    /** Interactive HTML (filters, collapsible scans). */
    public String buildHtml(JSONObject analyze) {
        return render(analyze, false);
    }

    /** Print variant for the PDF (no scripts / details, page-break friendly). */
    public String buildPrintHtml(JSONObject analyze) {
        return render(analyze, true);
    }

    private String render(JSONObject analyze, boolean print) {

        String source = asset(TEMPLATE);

        Map<String, Object> model = buildModel(analyze, print);

        model.put("css", asset(CSS));
        model.put("js",  asset(JS));

        Context ctx = Context
                .newBuilder(model)
                .resolver(MapValueResolver.INSTANCE)
                .build();

        try {
            return handlebars
                    .compileInline(source)
                    .apply(ctx);

        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Report template could not be rendered",
                    e
            );
        }
    }

    /**
     * Assetleri sadece config.json -> report.assets içerisinden okur.
     */
    private String asset(String name) {

        String text = assets.text(name, null);

        if (text == null) {
            throw new IllegalStateException(
                    "Report asset '" + name
                            + "' not found in config.json -> report.assets. "
                            + "Run: gradle updateConfigAssets"
            );
        }

        return text;
    }

    // ------------------------------------------------------------------
    // View model
    // ------------------------------------------------------------------

    private static final class Counts {

        int ok;
        int low;
        int medium;
        int high;
        int missing;

        void add(String sev) {

            switch (sev) {

                case "ok":
                    ok++;
                    break;

                case "low":
                    low++;
                    break;

                case "high":
                    high++;
                    break;

                case "missing":
                    missing++;
                    break;

                default:
                    medium++;
            }
        }

        int different() {
            return low + medium + high;
        }

        int total() {
            return ok + low + medium + high + missing;
        }

        Map<String, Object> toMap() {
            return m(
                    "ok", ok,
                    "different", different(),
                    "high", high,
                    "medium", medium,
                    "low", low,
                    "missing", missing
            );
        }
    }

    /**
     * Returns an ISO 639-1 code -> display name map.
     */
    private static Map<String, String> langNames() {

        Map<String, String> m = new LinkedHashMap<>();

        m.put("TR", "Türkçe");
        m.put("EN", "English");
        m.put("AR", "العربية");
        m.put("DE", "Deutsch");
        m.put("FR", "Français");
        m.put("RU", "Русский");
        m.put("ES", "Español");
        m.put("IT", "Italiano");
        m.put("ZH", "中文");
        m.put("JA", "日本語");
        m.put("KO", "한국어");
        m.put("PT", "Português");
        m.put("NL", "Nederlands");
        m.put("PL", "Polski");
        m.put("CS", "Čeština");
        m.put("SV", "Svenska");
        m.put("HU", "Magyar");
        m.put("RO", "Română");
        m.put("UK", "Українська");
        m.put("FA", "فارسی");

        return m;
    }

    private static String langDisplay(
            String isoCode,
            boolean multiLang
    ) {

        if (!multiLang) {
            return "";
        }

        Map<String, String> names = langNames();

        String name = names.getOrDefault(
                isoCode.toUpperCase(),
                null
        );

        return name != null
                ? isoCode + ": " + name
                : isoCode;
    }

    public Map<String, Object> buildModel(
            JSONObject analyze,
            boolean print
    ) {

        String base = analyze.getString("baseline");

        List<String> docs =
                list(analyze.getJSONArray("documents"));

        List<String> languages =
                list(analyze.getJSONArray("languages"));

        JSONObject meta =
                analyze.optJSONObject("meta");

        boolean single = docs.size() < 2;
        boolean multiLang = languages.size() > 1;

        Counts total = new Counts();

        int clauseTotal = 0;

        List<Map<String, Object>> sections =
                new ArrayList<>();

        List<Map<String, Object>> findings =
                new ArrayList<>();

        for (String l : languages) {

            Map<String, Object> s =
                    section(
                            analyze,
                            l,
                            docs,
                            base,
                            print,
                            total,
                            findings
                    );

            s.put(
                    "langDisplay",
                    langDisplay(l, multiLang)
            );

            clauseTotal +=
                    (Integer) s.get("clauseCount");

            sections.add(s);
        }

        findings.sort(
                Comparator
                        .<Map<String, Object>, String>
                                comparing(f -> (String) f.get("lang"))
                        .thenComparing(
                                (a, b) ->
                                        ClauseNumber.COMPARATOR.compare(
                                                (String) a.get("no"),
                                                (String) b.get("no")
                                        )
                        )
        );

        List<Map<String, Object>> signatures =
                signatureRows(analyze);

        Map<String, Object> m =
                new LinkedHashMap<>();

        m.put("print", print);
        m.put("screen", !print);
        m.put("lang", turkish ? "tr" : "en");

        m.put(
                "title",
                labelSpan("Contract Analysis Report", "Sözleşme Analiz Raporu", print)
        );

        // Plain text title for use in <title> tag (no HTML spans)
        m.put(
                "titlePlain",
                turkish ? "Sözleşme Analiz Raporu" : "Contract Analysis Report"
        );

        m.put(
                "title_i18n",
                i18n("Contract Analysis Report", "Sözleşme Analiz Raporu")
        );

        m.put(
                "generated",
                meta == null
                        ? ""
                        : meta.optString("generated", "")
        );

        m.put("logo", logo());

        m.put("singleDocument", single);
        m.put("multiLang", multiLang);

        m.put(
                "kpi",
                m(
                        "documents", docs.size(),
                        "languages", languages.size(),
                        "clauses", clauseTotal,
                        "identical", total.ok,
                        "different", total.different(),
                        "high", total.high,
                        "missing", total.missing,
                        "signatures", signatures.size()
                )
        );

        m.put("bar", bar(total, print));

        m.put(
                "documents",
                documentRows(
                        analyze,
                        docs,
                        base,
                        print
                )
        );

        m.put("findings", findings);
        m.put("findingCount", findings.size());
        m.put("sections", sections);

        m.put("hasFacts", false);
        m.put("factsAndIndex", new ArrayList<>());

        // ---- cross-clause dependencies ----
        List<Map<String, Object>> dependencies = buildDependencies(analyze, languages);
        m.put("dependencies", dependencies);
        m.put("hasDependencies", !dependencies.isEmpty());

        m.put("signatures", signatures);
        m.put("hasSignatures", !signatures.isEmpty());

        m.put("steps", steps(meta));

        return m;
    }

    // ------------------------------------------------------------------
    // Cross-clause dependency view-model
    // ------------------------------------------------------------------

    /**
     * Builds the view-model list for the dependencies section.
     *
     * Each entry:
     * <pre>
     * {
     *   "lang":            "TR",
     *   "trigger":         "5",
     *   "triggerLabel":    "Madde 5",
     *   "triggerSummary":  "Ödeme süresi 30→45 gün",
     *   "triggerAnchor":   "c-TR-5",
     *   "affected": [
     *     {
     *       "clause":      "8",
     *       "label":       "Madde 8",
     *       "anchor":      "c-TR-8",
     *       "sharedValue": "30 gün",
     *       "relation":    "...",
     *       "impact":      "...",
     *       "risk":        "MEDIUM",
     *       "riskSev":     "medium",
     *       "riskLabel":   "Orta risk"
     *     }
     *   ],
     *   "chain":           ["5", "8", "12"],
     *   "chainLabels":     ["Madde 5", "Madde 8", "Madde 12"],
     *   "chainArrow":      "Madde 5 → Madde 8 → Madde 12",
     *   "chainExplanation":"...",
     *   "hasChain":        true,
     *   "confirmedCount":  2
     * }
     * </pre>
     */
    private List<Map<String, Object>> buildDependencies(JSONObject analyze, List<String> languages) {
        List<Map<String, Object>> result = new ArrayList<>();

        JSONObject allDeps = analyze.optJSONObject("dependencies");
        if (allDeps == null) return result;

        for (String lang : languages) {
            JSONArray langDeps = allDeps.optJSONArray(lang);
            if (langDeps == null) continue;

            for (int i = 0; i < langDeps.length(); i++) {
                JSONObject dep = langDeps.optJSONObject(i);
                if (dep == null) continue;
                if (!dep.optBoolean("confirmed", false)) continue;

                String trigger      = dep.optString("trigger", "");
                // triggerLabel stored as plain clause number since CrossClauseDependencyAnalyzer refactor;
                // build bilingual display labels here
                String triggerLabelTr = "Madde " + trigger;
                String triggerLabelEn = "Article " + trigger;
                String triggerLabel   = turkish ? triggerLabelTr : triggerLabelEn;
                String triggerAnchor = "c-" + lang + "-" + trigger.replace('.', '_');

                // affected clauses
                List<Map<String, Object>> affected = new ArrayList<>();
                JSONArray affArr = dep.optJSONArray("affected");
                if (affArr != null) {
                    for (int j = 0; j < affArr.length(); j++) {
                        JSONObject a = affArr.optJSONObject(j);
                        if (a == null) continue;
                        String no      = a.optString("clause", "");
                        String labelTr = "Madde " + no;
                        String labelEn = "Article " + no;
                        String risk    = a.optString("risk", "MEDIUM");
                        String riskSev = riskToSev(risk);
                        affected.add(m(
                            "clause",        no,
                            "label",         turkish ? labelTr : labelEn,
                            "label_i18n",    i18n(labelEn, labelTr),
                            "anchor",        "c-" + lang + "-" + no.replace('.', '_'),
                            "sharedValue",   a.optString("sharedValue", ""),
                            "relation",      resolveI18nString(a.opt("relation")),
                            "relation_i18n", toI18nMap(a.opt("relation")),
                            "impact",        resolveI18nString(a.opt("impact")),
                            "impact_i18n",   toI18nMap(a.opt("impact")),
                            "risk",          risk,
                            "riskSev",       riskSev,
                            "riskLabel",     depRiskLabel(riskSev),
                            "riskLabel_i18n", depRiskLabelI18n(riskSev)
                        ));
                    }
                }

                // chain
                JSONArray chainArr   = dep.optJSONArray("chain");
                List<String> chain   = new ArrayList<>();
                List<String> chainLblsTr = new ArrayList<>();
                List<String> chainLblsEn = new ArrayList<>();
                if (chainArr != null) {
                    for (int k = 0; k < chainArr.length(); k++) {
                        String no = chainArr.optString(k, "");
                        chain.add(no);
                        chainLblsTr.add("Madde " + no);
                        chainLblsEn.add("Article " + no);
                    }
                }
                List<String> chainLbls = turkish ? chainLblsTr : chainLblsEn;
                String chainArrow   = String.join(" → ", chainLbls);
                String chainArrowEn = String.join(" → ", chainLblsEn);
                String chainArrowTr = String.join(" → ", chainLblsTr);

                result.add(m(
                    "lang",                   lang,
                    "trigger",                trigger,
                    "triggerLabel",           triggerLabel,
                    "triggerLabel_i18n",      i18n(triggerLabelEn, triggerLabelTr),
                    "triggerSummary",         resolveI18nString(dep.opt("triggerSummary")),
                    "triggerSummary_i18n",    toI18nMap(dep.opt("triggerSummary")),
                    "triggerAnchor",          triggerAnchor,
                    "affected",               affected,
                    "chain",                  chain,
                    "chainLabels",            chainLbls,
                    "chainLabels_i18n",       i18n(String.join(", ", chainLblsEn), String.join(", ", chainLblsTr)),
                    "chainArrow",             chainArrow,
                    "chainArrow_i18n",        i18n(chainArrowEn, chainArrowTr),
                    "chainExplanation",       resolveI18nString(dep.opt("chainExplanation")),
                    "chainExplanation_i18n",  toI18nMap(dep.opt("chainExplanation")),
                    "hasChain",               chain.size() > 2,
                    "confirmedCount",         dep.optInt("confirmedCount", affected.size()),
                    "multiLang",              languages.size() > 1
                ));
            }
        }

        // Sort by trigger clause number
        result.sort(Comparator.comparing(r -> (String) r.get("trigger")));
        return result;
    }

    private static String riskToSev(String risk) {
        switch (risk == null ? "" : risk.toUpperCase()) {
            case "HIGH": return "high";
            case "LOW":  return "low";
            default:     return "medium";
        }
    }

    private String depRiskLabel(String sev) {
        switch (sev) {
            case "high":   return L("High risk",   "Yüksek risk");
            case "low":    return L("Low risk",    "Düşük risk");
            default:       return L("Medium risk", "Orta risk");
        }
    }

    private Map<String, Object> depRiskLabelI18n(String sev) {
        switch (sev) {
            case "high": return i18n("High risk",   "Yüksek risk");
            case "low":  return i18n("Low risk",    "Düşük risk");
            default:     return i18n("Medium risk", "Orta risk");
        }
    }

    private String logo() {

        for (String n :
                new String[]{
                        "logo.svg",
                        "logo.png",
                        "logo.jpg",
                        "logo.jpeg"
                }) {

            if (assets.has(n)) {
                return assets.dataUri(n);
            }
        }

        return "";
    }

    private List<Map<String, Object>> bar(Counts t, boolean print) {

        List<Map<String, Object>> out =
                new ArrayList<>();

        int all = t.total();

        if (all == 0) {
            return out;
        }

        addBar(out, labelSpan("Identical", "Aynı",     print), i18n("Identical", "Aynı"),     C_OK,      t.ok,      all);
        addBar(out, labelSpan("Low",       "Düşük",    print), i18n("Low",       "Düşük"),    C_LOW,     t.low,     all);
        addBar(out, labelSpan("Medium",    "Orta",     print), i18n("Medium",    "Orta"),     C_MEDIUM,  t.medium,  all);
        addBar(out, labelSpan("High",      "Yüksek",   print), i18n("High",      "Yüksek"),   C_HIGH,    t.high,    all);
        addBar(out, labelSpan("Missing",   "Eksik",    print), i18n("Missing",   "Eksik"),    C_MISSING, t.missing, all);

        return out;
    }

    private static void addBar(
            List<Map<String, Object>> out,
            String label,
            Map<String, Object> labelI18n,
            String color,
            int count,
            int all
    ) {

        if (count > 0) {

            out.add(
                    m(
                            "label", label,
                            "label_i18n", labelI18n,
                            "color", color,
                            "count", count,
                            "pct",
                            String.valueOf(
                                    Math.max(
                                            1.0,
                                            Math.round(
                                                    count * 1000.0 / all
                                            ) / 10.0
                                    )
                            ).replace(',', '.')
                    )
            );
        }
    }

    private List<Map<String, Object>> documentRows(
            JSONObject analyze,
            List<String> docs,
            String base,
            boolean print
    ) {

        List<Map<String, Object>> rows =
                new ArrayList<>();

        JSONObject dl =
                analyze.optJSONObject(
                        "document_languages"
                );

        JSONObject titles =
                analyze.optJSONObject("title");

        for (String d : docs) {

            JSONArray la =
                    dl == null
                            ? null
                            : dl.optJSONArray(d);

            List<String> langs =
                    la == null
                            ? new ArrayList<>()
                            : list(la);

            List<String> tl =
                    new ArrayList<>();

            JSONObject t =
                    titles == null
                            ? null
                            : titles.optJSONObject(d);

            for (String l : langs) {

                String v =
                        t == null
                                ? ""
                                : t.optString(l, "");

                if (!v.isEmpty()) {
                    tl.add(l + ": " + v);
                }
            }

            rows.add(
                    m(
                            "key", d,
                            "label", labelSpan(
                                    (String) docLabelI18n(d).get("en"),
                                    (String) docLabelI18n(d).get("tr"),
                                    print
                            ),
                            "label_i18n", docLabelI18n(d),
                            "isBaseline", d.equals(base),
                            "langs", langs,
                            "titles", tl
                    )
            );
        }

        return rows;
    }

    private Map<String, Object> section(
            JSONObject analyze,
            String l,
            List<String> docs,
            String base,
            boolean print,
            Counts total,
            List<Map<String, Object>> findings
    ) {

        JSONObject clauses =
                analyze
                        .getJSONObject(l)
                        .getJSONObject("clauses");

        List<String> nos =
                new ArrayList<>(clauses.keySet());

        /*
         * Clause numaraları:
         *
         * 1
         * 1.1
         * 1.2
         * 1.10
         * 2
         *
         * şeklinde hiyerarşik sıralanır.
         */
        nos.sort(ClauseNumber.COMPARATOR);

        JSONObject dl =
                analyze.optJSONObject(
                        "document_languages"
                );

        List<String> ldocs =
                new ArrayList<>();

        for (String d : docs) {

            JSONArray a =
                    dl == null
                            ? null
                            : dl.optJSONArray(d);

            if (a == null || list(a).contains(l)) {
                ldocs.add(d);
            }
        }

        JSONObject bl =
                analyze.optJSONObject("baselines");

        String lbase =
                bl == null
                        ? base
                        : bl.optString(l, base);

        List<String> lothers =
                new ArrayList<>(ldocs);

        lothers.remove(lbase);

        boolean compared =
                !lothers.isEmpty();

        Counts lc = new Counts();

        List<Map<String, Object>> cards =
                new ArrayList<>();

        List<String> identical =
                new ArrayList<>();

        List<Map<String, Object>> sidebarItems =
                new ArrayList<>();

        for (String no : nos) {

            JSONObject c =
                    clauses.getJSONObject(no);

            if (!compared) {

                Map<String, Object> cc =
                        contentCard(
                                no,
                                c,
                                l,
                                lbase,
                                print
                        );

                cards.add(cc);

                sidebarItems.add(
                        m(
                                "anchor",
                                cc.get("anchor"),
                                "sev",
                                cc.get("sev"),
                                "tocLabel",
                                cc.get("tocLabel"),
                                "no",
                                no,
                                "label",
                                labelAfterNo(
                                        (String) cc.get("tocLabel"),
                                        no
                                )
                        )
                );

                continue;
            }

            List<Map<String, Object>> items =
                    new ArrayList<>();

            List<Map<String, Object>> badges =
                    new ArrayList<>();

            List<Map<String, Object>> findingRows =
                    new ArrayList<>();

            String worst = "ok";

            boolean ocrSuspected = false;

            String anchor = anchor(l, no);

            boolean any = false;

            for (String o : lothers) {

                JSONObject cmp = cmp(c, o);

                if (cmp == null) {
                    continue;
                }

                any = true;

                String st =
                        cmp.optString("status");

                JSONObject ai =
                        cmp.optJSONObject("ai");

                String sev =
                        severity(st, ai);

                lc.add(sev);
                total.add(sev);

                if ("ok".equals(sev)) {
                    continue;
                }

                Map<String, Object> item =
                        item(
                                c,
                                lbase,
                                o,
                                st,
                                cmp,
                                ai,
                                sev,
                                print
                        );

                items.add(item);

                badges.add(
                        m(
                                "sev",
                                sev,
                                "text",
                                labelSpan(
                                        (String) docLabelI18n(o).get("en") + ": " + (String) verdictOrStatusI18n(st, ai, lbase, o).get("en"),
                                        (String) docLabelI18n(o).get("tr") + ": " + (String) verdictOrStatusI18n(st, ai, lbase, o).get("tr"),
                                        print
                                )
                        )
                );

                if (rank(sev) > rank(worst)) {
                    worst = sev;
                }

                if (
                        "signed".equals(o)
                                && ai != null
                                && ai.optBoolean(
                                "ocr_suspected",
                                false
                        )
                ) {
                    ocrSuspected = true;
                }

                findings.add(
                        m(
                                "lang", l,
                                "no", no,
                                "anchor", anchor,
                                "docLabel", labelSpan(
                                        (String) docLabelI18n(o).get("en"),
                                        (String) docLabelI18n(o).get("tr"),
                                        print
                                ),
                                "docLabel_i18n", docLabelI18n(o),
                                "sev", sev,
                                "sevLabel", labelSpan(
                                        (String) sevLabelI18n(sev).get("en"),
                                        (String) sevLabelI18n(sev).get("tr"),
                                        print
                                ),
                                "sevLabel_i18n", sevLabelI18n(sev),
                                "verdictLabel",
                                ai == null ? "" : labelSpan(
                                        (String) verdictLabelI18n(ai.optString("verdict", "")).get("en"),
                                        (String) verdictLabelI18n(ai.optString("verdict", "")).get("tr"),
                                        print
                                ),
                                "verdictLabel_i18n",
                                ai == null
                                        ? i18n("", "")
                                        : verdictLabelI18n(ai.optString("verdict", "")),
                                "summary",
                                i18nSpan(
                                        ai == null ? null : ai.opt("summary"),
                                        print
                                ),
                                "summaryShort",
                                shorten(
                                        resolveI18nString(ai == null ? null : ai.opt("summary")),
                                        260
                                ),
                                "screen", !print,
                                "baseTags",
                                item.get("baseTags"),
                                "otherTags",
                                item.get("otherTags"),
                                "tagDiff",
                                item.get("tagDiff"),
                                "hasTagDiff",
                                item.get("hasTagDiff"),
                                "responsibleUnit",
                                item.get("responsibleUnit"),
                                "responsibleUnit_i18n",
                                item.get("responsibleUnit_i18n"),
                                "responsibleUnitKey",
                                item.get("responsibleUnitKey"),
                                "hasUnit",
                                item.get("hasUnit")
                        )
                );

                // Same data embedded directly on the card so the template
                // can render a finding-row banner without cross-list lookups.
                findingRows.add(m(
                        "docLabel",        labelSpan(
                                               (String) docLabelI18n(o).get("en"),
                                               (String) docLabelI18n(o).get("tr"),
                                               print),
                        "sev",             sev,
                        "sevLabel",        labelSpan(
                                               (String) sevLabelI18n(sev).get("en"),
                                               (String) sevLabelI18n(sev).get("tr"),
                                               print),
                        "verdictLabel",    ai == null ? "" : labelSpan(
                                               (String) verdictLabelI18n(ai.optString("verdict","")).get("en"),
                                               (String) verdictLabelI18n(ai.optString("verdict","")).get("tr"),
                                               print),
                        "summary",         i18nSpan(ai == null ? null : ai.opt("summary"), print),
                        "tagDiff",         item.get("tagDiff"),
                        "hasUnit",         item.get("hasUnit"),
                        "responsibleUnit", item.get("responsibleUnit"),
                        "responsibleUnit_i18n", item.get("responsibleUnit_i18n")
                ));
            } // end for lothers

            if (!any) {
                continue;
            }

            if (items.isEmpty()) {

                identical.add(no);

                String identicalTitle =
                        clauseTitleFromContent(
                                c,
                                lbase,
                                no,
                                60
                        );

                String identicalTocLbl =
                        identicalTitle.isEmpty()
                                ? no
                                : no + " " + identicalTitle;

                sidebarItems.add(
                        m(
                                "anchor",
                                anchor(l, no),
                                "sev",
                                "ok",
                                "tocLabel",
                                identicalTocLbl,
                                "no",
                                no,
                                "label",
                                identicalTitle
                        )
                );

                // Build a full card for identical clauses so that clicking the
                // sidebar item scrolls to a real element that shows the baseline
                // clause content (all document versions side-by-side when on screen).
                Map<String, Object> identCard =
                        new LinkedHashMap<>();

                identCard.put("no", no);
                identCard.put("anchor", anchor(l, no));
                identCard.put("sev", "ok");
                identCard.put("badges", new ArrayList<>());
                identCard.put("contentOnly", false);
                identCard.put("identical", true);
                identCard.put("multiDoc", ldocs.size() > 2); // true only when 3+ docs; false for 2-doc (orig+signed)
                identCard.put("items", new ArrayList<>());
                identCard.put("tocLabel", identicalTocLbl);
                identCard.put(
                        "columns",
                        print
                                ? new ArrayList<>()
                                : columns(c, ldocs, lbase, print)
                );
                identCard.put("screen", !print);
                identCard.put("printScan", print ? findImage(c, ldocs) : "");

                cards.add(identCard);

                continue;
            }

            String worstSummary =
                    items.isEmpty()
                            ? ""
                            : (String) items
                            .get(0)
                            .get("summary");

            String tocLbl =
                    buildTocLabel(
                            no,
                            worstSummary,
                            64
                    );

            Map<String, Object> card =
                    new LinkedHashMap<>();

            card.put("no", no);
            card.put("anchor", anchor);
            card.put("sev", worst);
            card.put("badges", badges);
            card.put("contentOnly", false);
            card.put("items", items);
            card.put("findingRows", findingRows);
            // true when ANY item has hasPanels=true — used to suppress the
            // redundant "Formatted text and scan" details block for missing clauses
            boolean anyPanels = items.stream().anyMatch(it -> Boolean.TRUE.equals(it.get("hasPanels")));
            card.put("hasAnyPanels", anyPanels);
            // suppress the scan/cols details block only for pure missing clauses
            // (hasPanels=true but no real scan image — cols would just repeat the split table)
            card.put("isMissing", "missing".equals(worst));
            card.put("tocLabel", tocLbl);

            card.put(
                    "columns",
                    print
                            ? new ArrayList<>()
                            : columns(
                            c,
                            ldocs,
                            lbase,
                            print
                    )
            );

            card.put("screen", !print);

            card.put(
                    "printScan",
                    print
                            ? findImage(c, ldocs)
                            : ""
            );

            cards.add(card);

            String origTitle =
                    clauseTitleFromContent(
                            c,
                            lbase,
                            no,
                            60
                    );

            sidebarItems.add(
                    m(
                            "anchor",
                            anchor,
                            "sev",
                            worst,
                            "tocLabel",
                            origTitle.isEmpty()
                                    ? tocLbl
                                    : no + " " + origTitle,
                            "no",
                            no,
                            "label",
                            origTitle
                    )
            );
        }

        List<Map<String, Object>> toc =
                new ArrayList<>();

        for (Map<String, Object> card : cards) {

            String cardNo =
                    (String) card.get("no");

            String cardSev =
                    (String) card.get("sev");

            String cardSummary = "";

            if (
                    !Boolean.TRUE.equals(
                            card.get("contentOnly")
                    )
            ) {

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cardItems =
                        (List<Map<String, Object>>)
                                card.get("items");

                if (
                        cardItems != null
                                && !cardItems.isEmpty()
                ) {

                    Object sv =
                            cardItems
                                    .get(0)
                                    .get("summary");

                    if (sv != null) {
                        // sv is already a pre-rendered i18n span — strip tags for plain text TOC
                        String plain = ((String) sv).replaceAll("<[^>]+>", "").trim();
                        cardSummary = shorten(plain, 120);
                    }
                }
            }

            toc.add(
                    m(
                            "no",
                            cardNo,
                            "anchor",
                            card.get("anchor"),
                            "sev",
                            cardSev,
                            "sevLabel",
                            labelSpan(
                                    (String) sevLabelI18n(cardSev).get("en"),
                                    (String) sevLabelI18n(cardSev).get("tr"),
                                    print
                            ),
                            "sevLabel_i18n",
                            sevLabelI18n(cardSev),
                            "summary",
                            cardSummary
                    )
            );
        }

        for (String ino : identical) {

            toc.add(
                    m(
                            "no",
                            ino,
                            "anchor",
                            anchor(l, ino),
                            "sev",
                            "ok",
                            "sevLabel",
                            labelSpan(
                                    (String) sevLabelI18n("ok").get("en"),
                                    (String) sevLabelI18n("ok").get("tr"),
                                    print
                            ),
                            "sevLabel_i18n",
                            sevLabelI18n("ok"),
                            "summary",
                            ""
                    )
            );
        }

        Map<String, Object> s =
                new LinkedHashMap<>();

        s.put("lang", l);
        s.put("screen", !print);
        s.put("compared", compared);
        s.put("baselineLabel", labelSpan(
                (String) docLabelI18n(lbase).get("en"),
                (String) docLabelI18n(lbase).get("tr"),
                print
        ));
        s.put("baselineLabel_i18n", docLabelI18n(lbase));
        s.put("clauseCount", nos.size());
        s.put("stats", lc.toMap());
        s.put("toc", toc);
        s.put("cards", cards);
        s.put("identical", identical);
        s.put("identicalCount", identical.size());
        s.put("sidebarItems", sidebarItems);

        return s;
    }

    private Map<String, Object> contentCard(
            String no,
            JSONObject c,
            String lang,
            String lbase,
            boolean print
    ) {

        String key = null;

        for (String k : c.keySet()) {

            if (k.endsWith("_content")) {
                key = k;
                break;
            }
        }

        String contentSnippet =
                key == null
                        ? ""
                        : c.getString(key)
                        .replaceAll(
                                "[#*_`>\\[\\]()]",
                                ""
                        )
                        .trim();

        String tocLbl =
                buildTocLabel(
                        no,
                        contentSnippet,
                        64
                );

        Map<String, Object> card =
                new LinkedHashMap<>();

        card.put("no", no);
        card.put(
                "anchor",
                anchor(lang, no)
        );
        card.put("sev", "neutral");
        card.put(
                "badges",
                new ArrayList<>()
        );
        card.put("contentOnly", true);
        card.put(
                "contentHtml",
                key == null
                        ? ""
                        : md(c.getString(key))
        );
        card.put("tocLabel", tocLbl);
        card.put("screen", !print);

        return card;
    }

    private Map<String, Object> item(
            JSONObject c,
            String lbase,
            String other,
            String st,
            JSONObject cmp,
            JSONObject ai,
            String sev,
            boolean print
    ) {

        Map<String, Object> it =
                new LinkedHashMap<>();

        it.put(
                "baseLabel",
                labelSpan(
                        (String) docLabelI18n(lbase).get("en"),
                        (String) docLabelI18n(lbase).get("tr"),
                        print
                )
        );

        it.put(
                "baseLabel_i18n",
                docLabelI18n(lbase)
        );

        it.put(
                "docLabel",
                labelSpan(
                        (String) docLabelI18n(other).get("en"),
                        (String) docLabelI18n(other).get("tr"),
                        print
                )
        );

        it.put(
                "docLabel_i18n",
                docLabelI18n(other)
        );

        it.put("sev", sev);

        it.put(
                "statusText",
                verdictOrStatus(
                        st,
                        ai,
                        lbase,
                        other
                )
        );

        it.put(
                "statusText_i18n",
                verdictOrStatusI18n(st, ai, lbase, other)
        );

        // Pre-rendered i18n span for the browser language-switcher
        Map<String, Object> stI18n = verdictOrStatusI18n(st, ai, lbase, other);
        it.put(
                "statusTextSpan",
                labelSpan((String) stI18n.get("en"), (String) stI18n.get("tr"), false)
        );

        it.put(
                "similarity",
                cmp.has("similarity")
                        ? String.valueOf(
                        Math.round(
                                cmp.getDouble(
                                        "similarity"
                                ) * 1000
                        ) / 10.0
                )
                        : ""
        );

        it.put(
                "summary",
                ai == null
                        ? ""
                        : i18nSpan(ai.opt("summary"), false)
        );

        it.put(
                "summary_print",
                ai == null
                        ? ""
                        : i18nSpan(ai.opt("summary"), true)
        );

        it.put(
                "ocrSuspected",
                "signed".equals(other)
                        && ai != null
                        && ai.optBoolean(
                        "ocr_suspected",
                        false
                )
        );

        it.put(
                "renumberedFrom",
                c.optString(
                        other + "_renumbered_from",
                        ""
                )
        );

        List<Map<String, Object>> diffs =
                differences(ai, print);

        int max = 8;

        it.put(
                "differences",
                diffs.size() > max
                        ? new ArrayList<>(
                        diffs.subList(0, max)
                )
                        : diffs
        );

        it.put(
                "moreDifferences",
                Math.max(
                        0,
                        diffs.size() - max
                )
        );

        // ------------------------------------------------------------
        // Clause tags
        // ------------------------------------------------------------

        List<String> baseTags =
                jsonArrayToList(
                        ai == null
                                ? null
                                : ai.optJSONArray(
                                "base_tags"
                        )
                );

        List<String> otherTags =
                jsonArrayToList(
                        ai == null
                                ? null
                                : ai.optJSONArray(
                                "other_tags"
                        )
                );

        it.put("baseTags", baseTags);
        it.put("otherTags", otherTags);

        it.put(
                "hasTags",
                !baseTags.isEmpty()
                        || !otherTags.isEmpty()
        );

        List<Map<String, Object>> tagDiff =
                tagDiff(
                        baseTags,
                        otherTags,
                        docLabel(lbase),
                        docLabel(other),
                        print
                );

        it.put("tagDiff", tagDiff);

        it.put(
                "hasTagDiff",
                tagDiff.stream()
                        .anyMatch(
                                td ->
                                        !"both".equals(
                                                td.get("side")
                                        )
                        )
        );

        // ------------------------------------------------------------
        // Responsible unit
        // ------------------------------------------------------------

        String unitKey = ai == null ? "" : ai.optString("responsible_unit", "");
        String unitDisplay = unitLabel(unitKey);
        it.put("responsibleUnit", unitDisplay);
        it.put("responsibleUnit_i18n", unitLabelI18n(unitKey));
        it.put("responsibleUnitKey", unitKey);
        it.put("hasUnit", !unitKey.isEmpty());

        // ------------------------------------------------------------
        // Panels
        // ------------------------------------------------------------

        if ("MISSING_IN_BASELINE".equals(st)) {

            it.put("hasPanels", true);

            it.put(
                    "baseHtml",
                    "<p class=\"muted\">&#8212;</p>"
            );

            it.put(
                    "otherHtml",
                    md(
                            c.optString(
                                    other + "_content",
                                    ""
                            )
                    )
            );

        } else if ("MISSING_IN_OTHER".equals(st)) {

            it.put("hasPanels", true);

            it.put(
                    "baseHtml",
                    md(
                            c.optString(
                                    lbase + "_content",
                                    ""
                            )
                    )
            );

            it.put(
                    "otherHtml",
                    "<p class=\"muted\">&#8212;</p>"
            );

        } else if (cmp.has("html_base")) {

            it.put("hasPanels", true);

            it.put(
                    "baseHtml",
                    cmp.has("html_base_md")
                            ? mdDiff(cmp.optString("html_base_md", ""))
                            : cmp.optString("html_base", "")
            );

            it.put(
                    "otherHtml",
                    cmp.has("html_other_md")
                            ? mdDiff(cmp.optString("html_other_md", ""))
                            : cmp.optString("html_other", "")
            );

        } else {

            it.put("hasPanels", false);

            it.put(
                    "mergedHtml",
                    cmp.optString(
                            "html",
                            ""
                    )
            );
        }

        return it;
    }

    /**
     * Computes a tag diff between baseline and other document tags.
     */
    private List<Map<String, Object>> tagDiff(
            List<String> baseTags,
            List<String> otherTags,
            String baseLabel,
            String otherLabel,
            boolean print
    ) {

        List<Map<String, Object>> out =
                new ArrayList<>();

        java.util.Set<String> baseSet =
                new java.util.LinkedHashSet<>(
                        baseTags
                );

        java.util.Set<String> otherSet =
                new java.util.LinkedHashSet<>(
                        otherTags
                );

        java.util.Set<String> all =
                new java.util.LinkedHashSet<>();

        all.addAll(baseTags);
        all.addAll(otherTags);

        for (String tag : all) {

            boolean inBase =
                    baseSet.contains(tag);

            boolean inOther =
                    otherSet.contains(tag);

            String side =
                    (inBase && inOther)
                            ? "both"
                            : inBase
                            ? "base"
                            : "other";

            // Build bilingual tag label for the i18n switcher
            Map<String, Object> lbl = tagLabelI18n(tag);
            String tagEn = (String) lbl.get("en");
            String tagTr = (String) lbl.get("tr");

            out.add(
                    m(
                            "tag",      turkish ? tagTr : tagEn,  // default display text
                            "tagEn",    tagEn,
                            "tagTr",    tagTr,
                            "side",
                            side,
                            "baseLabel",
                            baseLabel,
                            "otherLabel",
                            otherLabel,
                            // Plain-text tooltip strings (safe in title="" attributes — no HTML spans)
                            "baseTitleEn",  "Only in baseline",
                            "baseTitleTr",  "Sadece referansta",
                            "otherTitleEn", "Only in compared doc",
                            "otherTitleTr", "Sadece karşılaştırılanda"
                    )
            );
        }

        return out;
    }

    private static List<String> jsonArrayToList(
            JSONArray arr
    ) {

        List<String> out =
                new ArrayList<>();

        if (arr == null) {
            return out;
        }

        for (int i = 0; i < arr.length(); i++) {

            String s =
                    arr.optString(i, "").trim();

            if (!s.isEmpty()) {
                out.add(s);
            }
        }

        return out;
    }

    private List<Map<String, Object>> columns(
            JSONObject c,
            List<String> ldocs,
            String lbase,
            boolean print
    ) {

        List<Map<String, Object>> cols =
                new ArrayList<>();

        for (String d : ldocs) {

            Map<String, Object> lblI18n = d.equals(lbase)
                    ? i18n(
                        (String) docLabelI18n(d).get("en") + " (baseline)",
                        (String) docLabelI18n(d).get("tr") + " (reference)")
                    : docLabelI18n(d);

            cols.add(
                    m(
                            "label",
                            labelSpan(
                                    (String) lblI18n.get("en"),
                                    (String) lblI18n.get("tr"),
                                    print
                            ),
                            "label_i18n",
                            lblI18n,
                            "image",
                            c.optString(
                                    d + "_image",
                                    ""
                            ),
                            "html",
                            c.has(
                                    d + "_content"
                            )
                                    ? md(
                                    c.getString(
                                            d + "_content"
                                    )
                            )
                                    : "<p class=\"muted\">&#8212;</p>"
                    )
            );
        }

        return cols;
    }

    private static String findImage(
            JSONObject c,
            List<String> ldocs
    ) {

        for (String d : ldocs) {

            String img =
                    c.optString(
                            d + "_image",
                            ""
                    );

            if (!img.isEmpty()) {
                return img;
            }
        }

        return "";
    }

    private List<Map<String, Object>> differences(
            JSONObject ai,
            boolean print
    ) {

        List<Map<String, Object>> out =
                new ArrayList<>();

        JSONArray arr =
                ai == null
                        ? null
                        : ai.optJSONArray(
                        "differences"
                );

        if (arr == null) {
            return out;
        }

        for (int i = 0; i < arr.length(); i++) {

            JSONObject d =
                    arr.optJSONObject(i);

            if (d == null) {
                continue;
            }

            out.add(
                    m(
                            "kindLabel",
                            labelSpan(
                                    (String) kindLabelI18n(d.optString("kind")).get("en"),
                                    (String) kindLabelI18n(d.optString("kind")).get("tr"),
                                    print
                            ),
                            "kindLabel_i18n",
                            kindLabelI18n(d.optString("kind")),
                            "a",
                            d.optString("a"),
                            "b",
                            d.optString("b"),
                            "impact",
                            i18nSpan(d.opt("impact"), print)
                    )
            );
        }

        return out;
    }

    /**
     * Builds the view-model rows for the signatures section.
     */
    private List<Map<String, Object>> signatureRows(
            JSONObject analyze
    ) {

        List<Map<String, Object>> out =
                new ArrayList<>();

        JSONArray sigs =
                analyze.optJSONArray(
                        "signatures"
                );

        if (sigs == null) {
            return out;
        }

        for (int i = 0; i < sigs.length(); i++) {

            JSONObject s =
                    sigs.optJSONObject(i);

            if (s == null) {
                continue;
            }

            int page =
                    s.optInt("page", 0);

            String label =
                    s.optString(
                            "label",
                            ""
                    ).trim();

            String image =
                    s.optString(
                            "image",
                            ""
                    );

            if (image.isEmpty()) {
                continue;
            }

            Map<String, Object> row =
                    new LinkedHashMap<>();

            row.put("page", page);

            row.put(
                    "pageLabel",
                    L("Page", "Sayfa")
                            + " "
                            + page
            );

            row.put(
                    "pageLabel_i18n",
                    i18n("Page " + page, "Sayfa " + page)
            );

            row.put("label", label);
            row.put(
                    "hasLabel",
                    !label.isEmpty()
            );
            row.put("image", image);

            out.add(row);
        }

        return out;
    }

    private List<Map<String, Object>> steps(
            JSONObject meta
    ) {

        List<Map<String, Object>> out =
                new ArrayList<>();

        JSONObject steps =
                meta == null
                        ? null
                        : meta.optJSONObject(
                        "steps"
                );

        if (steps == null) {
            return out;
        }

        for (String key : steps.keySet()) {

            out.add(
                    m(
                            "key",
                            key,
                            "value",
                            steps.optString(key)
                    )
            );
        }

        return out;
    }

    // ------------------------------------------------------------------
    // Labels / helpers
    // ------------------------------------------------------------------

    private String md(String markdown) {

        return renderer.render(
                parser.parse(
                        stripMarkdownImages(
                                markdown == null ? "" : markdown
                        )
                )
        );
    }

    /**
     * Like {@link #md(String)} but uses the {@link #diffRenderer} that does NOT escape HTML.
     * Used to render Markdown that already contains pre-generated {@code <del>}/{@code <ins>}
     * inline spans from {@link steps.ClauseDiffer#diff} so those spans survive the render.
     */
    private String mdDiff(String markdown) {
        return diffRenderer.render(
                parser.parse(
                        stripMarkdownImages(
                                markdown == null ? "" : markdown
                        )
                )
        );
    }

    /** Removes Markdown image references (e.g. ![img-0.jpeg](img-0.jpeg)) that Mistral OCR sometimes
     *  embeds in block content.  These are bare filenames that openhtmltopdf cannot resolve and that
     *  carry no useful information in a contract report — the actual page scan is already embedded as
     *  a base64 PNG.  Stripping here is a last-resort safety net that works even when the reference
     *  survived in a cached checkpoint JSON written before the OCR-level fix was deployed. */
    private static String stripMarkdownImages(String md) {
        // block-level:  ![alt](src) on its own line
        md = md.replaceAll("(?m)^\\s*!\\[[^]]*]\\([^)]*\\)\\s*$\\n?", "");
        // inline:       ![alt](src) mid-paragraph
        md = md.replaceAll("!\\[[^]]*]\\([^)]*\\)", "");
        // legacy checkpoint marker written before thematic-break migration
        md = md.replace("<!-- PAGE_BREAK -->", "\n\n---\n\n");
        // legacy inline HTML page-break written before thematic-break migration
        md = md.replace("<hr/>", "\n\n---\n\n");
        md = md.replace("<hr>",  "\n\n---\n\n");
        return md;
    }

    private static String severity(
            String status,
            JSONObject ai
    ) {

        if ("IDENTICAL".equals(status)) {
            return "ok";
        }

        if (
                "MISSING_IN_BASELINE".equals(status)
                        || "MISSING_IN_OTHER".equals(status)
        ) {
            return "missing";
        }

        if (ai == null) {
            return "medium";
        }

        String risk =
                ai.optString(
                        "risk",
                        "MEDIUM"
                );

        String verdict =
                ai.optString(
                        "verdict",
                        ""
                );

        if (
                "FORMATTING_ONLY".equals(verdict)
                        || (
                        ai.optBoolean(
                                "ocr_suspected",
                                false
                        )
                                && !"SUBSTANTIVE".equals(
                                verdict
                        )
                )
        ) {
            return "low";
        }

        return "HIGH".equals(risk)
                ? "high"
                : "LOW".equals(risk)
                ? "low"
                : "medium";
    }

    private static int rank(String sev) {

        switch (sev) {

            case "high":
                return 5;

            case "missing":
                return 4;

            case "medium":
                return 3;

            case "low":
                return 2;

            default:
                return 1;
        }
    }

    private String sevLabel(String sev) {

        switch (sev) {

            case "high":
                return L(
                        "High risk",
                        "Yüksek risk"
                );

            case "medium":
                return L(
                        "Medium risk",
                        "Orta risk"
                );

            case "low":
                return L(
                        "Low risk",
                        "Düşük risk"
                );

            case "missing":
                return L(
                        "Missing clause",
                        "Eksik madde"
                );

            default:
                return L(
                        "Identical",
                        "Aynı"
                );
        }
    }

    private Map<String, Object> sevLabelI18n(String sev) {
        switch (sev) {
            case "high":    return i18n("High risk",      "Yüksek risk");
            case "medium":  return i18n("Medium risk",    "Orta risk");
            case "low":     return i18n("Low risk",       "Düşük risk");
            case "missing": return i18n("Missing clause", "Eksik madde");
            default:        return i18n("Identical",      "Aynı");
        }
    }

    private String verdictLabel(String v) {

        switch (v) {

            case "SUBSTANTIVE":
                return L(
                        "Substantive difference",
                        "Esaslı fark"
                );

            case "MINOR":
                return L(
                        "Minor difference",
                        "Küçük fark"
                );

            case "FORMATTING_ONLY":
                return L(
                        "Formatting only",
                        "Yalnızca biçim"
                );

            case "IDENTICAL":
                return L(
                        "Same meaning",
                        "Anlam aynı"
                );

            default:
                return "";
        }
    }

    private Map<String, Object> verdictLabelI18n(String v) {
        switch (v == null ? "" : v) {
            case "SUBSTANTIVE":    return i18n("Substantive difference", "Esaslı fark");
            case "MINOR":          return i18n("Minor difference",       "Küçük fark");
            case "FORMATTING_ONLY":return i18n("Formatting only",        "Yalnızca biçim");
            case "IDENTICAL":      return i18n("Same meaning",           "Anlam aynı");
            default:               return i18n("", "");
        }
    }

    private String verdictOrStatus(
            String st,
            JSONObject ai,
            String base,
            String other
    ) {

        if ("MISSING_IN_BASELINE".equals(st)) {

            return L(
                    "not in ",
                    "şurada yok: "
            ) + docLabel(base);
        }

        if ("MISSING_IN_OTHER".equals(st)) {

            return L(
                    "not in ",
                    "şurada yok: "
            ) + docLabel(other);
        }

        String v =
                ai == null
                        ? ""
                        : verdictLabel(
                        ai.optString(
                                "verdict"
                        )
                );

        return v.isEmpty()
                ? L(
                "Different",
                "Farklı"
        )
                : v;
    }

    private Map<String, Object> verdictOrStatusI18n(
            String st,
            JSONObject ai,
            String base,
            String other
    ) {
        if ("MISSING_IN_BASELINE".equals(st)) {
            return i18n("not in " + docLabel(base), "şurada yok: " + docLabel(base));
        }
        if ("MISSING_IN_OTHER".equals(st)) {
            return i18n("not in " + docLabel(other), "şurada yok: " + docLabel(other));
        }
        String verdict = ai == null ? "" : ai.optString("verdict", "");
        if (!verdict.isEmpty()) {
            return verdictLabelI18n(verdict);
        }
        return i18n("Different", "Farklı");
    }

    private String kindLabel(String kind) {

        switch (
                kind == null
                        ? ""
                        : kind.toLowerCase()
        ) {

            case "added":
                return L(
                        "Added",
                        "Eklendi"
                );

            case "removed":
                return L(
                        "Removed",
                        "Silindi"
                );

            case "changed":
                return L(
                        "Changed",
                        "Değişti"
                );

            default:
                return kind == null
                        ? ""
                        : kind;
        }
    }

    private Map<String, Object> kindLabelI18n(String kind) {
        switch (kind == null ? "" : kind.toLowerCase()) {
            case "added":   return i18n("Added",   "Eklendi");
            case "removed": return i18n("Removed", "Silindi");
            case "changed": return i18n("Changed", "Değişti");
            default:        String k = kind == null ? "" : kind; return i18n(k, k);
        }
    }

    private String docLabel(String key) {

        if ("signed".equals(key)) {

            return L(
                    "Signed/Scanned",
                    "İmzalı/Taranmış"
            );
        }

        if (key.startsWith("original")) {

            return L(
                    "Original-",
                    "Original-"
            ) + key.substring(
                    "original".length()
            );
        }

        return key;
    }

    private Map<String, Object> docLabelI18n(String key) {
        if ("signed".equals(key)) {
            return i18n("Signed/Scanned", "İmzalı/Taranmış");
        }
        if (key.startsWith("original")) {
            String suffix = key.substring("original".length());
            return i18n("Original-" + suffix, "Original-" + suffix);
        }
        return i18n(key, key);
    }

    private static String anchor(
            String lang,
            String no
    ) {

        return "c-"
                + lang
                + "-"
                + no.replace('.', '_');
    }

    private static String shorten(
            String s,
            int max
    ) {

        if (s == null) {
            return "";
        }

        return s.length() <= max
                ? s
                : s.substring(
                0,
                max - 1
        ).trim() + "…";
    }

    /**
     * Extracts a short, human-readable title from the baseline clause content.
     * Takes the first non-empty line, strips markdown decoration and the leading
     * clause number (e.g. "5." / "5.1 " prefix), then truncates to {@code max} chars.
     */
    private static String clauseTitleFromContent(
            JSONObject clause,
            String lbase,
            String no,
            int max
    ) {
        String raw = clause.optString(lbase + "_content", "").trim();
        if (raw.isEmpty()) {
            return "";
        }
        // Take only the first non-empty line
        String firstLine = raw;
        for (String line : raw.split("\n")) {
            String l = line.replaceAll("[#*_`>\\[\\]()]", "").trim();
            if (!l.isEmpty()) {
                firstLine = l;
                break;
            }
        }
        // Strip leading clause-number prefix like "5." "5.1." "5 " "5.1 "
        String stripped = firstLine.replaceFirst(
                "^" + java.util.regex.Pattern.quote(no) + "\\.?\\s*",
                ""
        ).trim();
        if (stripped.isEmpty()) {
            stripped = firstLine.trim();
        }
        return shorten(stripped, max);
    }

    private static String labelAfterNo(
            String tocLabel,
            String no
    ) {

        if (
                tocLabel == null
                        || tocLabel.isBlank()
        ) {
            return "";
        }

        String prefix = no + " ";

        if (tocLabel.startsWith(prefix)) {
            return tocLabel
                    .substring(
                            prefix.length()
                    )
                    .trim();
        }

        return tocLabel.equals(no)
                ? ""
                : tocLabel;
    }

    private static String buildTocLabel(
            String no,
            String extra,
            int max
    ) {

        if (
                extra == null
                        || extra.isBlank()
        ) {
            return no;
        }

        String clean =
                extra
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        String candidate =
                no + " " + clean;

        return shorten(
                candidate,
                max
        );
    }

    private static JSONObject cmp(
            JSONObject clause,
            String other
    ) {

        JSONObject cmps =
                clause.optJSONObject(
                        "comparisons"
                );

        return cmps == null
                ? null
                : cmps.optJSONObject(other);
    }

    private static List<String> list(
            JSONArray a
    ) {

        List<String> l =
                new ArrayList<>();

        for (int i = 0; i < a.length(); i++) {
            l.add(a.getString(i));
        }

        return l;
    }

    private static Map<String, Object> m(
            Object... kv
    ) {

        Map<String, Object> r =
                new LinkedHashMap<>();

        for (
                int i = 0;
                i + 1 < kv.length;
                i += 2
        ) {

            r.put(
                    (String) kv[i],
                    kv[i + 1]
            );
        }

        return r;
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    public void writePdf(
            String xhtml,
            Path out
    ) throws IOException {

        PdfRendererBuilder b =
                new PdfRendererBuilder();

        b.useFastMode();

        File font =
                findFont(
                        cfg.reportOptions().optString(
                                "fontPath",
                                ""
                        ),
                        REGULAR_FONTS
                );

        File bold =
                findFont(
                        cfg.reportOptions().optString(
                                "fontBoldPath",
                                ""
                        ),
                        BOLD_FONTS
                );

        if (font != null) {

            b.useFont(
                    font,
                    "ReportFont",
                    400,
                    BaseRendererBuilder.FontStyle.NORMAL,
                    true
            );

            if (bold != null) {

                b.useFont(
                        bold,
                        "ReportFont",
                        700,
                        BaseRendererBuilder.FontStyle.NORMAL,
                        true
                );
            }

        } else {

            log.warn(
                    "No TTF font found (report.fontPath): "
                            + "Turkish/non-Latin characters may be missing in the PDF"
            );
        }

        b.withHtmlContent(
                xhtml,
                out.getParent().toUri().toString()
        );

        try (OutputStream os =
                     Files.newOutputStream(out)) {

            b.toStream(os);
            b.run();

        } catch (IOException e) {

            throw e;

        } catch (Exception e) {

            throw new IOException(
                    "PDF rendering failed: "
                            + e.getMessage(),
                    e
            );
        }
    }

    private static File findFont(
            String configured,
            String[] candidates
    ) {

        if (
                configured != null
                        && !configured.isEmpty()
        ) {

            File f =
                    new File(configured);

            return f.isFile()
                    ? f
                    : null;
        }

        for (String c : candidates) {

            File f = new File(c);

            if (f.isFile()) {
                return f;
            }
        }

        return null;
    }
}