# Clause Extraction — Entegrasyon Kılavuzu

Bu kılavuz `extraction/` paketini mevcut `ContractAnalyzer` projesine bağlamak için
gereken **minimum değişiklikleri** adım adım anlatır.
Downstream sınıflar (`ReportBuilder`, `AnalyzeMerger`, `ClauseDiffer`) değişmez.

---

## Ön Koşul: Selftest Yeşil

```powershell
.\gradlew compileJava
# veya doğrudan:
javac -d build/classes/java/main src/main/java/extraction/*.java
java -cp build/classes/java/main extraction.Selftest
# Beklenti: PASS=37  FAIL=0
```

CI'a eklemek için `Selftest.main()` metodunu `@Test` annotasyonlu bir JUnit metotla
sarmalamanız yeterlidir — API anahtarı veya DMS session gerektirmez.

---

## Adım 1 — Paketin yeri

`extraction/` klasörü zaten `src/main/java/extraction/` altında.
`build.gradle` ek yapılandırma gerektirmez; mevcut `sourceCompatibility` (Java 11)
tüm sınıfları destekler.

---

## Adım 2 — DOCX çıkarımını değiştir (en büyük kazanç)

**Dosya:** `src/main/java/steps/DocxClauseExtractor.java`

`extract(List<Block> blocks, String lang)` metodundaki model çağrısını
(`ai.chat(SYSTEM, prompt(...), ...)`) ve ardışık `isValidNextClause` döngüsünü
kaldırıp aşağıdaki blokla değiştirin:

```java
// ---- YENİ: deterministik çıkarım (model çağrısı yok) ----
List<extraction.ClauseBlock> raw = new ArrayList<>();
for (int i = 0; i < workingBlocks.size(); i++) {
    Block b = workingBlocks.get(i);
    raw.add(new extraction.ClauseBlock(
            i,                                  // id: lokal indeks (parseIds uyumlu)
            0,                                  // DOCX'te sayfa yok → 0
            b.text,
            extraction.ClauseBlock.Source.DOCX,
            b.columnIndex));
}

extraction.ClauseExtraction.ExtractionResult r =
        extraction.ClauseExtraction.fromDocxBlocks(raw, lang);

if (r.isSuspicious()) {
    log.warn("DOCX [{}] şüpheli çıkarım: {}", lang, r.diagnosticReport());
}

// ---- Mevcut çıktı formatını koru ----
JSONObject clauses = new JSONObject();
for (Map.Entry<String, String> e : r.clauses.entrySet()) {
    clauses.put(e.getKey(), new JSONObject()
            .put("content",  e.getValue())
            .put("blocks",   new JSONArray(
                    r.blocksByClause.getOrDefault(e.getKey(), List.of()))));
}
long elapsed = System.currentTimeMillis() - t0;
log.info("DOCX [{}]: {} clauses extracted (deterministic, {}ms)",
        lang, clauses.length(), elapsed);
return new JSONObject().put("title", r.title).put("clauses", clauses);
```

