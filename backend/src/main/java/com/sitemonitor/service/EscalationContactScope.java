package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.EscalationContactRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Alarm alıcısı olarak eklenecek ESKALASYON KONTAKLARINI seçen TEK yer (ürün kararı 2026-09-28).
 *
 * <p><b>Kural.</b> Eskalasyon kontakları YALNIZ alarmın kendi takımının ({@code team_id = teamId}) etkin kontaklarıdır,
 * seviye eşiğine göre. Takımda uyan kontak yoksa liste BOŞTUR — alarm yalnız takımın kendi alıcılarına (takım adresi /
 * bildirim grubu / push) gider. Başka takımın müdürü ya da eskalasyon kişisi hiçbir koşulda alıcı olmaz. Takımsız
 * ({@code team_id IS NULL}) kontaklar hiçbir yolda alıcı değildir; takımsız alarm ({@code teamId == null}) zaten
 * sahipsizdir ve hiç bildirim üretmez ({@code EscalationService.sendCombinedAlert} kapısı).
 *
 * <p><b>Neden var (2026-09-28 prod hatası).</b> Eski yedek yol "Fall back to global contacts (no team assigned)"
 * diyordu ama {@code findByActiveTrueOrderByRoleAsc} / {@code findByMinAlertLevel…ActiveTrue} sorguları
 * {@code team_id}'yi HİÇ süzmüyordu. Kontağı olmayan takımın KRİTİK alarmı (ör. HOSTNAME_MISMATCH) TÜM takımların
 * etkin kontaklarına — başka takımların müdürlerine — gidiyordu; aynı kopya {@code StormService}'te de vardı.
 * Hata v3.0.0'dan (2026-05-17, takım kapsamı eklendiğinde) beri duruyordu.
 *
 * <p><b>Seviye eşiği</b> (kontağın {@code minAlertLevel}'i): KRİTİK alarm tüm etkin kontaklara, YÜKSEK alarm
 * UYARI+YÜKSEK eşikli kontaklara, UYARI alarmı yalnız UYARI eşikli kontaklara gider. Bilinmeyen/boş seviye UYARI
 * sayılır (en dar küme).
 *
 * <p>Salt okur, günlük yazmaz. Gönderim ve "Tekrar bildir" önizlemesi {@code EscalationService.getContactsForLevel}
 * üzerinden gelir ve boş sonucu WARN'lar; simülatör doğrudan bu sınıfı çağırır (sessiz). Hepsi aynı kuralı kullanır —
 * önizleme ile gerçek gönderim asla sapmaz. Kapı:
 * {@code EscalationContactScopeTest} (davranış + takım süzgeçsiz sorgunun yalnız yönetim listesinde kullanılması).
 */
public final class EscalationContactScope {

    private EscalationContactScope() {}

    /** YÜKSEK alarmı alan kontak eşikleri. */
    static final List<String> HIGH_LEVELS = List.of("WARNING", "HIGH");

    /**
     * "Her sahip takım kendi kişisi" (ürün kararı 2026-09-28): SY ve UG takımının HER BİRİ yalnız KENDİ kişilerini
     * getirir; sonuç ikisinin birleşimidir (SY önce). Yalnız UG'li kayıtta UG'nin kişileri gelir. Başka takımın kişisi
     * hiçbir koşulda eklenmez.
     *
     * <p><b>Tekilleştirme:</b> liste KAYIT düzeyinde tekildir (aynı takım iki kez sorgulanmaz). E-posta adresine göre
     * tekilleştirme alıcı listesi kurulurken yapılır (gönderim, önizleme, çözüm, fırtına — aynı adrese TEK e-posta).
     * Burada adrese göre elenseydi aynı kişinin UG'deki kaydındaki Teams/Slack webhook'u SY kaydı yüzünden düşerdi.
     */
    public static List<EscalationContact> forOwners(EscalationContactRepository repo, String level,
                                                    Long syTeamId, Long ugTeamId) {
        List<Long> owners = new ArrayList<>(2);
        if (syTeamId != null) owners.add(syTeamId);
        if (ugTeamId != null && !ugTeamId.equals(syTeamId)) owners.add(ugTeamId);   // aynı takım iki kez sorgulanmaz
        List<EscalationContact> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long team : owners) {
            for (EscalationContact c : forLevel(repo, level, team)) {
                String key = c.getId() != null ? "i:" + c.getId() : "o:" + System.identityHashCode(c);
                if (seen.add(key)) out.add(c);
            }
        }
        return out;
    }

    public static List<EscalationContact> forLevel(EscalationContactRepository repo, String level, Long teamId) {
        if (teamId == null) return List.of();   // takımsız alarm/kontak: kimse (sahipsiz → bildirim yok)
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return switch (lvl) {
            case "CRITICAL" -> repo.findByTeamIdAndActiveTrueOrderByRoleAsc(teamId);
            case "HIGH"     -> repo.findByTeamIdAndMinAlertLevelInAndActiveTrue(teamId, HIGH_LEVELS);
            default         -> repo.findByTeamIdAndMinAlertLevelAndActiveTrue(teamId, "WARNING");
        };
    }

    /**
     * {@link #forLevel} sorgularının BELLEK-İÇİ aynası (2026-10-01, zamana bağlı eskalasyon adımı): kişi bu seviyedeki
     * alarmı alır mı? KRİTİK → her etkin kişi; YÜKSEK → eşiği UYARI ya da YÜKSEK; diğer (UYARI / boş / bilinmeyen) →
     * yalnız eşiği tam olarak UYARI. Etkin olmayan kişi hiçbir seviyede almaz. Eşdeğerlik {@code EscalationContactScopeTest}'te.
     */
    public static boolean matchesLevel(EscalationContact c, String level) {
        if (c == null || !Boolean.TRUE.equals(c.getActive())) return false;
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return switch (lvl) {
            case "CRITICAL" -> true;
            case "HIGH"     -> HIGH_LEVELS.contains(c.getMinAlertLevel());
            default         -> "WARNING".equals(c.getMinAlertLevel());
        };
    }

    /**
     * {@link #forOwners(EscalationContactRepository, String, Long, Long)} kuralının ÖNCEDEN YÜKLENMİŞ kişilerle çalışan
     * eşi — toplu iş (eskalasyon adımı süpürmesi) alarm başına sorgu atmasın diye. {@code byTeam}: takım kimliği → o
     * takımın etkin kişileri (çağıran {@code findByTeamIdInAndActiveTrueOrderByRoleAsc} ile TEK sorguda yükler). Kural
     * birebir aynı: SY ve UG yalnız KENDİ kişileri, birleşim kayıt düzeyinde tekil, sahipsiz alarm → boş.
     */
    public static List<EscalationContact> forOwners(Map<Long, List<EscalationContact>> byTeam, String level,
                                                    Long syTeamId, Long ugTeamId) {
        List<Long> owners = new ArrayList<>(2);
        if (syTeamId != null) owners.add(syTeamId);
        if (ugTeamId != null && !ugTeamId.equals(syTeamId)) owners.add(ugTeamId);
        List<EscalationContact> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long team : owners) {
            for (EscalationContact c : byTeam.getOrDefault(team, List.of())) {
                if (!team.equals(c.getTeamId()) || !matchesLevel(c, level)) continue;   // başka takımın kişisi asla
                String key = c.getId() != null ? "i:" + c.getId() : "o:" + System.identityHashCode(c);
                if (seen.add(key)) out.add(c);
            }
        }
        return out;
    }
}
