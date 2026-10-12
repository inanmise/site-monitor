package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_logs")
@Data
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long alertEventId;

    private String sentAt;
    private String recipientName;
    private String recipientEmail;
    private String recipientRole;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** SENT / SKIPPED_DISABLED / FAILED:... */
    private String emailStatus;

    /** SENT / SKIPPED / FAILED:... */
    private String webhookStatus;

    /** INITIAL / ESCALATION / DAILY_REALERT / MANUAL / RESOLUTION */
    private String trigger;

    /** Configured from-address used when sending this email */
    private String emailFrom;

    /** Comma-separated CC addresses, null if none */
    private String cc;

    /** VARCHAR kolonlarının uzunluğu (kolon tanımı yok → JPA varsayılanı 255). */
    public static final int VARCHAR_MAX = 255;

    /**
     * Yazmadan önce VARCHAR kolonlarına sığdırır (2026-10-12). Uzun alıcı listesi ya da durum metni PostgreSQL'de
     * "value too long" ile satırı düşürüyordu — çağıranlar günlüğü try/catch ile yazdığı için bildirim izi SESSİZCE
     * kayboluyordu (her yol ayrı ayrı kırpmıyordu: izleyici / olay aksiyonu 1000'e, eskalasyon / fırtına / raporlar
     * hiç). Tek kapı burası: alıcı ve CC listeleri virgül sınırında "… +N" ile, diğerleri "…" ile kısalır.
     */
    @PrePersist
    @PreUpdate
    void fitColumns() {
        sentAt = fit(sentAt, VARCHAR_MAX);
        recipientName = fit(recipientName, VARCHAR_MAX);
        recipientEmail = fitList(recipientEmail, VARCHAR_MAX);
        recipientRole = fit(recipientRole, VARCHAR_MAX);
        emailStatus = fit(emailStatus, VARCHAR_MAX);
        webhookStatus = fit(webhookStatus, VARCHAR_MAX);
        trigger = fit(trigger, VARCHAR_MAX);
        emailFrom = fit(emailFrom, VARCHAR_MAX);
        cc = fitList(cc, VARCHAR_MAX);
    }

    /** Değer {@link #fit} / {@link #fitList} ile kısaltılmış mı (kısaltılmış alıcı listesine yeniden gönderilmez). */
    public static boolean fitted(String s) {
        if (s == null) return false;
        if (s.endsWith("…")) return true;
        int at = s.lastIndexOf(" … +");
        if (at < 0 || at + 4 >= s.length()) return false;
        for (int i = at + 4; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** {@code max} karaktere sığdırır; kesilirse sonu "…" (vekil çift bölünmez). */
    public static String fit(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    /**
     * Virgüllü listeyi {@code max} karaktere sığdırır: sığan öğeler korunur, kalanlar " … +N" olarak sayılır (yarım adres
     * yazılmaz). İlk öğe bile sığmıyorsa düz {@link #fit}.
     */
    public static String fitList(String s, int max) {
        if (s == null || s.length() <= max) return s;
        String[] parts = s.split(",");
        int total = 0;
        for (String p : parts) if (!p.isBlank()) total++;
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String p : parts) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            String suffix = kept + 1 < total ? " … +" + (total - kept - 1) : "";
            int len = sb.length() + (sb.length() == 0 ? 0 : 2) + v.length() + suffix.length();
            if (len > max) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
            kept++;
        }
        if (kept == 0) return fit(s, max);
        return kept < total ? sb + " … +" + (total - kept) : sb.toString();
    }
}
