# ContractAnalyzer — Codebase Architecture & Clause Extraction

## Genel Bakış

ContractAnalyzer, sözleşme dokümanlarını (DOCX + PDF) clause bazında analiz eden bir Java/Gradle projesidir. Pipeline, orijinal DOCX ile imzalı PDF'leri clause bazında karşılaştırır ve farkları raporlar.

## Clause Extraction Sistemi — İki Paralel Yol

### 1. Canlı Pipeline (LLM destekli) — gerçek dokümanlarda çalışan
- `steps/DocxClauseExtractor.java` — DOCX için, LLM hangi blokların hangi maddeye ait olduğuna karar verir, metin birebir kopyalanır (hallucination yok). `ClauseNumbering.classify` kullanır, sadece INVALID reddeder (RESET/BACKWARD kabul edilir).
- `steps/PdfClauseExtractor.java` — PDF için iki mod:
  - **Native text** (`segmentNative`): PDFBox'tan gerçek bbox alır, AI yok. Kendi regex'i `DEFAULT_CLAUSE_HEADING` (7 grup).
  - **OCR modu** (`segmentAndCrop`): LLM destekli, `ClauseNumbering.classify` kullanır.

### 2. Deterministik Motor (`extraction.*`) — sadece Selftest'te kullanılıyor
- `extraction/ClauseNumbering.java` — tek doğruluk kaynağı: numara parse (`HEADING` regex, 11 grup) + ardıllık doğrulaması (`classify`: VALID/RESET/DUPLICATE/BACKWARD/INVALID) + yasal atıf guard (`isLegalReference`) + artifact temizleme (`stripArtifactPrefix`)
- `extraction/ClauseTree.java` — blok listesini segmentlere böler. RESET/BACKWARD → yeni segment. `ensureParents` ile eksik parent'ları tamamlar.
- `extraction/ClauseExtraction.java` — adapter: `fromDocxBlocks` (LineJoiner yok), `fromPdfLines` (LineJoiner var), `fromOcrBlocks` (LineJoiner yok). `buildResult` her zaman boş title döndürür.
- `extraction/ClauseExtractionValidator.java` — kalite kontrolü. `hasBlocker` ama canlı pipeline'da çağrılmıyor.
- `extraction/Selftest.java` — 9 senaryo / 37 check. Deterministik motoru test eder.

### 3. Paylaşılan bileşen
- `extraction/ClauseNumbering` her iki yolun da kullandığı tek paylaşılan bileşen.
- `util/ClauseNumber.java` — köprü: `isValidNextClause` → `ClauseNumbering.classify` (sadece VALID|DUPLICATE → true).

## Hiyerarşik Numaralandırma Kuralı (kullanıcının tanımı)

```
5   → sonra 5.1 veya 6 gelir
5.1 → sonra 5.1.1 veya 5.2 gelir
```

Bu kural `ClauseNumbering.classify` metodunda uygulanır:
- Aynı derinlik: prefix eşleşmeli, diff 0<diff≤MAX_SKIP(20) → VALID
- Bir seviye derin: prefix = current tam, yeni bileşen = 1 → VALID
- Seviye yukarı: shared prefix eşleşmeli, diff kontrolü
- İlk madde: first-component ≤ 20 → VALID

## Tespit Edilen Zayıf Noktalar

| # | Sorun | Dosya | Etki |
|---|-------|------|------|
| 1 | `segmentNative` kendi regex'ini kullanıyor (`DEFAULT_CLAUSE_HEADING`, 7 grup) — `ClauseNumbering.HEADING`'den dar (Arapça المادة, trailing-paren, standalone Roman eksik) | `PdfClauseExtractor.java:542-590` | Bu formatlardaki PDF'lerde madde bulunamıyor |
| 2 | `segmentNative` `isLegalReference` çağırmıyor — "3. maddede belirtilen" sahte madde açıyor | `PdfClauseExtractor.java:593-725` | Yasal atıflar madde sanılıyor |
| 3 | `segmentNative` `isValidNextClause` köprüsünü kullanıyor — RESET/BACKWARD reddediliyor, EK bölümleri kayboluyor | `PdfClauseExtractor.java:620` | EK/Schedule bölümleri siliniyor |
| 4 | Deterministik motor canlı pipeline'a bağlı değil — validator çalışmıyor | genel | Kalite kontrolü devre dışı |
| 5 | `MAX_SKIP=20` ve "ilk madde ≤20" sabit eşikleri | `ClauseNumbering.java:266` | Büyük atlamalar RESET'e dönüşüyor |
| 6 | Parantezli alt madde numaralandırması pozisyonel, semantik değil | `ClauseTree.java:233-264` | Karışık (1)/(a)/(i) serisi tek sayaçla numaralanıyor |
| 7 | `ClauseExtraction.buildResult` her zaman boş title döndürür | `ClauseExtraction.java:150` | Deterministik yolda başlık yok |
| 8 | Synthetic-parent ekleme sırası child'dan sonra; COMPARATOR sıralamasına bağımlı | `ClauseTree.java:383-400` | Ham map sırası yanlış |

