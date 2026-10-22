package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * İzleme değerlendirme gelen kutusu (2026-10-21, çok pod R1-B, MP-14) — tarama lideri OLMAYAN pod'un elle "Şimdi kontrol et"
 * sonucunu lidere iletir. Alarm durumu (kurtarma sayaçları, kardeş DOWN gözlemleri, kurtarma zincirleri) yalnız liderin
 * belleğindedir; takipçinin kendi boş belleğinde değerlendirmek kardeş kuralını atlatıyor, sayaçları sıfırlamıyordu.
 *
 * <p>Satır yalnız {@code service.monitoring.ManualEvalInbox} üzerinden yazılır / sahiplenilir / silinir (JdbcTemplate,
 * koşullu UPDATE / DELETE). Sonuç ham kontrol çıktısıdır (JSON); lider 5 sn'de bir boşaltır, 10 dk'dan eski satır silinir.
 * {@code manual=false} ileride (R3) dağıtık zamanlanmış sonuçlar içindir.
 */
@Entity
@Table(name = "monitor_eval_inbox", indexes = {
    @Index(name = "idx_mei_claim", columnList = "claimed_until_ms, id")
})
@Data
@NoArgsConstructor
public class MonitorEvalInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code dns | keyword | page | pagespeed | scripted | port | ping | http | domain} — kalıcı adlar. */
    @Column(name = "monitor_type", nullable = false, length = 20)
    private String monitorType;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "manual", nullable = false)
    private Boolean manual;

    /** Ham kontrol sonucu (JSON nesnesi). */
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    /** Kontrolün yapıldığı an (epoch ms). */
    @Column(name = "checked_ms", nullable = false)
    private Long checkedMs;

    /** Satırı yazan pod (iz). */
    @Column(name = "source_pod", length = 200)
    private String sourcePod;

    /** Sahiplenen pod; {@code claimed_until_ms} geçince başka pod yeniden sahiplenebilir. */
    @Column(name = "claimed_by", length = 200)
    private String claimedBy;

    @Column(name = "claimed_until_ms")
    private Long claimedUntilMs;

    @Column(name = "created_ms", nullable = false)
    private Long createdMs;
}
