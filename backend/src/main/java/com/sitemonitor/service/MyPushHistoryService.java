package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "Push geçmişim" (2026-10-04, onaylı öneri 3) — kişinin KENDİ push teslimatları + üyesi olduğu takımların alarmlarına ait
 * olay düzeyi push kararları. Salt okuma.
 *
 * <p><b>Gizlilik sözleşmesi.</b> (1) Kişinin satırları kanonik kullanıcı adıyla (büyük/küçük harf duyarsız) seçilir;
 * başka bir kullanıcının satırı ASLA dönmez. (2) Kullanıcı adı taşımayan karar satırları ('-': fırtına devri, sistem
 * bakımı, takım kapsamı …) YALNIZ üyesi olunan takımların satırlarıdır (görüş kapsamı değil üyelik — müdürün astlarının
 * takımları dahil edilmez); eskalasyon adımı kişi kararları hariç. (3) Kişinin satırındaki alarm artık görüş kapsamında
 * değilse (takım değişikliği) hedef, alarm kimliği ve metin gizlenir; satır "başka takımın alarmı" etiketiyle kalır.
 *
 * <p>Kodla giriş push'ları teslimat satırı üretmez (tasarım gereği, kod hiçbir yere yazılmaz) — bu listede görünmezler.
 *
 * <p><b>Fırtına bağı</b> (2026-10-04, {@link StormPushCoverageService#decorateViewerHistory}): {@code SKIPPED_STORM} karar
 * satırı {@code storm_push} taşır (o alarmı kapsayan fırtına push'unu KİŞİNİN aldığı an + durum ya da neden); kişinin
 * fırtına satırı {@code storm_alarms} taşır (kapsadığı alarmlardan görebildikleri; kalanı yalnız sayı). Sayfa başına
 * sabit sayıda sorgu; başka kişinin teslimat satırı dönmez.
 */
@Service
@RequiredArgsConstructor
public class MyPushHistoryService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Hiçbir takımın üyesi olmayan kullanıcıda {@code IN ()} boş kalmasın diye nöbetçi kimlik (hiçbir satırla eşleşmez). */
    static final Long NO_TEAM = -1L;
    public static final Set<String> FILTERS = Set.of("all", "sent", "not_sent");

    private final UserPushDeliveryRepository repo;
    private final TeamRepository teamRepo;

    /** Fırtına push'u ↔ alarm bağı (2026-10-04) — isteğe bağlı (alan enjeksiyonu; yapıcı imzası değişmez). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StormPushCoverageService stormPushCoverage;

    /** Test kancası. */
    void setStormPushCoverage(StormPushCoverageService s) { this.stormPushCoverage = s; }

    /**
     * @param username    oturumdaki kanonik kullanıcı adı
     * @param memberTeams kişinin ÜYESİ olduğu takımlar (karar satırları için)
     * @param canView     kişinin görüş kapsamı: takım kimliği → görebilir mi (kendi satırlarındaki alarmlar için)
     */
    public Map<String, Object> history(String username, Collection<Long> memberTeams, Predicate<Long> canView,
                                       int days, String filter, int page, int size, Instant now) {
        String f = filter == null || !FILTERS.contains(filter) ? "all" : filter;
        int d = Math.max(1, Math.min(30, days));
        int sz = Math.max(1, Math.min(100, size));
        int pg = Math.max(0, page);
        String since = ISO.format(now.minus(Duration.ofDays(d)));
        List<Long> teams = memberTeams == null || memberTeams.isEmpty() ? List.of(NO_TEAM) : new ArrayList<>(memberTeams);
        Set<Long> memberSet = new LinkedHashSet<>(teams);

        Page<UserPushDelivery> result = repo.myHistory(username, teams, since, f, PageRequest.of(pg, sz));
        List<UserPushDelivery> rows = result.getContent();

        Set<Long> teamIds = new LinkedHashSet<>();
        for (UserPushDelivery r : rows) if (r.getTeamId() != null) teamIds.add(r.getTeamId());
        Map<Long, String> teamNames = new HashMap<>();
        if (!teamIds.isEmpty()) for (Team t : teamRepo.findAllById(teamIds)) teamNames.put(t.getId(), t.getName());

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (UserPushDelivery r : rows) out.add(toRow(r, username, memberSet, canView, teamNames));
        // Fırtına push'u ↔ alarm bağı (2026-10-04): devir satırına kişinin aldığı fırtına push'u, kişinin fırtına satırına
        // kapsadığı alarmlar — sayfa başına sabit sorgu; alarm görünürlüğü geçmişin kuralıyla (üyelik ya da görüş kapsamı).
        StormPushCoverageService coverage = stormPushCoverage;
        if (coverage != null) {
            coverage.decorateViewerHistory(rows, out, username,
                    t -> t != null && (memberSet.contains(t) || (canView != null && canView.test(t))));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", out);
        resp.put("total", result.getTotalElements());
        resp.put("page", pg);
        resp.put("size", sz);
        resp.put("days", d);
        resp.put("filter", f);
        resp.put("kpis", kpis(username, teams, since));
        // Kodla giriş push'ları güvenlik gereği kayıt üretmez — arayüz dipnotu bunu söyler.
        resp.put("login_code_note", true);
        return resp;
    }

    Map<String, Object> toRow(UserPushDelivery r, String username, Set<Long> memberSet, Predicate<Long> canView,
                              Map<Long, String> teamNames) {
        boolean own = !UserPushService.SYSTEM_USER.equals(r.getUsername());
        // Kendi satırı: takımsız (özet, eski test) ya da görüş kapsamındaki / üyesi olunan takım → görünür.
        // Karar satırı: sorgu zaten yalnız üyesi olunan takımları döndürür (yine de burada da sınanır).
        boolean visible = r.getTeamId() == null
                ? own
                : (memberSet.contains(r.getTeamId()) || (own && canView != null && canView.test(r.getTeamId())));
        String status = r.getStatus();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("at", r.getSentAt() != null ? r.getSentAt() : r.getCreatedAt());
        m.put("created_at", r.getCreatedAt());
        m.put("sent_at", r.getSentAt());
        m.put("trigger", r.getTrigger());
        m.put("status", status);
        m.put("kind", kindOf(status));
        m.put("reason", "SENT".equals(status) || "PENDING".equals(status) ? null : status);
        m.put("own", own);
        m.put("team_decision", !own);
        m.put("visible", visible);
        m.put("scope", visible ? (own ? "own" : "team") : "other_team");
        m.put("alert_level", r.getAlertLevel());
        m.put("monitor_type", r.getMonitorType());
        m.put("lang", r.getPushLang() == null ? PushI18n.TR : r.getPushLang());
        m.put("summarized_into", r.getOverflowSummaryId());
        if (visible) {
            m.put("target", r.getMonitorName());
            m.put("alert_event_id", r.getAlertEventId());
            m.put("team_id", r.getTeamId());
            m.put("team_name", r.getTeamId() == null ? null : teamNames.get(r.getTeamId()));
        }
        // Metin: yalnız kişinin KENDİ push'u ve alarm hâlâ görüş kapsamındaysa (metin hedefi taşır — sızıntı olmasın).
        m.put("message", own && visible ? r.getMessage() : null);
        m.put("message_hidden", own && !visible && r.getMessage() != null);
        if (UserPushOverflowService.TRIGGER.equals(r.getTrigger()) && r.getId() != null) {
            m.put("summarized_count", repo.countByOverflowSummaryId(r.getId()));
        }
        return m;
    }

    /** sent / pending / not_sent — {@code PushLogQueryService.kindOf}'un kişi yüzündeki sadeleştirmesi. */
    static String kindOf(String status) {
        if ("SENT".equals(status)) return "sent";
        if ("PENDING".equals(status)) return "pending";
        return "not_sent";
    }

    Map<String, Object> kpis(String username, List<Long> teams, String since) {
        long sent = 0, pending = 0, notSent = 0;
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (Object[] row : repo.myStatusCounts(username, since)) {
            String st = row[0] == null ? "UNKNOWN" : row[0].toString();
            long n = row[1] instanceof Number x ? x.longValue() : 0L;
            switch (kindOf(st)) {
                case "sent" -> sent += n;
                case "pending" -> pending += n;
                default -> { notSent += n; byReason.merge(st, n, Long::sum); }
            }
        }
        long teamDecisions = 0;
        Map<String, Long> teamByReason = new LinkedHashMap<>();
        for (Object[] row : repo.teamDecisionCounts(teams, since)) {
            String st = row[0] == null ? "UNKNOWN" : row[0].toString();
            long n = row[1] instanceof Number x ? x.longValue() : 0L;
            teamDecisions += n;
            teamByReason.merge(st, n, Long::sum);
        }
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("sent", sent);
        k.put("pending", pending);
        k.put("not_sent", notSent);
        k.put("not_sent_by_reason", sortDesc(byReason));
        k.put("summarized", repo.mySummarizedCount(username, since));
        k.put("team_decisions", teamDecisions);
        k.put("team_decisions_by_reason", sortDesc(teamByReason));
        return k;
    }

    private static Map<String, Long> sortDesc(Map<String, Long> in) {
        Map<String, Long> out = new LinkedHashMap<>();
        in.entrySet().stream()
                .sorted((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey())
                        : Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