## Anahtar Dosyalar (önem sırasına göre)

1. `src/main/java/extraction/ClauseNumbering.java` — numara parse + hiyerarşi doğrulaması (tek doğruluk kaynağı)
2. `src/main/java/extraction/ClauseTree.java` — deterministik segmentasyon motoru
3. `src/main/java/steps/PdfClauseExtractor.java` — canlı PDF extractor (native + OCR)
4. `src/main/java/steps/DocxClauseExtractor.java` — canlı DOCX extractor (LLM)
5. `src/main/java/extraction/ClauseExtraction.java` — orchestrator/adapter (sadece Selftest'te)
6. `src/main/java/extraction/ClauseExtractionValidator.java` — kalite kontrolü (wired değil)
7. `src/main/java/extraction/LineJoiner.java` — PDF satır→paragraf birleştirme
8. `src/main/java/extraction/TextClean.java` — Unicode/ligatür/normalize temizleme
9. `src/main/java/util/ClauseNumber.java` — köprü sınıf
10. `src/main/java/extraction/ClauseBlock.java` — kaynak-bağımsız blok modeli
11. `src/main/java/extraction/Selftest.java` — 9 senaryo / 37 check
12. `src/main/java/steps/ContractAnalyzer.java` — facade (pipeline entry point)
13. `src/main/java/analyzer/AnalyzeHelper.java` — örnek çağırıcı (Sample01-11)

## Pipeline Akışı

```
AnalyzeHelper.contractAnalyze(path, docx, signed)
  → Pipeline.run(signedPdf, docxList, input, fresh)
    → ContractAnalyzer (facade)
      → clauseExtractionDOCOriginalDocument(docx, lang)   → DocxClauseExtractor.extract()
      → clauseExtractionPDFSignedDocument(pdf, lang, ocrPages) → PdfClauseExtractor.extractWithOcrPages()
      → aiClauseCompare(...) → AiClauseComparer.compare()
      → signatureDetection(...) → SignatureDetector.detect()
      → crossClauseDependency(...) → CrossClauseDependencyAnalyzer.analyze()
```

## Build & Test

- Gradle projesi: `./gradlew build`
- Selftest çalıştırma: `./gradlew run --args="..."` veya direkt `extraction.Selftest.main()`
- AI dependency'leri config'ten geliyor: `config/AppConfig.java`

## Yapılan İyileştirmeler (ClauseNumbering.java + ClauseTree.java)

### Sorun: Deterministik motor DOCX'den 0 madde buluyordu
DocxReader `paragraphToMarkdown()` metni `**bold**` markdown ile sarıyordu.
`ClauseNumbering.parse()` ve `isLegalReference()` markdown marker'larını tanıyamıyordu.

### Düzeltmeler

1. **`stripMarkdownPrefix()` metodu** (ClauseNumbering.java) — tüm `**` ve `*` marker'larını temizler. `parse()` ve `isLegalReference()` başında çağrılır. İçerik orijinal blok metninden gelir (markdown korunur).

2. **`isClauseKeywordPrefix()` guard'ı** (ClauseNumbering.java, `stripArtifactPrefix` Type A) — `MADDE`, `Article`, `Section` gibi clause keyword'leri artefact sanılıp soyulmasını engeller. Önceden `MADDE 1` → `1` olarak soyuluyordu.

3. **Multi-level number guard'ı** (ClauseNumbering.java, `stripArtifactPrefix` Type B) — `12.1.` gibi çok seviyeli numaralar yapışık çift numara sanılıp split edilmesini engeller. `t.matches("\\d+\\.\\d+.*")` kontrolü ile nokta içeren numaralar Type B'den geçmez.

4. **`HEADING_START` numeric/keyword ayrımı** (ClauseNumbering.java) — `HEADING_START_NUM` (CASE_INSENSITIVE olmadan, `\p{Lu}` gerçek büyük harf) ve `HEADING_START_KW` (keyword alternation) ayrı pattern'ler. `3. maddede` (küçük harf) heading sanılmaz, ama `3. TARAFLAR` (büyük harf) heading olarak tanınır.

5. **`minParenTailChars` düşürüldü** (ClauseTree.java) — 50 → 5. Kısa ama gerçek sub-clause'lar (örn. `(1) Alıcı XYZ A.Ş.` = 15 char) artık filtrelenmiyor.

### Sonuç (3 sample)

| Sample | Dil | Deterministik | LLM | LLM Coverage | Fazla (FP) | Eksik |
|--------|-----|--------------|-----|-------------|-----------|-------|
| 09 | TR | 167 | 167 | 100% | 0 | 0 |
| 10 | EN | 75 | 89 | 84% | 0 | 14 |
| 11 | TR | 92 | 90 | 100% | 2 | 0 |

- Selftest: **42/42 PASS** (regresyon yok)
- Master (DOCX) vs Signed (PDF): sample-09 167/167, sample-11 DOCX:90 PDF:82

### Sample-10 kalan farklar (14 eksik)
- 13.1-13.13: Numarasız bold heading'ler ("Termination and Refund:", "Alternative Delivery Location:") — LLM doküman yapısından sub-clause numarası infer ediyor, deterministik motor explicit numara olmadan tanıyamıyor
- 20: Auto-numbering offset aynı-seviyede — heading "19." ama gerçek clause 20 (Word auto-number +1 geride)

### Sample-11 fazla (2 tane)
- 10.2.14, 10.2.15: maxParenPerParent=15 eşiğini geçen tanım listesi kalemleri — LLM bunları sub-clause olarak görmüyor

## OCR Pipeline İyileştirmeleri (PdfClauseExtractor.java)

| # | İyileştirme | Açıklama |
|---|------------|---------|
| 1 | **`ocrRenderDpi` config** | OCR için ayrı DPI (default 300, fallback renderDpi). Native crop'tan bağımsız — daha yüksek DPI = daha iyi OCR |
| 2 | **Per-page hata toleransı** | Tek sayfa OCR hatası tüm extraction'ı çökertmez — try/catch, skip page, uyarı log |
| 3 | **Deterministik supplement** | LLM sonrası `ClauseExtraction.fromOcrBlocks` çalıştırılır. LLM'in kaçırdığı maddeler otomatik eklenir |
| 4 | **Guided pass deterministik pre-pass** | LLM çağrısından önce exact-prefix search ile eksik maddeler aranır. Tümü bulunursa LLM çağrısı atlanır |
| 5 | **`pdfChunkChars` config** | PDF segmentation chunk size'ı DOCX'ten bağımsız ayarlanabilir |

### Uygulanabilir Ek İyileştirmeler (ileri seviye)
- **Image preprocessing**: Deskew + binarize + contrast (Mistral OCR öncesi)
- **Mistral field name validation**: `top_left_x` vb. field adlarını config'e taşı, ilk response'u logla
- **Validator wiring**: `ClauseExtractionValidator.validatePairing` guided pass gating için
- **Block model unify**: `PdfClauseExtractor.Block` ↔ `ClauseBlock` bridge
- **Hybrid approach**: Deterministik pre-segment → LLM sadece belirsiz bloklar

## Kullanıcı Görevi (devam ediyor)

Kullanıcı örnek PDF ve DOCX verecek:
1. Bu dosyalarda clause extraction yapılacak (hiyerarşik numaralandırma kuralı uygulanarak)
2. İlk DOCX = master, sonraki PDF/DOCX master'a göre karşılaştırılacak
3. Benim extraction'ım ile projenin extraction'ı karşılaştırılıp iyileştirme yapılacak
4. Kural: 5 → 5.1 veya 6; 5.1 → 5.1.1 veya 5.2
