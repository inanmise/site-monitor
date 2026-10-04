package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bir keyword kontrolünün sonucu (geçmiş). */
@Entity
@Table(name = "keyword_results", indexes = {
    @Index(name = "idx_kwr_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_kwr_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class KeywordResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Anahtar kelime sayfada bulundu mu (koşuldan bağımsız ham gözlem). */
    @Column(nullable = false)
    private Boolean found = false;

    /** Kelimenin gövdede kaç kez geçtiği (adet koşulu için gözlenen değer). */
    @Column(name = "occurrences")
    private Integer occurrences;

    /** Koşula göre "sağlıklı" mı: NOT_CONTAINS→ok=!found, CONTAINS→ok=found. */
    @Column(nullable = false)
    private Boolean ok = false;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_ms")
    private Long responseMs;

    /** Eşleşme bağlamı (±50 karakter) — UI'da gösterim. */
    @Column(columnDefinition = "TEXT")
    private String snippet;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;

    // ── Hata teşhisi (2026-10-04, kullanıcı isteği: "kontrol geçmişinde hata olduğunda herhangi bir detay yok") ──
    // Hepsi NULL'lanabilir ve EK: eski satırlar NULL kalır (arayüz "ayrıntı kaydedilmemiş" der), ok/found anlamı ve
    // alarm kararı DEĞİŞMEDİ. Neden/ayrıntı/ipuçları/alıntı YALNIZ başarısız kontrolde yazılır; yanıt meta verisi
    // (son URL, yönlendirme, içerik türü, boyut, karakter kümesi, yol) her kontrolde — küçük ve sorgusuz.

    /** Başarısızlık nedeni kodu ({@code KeywordFailureClassifier.CODES}); başarılı kontrolde null. */
    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    /** Kısa, insan okunur TR açıklama (≤ 500 karakter) — {@code error} kolonu ayrıca korunur. */
    @Column(name = "failure_detail", length = 500)
    private String failureDetail;

    /** Son (yönlendirmeler sonrası) URL — kimlik bilgisi ve hassas sorgu değerleri MASKELİ. */
    @Column(name = "final_url", columnDefinition = "TEXT")
    private String finalUrl;

    /** İzlenen yönlendirme sayısı. */
    @Column(name = "redirect_count")
    private Integer redirectCount;

    /** Yanıtın Content-Type başlığı (kırpılmış). */
    @Column(name = "content_type", length = 200)
    private String contentType;

    /** Okunan gövde boyutu (bayt; tavanla sınırlı). */
    @Column(name = "body_bytes")
    private Long bodyBytes;

    /** Gövde okuma tavanına ({@code KeywordCheckerService.MAX_BODY_BYTES}) takıldı — sayfanın devamı aranmadı. */
    @Column(name = "body_truncated")
    private Boolean bodyTruncated;

    /** Bildirilen karakter kümesi (Content-Type ya da meta); arama UTF-8 ile yapılır. */
    @Column(name = "charset", length = 60)
    private String charset;

    /** Kontrolün çıktığı yol: {@code proxy} | {@code direct}. */
    @Column(name = "via", length = 10)
    private String via;

    /** "Neden bulunamadı" ipucu kodları — küçük JSON dizi ({@code ["CASE_MISMATCH"]}); API'de {@code hints} listesi. */
    @Column(name = "hints", length = 500)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String hintsJson;

    /** Görünür metinden ≤ 600 karakterlik alıntı (etiket/betik/stil ayıklanmış, sırlar maskeli) — yalnız başarısızlıkta. */
    @Column(name = "excerpt", columnDefinition = "TEXT")
    private String excerpt;

    /** İpucu kodları (JSON dizisinden). Kodlar {@code [A-Z_]} — basit ayrıştırma yeter, bozuk değer boş liste.
     *  Varlık ALAN erişimli (@Id alanda) — bu yöntem kalıcılığa girmez, yalnız JSON'a. */
    @com.fasterxml.jackson.annotation.JsonProperty("hints")
    public java.util.List<String> getHints() {
        return parseHints(hintsJson);
    }

    /** Liste → JSON dizi metni (boş/null → null). */
    public void setHints(java.util.List<String> codes) {
        this.hintsJson = toHintsJson(codes);
    }

    public static java.util.List<String> parseHints(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String c = part.replace("\"", "").trim();
            if (!c.isEmpty() && c.matches("[A-Z0-9_]{1,40}")) out.add(c);
        }
        return java.util.List.copyOf(out);
    }

    public static String toHintsJson(java.util.List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (String c : codes) {
            if (c == null || !c.matches("[A-Z0-9_]{1,40}")) continue;
            int add = c.length() + 2 + (sb.length() > 1 ? 1 : 0);
            if (sb.length() + add + 1 > 500) break;   // +1: kapanış ']'
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(c).append('"');
        }
        if (sb.length() == 1) return null;
        return sb.append(']').toString();
    }
}
