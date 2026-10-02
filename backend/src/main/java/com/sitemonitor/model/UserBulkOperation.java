package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sistem geneli TOPLU kullanıcı işlemi kaydı (2026-10-02, kullanıcı kararı: "admin sistemdeki kullanıcıları toplu pasife
 * alabilsin; admin kullanıcılar hariç"). Her "Toplu pasife al" çalıştırması TEK satır bırakır: kim, ne zaman, hangi ölçütle,
 * hangi notla, KİMLERİ pasife aldı ve sonuç sayıları. Geri alma ({@code undo}) bu satırdaki kullanıcı listesini okur.
 *
 * <p><b>Neden ayrı tablo, denetim kaydı yetmez mi?</b> Denetim satırı ({@code USER_BULK_DEACTIVATE}) özet taşır; geri alma
 * ise "bu işlemin pasife aldığı kişiler" listesine ve kişi başına bırakılan TERMINATED işaretine (marker) ihtiyaç duyar.
 * O işaret, kişinin işlemden sonra BAŞKA biri tarafından yeniden pasife alınıp alınmadığını kesin söyler (her pasifleştirme
 * yeni bir sentinel yazar) — geri alma yalnız hâlâ BU işlemin bıraktığı durumda olan hesapları açar.
 *
 * <p><b>Saklama:</b> silinmez (RetentionCatalog {@code user-bulk-operations}, BOUNDED) — yılda birkaç satır; "kim, ne
 * zaman, kimleri kapattı" sorusunun ve geri almanın tek kaynağı. Tablo {@code SchedulerService.applySchemaPatches}'te de
 * açıkça kurulur (ddl-auto'ya tek başına güvenilmez).
 */
@Entity
@Table(name = "user_bulk_operations")
@Data
@NoArgsConstructor
public class UserBulkOperation {

    public static final String KIND_DEACTIVATE = "DEACTIVATE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** İşlem türü — bugün yalnız {@link #KIND_DEACTIVATE}. */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    /** {@link #STATUS_RUNNING} → işlem sürüyor (dilim dilim ilerler); {@link #STATUS_DONE} → bitti. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Başlangıç anı (UTC ISO-8601, {@code yyyy-MM-dd'T'HH:mm:ss'Z'}). */
    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;

    @Column(name = "finished_at", length = 30)
    private String finishedAt;

    /** İşlemi başlatan global yöneticinin kullanıcı adı / kimliği. */
    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "actor_id")
    private Long actorId;

    /** Normalize edilmiş ölçüt (JSON) — önizlemede gösterilen ölçütün birebir kaydı. */
    @Column(name = "criteria_json", columnDefinition = "TEXT")
    private String criteriaJson;

    /** Yöneticinin isteğe bağlı gerekçesi (≤ 500). */
    @Column(name = "note", length = 500)
    private String note;

    /** Bu işlemin GERÇEKTEN pasife aldığı kullanıcı kimlikleri (JSON dizi). Atlananlar / başarısızlar yoktur. */
    @Column(name = "user_ids", columnDefinition = "TEXT")
    private String userIds;

    /** Kullanıcı kimliği → pasifleştirmede yazılan TERMINATED sentinel'i (JSON nesne). Geri alma kuralının kanıtı. */
    @Column(name = "markers", columnDefinition = "TEXT")
    private String markers;

    /** Uygulama anında yeniden hesaplanan hedef sayısı (önizlemede onaylanan sayıyla aynı). */
    @Column(name = "target_count")
    private Integer targetCount;

    @Column(name = "ok_count")
    private Integer okCount;

    @Column(name = "failed_count")
    private Integer failedCount;

    /** Uygulama sırasında yeniden denetimde atlananlar (bu arada ADMIN olmuş, zaten pasif olmuş…). */
    @Column(name = "skipped_count")
    private Integer skippedCount;

    @Column(name = "undone_at", length = 30)
    private String undoneAt;

    @Column(name = "undone_by", length = 120)
    private String undoneBy;

    @Column(name = "undone_by_id")
    private Long undoneById;

    @Column(name = "undo_ok_count")
    private Integer undoOkCount;

    @Column(name = "undo_skipped_count")
    private Integer undoSkippedCount;

    @Column(name = "undo_failed_count")
    private Integer undoFailedCount;
}
