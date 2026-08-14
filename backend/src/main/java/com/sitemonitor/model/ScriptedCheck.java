package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tek bir Senaryo İzleme (k6) kontrolünün sonucu. {@link PageCheck} iskeletini izler (monitorId'ye value-FK,
 * {@code ok} pipeline-uyumu bayrağı, ISO String zaman, index'ler). status = PASS/FAIL/ERROR/TIMEOUT.
 */
@Entity
@Table(name = "scripted_checks", indexes = {
        @Index(name = "idx_sc_monitor_id", columnList = "monitor_id"),
        @Index(name = "idx_sc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class ScriptedCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Pipeline-uyumu sağlık bayrağı (rollup/uptime sayımları): ok = (status == PASS). */
    @Column(nullable = false)
    private Boolean ok = false;

    /** PASS | FAIL | ERROR | TIMEOUT. */
    @Column(nullable = false)
    private String status = "ERROR";

    /** Toplam süreç süresi (ms). */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** k6 süreç çıkış kodu (timeout'ta -1). */
    @Column(name = "exit_code")
    private Integer exitCode;

    /** k6 özetinden: geçen/kalan check sayısı. */
    @Column(name = "checks_passed")
    private Integer checksPassed;

    @Column(name = "checks_failed")
    private Integer checksFailed;

    /** k6 iteration_duration (ms). */
    @Column(name = "iteration_ms")
    private Long iterationMs;

    /** k6 http_req_duration özeti (ms). */
    @Column(name = "http_req_avg_ms")
    private Long httpReqAvgMs;

    @Column(name = "http_req_p95_ms")
    private Long httpReqP95Ms;

    /** Check-bazlı geçti/kaldı listesi — JSON: [{"name":..,"passed":bool}]. */
    @Column(name = "checks_json", columnDefinition = "TEXT")
    private String checksJson;

    /** Maskeli stdout/stderr kuyruğu (boyut sınırlı; secret env değerleri maskelenmiş). */
    @Column(name = "output_tail", columnDefinition = "TEXT")
    private String outputTail;

    /** Kısa hata özeti (ERROR/TIMEOUT'ta). */
    @Column(columnDefinition = "TEXT")
    private String error;

    /** Bu koşum kurumsal vekil üzerinden mi yapıldı? "Neden bu koşum farklı?" sorusunu cevaplar
     *  (vekil ayarı değiştiğinde geçmiş satırlar aynı kalır; karşılaştırma ancak böyle yapılabilir). */
    @Column(name = "via_proxy")
    private Boolean viaProxy;

    /**
     * İSTEĞİN FAZ KIRILIMI (ms) — "nerede takıldı?" sorusunun cevabı.
     *
     * <p>k6 bunları her koşumda üretiyordu ama 2026-08'e kadar okunmuyordu; sahada 288 koşum
     * boyunca "request timeout" görülüp DNS/TCP/TLS/TTFB ayrımı yapılamadı. Sıra:
     * blocked (DNS+bekleme) → connecting (TCP) → tls → sending → waiting (TTFB) → receiving.
     *
     * <p>OKUMA KURALI (k6 v0.49 ile ölçüldü): girilmemiş faz {@code 0} yazılır, alan boş kalmaz.
     * Takılma noktası = SON SIFIR-OLMAYAN fazdan sonraki faz. Hepsi 0 ise istek hiç yol almamıştır.
     * {@code null} yalnız düzeltme ÖNCESİ kaydedilmiş satırlarda görülür.
     */
    @Column(name = "req_blocked_ms")     private Long reqBlockedMs;
    @Column(name = "req_connecting_ms")  private Long reqConnectingMs;
    @Column(name = "req_tls_ms")         private Long reqTlsMs;
    @Column(name = "req_sending_ms")     private Long reqSendingMs;
    @Column(name = "req_waiting_ms")     private Long reqWaitingMs;
    @Column(name = "req_receiving_ms")   private Long reqReceivingMs;

    /** Ağ düzeyinde taşınan byte (TLS dâhil) — "hiç yanıt yok" ile "kısa yanıt geldi"yi ayırır. */
    @Column(name = "data_sent")     private Long dataSent;
    @Column(name = "data_received") private Long dataReceived;

    @Column(name = "checked_at")
    private String checkedAt;
}
