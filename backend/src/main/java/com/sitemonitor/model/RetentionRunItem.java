package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;

/** Bir temizlik çalışmasının tek politika satırı — hangi tablodan kaç satır, ne kadar sürede. */
@Entity
@Table(name = "retention_run_item", indexes = {
        @Index(name = "idx_retitem_run", columnList = "run_id"),
        @Index(name = "idx_retitem_created", columnList = "created_at")
})
@Data
public class RetentionRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "policy_id", nullable = false, length = 60)
    private String policyId;

    @Column(name = "table_name", nullable = false, length = 60)
    private String tableName;

    /** Kullanılan kesim tarihi (öksüz temizliğinde null). */
    @Column(length = 30)
    private String cutoff;

    @Column(name = "rows_deleted")
    private Integer rowsDeleted = 0;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    /** Hata mesajı; başarılıysa null. */
    @Column(columnDefinition = "TEXT")
    private String error;

    /** Atlanma nedeni (ör. "opt-in kapalı", "legal hold"); çalıştıysa null. */
    @Column(length = 120)
    private String skipped;

    /** ISO-8601 UTC — bu tablonun kendi retention'ı bu kolona bakar. */
    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;
}
