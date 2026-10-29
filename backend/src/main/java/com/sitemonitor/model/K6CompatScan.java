package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * k6 uyumluluk taraması (gölge koşu) — 2026-10-29, k6 0.49 → 2.3 geçişi, Yayın A.
 *
 * <p>Genel yönetici başlatır; ADAY k6 ikilisi her etkin senaryo izlemesini bir kez derler + koşar ve sonucu izlemenin son
 * gerçek (zamanlanmış) kontrolüyle karşılaştırır. Taramanın kendisi geçmişe, alarm değerlendirmesine ya da bildirime
 * HİÇBİR ŞEY yazmaz; yalnız bu tabloya ve {@link K6CompatResult}'a.
 *
 * <p>Durum: RUNNING → DONE | CANCELLED | INTERRUPTED | FAILED; iptal isteği RUNNING → CANCELLING (koşan pod bir sonraki
 * izlemede görür). Zamanlar UTC ISO metin ({@code yyyy-MM-dd'T'HH:mm:ss}).
 */
@Entity
@Table(name = "k6_compat_scans", indexes = {
        @Index(name = "idx_k6cs_started_at", columnList = "started_at")
})
@Data
@NoArgsConstructor
public class K6CompatScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String state = "RUNNING";

    @Column(name = "started_at", length = 30)
    private String startedAt;

    @Column(name = "finished_at", length = 30)
    private String finishedAt;

    /** Koşan pod'un son sinyali — 5 dk'dan eskiyse ve kilit tutulmuyorsa tarama yarıda kalmıştır. */
    @Column(name = "heartbeat_at", length = 30)
    private String heartbeatAt;

    @Column(name = "started_by", length = 100)
    private String startedBy;

    @Column(name = "started_by_name", length = 200)
    private String startedByName;

    /** Taramayı koşan pod ({@code SchedulerService.instanceId()}). */
    @Column(length = 120)
    private String owner;

    @Column(name = "active_bin", length = 300)
    private String activeBin;

    @Column(name = "active_version", length = 40)
    private String activeVersion;

    @Column(name = "candidate_bin", length = 300)
    private String candidateBin;

    @Column(name = "candidate_version", length = 40)
    private String candidateVersion;

    /** ALL = tüm etkin izlemeler (dışarıda bırakılanlar hariç) · SELECTED = seçilen izlemeler. */
    @Column(length = 16)
    private String scope = "ALL";

    @Column(name = "excluded_ids", columnDefinition = "TEXT")
    private String excludedIds;

    private Integer total = 0;

    @Column(name = "done_count")
    private Integer doneCount = 0;

    private Integer regressions = 0;
    private Integer improvements = 0;
    private Integer unchanged = 0;

    @Column(name = "no_baseline")
    private Integer noBaseline = 0;

    /** İki motorda da hatalı ama durum farklı (CHANGED) — 2026-10-30, inceleme L2. */
    @Column(name = "changed_count")
    private Integer changedCount = 0;

    /** Aday koşamadı (NOT_RUN): tarama istisnası, aday atlandı, dosya okuma engeli. */
    @Column(name = "not_run")
    private Integer notRun = 0;

    /** Gerçek kontrolü yok ve aday HATALI (NEW_FAIL) — karşılaştırılamaz ama temiz de sayılmaz. */
    @Column(name = "new_fail")
    private Integer newFail = 0;

    /** FAILED / INTERRUPTED nedeni (Türkçe kısa metin). */
    @Column(columnDefinition = "TEXT")
    private String failure;
}
