package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Haftalık sağlık skoru (0-100) — deterministik, ağırlıklı ceza modeli (LLM yok).
 *
 * <p>{@code raw = 100 − wCrit·açıkKritik − wExp·tier1-2≤30gün − wWeak·zayıfAlgo − wUptime·(100−tier1Uptime)},
 * [0,100]'e kırpılıp yuvarlanır. Girdiler {@link WeeklyReportKpiService} tarafından AS-OF-HAFTA-SONU değerlendirilir
 * → önceki haftayla gerçek delta. Ağırlıklar {@code site.monitor.weekly.score.weight-*} anahtarlarından CANLI okunur
 * (AppSettingsCatalog'da kayıtlı; application.properties varsayılanları).
 */
@Component
@RequiredArgsConstructor
public class WeeklyScoreCalculator {

    private final AppSettingsService appSettings;

    /** Skor girdileri (hepsi as-of-hafta-sonu). tier1UptimePct 0-100; null → veri yok, 100 varsayılır (ceza yok). */
    public record ScoreInputs(int openCritical, int tier12Expiring30, Double tier1UptimePct, int weakAlgo) {}

    /** 0-100 sağlık skoru. */
    public int score(ScoreInputs in) {
        double wCrit   = appSettings.getDouble("site.monitor.weekly.score.weight-critical", 8.0);
        double wExp    = appSettings.getDouble("site.monitor.weekly.score.weight-expiring", 2.0);
        double wWeak   = appSettings.getDouble("site.monitor.weekly.score.weight-weak-algo", 3.0);
        double wUptime = appSettings.getDouble("site.monitor.weekly.score.weight-uptime", 0.5);
        double uptime  = in.tier1UptimePct() != null ? in.tier1UptimePct() : 100.0;
        double raw = 100.0
                - wCrit   * Math.max(0, in.openCritical())
                - wExp    * Math.max(0, in.tier12Expiring30())
                - wWeak   * Math.max(0, in.weakAlgo())
                - wUptime * Math.max(0.0, 100.0 - uptime);
        return (int) Math.round(Math.max(0.0, Math.min(100.0, raw)));
    }

    /** Skor bandı: ≥80 yeşil, 60-79 amber, <60 kırmızı. */
    public static String band(int score) {
        return score >= 80 ? "green" : score >= 60 ? "amber" : "red";
    }
}
