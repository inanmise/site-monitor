package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sayfa Bütünlüğü (Page Integrity) monitörü: bir web sayfasının KOD SEVİYESİNDE sağlıklı yüklendiğini
 * doğrular — kırık site içi/dışı linkler, yüklenemeyen img/CSS/JS/font/iframe kaynakları, mixed content
 * ve içerik anomalileri (defacement sinyali). Erişilebilirlik (HttpMonitor) ve içerik-anahtar (KeywordMonitor)
 * kontrollerinden AYRIDIR; onların iskeletini birebir izler (ISO String zaman, teamId sahiplik, soft-delete
 * yok, eşik/recovery state entity'de {@code confirm*}/{@code recovery*} olarak taşınır).
 *
 * İki mod: {@code SINGLE_PAGE} yalnız hedef URL'i; {@code SITE_CRAWL} same-origin linkleri
 * {@code crawlDepth}/{@code crawlMaxPages} dahilinde (robots.txt/sitemap uyumlu) tarar.
 */
@Entity
@Table(name = "page_monitors")
@Data
@NoArgsConstructor
public class PageMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    /** Tarama modu: SINGLE_PAGE (yalnız hedef) | SITE_CRAWL (same-origin derinlik taraması). */
    @Column(name = "mode")
    private String mode = "SINGLE_PAGE";

    /** SITE_CRAWL: hedeften kaç seviye derine inilir (varsayılan 2). */
    @Column(name = "crawl_depth")
    private Integer crawlDepth = 2;

    /** SITE_CRAWL: taranacak azami sayfa sayısı (varsayılan 50). */
    @Column(name = "crawl_max_pages")
    private Integer crawlMaxPages = 50;

    /** Hariç tutulacak URL desenleri (satır-başı ayrık; bilinçli 404'ler / dış izleme pikselleri). */
    @Column(name = "exclude_patterns", columnDefinition = "TEXT")
    private String excludePatterns;

    /** Kaynak yüklenme süresi bu eşiği (ms) aşarsa SLOW işaretlenir (varsayılan 2000). */
    @Column(name = "slow_resource_ms")
    private Integer slowResourceMs = 2000;

    /** Üçüncü-taraf (dış origin) kaynak kırıkları da alarm üretsin mi (varsayılan false → yalnız birinci-taraf). */
    @Column(name = "alert_third_party")
    private Boolean alertThirdParty = false;

    /** Mixed content (HTTPS sayfada http:// kaynak) alarm üretsin mi (varsayılan true → mevcut davranış).
     *  Kapalıyken mixed content YİNE tespit edilir + sorun tablosunda görünür ama DEGRADED alarmı/e-postası üretmez. */
    @Column(name = "alert_mixed_content")
    private Boolean alertMixedContent = true;

    /** Zaman aşımına uğrayan kaynaklar izlensin/kırık sayılsın mı (varsayılan true → mevcut davranış).
     *  Kapalıyken timeout'lar KIRIK sayacına ve "Bozulmuş" durumuna GİRMEZ, alarm/e-posta üretmez; yine sorun
     *  tablosunda "Zaman aşımı" olarak görünür. NULL (eski satır) → !FALSE.equals = true (default AÇIK). */
    @Column(name = "alert_timeout")
    private Boolean alertTimeout = true;

    /** Kaynak doğrulamada eşzamanlı istek sınırı (nezaket + tek-pod yük; varsayılan 5, clamp'li). */
    @Column(name = "resource_concurrency")
    private Integer resourceConcurrency = 5;

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi (envanterden bağımsız). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 4000;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3; 0 = anında). */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı (varsayılan 3). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn): recoveryChecks denemesi bu süre arayla yapılır. */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /** Serbest etiketler — virgülle ayrılmış (organizasyon/filtreleme). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** E-posta bildirimi açık mı (varsayılan true). */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
