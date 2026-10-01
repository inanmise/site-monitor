package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ayarlar → Haftalık Raporlar → takım görünürlüğü satırlarının ZENGİNLEŞTİRMESİ (2026-09-30, kullanıcı isteği:
 * "takımların yanına PO bilgilerini ve müdür bilgilerini de ekleyelim; en son hangi hafta rapor gönderdiği de olsun").
 *
 * <p>Üç alan, üçü de mevcut kurallardan türetilir — yeni kural YOK:
 * <ul>
 *   <li><b>PO'lar</b> — takımın AKTİF üyeleri ({@link TeamManagerResolver#isMember}: birincil takım ya da ek üyelik)
 *       arasından org rolü {@code PO} olanlar; ad (Türkçe sıralama). Uygulamanın her yerindeki üyelik yüklemi
 *       (TeamDirectory / NOC / takım sayaçları) ile aynı.</li>
 *   <li><b>Müdür</b> — {@link TeamManagerResolver#resolve} (elle {@code teams.manager_id} &gt; AD zinciri); Takım
 *       Yönetimi ekranıyla aynı kişi. {@code manager_manual} kaynağı işaretler (ekranda "elle" / "AD").</li>
 *   <li><b>Son rapor</b> — takımın en güncel {@code weekly_reports} satırı (yıl/hafta sırasıyla): ISO hafta etiketi
 *       ({@code 2026-W39}), durum ve varsa gönderim/onay zamanı. Hiç yoksa {@code null} → ekranda "Hiç gönderilmedi".</li>
 * </ul>
 * Kullanıcı listesi bir kez yüklenir (takım başına sorgu yok).
 *
 * <p><b>Performans (2026-10-01):</b> yalnız AKTİF kullanıcılar yüklenir ({@code findByActiveTrueOrderByUsernameAsc} —
 * pasifler hem üyelikte hem müdür çözümünde zaten elenir, sonuç aynı) ve son raporlar takım başına sorgu yerine TEK
 * sorguda gelir ({@code findLatestPerTeam}): çağrı başına {@code 1 + N} yerine 2 sorgu.
 */
@Service
@RequiredArgsConstructor
public class WeeklyReportTeamInfoService {

    private static final Collator TR = Collator.getInstance(Locale.forLanguageTag("tr"));

    private final WeeklyReportRepository reportRepo;
    private final AppUserRepository userRepo;

    /** Takım id → ek alanlar ({@code po_users}, {@code manager_*}, {@code last_report}). */
    public Map<Long, Map<String, Object>> infoByTeam(Collection<Team> teams) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (teams == null || teams.isEmpty()) return out;
        List<AppUser> all = userRepo.findByActiveTrueOrderByUsernameAsc();
        TeamManagerResolver.UserLookup lookup = TeamManagerResolver.UserLookup.ofUsers(all);
        Set<Long> teamIds = new LinkedHashSet<>();
        for (Team t : teams) if (t != null && t.getId() != null) teamIds.add(t.getId());
        Map<Long, WeeklyReport> latest = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (WeeklyReport r : reportRepo.findLatestPerTeam(teamIds)) {
                if (r == null || r.getTeamId() == null) continue;
                // (takım, yıl, hafta) tekil → takım başına tek satır; savunma: yine de en güncel kalsın.
                latest.merge(r.getTeamId(), r, (a, b) -> weekKey(b) > weekKey(a) ? b : a);
            }
        }
        for (Team t : teams) if (t != null && t.getId() != null) out.put(t.getId(), infoFor(t, all, lookup, latest.get(t.getId())));
        return out;
    }

    private static int weekKey(WeeklyReport r) {
        return (r.getReportYear() == null ? 0 : r.getReportYear()) * 100 + (r.getWeekNo() == null ? 0 : r.getWeekNo());
    }

    Map<String, Object> infoFor(Team team, List<AppUser> allUsers, TeamManagerResolver.UserLookup lookup, WeeklyReport latestReport) {
        List<AppUser> members = new ArrayList<>();
        for (AppUser u : allUsers) {
            if (u != null && Boolean.TRUE.equals(u.getActive()) && TeamManagerResolver.isMember(u, team.getId())) members.add(u);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("po_users", poUsers(members));

        Optional<TeamManagerResolver.Entry> mgr = TeamManagerResolver.resolve(team, members, lookup);
        m.putAll(TeamManagerResolver.toWire(mgr));
        AppUser mgrUser = mgr.map(TeamManagerResolver.Entry::userId).map(lookup::byId).orElse(null);
        m.put("manager_email", mgrUser != null ? mgrUser.getEmail() : null);

        m.put("last_report", latestReport == null ? null : lastReport(latestReport));
        return m;
    }

    private static List<Map<String, Object>> poUsers(List<AppUser> members) {
        List<AppUser> pos = new ArrayList<>();
        for (AppUser u : members) if (u.getOrgRole() != null && "PO".equalsIgnoreCase(u.getOrgRole().trim())) pos.add(u);
        pos.sort((a, b) -> TR.compare(nz(TeamManagerResolver.displayName(a)), nz(TeamManagerResolver.displayName(b))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : pos) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("user_id", u.getId());
            p.put("display_name", TeamManagerResolver.displayName(u));
            p.put("email", u.getEmail());
            out.add(p);
        }
        return out;
    }

    private static Map<String, Object> lastReport(WeeklyReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("report_year", r.getReportYear());
        m.put("week_no", r.getWeekNo());
        m.put("iso_week", isoWeek(r.getReportYear(), r.getWeekNo()));
        m.put("week_label", r.getWeekLabel());
        m.put("status", r.getStatus());
        m.put("submitted_at", r.getSubmittedAt());
        m.put("approved_at", r.getApprovedAt());
        m.put("sent_at", r.getSentAt());
        m.put("updated_at", r.getUpdatedAt());
        return m;
    }

    /** ISO hafta etiketi: {@code 2026-W07}. Yıl/hafta eksikse null. */
    static String isoWeek(Integer year, Integer week) {
        if (year == null || week == null) return null;
        return String.format(Locale.ROOT, "%d-W%02d", year, week);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
