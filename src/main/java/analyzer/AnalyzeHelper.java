package analyzer;

import ai.Router;

import common.GeneralLib;
import config.AppConfig;
import org.json.JSONObject;
import org.json.JSONTokener;
import report.ReportBuilder;
import steps.Pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalyzeHelper {
    public static JSONObject config  = (new JSONObject("{}"));
    public static JSONObject cfgSection = (new JSONObject("{}"))
            .put("pipeline", "ContractAnalyzer_Pipeline")
            .put("providers", "ContractAnalyzer_Providers")
            .put("steps", "ContractAnalyzer_Steps")
            .put("report-options", "ContractAnalyzer_ReportOptions")
            .put("report-assets", "ContractAnalyzer_ReportAssets");

    public static JSONObject loadConfig() throws Exception {
        if(config.names() != null && !config.names().isEmpty()){return config;}
        config = new JSONObject("{}");

        for(String section : cfgSection.keySet()){
            String gvlName = cfgSection.optString(section, "");
            if(gvlName.isBlank()){continue;}

            JSONObject gvl = GeneralLib.getOptions(gvlName, "name", "value");
            for(String g : gvl.keySet()){
                String gv = gvl.optString(g,"\"\"");
                gvl.put(g, (new JSONTokener(gv)).nextValue());
            }

            config.put(section, gvl);
        }
        return config;
    }
    public static JSONObject contractAnalyze(String path, List<String> originals) throws Exception {
        return contractAnalyze(path, originals, null, false);
    }
    public static JSONObject contractAnalyze(String path, String docx, String signed) throws Exception {
        return contractAnalyze(path, List.of(docx), signed, false);
    }
    public static JSONObject contractAnalyze(String path, List<String> originals, String signed, boolean fresh) throws Exception {

        GeneralLib._info("Original documents: " + originals + " ; signed: " + signed);
        AppConfig cfg = new AppConfig(loadConfig());
        Path input = Paths.get(path);
        Files.createDirectories(input);

        Path output = Paths.get(path + "results/");
        Files.createDirectories(output);

        JSONObject analyze;
        Router router = new Router(cfg);
        Pipeline pipeline = new Pipeline(cfg, router);
        List<File> docx = new ArrayList<>();
        for (String o : originals) {
            docx.add(GeneralLib.fileExists(o));
        }
        analyze = pipeline.run(signed == null ? null : GeneralLib.fileExists(signed), docx, input, fresh);
        Files.writeString(output.resolve("analyze.json"), analyze.toString(2));
        Files.writeString(output.resolve("config.json"), loadConfig().toString(2));
        GeneralLib._info("Wrote " + output.resolve("analyze.json"));

        // ── OCR data export ──────────────────────────────────────────────────────────
        // Copy raw OCR results (signed extract checkpoint containing per-page OCR blocks)
        // from work/ to results/ocr-data/ for diagnostics and audit.
        try {
            Path ocrDataDir = output.resolve("ocr-data");
            Files.createDirectories(ocrDataDir);
            Path workDir = input.resolve("work");
            if (Files.isDirectory(workDir)) {
                // signed.*.extract.json files contain the OCR page blocks
                try (java.util.stream.Stream<Path> stream = Files.list(workDir)) {
                    stream.filter(p -> p.getFileName().toString().matches("signed\\..*\\.extract.*\\.json"))
                          .forEach(src -> {
                              try {
                                  Files.copy(src, ocrDataDir.resolve(src.getFileName()),
                                          StandardCopyOption.REPLACE_EXISTING);
                              } catch (IOException e) {
                                  GeneralLib._info("OCR data copy warning: " + e.getMessage());
                              }
                          });
                }
                // Also export original extract checkpoints
                try (java.util.stream.Stream<Path> stream = Files.list(workDir)) {
                    stream.filter(p -> p.getFileName().toString().matches("original.*\\.extract\\.json"))
                          .forEach(src -> {
                              try {
                                  Files.copy(src, ocrDataDir.resolve(src.getFileName()),
                                          StandardCopyOption.REPLACE_EXISTING);
                              } catch (IOException e) {
                                  GeneralLib._info("OCR data copy warning: " + e.getMessage());
                              }
                          });
                }
            }
            GeneralLib._info("OCR data exported to: " + ocrDataDir);
        } catch (Exception e) {
            GeneralLib._info("OCR data export failed (non-critical): " + e.getMessage());
        }


        ReportBuilder rb = new ReportBuilder(cfg);
        Files.writeString(output.resolve("report.html"), rb.buildHtml(analyze));        // interactive (default lang)
        GeneralLib._info("Report HTML written: " + output.resolve("report.html"));

        // Produce one PDF per language (TR + EN) so each audience gets a fully localised copy.
        // withLang() deep-copies the config and overrides only defaultLang — pipeline/AI settings untouched.
        for (String lang : List.of("tr", "en")) {
            ReportBuilder rbLang = new ReportBuilder(cfg.withLang(lang));
            String pdfName = "report_" + lang.toUpperCase() + ".pdf";
            rbLang.writePdf(rbLang.buildPrintHtml(analyze), output.resolve(pdfName));
            GeneralLib._info("PDF written (" + lang.toUpperCase() + "): " + output.resolve(pdfName));
        }

        Map<String, Object> model = rb.buildModel(analyze, false);

        // Save the report view-model as analyze-model.json alongside the other outputs
        String modelJson = new JSONObject(model).toString(2);
        Files.writeString(output.resolve("analyze-model.json"), modelJson);
        GeneralLib._info("Wrote " + output.resolve("analyze-model.json"));

        return new JSONObject("{}")
                .put("analyzeJSON",      output.resolve("analyze.json").toString())
                .put("reportHTML",       output.resolve("report.html").toString())
                .put("reportPDF_TR",     output.resolve("report_TR.pdf").toString())
                .put("reportPDF_EN",     output.resolve("report_EN.pdf").toString())
                .put("analyzeModelJSON", output.resolve("analyze-model.json").toString())
                .put("analyzeModel",     model);
    }
}
