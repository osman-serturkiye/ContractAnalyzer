package steps;

import ai.Router;
import common.GeneralLib;
import config.AppConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * original DOCX(s) (one or more) + signed PDF (optional)  ->  analyze JSON.
 * Report (HTML/PDF) is produced separately by ReportBuilder from the analyze JSON.
 *
 * Order:
 * 1. langsDetectionDOCOriginalDocument   (per DOCX, Lingua, deterministic)  e.g. TR ; EN ; TR+EN
 * 2. clauseExtractionDOCOriginalDocument (per DOCX, per language of that DOCX)
 * 3. clauseExtractionPDFSignedDocument   (signed PDF, for every language found in ANY original: block-aware
 *    OCR with real bounding boxes + deterministic text-only segmentation + deterministic crop - see
 *    PdfClauseExtractor. Replaces the old clauseImageCropPDFSignedDocument + ocrContentClauseImages pair.)
 * 3b. signatureDetection  (signed PDF only, optional step): re-uses the OCR blocks already captured in step 3
 *    to locate ink signature / stamp areas via a vision LLM; result stored under analyze.signatures.
 *    Activated only when config key {@code steps.signaturePageDetection} is present.
 * 4. merge -> 5. diff -> 6. aiClauseCompare
 *
 * The signed PDF may be multilingual. Its languages are the UNION of the languages of all original DOCX files
 * (sozlesme_TR.docx + sozlesme_EN.docx, or a single sozlesme_TR_EN.docx, ...).
 * Baseline of a language = the first original DOCX that contains that language; signed (if given) is compared against it.
 */
public final class Pipeline {
    private static final Logger log = LogManager.getLogger();

    private final AppConfig cfg;
    private final Router router;
    private final ContractAnalyzer az;

    public Pipeline(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
        this.az = new ContractAnalyzer(cfg, router);
    }

    public JSONObject run(File signedPdf, List<File> originals, Path outDir, boolean fresh) throws Exception {
        long tPipeline = System.currentTimeMillis();

        // ---- Pipeline log dosyası: results/pipeline.log
        Path resultsDir = outDir.resolve("results");
        Files.createDirectories(resultsDir);
        Path pipelineLogFile = resultsDir.resolve("pipeline.log");
        startPipelineLogger(pipelineLogFile);

        try {
            return runInternal(signedPdf, originals, outDir, fresh, pipelineLogFile.toString());
        } finally {
            stopPipelineLogger();
        }
    }