`title` alanı şimdilik boş string döner (mevcut davranışla özdeş — title zaten
model tarafından üretiliyordu ve downstream'de opsiyonel).

**Kaldırılabilecekler** (Adım 2 tamamlandıktan sonra):
- `router.get("clauseExtractionDOCOriginalDocument")` çağrısı
- `prompt(lang, chunk, prev)` metodu
- `chunk(blocks, maxChars)` metodu (PdfClauseExtractor'da da ayrı kopyası var)
- `aiSteps.put("clauseExtractionDOCOriginalDocument", "ai")` satırı `Pipeline.java`'da

---

## Adım 3 — PDF native modunu değiştir

**Dosya:** `src/main/java/steps/PdfClauseExtractor.java`

### 3a. Tam genişlikli satır sütun ataması düzeltmesi

`readBlocks()` içinde native metin modunda sütun ataması yapılan satırı:

```java
// ÖNCE:
colIdx = fullWidth ? 0 : (centre < columnSplitX ? 0 : 1);

// SONRA:
colIdx = fullWidth ? -1 : (centre < columnSplitX ? 0 : 1);
```

Bu tek karakter değişikliği, tam genişlikli başlık/önsöz bloklarının her iki dil
ekstraksiyonuna girmesini sağlar (bkz. Kök Neden §3).

### 3b. `segmentNative()` yerine `ClauseExtraction.fromPdfLines()`

```java
private JSONObject segmentNative(List<Block> blocks,
                                  List<BufferedImage> pages,
                                  String lang) throws IOException {
    // PDF Block → ClauseBlock dönüşümü
    List<extraction.ClauseBlock> lines = new ArrayList<>();
    for (Block b : blocks) {
        lines.add(new extraction.ClauseBlock(
                b.id, b.page, b.text,
                extraction.ClauseBlock.Source.PDF_LINE,
                b.columnIndex,
                b.x0, b.y0, b.x1, b.y1));
    }

    extraction.ClauseExtraction.ExtractionResult r =
            extraction.ClauseExtraction.fromPdfLines(lines, lang);

    // Kırpma için orijinal Block listesi kullanılmaya devam ediyor
    Map<Integer, Block> byId = new LinkedHashMap<>();
    for (Block b : blocks) byId.put(b.id, b);

    JSONObject clauses = new JSONObject();
    for (Map.Entry<String, List<Integer>> e : r.blocksByClause.entrySet()) {
        List<Block> own = new ArrayList<>();
        for (int id : e.getValue()) {
            Block b = byId.get(id);
            if (b != null) own.add(b);
        }
        if (own.isEmpty()) continue;
        JSONObject c = new JSONObject();
        c.put("signed_content", content(own, true));
        BufferedImage img = cropClause(own, pages);
        if (img != null) c.put("signed_image", Images.toBase64Png(img));
        c.put("bboxes", bboxes(own));
        clauses.put(e.getKey(), c);
    }
    log.info("Signed PDF [{}]: {} clauses (deterministic native, LineJoiner on)",
            lang, clauses.length());
    releasePages(pages);
    return new JSONObject()
            .put("title",   r.title)
            .put("clauses", clauses);
}
```

`cropClause`, `content`, `bboxes`, `releasePages` metodları değişmez.

---

## Adım 4 — Validator'ı Checkpoint önüne koy

**Dosya:** `src/main/java/steps/Pipeline.java`

`cp.obj("original1.TR.extract", ...)` çağrısını saran lambda içinde,
`return extracted;` satırından hemen önce:

```java
// Kalite kapısı: kötü çıkarım cache'lenmeden önce yakala
List<extraction.ClauseExtractionValidator.Finding> findings =
        extraction.ClauseExtractionValidator.validate(
                extraction.ClauseExtraction
                        .fromDocxBlocks(/* ... */, lang)  // ya da tree'yi doğrudan geçirin
                        .tree);
if (extraction.ClauseExtractionValidator.hasBlocker(findings)) {
    throw new IOException("Clause extraction quality gate failed for "
            + key + " [" + lang + "]: " + findings);
}
for (extraction.ClauseExtractionValidator.Finding f : findings) {
    plog(f.severe ? "ERROR" : "WARN", "[{}][{}] {}", key, lang, f);
}
```

`ExtractionResult.tree` alanı doğrudan `ClauseTree.Result` nesnesidir;
`validate(tree)` bunu parametre olarak alır — ikinci bir segmentasyon çalıştırmanız
gerekmez.

### Çift kontrol: `validatePairing`

`AnalyzeMerger.merge()` sonrasında, diff adımından önce:

```java
// İki doküman karşılaştırmasında coverage kontrolü
for (String lang : allLanguages) {
    Set<String> baseNos = originalDocs.get(0).content
            .getOrDefault(lang, Map.of()).keySet();
    Set<String> signedNos = signedDoc.content
            .getOrDefault(lang, Map.of()).keySet();
    List<extraction.ClauseExtractionValidator.Finding> pf =
            extraction.ClauseExtractionValidator.validatePairing(baseNos, signedNos);
    for (extraction.ClauseExtractionValidator.Finding f : pf) {
        plog(f.severe ? "ERROR" : "WARN", "Pairing [{}]: {}", lang, f);
    }
}
```

---

## Adım 5 — OCR modunu değiştir

**Dosya:** `src/main/java/steps/PdfClauseExtractor.java`

`segmentAndCrop()` içindeki `chunk()` → `prompt()` → `ai.chat()` döngüsünü:

```java
List<extraction.ClauseBlock> ocrBlocks = new ArrayList<>();
for (Block b : blocks) {
    ocrBlocks.add(new extraction.ClauseBlock(
            b.id, b.page, b.text,
            extraction.ClauseBlock.Source.OCR_BLOCK,
            b.columnIndex,
            b.x0, b.y0, b.x1, b.y1));
}
extraction.ClauseExtraction.ExtractionResult r =
        extraction.ClauseExtraction.fromOcrBlocks(ocrBlocks, lang);
```

ile değiştirin. Kırpma kodu (Adım 3b ile aynı) değişmez.

**Not:** `extractGuided` / `segmentAndCropGuided` yalnızca teşhisin gösterdiği
biçimler için (örn. numarasız başlıklar) saklı tutulabilir; artık varsayılan yol
değildir.

---

## Adım 6 — Prompt'tan çıkarılması gerekenler

`DocxClauseExtractor.prompt()` ve `PdfClauseExtractor.prompt()` içinden
(modeli hâlâ aiClauseCompare gibi yorum adımları için tutuyorsanız):

| Kaldırılmalı | Neden |
|---|---|
| `"assign it a sequential number continuing from the last known clause"` | Numara artık kod tarafından atanıyor |
| `"the next top-level clause after N is N+1"` | `ClauseNumbering.classify` bunu garantiliyor |
| `(1)/(a)/(i)` eşleme talimatları | `ClauseTree` parantezli alt maddeleri hallediyor |
| `"number: digits and dots only"` | Normalize kod tarafından yapılıyor |

Modele bırakılabilenler (yorum adımları):
- Risk seviyesi tespiti
- Birim etiketi (LEGAL / FINANCE / DPO vb.)
- Fark yorumu

---

## Sütun Düzeltmesi Özeti

| Satır | Önce | Sonra |
|---|---|---|
| `readBlocks()` native mod sütun ataması | `colIdx = fullWidth ? 0 : ...` | `colIdx = fullWidth ? -1 : ...` |

Bu değişiklik `filterByColumn()` mantığıyla uyumludur:
`columnIndex < 0` olan bloklar her iki dil için de korunur.

---

## Önerilen Sıra

| Adım | Dosya | Kazanç |
|---|---|---|
| 1 | — | Selftest CI'da yeşil |
| 2 | `DocxClauseExtractor.java` | Model çağrısı kalkıyor, token maliyeti düşüyor |
| 3 | `PdfClauseExtractor.java` | Satır birleştirme açılıyor, sütun hatası düzeliyor |
| 4 | `Pipeline.java` | Kötü çıkarım cache'lenmiyor |
| 5 | `PdfClauseExtractor.java` | OCR mod deterministik |
| 6 | Rapor katmanı | `S<n>#` prefix ek bölümü olarak göster |
