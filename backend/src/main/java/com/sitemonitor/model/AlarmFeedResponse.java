package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm akışının döndürdüğü bir yanıt gövdesinin BİREBİR kopyası — gzip'li, SHA-256 özetiyle bir kez (2026-10-30). Dakikalık
 * çağrıların çoğu aynı gövdeyi döndürür; {@link AlarmFeedRequest#getResponseHash()} buraya bağlanır. Özet çakışmasında satır
 * yeniden yazılmaz (aynı özet = aynı gövde); {@code last_used_at} gece temizliğine yardımcı bilgidir.
 */
@Entity
@Table(name = "alarm_feed_responses")
@Data
@NoArgsConstructor
public class AlarmFeedResponse {

    /** Gövdenin (UTF-8 bayt) SHA-256'sı, küçük harf onaltılık. */
    @Id
    @Column(length = 64)
    private String hash;

    /** gzip'li gövde. Uzunluk H2 içindir (varsayılan VARBINARY(255)); PostgreSQL'de tür BYTEA. */
    @JsonIgnore
    @Column(nullable = false, length = 64 * 1024 * 1024)
    private byte[] body;

    @Column(name = "content_type", length = 40)
    private String contentType;

    /** Sıkıştırılmamış bayt sayısı. */
    @Column(name = "raw_bytes")
    private Long rawBytes;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    /** Bu gövdenin en son döndüğü an (UTC ISO; pod başına en çok 10 dakikada bir tazelenir) — saklama süresi buna göre. */
    @Column(name = "last_used_at", length = 30)
    private String lastUsedAt;
}
