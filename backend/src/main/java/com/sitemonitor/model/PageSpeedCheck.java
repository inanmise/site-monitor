package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tek bir Sayfa Hızı ölçümünün ÖZETİ — her kontrolde bir satır. Grafik, uptime rollup'ı ve haftalık rapor
 * bu tablodan okur.
 *
 * <p>Ağır kaynak kırılımı burada DEĞİL, {@link PageSpeedResource} tablosundadır ve bilinçli olarak yalnız
 * son ölçüm + eşik ihlali anları için tutulur (100 sayfa x 500 kaynak x yarım saatte bir = günde milyonlarca
 * satır; tek pod'da o tablo her şeyi yavaşlatırdı).
 */
@Entity
@Table(name = "pagespeed_checks",
       indexes = @Index(name = "idx_ps_monitor_checked", columnList = "monitor_id,checked_at"))
@Data
@NoArgsConstructor
public class PageSpeedCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** ISO-UTC (diğer kontrol tablolarıyla aynı sözleşme; UI yerel saate çevirir). */
    @Column(name = "checked_at", nullable = false, length = 30)
    private String checkedAt;

    /** Ana sayfa alınabildi mi. false = kesinti (DOWN); eşik aşımı bunu FALSE YAPMAZ. */
    @Column(nullable = false)
    private Boolean ok = true;

    @Column(name = "status_code")
    private Integer statusCode;

    /** İlk bayta kadar (ms) — sunucunun düşünme süresi. */
    @Column(name = "ttfb_ms")
    private Integer ttfbMs;

    /** Yalnız HTML gövdesinin tamamlanması (ms). */
    @Column(name = "html_ms")
    private Integer htmlMs;

    /** Toplam yükleme süresi (ms) — HTML + tüm alt kaynaklar. Uptime rollup'ı BU kolonu okur
     *  ({@code response_ms} adı diğer kontrol tablolarıyla ortak sözleşmedir, değiştirmeyin). */
    @Column(name = "response_ms")
    private Integer responseMs;

    /** Transfer edilen toplam bayt (HTML + alt kaynaklar). */
    @Column(name = "total_bytes")
    private Long totalBytes;

    /** Toplam istek sayısı (ana sayfa dahil). */
    @Column(name = "request_count")
    private Integer requestCount;

    /** Alınamayan alt kaynak sayısı — ölçümün ne kadar eksik olduğunu gösterir. */
    @Column(name = "failed_count")
    private Integer failedCount;

    /** Kaynak SAYISI tavanına takıldı mı (ölçüm kısmi: "en az bu kadar"). */
    @Column(nullable = false)
    private Boolean capped = false;

    /**
     * En az bir kaynağın okuması BAYT tavanında kesildi mi — {@code capped}'ten farklıdır:
     * o "kaç kaynak", bu "kaç bayt". True ise {@code totalBytes} gerçek toplam değil ALT SINIRDIR
     * ve arayüz rakamı "≥" ile gösterir; aksi halde kullanıcı eşiğini eksik bir toplama göre kurar.
     */
    // nullable BİLİNÇLİ: bu kolon tablo oluşturulduktan SONRA eklendi. Dolu bir tabloya
    // "NOT NULL" kolon eklemek Postgres'te reddedilir; Hibernate hatayı yutar ve kolon HİÇ
    // oluşmaz (2026-08-23: tüm sayfa hızı sorguları düştü). Yazarken hep değer veriliyor,
    // okurken null "false" sayılıyor.
    @Column(name = "bytes_truncated")
    private Boolean bytesTruncated = false;

    /** Aşılan eşikler, virgülle: {@code LOAD,TTFB,SIZE,REQUESTS}. Boş/null = ihlal yok.
     *  Serbest metin değil sabit anahtar listesidir — UI ve e-posta bunu çevirir. */
    @Column(name = "breached_metrics", length = 60)
    private String breachedMetrics;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** TTFB faz kırılımı (ms). Ölçülemeyen faz {@code null} kalır — sıfır yazmak, olmayan bir
     *  hızı iddia etmek olurdu. {@code serverMs} EŞİĞİN baktığı değerdir. */
    @Column(name = "dns_ms")     private Integer dnsMs;
    @Column(name = "connect_ms") private Integer connectMs;
    @Column(name = "tls_ms")     private Integer tlsMs;
    @Column(name = "server_ms")  private Integer serverMs;

    /** Ölçüm dışı bırakılan {@code loading="lazy"} kaynak sayısı — tarayıcı da onları istemez. */
    @Column(name = "skipped_lazy")
    private Integer skippedLazy;

    /**
     * İhlal DELİLİ: hangi eşik neydi, ölçülen neydi (ör. {@code "TTFB:1000>2955"}).
     *
     * <p>Neden kaydediliyor: eşik izlemenin ANLIK ayarıdır; kullanıcı sonradan eşiği değiştirince
     * geçmiş satırın yanına bugünkü eşiği yazmak DELİLİ BOZAR — "neden alarm verdi" sorusu yanlış
     * sayıyla cevaplanır. Ölçüm anındaki değer burada donar.
     */
    @Column(name = "breach_detail", columnDefinition = "TEXT")
    private String breachDetail;
}