    private JSONObject runInternal(File signedPdf, List<File> originals, Path outDir, boolean fresh,
                                   String pipelineLogPath) throws Exception {
        long tPipeline = System.currentTimeMillis();
        int totalDocs = (signedPdf != null ? 1 : 0) + originals.size();
        if (totalDocs == 0) {
            throw new IllegalArgumentException("Give at least one document (signed PDF and/or original DOCX)");
        }
        if (signedPdf != null && originals.isEmpty()) {
            throw new IllegalArgumentException(
                    "A signed PDF needs at least one original DOCX: the languages are detected from the original documents");
        }

        // ---- 0. AI steps needed for THIS input; validated before any processing starts
        JSONObject aiSteps = new JSONObject();
        aiSteps.put("clauseExtractionDOCOriginalDocument", "ai");
        boolean runSignatureDetection = false;
        if (signedPdf != null) {
            // pdfPageBlockOcr is NOT added to aiSteps validation: readable PDFs skip OCR entirely.
            // If OCR is needed at runtime and the step is not configured, router.get() will throw
            // with a clear message. Only the segmentation step is always required for signed PDFs.
            aiSteps.put("clauseSegmentationPDFSignedDocument", "ai");
            // signaturePageDetection is optional: activated only when the step is configured
            if (cfg.step("signaturePageDetection") != null) {
                aiSteps.put("signaturePageDetection", "ai");
                runSignatureDetection = true;
            } else {
                plog("INFO", "Step signaturePageDetection not configured - signature detection skipped");
            }
        }
        if (totalDocs >= 2) {
            aiSteps.put("aiClauseCompare", "ai");
        } else {
            plog("WARN", "Only one document given: nothing to compare, report will only list the extracted clauses");
        }
        router.validate(aiSteps);

        JSONObject usedSteps = new JSONObject();
        usedSteps.put("langsDetectionDOCOriginalDocument", "lingua (deterministic)");
        for (String step : aiSteps.keySet()) {
            usedSteps.put(step, router.get(step).describe());
            plog("INFO", "Step {} -> {}", step, router.get(step).describe());
        }

        Files.createDirectories(outDir);
        Checkpoint cp = new Checkpoint(outDir.resolve("work"), fresh);

        boolean debug = cfg.isDebug();
        int minClauses = Math.max(1, cfg.pipelineInt("minClausesPerLanguage", 1));
        // origDocsParallel: original DOCX files are processed concurrently (default: true).
        // Set to false to process originals one-by-one (parallelism=1 for doc extraction).
        boolean origDocsParallel = cfg.pipelineBoolean("origDocsParallel", true);
        // signedParallel: signed PDF extraction runs concurrently with originals (default: true).
        // Set to false to run signed only after all originals complete (sequential mode).
        boolean signedParallel = cfg.pipelineBoolean("signedParallel", true);
        // docPool size: one thread per original when parallel, 1 when sequential.
        // Signed adds one extra slot only when both signedParallel=true and origDocsParallel=true.
        int docPoolSize = (origDocsParallel ? originals.size() : 1)
                        + (signedPdf != null && signedParallel ? 1 : 0);
        ExecutorService docPool = Executors.newFixedThreadPool(Math.max(1, docPoolSize));

        if (debug) {
            log.info("[DEBUG] Pipeline config — originals: {}, signedPdf: {}, origDocsParallel: {}, signedParallel: {}, docPoolSize: {}, aiClauseCompareParallelism: {}",
                    originals.size(),
                    signedPdf != null ? signedPdf.getName() : "none",
                    origDocsParallel, signedParallel, docPoolSize,
                    cfg.pipelineInt("aiClauseCompareParallelism", 4));
        }

        // ---- Faz A: langDetect — paralel (origDocsParallel=true) veya sıralı (false)
        // Sonuçlar sıralı tutulmalı (original1, original2, ...) — Future listesi index'i korur.
        if (debug) log.info("[DEBUG] Faz A — langDetect başlıyor ({} doküman, parallel={})",
                originals.size(), origDocsParallel);
        long tA = System.currentTimeMillis();
        List<Future<List<String>>> langFutures = new ArrayList<>();
        for (File docx : originals) {
            langFutures.add(docPool.submit(() -> az.langsDetectionDOCOriginalDocument(docx)));
        }

        // Faz A sonuçlarını topla: allLanguages union'ı ve per-doc detected dilleri
        // LinkedHashSet ekleme sırası korunmalı (TR önce gelirse TR önce kalmalı) — sequential toplama yeterli.
        Set<String> allLanguages = new LinkedHashSet<>();
        List<List<String>> detectedPerDoc = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            List<String> detected;
            try {
                detected = langFutures.get(i).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                docPool.shutdownNow();
                throw new IOException("Interrupted during language detection", ie);
            } catch (ExecutionException ee) {
                docPool.shutdownNow();
                Throwable cause = ee.getCause();
                throw new IOException("Language detection failed for " + originals.get(i).getName()
                        + ": " + (cause != null ? cause.getMessage() : "unknown"), cause);
            }
            plog("INFO", "original{} ({}): detected languages {}", i + 1, originals.get(i).getName(), detected);
            detectedPerDoc.add(detected);
            allLanguages.addAll(detected);
        }
        if (debug) log.info("[DEBUG] Faz A tamamlandı — {}ms, allLanguages: {}",
                System.currentTimeMillis() - tA, allLanguages);

        // ---- Faz B: original clauseExtract + signed extract paralel veya sıralı
        // origDocsParallel=true → her original ayrı bir Future olarak pool'a submit edilir.
        // origDocsParallel=false → tek thread'li pool ile sıralı işlenir (pool size=1).
        if (debug) log.info("[DEBUG] Faz B — clauseExtract başlıyor ({} original, parallel={})",
                originals.size(), origDocsParallel);
        long tB = System.currentTimeMillis();
        List<Future<DocAnalysis>> originalFutures = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            final File docx = originals.get(i);
            final String key = "original" + (i + 1);
            final List<String> detected = detectedPerDoc.get(i);
            if (debug) log.info("[DEBUG] {} submit — file: {}, langs: {}", key, docx.getName(), detected);

