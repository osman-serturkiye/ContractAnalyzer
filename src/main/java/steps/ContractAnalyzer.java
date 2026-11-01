package steps;

import ai.Router;
import config.AppConfig;
import docx.DocxReader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Facade: one method per pipeline operation (names follow the process design). */
public final class ContractAnalyzer {
    private final AppConfig cfg;
    private final LangDetector langDetector;
    private final PdfClauseExtractor pdfExtractor;
    private final DocxClauseExtractor extractor;
    private final AiClauseComparer comparer;
    private final SignatureDetector sigDetector;
    private final CrossClauseDependencyAnalyzer dependencyAnalyzer;
    private final Map<File, List<DocxReader.Block>> blockCache = new HashMap<>();

    public ContractAnalyzer(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.langDetector = new LangDetector(cfg, router);
        this.pdfExtractor = new PdfClauseExtractor(cfg, router);
        this.extractor = new DocxClauseExtractor(cfg, router);
        this.comparer = new AiClauseComparer(cfg, router);
        this.sigDetector = new SignatureDetector(cfg, router);
        this.dependencyAnalyzer = new CrossClauseDependencyAnalyzer(cfg, router);
    }

    /**
     * 2+3. signed PDF → clauses for one language: block-aware OCR (real bounding boxes, no pixel guessing) +
     * deterministic text-only segmentation + deterministic crop. Also captures raw per-page OCR responses
     * into {@code ocrPagesOut} so that {@link #signatureDetection} can reuse them without a second API call.
     */
    public JSONObject clauseExtractionPDFSignedDocument(File pdf, String lang,
                                                        List<JSONObject> ocrPagesOut) throws Exception {
        return pdfExtractor.extractWithOcrPages(pdf, lang, ocrPagesOut);
    }

    /**
     * 2b. Guided second-pass extraction for one language.
     *
     * <p>Called after the normal {@link #clauseExtractionPDFSignedDocument} pass when some clause
     * numbers present in original-1 were not found in the signed copy. Returns a JSONObject with
     * shape {@code {"clauses":{...}}} containing only the newly recovered clauses.
     */
    public JSONObject clauseExtractionPDFSignedDocumentGuided(File pdf, String lang,
                                                              List<JSONObject> ocrPagesOut,
                                                              JSONObject firstPassResult,
                                                              java.util.Set<String> missingNos) throws Exception {
        return pdfExtractor.extractGuided(pdf, lang, ocrPagesOut, firstPassResult, missingNos);
    }

    /**
     * 3b. Detect ink signatures in the signed PDF using the OCR pages already collected during
     * {@link #clauseExtractionPDFSignedDocument}.  Returns a JSON array of confirmed signatures
     * (see {@link SignatureDetector} javadoc).  Safe to call with an empty list — returns [] immediately.
     */
    public JSONArray signatureDetection(File pdf, List<JSONObject> ocrPages) throws Exception {
        return sigDetector.detect(pdf, ocrPages);
    }

    /** 4. languages of the original DOCX - deterministic (Lingua). */
    public List<String> langsDetectionDOCOriginalDocument(File docx) throws IOException {
        return langDetector.detectDocx(blocks(docx));
    }

    /** 5. clause extraction of the original DOCX for one language. */
    public JSONObject clauseExtractionDOCOriginalDocument(File docx, String lang) throws IOException {
        return extractor.extract(blocks(docx), lang);
    }

    /** 7. AI review of one clause pair. */
    public JSONObject aiClauseCompare(String lang, String clauseNo, String baseLabel, String baseText,
                                      String otherLabel, String otherText, JSONObject diff,
                                      String otherImageB64, boolean otherIsSignedPdf) throws IOException {
        return comparer.compare(lang, clauseNo, baseLabel, baseText, otherLabel, otherText, diff, otherImageB64, otherIsSignedPdf);
    }

    /**
     * 7b. Tag-only AI call for IDENTICAL clauses: classifies subject-matter without a full diff analysis.
     * Returns {"base_tags":[...], "other_tags":[...]} or empty object if tagging is not configured.
     */
    public JSONObject aiClauseTags(String lang, String clauseNo,
                                   String baseLabel, String baseText,
                                   String otherLabel, String otherText) throws IOException {
        return comparer.tagsOnly(lang, clauseNo, baseLabel, baseText, otherLabel, otherText);
    }

    /**
     * Returns true when clause tagging is enabled in config (clauseTags list is non-empty).
     * Pipeline uses this to skip tag-only calls entirely when tagging is not configured.
     */
    public boolean clauseTagsConfigured() {
        JSONObject stepCfg = cfg.step("aiClauseCompare");
        if (stepCfg == null) return false;
        org.json.JSONArray tags  = stepCfg.optJSONArray("clauseTags");
        org.json.JSONArray units = stepCfg.optJSONArray("clauseUnits");
        return (tags  != null && tags.length()  > 0)
            || (units != null && units.length() > 0);
    }

    /**
     * 8. Cross-clause dependency analysis: finds clauses that share numeric/date values
     * with changed clauses and uses AI to confirm whether real dependencies exist.
     * Skipped silently when {@code steps.crossClauseDependency} is not configured.
     */
    public JSONObject crossClauseDependency(JSONObject analyze, Checkpoint cp) throws IOException {
        return dependencyAnalyzer.analyze(analyze, cp);
    }

    private synchronized List<DocxReader.Block> blocks(File docx) throws IOException {
        List<DocxReader.Block> b = blockCache.get(docx);
        if (b == null) {
            b = DocxReader.read(docx);
            blockCache.put(docx, b);
        }
        return b;
    }
}
