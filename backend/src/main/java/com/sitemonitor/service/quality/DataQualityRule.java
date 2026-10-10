package com.sitemonitor.service.quality;

import java.util.Arrays;
import java.util.List;

/**
 * Takım veri kalitesi puanının KURAL KATALOĞU — tek kaynak (2026-10-10, kullanıcı isteği: "sahipsiz kayıt, eksik
 * iletişim bilgisi, yanlış katman, 7/24'e bildirilmeyen kritik izleme gibi sorunları tek bir skora ve düzeltme
 * listesine çevir").
 *
 * <p>Her kural: kod (enum adı — arayüz {@code dq.rule.<KOD>.title|why|fix} anahtarlarına dinamik çevirir, bu yüzden
 * {@code DataQualityRuleI18nGateTest} her kodun TR + EN metnini ve arayüz kopyasını ({@code dataQualityCodes.js})
 * sıra dahil pinler), kapsam (neyi sayar), önem ve AĞIRLIK. Ağırlık önemden türer (YÜKSEK 3, ORTA 2, DÜŞÜK 1) — tek
 * yerde değişsin diye ayrı bir sayı tablosu tutulmaz.
 *
 * <p><b>Uygunluk (paydalar)</b> — bir kuralın "kaç öğeye baktığı" {@link DataQualityEvaluator}'da; puan formülü
 * {@link DataQualityScore}'da. Sahiplik kuralları ({@link #INV_NO_TEAM}, {@link #MON_NO_TEAM}) takım puanına HİÇ girmez
 * (sahipsiz öğe tanım gereği hiçbir takımın değildir): yalnız kurum puanına ve "Sahipsiz" kovasına sayılır.
 *
 * <p><b>Sıra anlam taşır:</b> arayüzde düzeltme listesi bu sırayla gruplanır (önce sahiplik, sonra envanter, izleme,
 * takım). Yeni kural SONA eklenmez — anlamca ait olduğu yere; arayüz listesi aynı sırayla güncellenir (kapı pinler).
 */
public enum DataQualityRule {

    // ── Sahiplik (yalnız kurum puanı + Sahipsiz kovası) ─────────────────────────────────────────────
    /** Aktif envanter kaydının sahibi takım yok / silinmiş / pasif — alarm hiçbir kanaldan gitmez. */
    INV_NO_TEAM(Scope.INVENTORY, Severity.HIGH, true),
    /** Aktif bağımsız izlemenin sahibi takım yok / silinmiş / pasif — sahipsiz alarm hiç bildirim üretmez. */
    MON_NO_TEAM(Scope.MONITOR, Severity.HIGH, true),

    // ── Envanter ───────────────────────────────────────────────────────────────────────────────────
    /** Kritiklik katmanı (tier) atanmamış — önceliklendirme, eşikler ve 7/24 kapsamı tier'a bakar. */
    INV_NO_TIER(Scope.INVENTORY, Severity.MEDIUM, false),
    /** Katman sinyallerle çelişiyor (muhafazakâr, açıklanabilir sezgiler — {@link TierHeuristics}). */
    INV_TIER_SUSPECT(Scope.INVENTORY, Severity.LOW, false),
    /** Sorumlu ekip iletişim bilgisi (Servis Yönetimi / Uygulama Geliştirme / IIS / WAF) hiç girilmemiş. */
    INV_NO_CONTACTS(Scope.INVENTORY, Severity.LOW, false),
    /** Aktif kayıt hiç kontrol edilmemiş — izleniyor sanılıyor ama izlenmiyor. */
    INV_NEVER_CHECKED(Scope.INVENTORY, Severity.MEDIUM, false),
    /** Son kontrol bayat (tarama eşiğini aşmış). */
    INV_STALE_CHECK(Scope.INVENTORY, Severity.MEDIUM, false),
    /** Kontrol tekrar tekrar hata veriyor (son durum hata VE son 7 günde ≥ 3 hatalı tarama). */
    INV_CHECK_FAILING(Scope.INVENTORY, Severity.MEDIUM, false),
    /** Kritik (tier 1–2) kayıt 7/24 izleme ekibine bildirilmiyor (izlemede 7/24 anahtarı kapalı). */
    NOC_CRITICAL_UNCOVERED(Scope.INVENTORY, Severity.HIGH, false),

    // ── İzlemeler ──────────────────────────────────────────────────────────────────────────────────
    /** İzleme (ya da envanter kaydı) uzun süredir duraklatılmış — unutulmuş duraklatma. */
    MON_PAUSED_LONG(Scope.MONITOR, Severity.LOW, false),
    /** Bağımsız izlemenin grup adı yok — listeler, durum sayfası ve fırtına grupları grup adına bakar. */
    MON_NO_GROUP(Scope.MONITOR, Severity.LOW, false),
    /** Aynı takımda aynı türde aynı hedefe ikinci izleme — çift yük, çift alarm. */
    MON_DUPLICATE(Scope.MONITOR, Severity.LOW, false),

    // ── Takım ──────────────────────────────────────────────────────────────────────────────────────
    /** Takımın aktif üyesi yok. */
    TEAM_NO_MEMBERS(Scope.TEAM, Severity.HIGH, false),
    /** Takımın aktif müdürü de lideri de yok. */
    TEAM_NO_MANAGER(Scope.TEAM, Severity.MEDIUM, false),
    /** Takım e-posta adresi yok ve adresli aktif bildirim grubu da yok (yalnız izlemesi olan takımlar). */
    TEAM_NO_NOTIFY_ADDRESS(Scope.TEAM, Severity.HIGH, false),
    /** YÜKSEK / KRİTİK alarmı alacak aktif eskalasyon kişisi yok (yalnız izlemesi olan takımlar). */
    TEAM_NO_ESCALATION(Scope.TEAM, Severity.HIGH, false);

    /** Kuralın saydığı öğe türü. */
    public enum Scope { INVENTORY, MONITOR, TEAM }

    /** Önem — ağırlığın tek kaynağı. */
    public enum Severity {
        HIGH(3), MEDIUM(2), LOW(1);

        public final int weight;

        Severity(int weight) { this.weight = weight; }
    }

    public final Scope scope;
    public final Severity severity;
    /** Sahiplik kuralı: takım puanına girmez; yalnız kurum puanı + Sahipsiz kovası. */
    public final boolean ownership;

    DataQualityRule(Scope scope, Severity severity, boolean ownership) {
        this.scope = scope;
        this.severity = severity;
        this.ownership = ownership;
    }

    public int weight() { return severity.weight; }

    /** Katalog sırasıyla kodlar — arayüz listesi ve i18n kapısı bununla karşılaştırır. */
    public static final List<String> CODES = Arrays.stream(values()).map(Enum::name).toList();
}
