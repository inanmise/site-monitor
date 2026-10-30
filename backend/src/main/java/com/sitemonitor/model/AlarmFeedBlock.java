package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm akışının otomatik IP engeli (2026-10-31) — IP başına TEK satır. Aynı IP dakikalık eşiği aşınca süreli engel yazılır;
 * süre bitince satır kalır (geçmiş + tekrarında süreyi katlamak için). Yazımlar yalnız koşullu JDBC ifadeleri
 * ({@code AlarmFeedBlocks}); zamanlar epoch ms (karşılaştırma), {@code updated_at} UTC ISO (saklama politikası).
 */
@Entity
@Table(name = "alarm_feed_blocks", indexes = {
        @Index(name = "idx_afb_until", columnList = "blocked_until_ms"),
        @Index(name = "idx_afb_at", columnList = "blocked_at_ms")})
@Data
@NoArgsConstructor
public class AlarmFeedBlock {

    @Id
    @Column(name = "client_ip", length = 64)
    private String clientIp;

    /** Son engelin başladığı an (epoch ms). */
    @Column(name = "blocked_at_ms")
    private Long blockedAtMs;

    /** Son engelin bittiği / biteceği an (epoch ms); elle kaldırılınca kaldırma anı. */
    @Column(name = "blocked_until_ms")
    private Long blockedUntilMs;

    /** Son engelin süresi (dk) — tekrarında katlanır. */
    @Column(name = "minutes")
    private Integer minutes;

    /** Engeli tetikleyen dakikadaki çağrı sayısı. */
    @Column(name = "hits")
    private Integer hits;

    /** Eşik (engel anındaki ayar). */
    @Column(name = "threshold")
    private Integer threshold;

    /** Bu IP'nin toplam engel sayısı. */
    @Column(name = "block_count")
    private Integer blockCount;

    @Column(name = "last_user_agent", length = 500)
    private String lastUserAgent;

    /** Engeli yazan pod. */
    @Column(name = "blocked_by_pod", length = 80)
    private String blockedByPod;

    /** Elle kaldırıldıysa an (epoch ms) ve kaldıran. */
    @Column(name = "unblocked_at_ms")
    private Long unblockedAtMs;

    @Column(name = "unblocked_by", length = 200)
    private String unblockedBy;

    /** Son yazım (UTC ISO) — saklama politikası buna bakar. */
    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
