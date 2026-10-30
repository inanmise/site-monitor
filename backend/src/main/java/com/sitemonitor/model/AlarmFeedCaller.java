package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm akışını çağıran IP'ler — IP başına TEK satır (2026-10-30). İlk çağrıyı KAYDEDEN pod (birincil anahtar çakışmasını
 * kazanan) denetim günlüğüne {@code ALARM_FEED_NEW_CALLER} yazar; böylece çok pod'da da yeni bir IP bir kez denetlenir.
 * Ayarlar sayfasındaki "Çağıranlar" listesi ve "arayan sessiz" uyarısı buradan okunur.
 */
@Entity
@Table(name = "alarm_feed_callers")
@Data
@NoArgsConstructor
public class AlarmFeedCaller {

    @Id
    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "first_seen_at", length = 30)
    private String firstSeenAt;

    @Column(name = "last_seen_at", length = 30)
    private String lastSeenAt;

    /** İlk KABUL edilen çağrı anı (UTC; reddedilmiş çağrılar sayılmaz). Yalnız koşullu UPDATE yazar. */
    @Column(name = "first_ok_at", length = 30)
    private String firstOkAt;

    @Column(name = "call_count")
    private Long callCount;

    @Column(name = "last_user_agent", length = 500)
    private String lastUserAgent;

    @Column(name = "last_outcome", length = 20)
    private String lastOutcome;
}
