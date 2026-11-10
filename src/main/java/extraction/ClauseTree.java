package extraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministik madde segmentasyon motoru.
 *
 * <p>Model kullanılmaz. Madde sınırları tamamen kod tarafından belirlenir;
 * {@link ClauseNumbering} tek doğruluk kaynağıdır.
 *
 * <p><b>Özellikler:</b>
 * <ul>
 *   <li>RESET / BACKWARD durumları ek/şedül segmenti olarak korunur (kaybolmaz)</li>
 *   <li>Yasal atıf satırları ({@link ClauseNumbering#isLegalReference}) başlık
 *       olarak işlenmez; gövde metne eklenir</li>
 *   <li>Sayfa sonu devamlılığı: {@code currentClause} sayfalar arasında sıfırlanmaz</li>
 *   <li>Eksik parent: "2.1" var ama "2" yoksa "2" başlıksız ve işaretli olarak oluşturulur</li>
 *   <li>Parantezli alt maddeler ({@code (1)}, {@code (a)}, {@code (i)}) parent altında
 *       sıralı numera alır: parent.1, parent.2, …</li>
 * </ul>
 */
public final class ClauseTree {

    // ------------------------------------------------------------------
    // Segment (ek/şedül bölümü)

    /**
     * Bir segmenti temsil eder. Doküman iki veya daha fazla numaralandırma
     * bloğuna sahipse (ana gövde + EK-1, EK-2 …) her biri ayrı segment olur.
     * Segment kimliği "{@code S<n>#}" önekiyle raporda ayırt edilir.
     */
    public static final class Segment {
        /** Segment sırası: 0 = ana gövde, 1+ = ek/şedül */
        public final int index;
        /** Segmentin opsiyonel etiketi (varsa EK/ANNEX/SCHEDULE başlığı). */
        public final String label;
        /** Segmentteki madde numaraları → {@link ClauseEntry} (ekleme sırası korunur). */
        public final Map<String, ClauseEntry> entries = new LinkedHashMap<>();

        Segment(int index, String label) {
            this.index = index;
            this.label = label != null ? label : "";
        }

        /** Segment öneki: segment 0 için "", segment 1 için "S1#" vb. */
        public String prefix() {
            return index == 0 ? "" : "S" + index + "#";
        }

        /** Madde numarasını segment-nitelikli biçime çevirir: "1.1" → "S1#1.1". */
        public String qualify(String no) {
            return prefix() + no;
        }
    }

    // ------------------------------------------------------------------
    // Madde girdisi

    /** Tek bir maddeye ait bilgiler. */
    public static final class ClauseEntry {
        /** Normalize madde numarası (sadece segment içinde benzersiz). */
        public final String number;
        /** Madde başlığı (varsa; heading satırındaki metin). */
        public String heading;
        /** Madde metnini oluşturan blok id listesi (ekleme sırası). */
        public final List<Integer> blockIds = new ArrayList<>();
        /** {@code true} → bu maddenin parent'ı mevcut değildi; otomatik oluşturuldu. */
        public boolean syntheticParent = false;

        ClauseEntry(String number, String heading) {
            this.number  = number;
            this.heading = heading != null ? heading : "";
        }
    }

    // ------------------------------------------------------------------
    // Sonuç

    /** Segmentasyon sonucu: segmentler + teşhis mesajları. */
    public static final class Result {
        /** Segmentler (index 0 = ana gövde). */
        public final List<Segment> segments;
        /** Teşhis mesajları (uyarılar, reddedilen satırlar). */
        public final List<String> diagnostics;

        Result(List<Segment> segments, List<String> diagnostics) {
            this.segments    = Collections.unmodifiableList(segments);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }

        /** Tüm segmentlerde toplam madde sayısı. */
        public int totalClauses() {
            return segments.stream().mapToInt(s -> s.entries.size()).sum();
        }

        /** Hiç madde bulunamadıysa {@code true}. */
        public boolean isEmpty() {
            return totalClauses() == 0;
        }

        /** Tüm segment-nitelikli madde numaralarını {@link ClauseNumbering#COMPARATOR}
         *  sıralamasıyla döner (segment 0'ın numaraları önce, segment 1 vb. sonra). */
        public List<String> allNumbers() {
            List<String> out = new ArrayList<>();
            for (Segment s : segments) {
                for (String no : s.entries.keySet()) {
                    out.add(s.qualify(no));
                }
            }
            return out;
        }

        /** Verilen segment-nitelikli numaranın içeriğini (blok id listesi) döner. */
        public List<Integer> blockIds(String qualifiedNo) {
            for (Segment s : segments) {
                String prefix = s.prefix();
                if (qualifiedNo.startsWith(prefix)) {
                    String no = qualifiedNo.substring(prefix.length());
                    ClauseEntry e = s.entries.get(no);
                    if (e != null) return e.blockIds;
                }
            }
            return Collections.emptyList();
        }

        /** Boş sonuç (hiç blok yok). */
        public static Result empty(String reason) {
            List<String> diag = new ArrayList<>();
            diag.add("EMPTY: " + reason);
            return new Result(new ArrayList<>(), diag);
        }
    }

    // ------------------------------------------------------------------
    // Yapılandırma

    /**
     * RESET durumunu yeni segment mi başlatır yoksa yoksayılır mı?
     * Default {@code true} — EK bölümleri ayrı segment olarak korunur.
     */
    private final boolean keepResetAsSegment;

    /**
     * Parantezli alt madde olarak kabul edilmesi için gereken minimum tail uzunluğu
     * (parantezden sonraki metin, karakter cinsinden).
     *
     * <p>Tanım listeleri "(a) Amerika Birleşik Devletleri;" gibi çok kısa kalemler
     * içerir. Gerçek sub-clause içerikleri ise tipik olarak 50+ karakter uzunluğundadır.
     * Bu eşiğin altındaki parantezli satırlar body metni olarak eklenir, ayrı madde
     * numarası almaz.
     *
     * <p>Default: 50 karakter — kısa tanım listesi kalemlerini filtreler, gerçek sub-clause'ları korur.
     * Örnekler:
     * <ul>
     *   <li>"(a) Amerika Birleşik Devletleri;" → tail 30 char → tanım listesi → body</li>
     *   <li>"(a) yaptırım listesinde yer alan birden fazla kişinin sahibi..." → 50+ char → sub-clause</li>
     * </ul>
     */
    private final int minParenTailChars;

    /**
     * Aynı parent altında kabul edilecek maksimum parantezli alt madde sayısı.
     *
     * <p>Gerçek sözleşmelerde bir maddenin (a)(b)(c) alt maddeleri genellikle 6'yı
     * geçmez. Daha uzun seriler (örn. "Yaptırım Otoritesi" tanımındaki 6 ülke listesi)
     * tanım listesidir; {@code minParenTailChars} bu durumları zaten yakalamalı,
     * ama bu sayaç ek güvence sağlar.
     *
     * <p>Default: 12. 12'den fazla parantezli kalem aynı parent altında gelirse
     * kalanlar body'e eklenir.
     */
    private final int maxParenPerParent;

    public ClauseTree() {
        this(true);
    }

    public ClauseTree(boolean keepResetAsSegment) {
        this(keepResetAsSegment, 50, 15);
    }

    public ClauseTree(boolean keepResetAsSegment, int minParenTailChars, int maxParenPerParent) {
        this.keepResetAsSegment  = keepResetAsSegment;
        this.minParenTailChars   = minParenTailChars;
        this.maxParenPerParent   = maxParenPerParent;
    }

    // ------------------------------------------------------------------
    // Ana segmentasyon metodu

    /**
     * Blok listesini maddelere böler.
     *
     * @param blocks sıralı blok listesi (tüm sayfalar); boş liste güvenle desteklenir
     * @return segmentasyon sonucu
     */
    public Result segment(List<ClauseBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Result.empty("no blocks");
        }

        List<Segment>  segments    = new ArrayList<>();
        List<String>   diagnostics = new ArrayList<>();

        Segment  curSeg     = new Segment(0, "");
        segments.add(curSeg);

        String   currentNo  = null;   // en son kabul edilen numera (segment-local)
        String   currentParent = null; // son EXPLICIT parent (parantezli alt madde için)
        int      parenCounter  = 0;   // parantezli alt madde sayacı

        for (ClauseBlock block : blocks) {
            String text = TextClean.clean(block.text).trim();
            if (text.isEmpty()) continue;

            // Yasal atıf kontrolü: geçer ise gövde olarak ekle
            if (ClauseNumbering.isLegalReference(text)) {
                appendToCurrentClause(curSeg, currentNo, block.id);
                diagnostics.add("REF_SKIP block=" + block.id + " text='" + abbreviate(text, 80) + "'");
                continue;
            }

            ClauseNumbering.ParseResult pr = ClauseNumbering.parse(text);
            if (pr == null) {
                // Gövde bloğu: mevcut maddeye ekle
                appendToCurrentClause(curSeg, currentNo, block.id);
                continue;
            }

            // Numera elde edildi — sınıflandır
            String candidateNo;
            if (pr.kind == ClauseNumbering.Kind.PARENTHESIZED) {
                // Parantezli alt madde: parent altında sıralı numera.
                // İki guard ile tanım listesi kalemlerini filtrele:
                //   1. tail çok kısaysa (< minParenTailChars) → tanım listesi kalemi, body olarak ekle.
                //   2. parenCounter zaten maxParenPerParent'e ulaştıysa → body olarak ekle.
                if (currentParent == null) {
                    // Parent yoksa gövde olarak ekle
                    appendToCurrentClause(curSeg, currentNo, block.id);
                    diagnostics.add("PAREN_NO_PARENT block=" + block.id);
                    continue;
                }
                // Guard 1: tail uzunluğu kontrolü — kısa kalemler tanım listesidir
                if (pr.tail.length() < minParenTailChars) {
                    appendToCurrentClause(curSeg, currentNo, block.id);
                    diagnostics.add("PAREN_SHORT_TAIL block=" + block.id
                            + " tail_len=" + pr.tail.length()
                            + " min=" + minParenTailChars
                            + " text='" + abbreviate(text, 60) + "'");
                    continue;
                }
                // Guard 2: aynı parent altında çok fazla parantezli kalem
                if (parenCounter >= maxParenPerParent) {
                    appendToCurrentClause(curSeg, currentNo, block.id);
                    diagnostics.add("PAREN_OVERFLOW block=" + block.id
                            + " parent=" + currentParent
                            + " counter=" + parenCounter);
                    continue;
                }
                parenCounter++;
                candidateNo = currentParent + "." + parenCounter;
            } else {
                candidateNo = pr.normalized;
            }

            ClauseNumbering.Relation rel = ClauseNumbering.classify(currentNo, candidateNo);

            switch (rel) {
                case VALID:
                case DUPLICATE:
                    // Normal: maddeyi aç veya devam et
                    if (rel == ClauseNumbering.Relation.VALID) {
                        openClause(curSeg, candidateNo, pr.tail, block.id, diagnostics);
                    }
                    // DUPLICATE: chunk sınırı — bloku mevcut maddeye ekle
                    appendToCurrentClause(curSeg, candidateNo, block.id);
                    if (pr.kind == ClauseNumbering.Kind.EXPLICIT) {
                        currentParent = candidateNo;
                        parenCounter  = 0;
                    }
                    currentNo = candidateNo;
                    break;

                case RESET:
                    if (keepResetAsSegment) {
                        // Yeni segment başlat
                        curSeg = new Segment(segments.size(), text);
                        segments.add(curSeg);
                        currentNo     = null;
                        currentParent = null;
                        parenCounter  = 0;
                        diagnostics.add("RESET→new segment S" + (segments.size() - 1)
                                + "# at block=" + block.id);
                        // Segmentin ilk maddesini aç
                        openClause(curSeg, candidateNo, pr.tail, block.id, diagnostics);
                        appendToCurrentClause(curSeg, candidateNo, block.id);
                        if (pr.kind == ClauseNumbering.Kind.EXPLICIT) {
                            currentParent = candidateNo;
                            parenCounter  = 0;
                        }
                        currentNo = candidateNo;
                    } else {
                        // RESET yok sayılıyor — gövde bloğu
                        appendToCurrentClause(curSeg, currentNo, block.id);
                        diagnostics.add("RESET_IGNORED block=" + block.id
                                + " candidate=" + candidateNo);
                    }
                    break;

                case BACKWARD:
                    // Geriye gidiş: keepResetAsSegment modunda yeni ek/şedül segmenti açılır.
                    // "2"den sonra "1" geliyorsa bu her zaman EK bölümüdür.
                    if (keepResetAsSegment) {
                        curSeg = new Segment(segments.size(), text);
                        segments.add(curSeg);
                        currentNo     = null;
                        currentParent = null;
                        parenCounter  = 0;
                        diagnostics.add("BACKWARD→new segment S" + (segments.size() - 1)
                                + "# at block=" + block.id
                                + " current=" + currentNo + " candidate=" + candidateNo);
                        openClause(curSeg, candidateNo, pr.tail, block.id, diagnostics);
                        appendToCurrentClause(curSeg, candidateNo, block.id);
                        if (pr.kind == ClauseNumbering.Kind.EXPLICIT) {
                            currentParent = candidateNo;
                            parenCounter  = 0;
                        }
                        currentNo = candidateNo;
                    } else {
                        // Geriye gidiş — gövde olarak ekle (sessizce yutmak yerine teşhis)
                        appendToCurrentClause(curSeg, currentNo, block.id);
                        diagnostics.add("BACKWARD_IGNORED block=" + block.id
                                + " current=" + currentNo + " candidate=" + candidateNo);
                    }
                    break;

                case INVALID:
                    // Geçersiz — gövde olarak ekle, parenCounter sıfırlama
                    appendToCurrentClause(curSeg, currentNo, block.id);
                    if (parenCounter > 0) {
                        // Seri kesildi
                        diagnostics.add("INVALID_NO block=" + block.id
                                + " candidate=" + candidateNo + " (paren counter reset)");
                        parenCounter = 0;
                    }
                    break;
            }
        }

        // Eksik parent'ları tamamla
        for (Segment seg : segments) {
            ensureParents(seg, diagnostics);
        }

        return new Result(segments, diagnostics);
    }

    // ------------------------------------------------------------------
    // Yardımcılar

    /** Segmentte yeni madde girdisi açar. */
    private static void openClause(Segment seg, String no, String heading,
                                   int blockId, List<String> diagnostics) {
        if (!seg.entries.containsKey(no)) {
            seg.entries.put(no, new ClauseEntry(no, heading));
        }
    }

    /** Bloğu mevcut maddeye ekler. Madde henüz açılmamışsa sessizce atlar. */
    private static void appendToCurrentClause(Segment seg, String currentNo, int blockId) {
        if (currentNo == null) return;
        ClauseEntry entry = seg.entries.get(currentNo);
        if (entry != null) {
            entry.blockIds.add(blockId);
        }
    }

    /**
     * Eksik parent maddeleri oluşturur.
     *
     * <p>Örnek: "2.1" var ama "2" yoksa "2" boş içerikli ve
     * {@code syntheticParent=true} olarak eklenir.
     */
    private static void ensureParents(Segment seg, List<String> diagnostics) {
        // Kopyasını al — iterasyon sırasında map değişiyor
        List<String> nos = new ArrayList<>(seg.entries.keySet());
        for (String no : nos) {
            String parent = parentOf(no);
            if (parent != null && !seg.entries.containsKey(parent)) {
                ClauseEntry synth = new ClauseEntry(parent, "");
                synth.syntheticParent = true;
                // Parent, child'dan önce olmalı — LinkedHashMap ekleme sırası korur,
                // ama parent child'dan sonra ekleniyor. Bu sıralama raporlarda
                // ClauseNumbering.COMPARATOR ile düzeltilir.
                seg.entries.put(parent, synth);
                diagnostics.add("SYNTHETIC_PARENT " + parent + " (for " + no + ")");
            }
        }
    }

    /**
     * "2.1" → "2", "2.1.3" → "2.1", "2" → null.
     */
    private static String parentOf(String no) {
        int lastDot = no.lastIndexOf('.');
        return lastDot > 0 ? no.substring(0, lastDot) : null;
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
