package com.sitemonitor.service;

/**
 * Alarm gürültü analizi — çözüm önerisi KATALOĞU (2026-10-01, Gürültü analizi yeniden tasarımı).
 *
 * <p>Sunucu {@link AlertNoiseService} içinde her gürültü desenini bir KOD ile üretir; metin arayüzdedir
 * ({@code noise.sug.<KOD>.title} / {@code noise.sug.<KOD>.body}, TR + EN). Her kodun iki dilde de anahtarı
 * olduğu {@code AlertNoiseSuggestionI18nGateTest} ile kaynak dosyadan ({@code frontend/src/i18n/index.jsx})
 * doğrulanır — yeni bir kod eklerken önce anahtarlarını yaz, aksi hâlde kapı kırmızıya döner ve arayüzde
 * ham anahtar görünürdü.
 *
 * <p>Eşikler {@link AlertNoiseService} sabitlerindedir (tek yer). {@code action} arayüzün düğmeye çevirdiği
 * ipucudur: {@code open_monitor} (tür sekmesi + {@code q}), {@code open_alerts} (Alarm Geçmişi süzgeci),
 * {@code open_settings} (Ayarlar bölümü), {@code open_maintenance} (Bakım Pencereleri).
 */
public enum AlertNoiseSuggestion {

    /** 5+ alarm, ortalama ≤ 10 dk: titreme — onay sayısı / aralığı ya da zaman aşımı artırılmalı. */
    FLAPPING("HIGH", "open_monitor"),
    /** 3+ alarm, ortanca süre < 5 dk: kısa kesintiler — onay penceresi / kurtarma kontrolü genişletilmeli. */
    SHORT_OUTAGES("MEDIUM", "open_monitor"),
    /** Aynı hedefte pencere içinde çok sayıda alarm: hedefin sağlığı / bakım penceresi / duraklatma. */
    REPEAT_SAME_TARGET("HIGH", "open_maintenance"),
    /** {@code *_SLOW} alarmları baskın: yavaşlık eşiği gevşetilmeli ya da yavaşlık alarmı kapatılmalı. */
    SLOW_THRESHOLD_TIGHT("MEDIUM", "open_monitor"),
    /** Aynı host'u birden çok izleme türü / takım izliyor: birleştir (fırtına eşiği AYRI hedef sayar). */
    DUPLICATE_MONITORS("MEDIUM", "open_alerts"),
    /** Çok sayıda sessiz kapanış (silinen / duraklatılan monitör, pasif envanter): envanteri temizle. */
    SILENT_CLOSES("INFO", "open_alerts"),
    /** Gece saatlerinde yoğun ve kimsenin sahiplenmediği alarmlar: sessiz saat / anlık bildirim seviyesi / bakım. */
    OFF_HOURS_NOISE("MEDIUM", "open_settings"),
    /** Takım pencere içinde birden çok fırtına açtı: eşik / sessiz pencere ayarlarını gözden geçir. */
    STORM_PRONE("MEDIUM", "open_settings");

    /** HIGH / MEDIUM / INFO — arayüz gruplaması. */
    public final String severity;
    /** Arayüzün düğmeye çevirdiği eylem ipucu. */
    public final String action;

    AlertNoiseSuggestion(String severity, String action) {
        this.severity = severity;
        this.action = action;
    }

    /** i18n anahtar kökü: {@code noise.sug.<KOD>} — {@code .title} / {@code .body} eklenir. */
    public String titleKey() { return "noise.sug." + name(); }
}
