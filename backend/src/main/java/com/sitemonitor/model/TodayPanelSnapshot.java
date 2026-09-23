package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "Sizin için — bugün" panelinin saatlik kimlik anlık görüntüsü (2026-09-23, "dünden bugüne").
 *
 * <p>Her kartın o andaki satırlarının KİMLİĞİ ({@code k}) ve görünürlük ipuçları ({@code t} takım,
 * {@code d} alan, {@code a} herkese açık) TÜM takımlar için tek JSON'da saklanır. Panel isteğinde
 * ~24 saat önceki satır kullanıcının görünürlük kuralıyla süzülüp sayılır → kart başına "dün bu
 * saatte N". Sayı değil kimlik saklanır, çünkü görünürlük kullanıcıya göre değişir: tek bir toplam
 * sayı takım kapsamını delerdi.
 *
 * <p>Tablo SINIRLI: {@code TodayPanelService} her kayıtta 72 saatten eskileri siler (saatte bir satır
 * → en fazla ~72 satır). Yeni kolon eklenirse nullable olmalı (dolu tabloya NOT NULL ddl-auto'da
 * sessizce düşer).
 */
@Entity
@Table(name = "today_panel_snapshots", indexes = {
        @Index(name = "idx_tps_taken", columnList = "takenAt")
})
@Data
@NoArgsConstructor
public class TodayPanelSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}). */
    @Column(name = "taken_at", nullable = false, length = 19)
    private String takenAt;

    /** {@code {"certs":[{"k":..,"t":..,"d":..,"a":..}], ...}} — kart anahtarı → satır kimlikleri. */
    @Column(columnDefinition = "TEXT")
    private String payload;
}