            originalFutures.add(docPool.submit(() -> {
                long t0 = System.currentTimeMillis();
                JSONObject originalAnalyze = new JSONObject();
                List<String> kept = new ArrayList<>();
                for (String lang : detected) {
                    if (debug) log.info("[DEBUG] {} [{}] clauseExtract başlıyor", key, lang);
                    JSONObject extracted = cp.obj(key + "." + lang + ".extract",
                            () -> az.clauseExtractionDOCOriginalDocument(docx, lang));
                    JSONObject cl = extracted.optJSONObject("clauses");
                    if (cl == null || cl.length() < minClauses) {
                        log.warn("{}: language {} detected but no clauses found - language ignored for this document", key, lang);
                        continue;
                    }
                    if (debug) log.info("[DEBUG] {} [{}] clauseExtract tamamlandı — {} madde", key, lang, cl.length());
                    kept.add(lang);
                    originalAnalyze.put(lang, extracted);
                }
                if (kept.isEmpty()) {
                    if ("original1".equals(key)) {
                        // Esas (kabul edilen) dokümanda hiç madde bulunamadı → süreç durduruluyor.
                        throw new IllegalStateException(
                                "HATA: Esas (kabul edilen) dokümanda (''" + docx.getName()
                                + "'') hiç madde (clause) bulunamadı. Süreç devam edemez.");
                    } else {
                        // orig-2, orig-3, ... için madde bulunamadı → BULGU (süreç devam eder).
                        log.warn("BULGU: '{}' ({}) dokümanında hiç madde bulunamadı — bu doküman karşılaştırmadan çıkarıldı.",
                                key, docx.getName());
                        originalAnalyze.put("languages", new JSONArray());
                        return DocAnalysis.from(key, originalAnalyze);
                    }
                }
                originalAnalyze.put("languages", new JSONArray(kept));
                if (debug) log.info("[DEBUG] {} tamamlandı — {}ms, langs: {}", key,
                        System.currentTimeMillis() - t0, kept);
                return DocAnalysis.from(key, originalAnalyze);
            }));
        }

        // signed PDF extract: allLanguages union'ı artık hazır.
        // signedParallel=true  → original clauseExtract'larla eş zamanlı pool'a submit edilir.
        // signedParallel=false → original'lar bittikten sonra (aşağıda) sıralı çalıştırılır.
        Future<DocAnalysis> signedFuture = null;
        // ocrPages: signed Future'ın içinde doldurulur; signature detection bu Future bittikten sonra çalışır.
        final List<JSONObject> ocrPages = new ArrayList<>();
        if (signedPdf != null && signedParallel) {
            signedFuture = submitSignedExtract(docPool, signedPdf, allLanguages, ocrPages,
                    runSignatureDetection, cp);
        }

        // Faz B sonuçlarını topla — sıra korunmalı (original1, original2, ..., signed)
        List<DocAnalysis> originalDocs = new ArrayList<>();
        for (int i = 0; i < originalFutures.size(); i++) {
            try {
                originalDocs.add(originalFutures.get(i).get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                docPool.shutdownNow();
                throw new IOException("Interrupted during clause extraction", ie);
            } catch (ExecutionException ee) {
                docPool.shutdownNow();
                Throwable cause = ee.getCause();
                throw new IOException("Clause extraction failed for original" + (i + 1)
                        + ": " + (cause != null ? cause.getMessage() : "unknown"), cause);
            }
        }
        docPool.shutdown();
        if (debug) log.info("[DEBUG] Faz B original'lar tamamlandı — {}ms", System.currentTimeMillis() - tB);

        // originals first so that original1 becomes the baseline for every language;
        // signed (if present) is appended and compared against the original baseline.
        List<DocAnalysis> docs = new ArrayList<>(originalDocs);
        if (signedPdf != null && !signedParallel) {
            // sequential mode: signed starts only after all originals are done
            plog("INFO", "Signed PDF extraction running sequentially (signedParallel=false)");
            signedFuture = submitSignedExtract(
                    Executors.newSingleThreadExecutor(), signedPdf, allLanguages,
                    ocrPages, runSignatureDetection, cp);
        }
        if (signedFuture != null) {
            if (debug) log.info("[DEBUG] Signed PDF Future bekleniyor...");
            long tS = System.currentTimeMillis();
            DocAnalysis signedDoc;
            try {
                signedDoc = signedFuture.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during signed PDF extraction", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                throw new IOException("Signed PDF extraction failed: "
                        + (cause != null ? cause.getMessage() : "unknown"), cause);
            }
            if (debug) log.info("[DEBUG] Signed PDF tamamlandı — {}ms", System.currentTimeMillis() - tS);

            // ---- Guided pass: recover clauses missing from pass-1 using original-1 numbers as hints
            // originalDocs.get(0) is always original1 (guaranteed by the clause-check above).
            // ocrPages is populated by the first signed extraction (empty for native PDFs — that is fine).
            boolean guidedEnabled = cfg.pipelineBoolean("signedGuidedPass", true);
            if (guidedEnabled && !originalDocs.isEmpty() && signedPdf != null) {
                DocAnalysis orig1 = originalDocs.get(0);
                long tG = System.currentTimeMillis();
                int totalRecovered = 0;
                for (String lang : orig1.languages) {
                    Map<String, String> orig1Clauses = orig1.content.get(lang);
                    if (orig1Clauses == null || orig1Clauses.isEmpty()) continue;

                    Map<String, String> signedClauses = signedDoc.content.get(lang);
                    java.util.Set<String> signedNos = signedClauses == null
                            ? java.util.Collections.emptySet()
                            : signedClauses.keySet();

                    java.util.Set<String> missingNos = new java.util.LinkedHashSet<>();
                    for (String no : orig1Clauses.keySet()) {
                        if (!signedNos.contains(no)) missingNos.add(no);
                    }

                    if (missingNos.isEmpty()) {
                        if (debug) log.info("[DEBUG] Guided pass [{}]: no missing clauses", lang);
                        continue;
                    }
                    plog("INFO", "Signed PDF guided pass [{}]: {} missing clause(s): {}", lang, missingNos.size(), missingNos);

                    // firstPassResult: the cached extraction JSONObject for this language
                    final String langFinal = lang;
                    final java.util.Set<String> missingFinal = missingNos;
                    JSONObject firstPassResult = cp.obj("signed." + lang + ".extract",
                            () -> { throw new IOException("pass-1 cache missing for " + langFinal); });

                    JSONObject guidedResult = cp.obj("signed." + lang + ".extract.guided",
                            () -> {
                                try {
                                    return az.clauseExtractionPDFSignedDocumentGuided(
                                            signedPdf, langFinal, ocrPages, firstPassResult, missingFinal);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });

                    JSONObject guidedClauses = guidedResult.optJSONObject("clauses");
                    if (guidedClauses == null || guidedClauses.length() == 0) {
                        if (debug) log.info("[DEBUG] Guided pass [{}]: no additional clauses found", lang);
                        continue;
                    }

                    // Merge guided clauses into signedDoc (only add, never overwrite pass-1 results)
                    Map<String, String> contentMap = signedDoc.content.computeIfAbsent(lang,
                            k -> new java.util.LinkedHashMap<>());
                    Map<String, String> imageMap = signedDoc.image.computeIfAbsent(lang,
                            k -> new java.util.LinkedHashMap<>());
                    int recovered = 0;
                    for (String no : guidedClauses.keySet()) {
                        if (contentMap.containsKey(no)) continue;   // pass-1 wins
                        JSONObject gc = guidedClauses.optJSONObject(no);
                        if (gc == null) continue;
                        String content = gc.optString("signed_content", gc.optString("content", ""));
                        if (!content.isEmpty()) {
                            contentMap.put(no, content);
                            String img = gc.optString("signed_image", "");
                            if (!img.isEmpty()) imageMap.put(no, img);
                            recovered++;
                        }
                    }
                    totalRecovered += recovered;
                    if (!signedDoc.languages.contains(lang) && !contentMap.isEmpty()) {
                        signedDoc.languages.add(lang);
                    }
                    plog("INFO", "Signed PDF guided pass [{}]: {}/{} missing clause(s) recovered",
                            lang, recovered, missingNos.size());
                }
                if (debug) log.info("[DEBUG] Guided pass tamamlandı — {}ms, {} madde kurtarıldı",
                        System.currentTimeMillis() - tG, totalRecovered);
            }

            docs.add(signedDoc);
        }

        // ---- İş Kuralı: non-baseline dokümanlar clause kontrolü
        // Tüm non-baseline dokümanlarda (orig-2, orig-3, ... ve signed) hiçbirinde clause yoksa → HATA.
        // Bireysel olarak clause bulunamayan her doküman için → BULGU (uyarı).
        // Not: original1 (baseline) zaten Faz B'de kontrol edildi; buraya gelindiyse en az 1 dil+clause içeriyor.
        if (docs.size() > 1) {
            List<DocAnalysis> nonBaseline = docs.subList(1, docs.size());
            boolean anyNonBaselineHasClauses = nonBaseline.stream()
                    .anyMatch(d -> d.languages != null && !d.languages.isEmpty());
            for (DocAnalysis d : nonBaseline) {
                boolean hasClauses = d.languages != null && !d.languages.isEmpty();
                if (!hasClauses) {
                    log.warn("BULGU: '{}' dokümanında hiç madde bulunamadı.", d.key);
                }
            }
            if (!anyNonBaselineHasClauses) {
                throw new IllegalStateException(
                        "HATA: Karşılaştırılacak dokümanların (orig-2/3/4... ve imzalı PDF) hiçbirinde madde (clause) bulunamadı. Süreç devam edemez.");
            }
        }

        // ---- 4. merge
        if (debug) log.info("[DEBUG] Merge başlıyor — {} doküman", docs.size());
        long tMerge = System.currentTimeMillis();
        JSONObject analyze = new AnalyzeMerger(cfg.pipelineDouble("renumberMatchThreshold", 0.85)).merge(docs);
        if (debug) log.info("[DEBUG] Merge tamamlandı — {}ms, languages: {}", System.currentTimeMillis() - tMerge,
                analyze.optJSONArray("languages"));
        analyze.put("meta", new JSONObject()
                .put("generated", OffsetDateTime.now().toString())
                .put("steps", usedSteps));

        // ---- 5. deterministic diff, 6. AI compare
        if (totalDocs >= 2) {
            if (debug) log.info("[DEBUG] Diff başlıyor...");
            long tDiff = System.currentTimeMillis();
            diffAll(analyze);
            if (debug) log.info("[DEBUG] Diff tamamlandı — {}ms", System.currentTimeMillis() - tDiff);

            if (debug) log.info("[DEBUG] AI compare başlıyor...");
            long tAi = System.currentTimeMillis();
            aiCompareAll(analyze, cp);
            if (debug) log.info("[DEBUG] AI compare tamamlandı — {}ms", System.currentTimeMillis() - tAi);
        }

        // ---- 7. cross-clause dependency analysis (regex candidates → AI, optional)
        if (totalDocs >= 2) {
            if (debug) log.info("[DEBUG] Cross-clause dependency analizi başlıyor...");
            long tDep = System.currentTimeMillis();
            analyze = az.crossClauseDependency(analyze, cp);
            if (debug) log.info("[DEBUG] Cross-clause dependency tamamlandı — {}ms", System.currentTimeMillis() - tDep);
        }

        if (debug) log.info("[DEBUG] Pipeline tamamlandı — toplam: {}ms", System.currentTimeMillis() - tA);
        router.usageStats().logSummary(System.currentTimeMillis() - tPipeline);
        return analyze;
    }

    // ------------------------------------------------------------------ signed extract helper

    /** Submits the signed PDF extraction (all languages + optional signature detection) to the given executor.
     *  Works for both parallel mode (docPool) and sequential mode (single-thread executor). */
    private Future<DocAnalysis> submitSignedExtract(ExecutorService executor,
                                                     File pdf,
                                                     Set<String> allLanguages,
                                                     List<JSONObject> ocrPages,
                                                     boolean detectSigs,
                                                     Checkpoint cp) {
        final List<String> langs = new ArrayList<>(allLanguages);
        final boolean debug = cfg.isDebug();
        plog("INFO", "Signed PDF languages (from original documents): {}", langs);
        return executor.submit(() -> {
            long t0 = System.currentTimeMillis();
            if (debug) log.info("[DEBUG] Signed PDF extraction başlıyor — langs: {}, file: {}", langs, pdf.getName());
            JSONObject signedAnalyze = new JSONObject();
            List<String> signedLangs = new ArrayList<>();
            // ocrPages: captured on the first language pass; subsequent languages reuse the checkpoint
            // cache and do NOT re-render pages, so we only need to capture once.
            for (int li = 0; li < langs.size(); li++) {
                final String lang = langs.get(li);
                final boolean captureOcr = (li == 0);
                if (debug) log.info("[DEBUG] Signed PDF [{}] clauseExtract başlıyor (captureOcr={})", lang, captureOcr);
                JSONObject extracted = cp.obj("signed." + lang + ".extract",
                        () -> {
                            try {
                                return az.clauseExtractionPDFSignedDocument(pdf, lang,
                                        captureOcr ? ocrPages : null);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                JSONObject cl = extracted.optJSONObject("clauses");
                if (cl == null || cl.length() == 0) {
                    log.warn("Signed PDF: no clauses found for language {} - language skipped", lang);
                    continue;
                }
                if (debug) log.info("[DEBUG] Signed PDF [{}] clauseExtract tamamlandı — {} madde", lang, cl.length());
                signedAnalyze.put(lang, extracted);
                signedLangs.add(lang);
            }
            if (signedLangs.isEmpty()) {
                log.warn("BULGU: İmzalı PDF'de ('{}') hiçbir dilde madde bulunamadı — imzalı doküman karşılaştırmadan çıkarıldı.",
                        pdf.getName());
            }
            signedAnalyze.put("languages", new JSONArray(signedLangs));

            // 3b. signature detection (optional) — runs after all language extractions so ocrPages is full
            if (detectSigs) {
                if (debug) log.info("[DEBUG] Signed PDF imza tespiti başlıyor — ocrPages: {}", ocrPages.size());
                JSONArray signatures = cp.arr("signed.signatures",
                        () -> {
                            try {
                                return az.signatureDetection(pdf, ocrPages);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                log.info("Signature detection: {} signature(s) found", signatures.length());
                signedAnalyze.put("signatures", signatures);
            }
            if (debug) log.info("[DEBUG] Signed PDF tamamlandı — {}ms, langs: {}", System.currentTimeMillis() - t0, signedLangs);
            return DocAnalysis.from("signed", signedAnalyze);
        });
    }

    // ------------------------------------------------------------------ diff

    private void diffAll(JSONObject analyze) {
        for (String lang : languages(analyze)) {
            String base = baselineOf(analyze, lang);
            List<String> others = otherDocs(analyze, base, lang);
            JSONObject clauses = analyze.getJSONObject(lang).getJSONObject("clauses");
            for (String no : clauses.keySet()) {
                JSONObject c = clauses.getJSONObject(no);
                JSONObject cmps = new JSONObject();
                for (String other : others) {
                    String a = c.optString(base + "_content", null);     // baseline text
                    String b = c.optString(other + "_content", null);    // other document text
                    JSONObject cmp = new JSONObject();
                    if (a == null && b == null) {
                        continue;
                    } else if (a == null) {
                        cmp.put("status", "MISSING_IN_BASELINE");
                        cmp.put("missingDocument", base);
                        cmp.put("presentDocument", other);
                    } else if (b == null) {
                        cmp.put("status", "MISSING_IN_OTHER");
                        cmp.put("missingDocument", other);
                        cmp.put("presentDocument", base);
                    } else {
                        JSONObject d = ClauseDiffer.diff(a, b, no);
                        cmp.put("status", d.getBoolean("equal") ? "IDENTICAL" : "DIFFERENT");
                        cmp.put("similarity", d.getDouble("similarity"));
                        cmp.put("ops", d.getJSONArray("ops"));
                        cmp.put("html", d.getString("html"));
                        cmp.put("html_base", d.getString("html_base"));
                        cmp.put("html_other", d.getString("html_other"));
                    }
                    cmps.put(other, cmp);
                }
                c.put("comparisons", cmps);
            }
        }
    }

    private void aiCompareAll(JSONObject analyze, Checkpoint cp) throws IOException {
        // Check if clause tagging is configured — if not, skip all tag-only calls
        boolean tagsConfigured = az.clauseTagsConfigured();
        boolean debug = cfg.isDebug();

        // aiClauseCompareParallelism: AI compare thread count (default: 4).
        int par = Math.max(1, cfg.pipelineInt("aiClauseCompareParallelism", 4));
        if (debug) log.info("[DEBUG] aiCompareAll — aiClauseCompareParallelism:{}", par);
        ExecutorService ex = Executors.newFixedThreadPool(par);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String lang : languages(analyze)) {
                final String base = baselineOf(analyze, lang);
                JSONObject clauses = analyze.getJSONObject(lang).getJSONObject("clauses");
                for (String no : clauses.keySet()) {
                    JSONObject c = clauses.getJSONObject(no);
                    JSONObject cmps = c.optJSONObject("comparisons");
                    if (cmps == null) {
                        continue;
                    }
                    for (String other : cmps.keySet()) {
                        JSONObject cmp = cmps.getJSONObject(other);
                        String status = cmp.optString("status");

                        if ("DIFFERENT".equals(status)
                                || "MISSING_IN_OTHER".equals(status)
                                || "MISSING_IN_BASELINE".equals(status)) {
                            // Full AI compare (verdict + risk + differences + tags)
                            final String baseText  = c.optString(base + "_content", "");
                            final String otherText = c.optString(other + "_content", "");
                            final String otherImage = c.optString(other + "_image", null);
                            futures.add(ex.submit(() -> {
                                if (debug) log.info("[DEBUG] AI compare — lang:{} clause:{} {}>>{} status:{}",
                                        lang, no, base, other, status);
                                JSONObject ai = cp.obj("ai." + lang + "." + no + "." + other,
                                        () -> az.aiClauseCompare(lang, no,
                                                docLabel(base), baseText,
                                                docLabel(other), otherText,
                                                cmp, otherImage,
                                                "signed".equals(other)));
                                if (debug) log.info("[DEBUG] AI compare tamamlandı — lang:{} clause:{} verdict:{}",
                                        lang, no, ai.optString("verdict", "?"));
                                cmp.put("ai", ai);
                                return null;
                            }));

                        } else if ("IDENTICAL".equals(status) && tagsConfigured) {
                            // Tag-only call: clause texts are identical, just classify subject matter.
                            // Applies to all comparison pairs: original→signed and original→original.
                            final String baseText  = c.optString(base + "_content", "");
                            final String otherText = c.optString(other + "_content", "");
                            futures.add(ex.submit(() -> {
                                if (debug) log.info("[DEBUG] AI tags — lang:{} clause:{} {}>>{}",
                                        lang, no, base, other);
                                JSONObject tags = cp.obj("tags." + lang + "." + no + "." + other,
                                        () -> az.aiClauseTags(lang, no,
                                                docLabel(base), baseText,
                                                docLabel(other), otherText));
                                cmp.put("ai", tags);
                                return null;
                            }));
                        }
                    }
                }
            }
            plog("INFO", "AI compare: {} clause pairs queued", futures.size());
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ie);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    String msg = cause == null ? "unknown"
                            : (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                    throw new IOException("AI compare failed: " + msg, cause);
                }
            }
        } finally {
            ex.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String docLabel(String key) {
        if ("signed".equals(key)) return "İmzalı/Taranmış";
        if (key != null && key.startsWith("original")) return "Orjinal-" + key.substring("original".length());
        return key;
    }

    private static List<String> toList(JSONArray a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            l.add(a.getString(i));
        }
        return l;
    }

    private static List<String> languages(JSONObject analyze) {
        return toList(analyze.getJSONArray("languages"));
    }

    private static String baselineOf(JSONObject analyze, String lang) {
        JSONObject b = analyze.optJSONObject("baselines");
        return b == null ? analyze.getString("baseline") : b.optString(lang, analyze.getString("baseline"));
    }

    /** Documents that contain the language, without the baseline. */
    private static List<String> otherDocs(JSONObject analyze, String base, String lang) {
        Set<String> s = new LinkedHashSet<>(toList(analyze.getJSONArray("documents")));
        s.remove(base);
        List<String> out = new ArrayList<>();
        for (String d : s) {
            if (hasLang(analyze, d, lang)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean hasLang(JSONObject analyze, String doc, String lang) {
        return toList(analyze.getJSONObject("document_languages").getJSONArray(doc)).contains(lang);
    }

    // ------------------------------------------------------------------ pipeline file logger

    private static final String APPENDER_NAME = "PipelineFileAppender";
    private static final String LOG_PATTERN   = "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n";

    // WriterAppender ve PrintWriter instance'ları — pipeline süresi boyunca tutulur
    private PrintWriter  pipelineWriter;
    private WriterAppender pipelineAppender;

    /** log.info + pipeline log dosyasına doğrudan yaz */
    private void plog(String level, String fmt, Object... args) {
        // Log4j API çağrısı (seviyeye göre)
        if ("WARN".equals(level))       log.warn(fmt, args);
        else if ("ERROR".equals(level)) log.error(fmt, args);
        else                            log.info(fmt, args);
        // Doğrudan writer'a yaz (Log4j'den bağımsız garanti)
        PrintWriter pw = pipelineWriter;
        if (pw == null) return;
        String ts  = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        // org.slf4j tarzı {} parametresini manuel olarak değiştir
        String msg = fmt;
        for (Object a : args) {
            int idx = msg.indexOf("{}");
            if (idx >= 0) msg = msg.substring(0, idx) + a + msg.substring(idx + 2);
        }
        pw.println(ts + " " + level + " Pipeline - " + msg);
        pw.flush();
    }

    /**
     * Log4j 2'ye runtime'da WriterAppender ekler.
     * <p>
     * Neden WriterAppender + PrintWriter?
     * <ul>
     *   <li>FileAppender.newBuilder().withFileName() Log4j 2 konfigürasyon bootstrap'ına bağlıdır;
     *       agent-server classloader hiyerarşisinde {@code getContext(false)} yanlış context döndürebilir.</li>
     *   <li>WriterAppender bir {@link java.io.Writer} kabul eder — classloader'dan bağımsız,
     *       saf Java I/O katmanında çalışır.</li>
     *   <li>Her iki context de denenir (getContext(false) + getContext(true)) böylece
     *       hangi classloader'da çalışılırsa çalışılsın appender eklenir.</li>
     * </ul>
     */
    private void startPipelineLogger(Path logFilePath) {
        try {
            pipelineWriter = new PrintWriter(new FileWriter(logFilePath.toFile(), false), true);

            // 1. GeneralLib._info/_warn/_error metodlarına doğrudan yaz (Log4j'den bağımsız)
            GeneralLib.pipelineLog = pipelineWriter;

            // 2. Log4j appender: Pipeline class'ının kendi logger'ının context'ini kullan —
            //    bu agent-server ortamında en güvenilir yol.
            LoggerContext ctx = (LoggerContext) ((org.apache.logging.log4j.core.Logger) log).getContext();
            addAppenderToContext(ctx, pipelineWriter);

            // Fallback: getContext(false) ve getContext(true) de dene (farklı classloader senaryoları için)
            addAppenderToContext(LogManager.getContext(false), pipelineWriter);
            LoggerContext ctx2 = (LoggerContext) LogManager.getContext(true);
            if (ctx2 != LogManager.getContext(false)) {
                addAppenderToContext(ctx2, pipelineWriter);
            }

            // Başlangıç kaydı — doğrudan writer üzerinden
            pipelineWriter.println("=== Pipeline log started: " + logFilePath + " ===");
            pipelineWriter.flush();
        } catch (Exception e) {
            log.warn("Pipeline log başlatılamadı [{}]: {}", logFilePath, e.getMessage(), e);
            if (pipelineWriter != null) {
                pipelineWriter.println("WARN: Log4j appender eklenemedi: " + e.getMessage());
                pipelineWriter.flush();
            }
        }
    }

    private void addAppenderToContext(Object rawCtx, PrintWriter writer) {
        if (!(rawCtx instanceof LoggerContext)) return;
        try {
            LoggerContext ctx = (LoggerContext) rawCtx;
            Configuration config = ctx.getConfiguration();

            // Aynı isimde appender zaten varsa önce kaldır
            if (config.getAppender(APPENDER_NAME) != null) {
                config.getRootLogger().removeAppender(APPENDER_NAME);
                config.getAppenders().remove(APPENDER_NAME);
            }

            PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern(LOG_PATTERN)
                    .withConfiguration(config)
                    .build();

            WriterAppender appender = WriterAppender.newBuilder()
                    .setName(APPENDER_NAME)
                    .setTarget(writer)
                    .setLayout(layout)
                    .setConfiguration(config)
                    .build();

            appender.start();
            config.addAppender(appender);
            config.getRootLogger().addAppender(appender, Level.DEBUG, null);
            ctx.updateLoggers();

            // Hangi context'e eklendiğini kaydet (ilk başarılı olan)
            if (pipelineAppender == null) {
                pipelineAppender = appender;
            }
        } catch (Exception e) {
            log.warn("addAppenderToContext başarısız: {}", e.getMessage());
        }
    }

    /** Appender'ı kaldırır, writer'ı kapatır. */
    private void stopPipelineLogger() {
        // GeneralLib pipeline log referansını temizle
        GeneralLib.pipelineLog = null;
        // Tüm context'lerden kaldır
        try {
            removeAppenderFromContext(
                    (LoggerContext) ((org.apache.logging.log4j.core.Logger) log).getContext());
        } catch (Exception ignored) {}
        removeAppenderFromContext(LogManager.getContext(false));
        try {
            LoggerContext ctx2 = (LoggerContext) LogManager.getContext(true);
            if (ctx2 != LogManager.getContext(false)) removeAppenderFromContext(ctx2);
        } catch (Exception ignored) {}

        if (pipelineWriter != null) {
            pipelineWriter.println("=== Pipeline log closed ===");
            pipelineWriter.flush();
            pipelineWriter.close();
            pipelineWriter   = null;
            pipelineAppender = null;
        }
    }

    private void removeAppenderFromContext(Object rawCtx) {
        if (!(rawCtx instanceof LoggerContext)) return;
        try {
            LoggerContext ctx = (LoggerContext) rawCtx;
            Configuration config = ctx.getConfiguration();
            if (config.getAppender(APPENDER_NAME) != null) {
                config.getRootLogger().removeAppender(APPENDER_NAME);
                org.apache.logging.log4j.core.Appender a = config.getAppenders().remove(APPENDER_NAME);
                if (a != null) a.stop();
                ctx.updateLoggers();
            }
        } catch (Exception e) {
            log.warn("removeAppenderFromContext başarısız: {}", e.getMessage());
        }
    }
}
