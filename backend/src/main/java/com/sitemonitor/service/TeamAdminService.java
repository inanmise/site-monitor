package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.MonitorSchedule;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Takım yönetimi zenginleştirmeleri (2026-09-20): satır sayaçları, silme/pasifleştirme ETKİ önizlemesi ve
 * varlıkları hedef takıma taşıma.
 *
 * <p>{@code deleteTeam} üç engeli metinle söylüyordu ("önce sertifikaları taşıyın…"); yönetici hangi
 * sertifikaların, kaç izlemenin, kimlerin bağlı olduğunu görmeden bir bir arıyordu. Önizleme listeyi verir,
 * {@link #moveAll} bildirim grubundaki "409 → taşı" desenini takıma uygular: envanter, dokuz izleme türü,
 * üyelikler, eskalasyon kişileri ve bildirim grupları hedef takıma geçer; sonra silme engelsiz düşer.
 */
@Service
@RequiredArgsConstructor
public class TeamAdminService {

    static final int SAMPLE = 20;

    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final EscalationContactRepository contactRepo;
    private final NotificationGroupRepository groupRepo;
    private final AlertEventRepository alertEventRepo;
    private final HttpMonitorRepository httpRepo;
    private final PortMonitorRepository portRepo;
    private final PingMonitorRepository pingRepo;
    private final DnsMonitorRepository dnsRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PageMonitorRepository pageRepo;
    private final PageSpeedMonitorRepository pageSpeedRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final DomainMonitorRepository domainRepo;

    /** Üyelik kaynak izi (2026-09-26): taşınan üyelik TEAM_MOVE olarak işaretlenir. Null-güvenli alan
     *  enjeksiyonu — yapıcıyla kurulan birim testleri iz tutmadan çalışır. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TeamMembershipSourceService teamSources;

    void setTeamSources(TeamMembershipSourceService teamSources) {
        this.teamSources = teamSources;
    }

    /**
     * Takım belgeleri (2026-10-22, ADMIN-1): kanal teknik hata raporu kanalları + raporları, takım şablon kütüphanesi ve
     * bakım pencereleri. Takım silinince yetim kuralları ({@code RetentionCatalog} tech-error-*-orphan) kanalları, raporları,
     * görselleri ve geçmişi KALICI siliyordu; etki önizlemesi bunları hiç saymıyor, "Taşı" taşımıyordu. Artık etki onları
     * sayar (silmeyi engeller), {@link #moveAll} taşır. Alan enjeksiyonu (kurucu ve {@code @InjectMocks} testleri
     * değişmesin); yokken (dilimli test) bölüm atlanır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.MaintenanceWindowRepository maintenanceRepo;

    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc = jdbc; }
    void setMaintenanceRepo(com.sitemonitor.repository.MaintenanceWindowRepository repo) { this.maintenanceRepo = repo; }

    /** Açık alarmların takım damgası taşıması hedefli (R2b · F) — isteğe bağlı; yokken eski yol. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.AlertEventFieldWriter alertFieldWriter;

    void setAlertFieldWriter(com.sitemonitor.service.AlertEventFieldWriter w) { this.alertFieldWriter = w; }

    /**
     * Veritabanı sorgu izlemesi (11. tür, 2026-10-31): izlemeler, takımın kayıtlı bağlantıları ve sorgu şablonları. Bağlantı ve
     * şablon TAKIM BELGESİDİR — etki önizlemesi onları sayar (silmeyi engeller, "önce taşı"), {@link #moveAll} izlemelerle
     * BİRLİKTE taşır (izleme ve bağlantısı aynı takımda kalmalı). Alan enjeksiyonu: kurucu ve elle kurulan testler değişmesin;
     * yokken bölüm atlanır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DbQueryMonitorRepository dbQueryRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DbConnectionProfileRepository dbConnectionRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DbQueryTemplateRepository dbQueryTemplateRepo;

    void setDbQueryRepos(com.sitemonitor.repository.DbQueryMonitorRepository monitors,
                         com.sitemonitor.repository.DbConnectionProfileRepository connections,
                         com.sitemonitor.repository.DbQueryTemplateRepository templates) {
        this.dbQueryRepo = monitors;
        this.dbConnectionRepo = connections;
        this.dbQueryTemplateRepo = templates;
    }

    /** Taşıma çakışması: hedef takımda aynı adlı kanal / şablon ya da hedefin sınırı dolu → 409, HİÇBİR şey taşınmaz. */
    public static class MoveConflictException extends com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException {
        public MoveConflictException(String code, String message) { super(code, message, null); }
    }

    /** İzleme türü → repo; tür adı UI etiketi anahtarıdır (todayMonitorRows ile aynı sözlük). */
    private Map<String, JpaRepository<? extends MonitorSchedule, Long>> monitorRepos() {
        Map<String, JpaRepository<? extends MonitorSchedule, Long>> m = new LinkedHashMap<>();
        m.put("HTTP", httpRepo); m.put("PORT", portRepo); m.put("PING", pingRepo); m.put("DNS", dnsRepo);
        m.put("KEYWORD", keywordRepo); m.put("PAGE", pageRepo); m.put("PAGESPEED", pageSpeedRepo);
        m.put("SCRIPTED", scriptedRepo); m.put("DOMAIN", domainRepo);
        if (dbQueryRepo != null) m.put("DBQUERY", dbQueryRepo);
        return m;
    }

    /** Takım id → {members, domains, monitors, open_alerts, contacts, groups}. Tek geçiş, takım başına sorgu yok. */
    public Map<Long, Map<String, Object>> stats() {
        // [members, domains, monitors, open_alerts, contacts, groups, members_inactive]
        Map<Long, int[]> c = new LinkedHashMap<>();
        for (var t : teamRepo.findAll()) c.put(t.getId(), new int[7]);
        for (AppUser u : userRepo.findAll()) {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            if (u.getTeamId() != null) ids.add(u.getTeamId());
            if (u.getTeamIds() != null) ids.addAll(u.getTeamIds());
            boolean inactive = !Boolean.TRUE.equals(u.getActive());
            for (Long id : ids) { bump(c, id, 0); if (inactive) bump(c, id, 6); }
        }
        for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) bump(c, i.getTeamId(), 1);
        for (var e : monitorRepos().entrySet()) {
            for (MonitorSchedule m : e.getValue().findAll()) if (Boolean.TRUE.equals(m.getActive())) bump(c, m.getTeamId(), 2);
        }
        for (AlertEvent a : alertEventRepo.findAllOpenOrderBySeverity()) bump(c, a.getTeamId(), 3);
        for (EscalationContact ec : contactRepo.findAll()) if (Boolean.TRUE.equals(ec.getActive())) bump(c, ec.getTeamId(), 4);
        for (NotificationGroup g : groupRepo.findAll()) if (!Boolean.FALSE.equals(g.getActive())) bump(c, g.getTeamId(), 5);
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (var e : c.entrySet()) {
            int[] v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("members", v[0]); m.put("domains", v[1]); m.put("monitors", v[2]);
            m.put("open_alerts", v[3]); m.put("contacts", v[4]); m.put("groups", v[5]);
            // 2026-10-02: üye sayısının pasif kısmı (members = toplam, pasifler dâhil) — arayüz "N aktif · M pasif" yazar.
            m.put("members_inactive", v[6]);
            out.put(e.getKey(), m);
        }
        return out;
    }

    private static void bump(Map<Long, int[]> c, Long teamId, int idx) {
        if (teamId == null) return;
        int[] v = c.get(teamId);
        if (v != null) v[idx]++;
    }

    /** Silme/pasifleştirme etki önizlemesi: bağlı varlıklar ad örnekleriyle (kalıcı yazma yok). */
    public Map<String, Object> impact(Long teamId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<CertificateInventory> inv = inventoryRepo.findByTeamIdOrderByDomainAsc(teamId);
        out.put("domains", sample(inv.stream().map(CertificateInventory::getDomain).toList()));
        // UG olarak bağlı kayıtlar da etkidir (2026-09-28): sayılmazsa takım silinir, kaydın UG'si silinmiş takımı gösterir
        // ve UG yolu (adres + kendi kişileri) sessizce kaybolur; yalnız UG'li kayıt sahipsiz kalır.
        List<CertificateInventory> ugInv = inventoryRepo.findByUgTeamIdOrderByDomainAsc(teamId);
        out.put("ug_domains", sample(ugInv.stream().map(CertificateInventory::getDomain).toList()));
        Map<String, Integer> byType = new LinkedHashMap<>();
        List<String> monitorNames = new ArrayList<>();
        for (var e : monitorRepos().entrySet()) {
            int n = 0;
            for (MonitorSchedule m : e.getValue().findAll()) {
                if (Objects.equals(m.getTeamId(), teamId)) { n++; monitorNames.add(e.getKey() + ": " + m.getName()); }
            }
            if (n > 0) byType.put(e.getKey(), n);
        }
        out.put("monitors", sample(monitorNames));
        out.put("monitors_by_type", byType);
        List<String> users = new ArrayList<>();
        for (AppUser u : userRepo.findAll()) {
            boolean member = Objects.equals(u.getTeamId(), teamId) || (u.getTeamIds() != null && u.getTeamIds().contains(teamId));
            if (member) users.add(u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() + " (" + u.getUsername() + ")" : u.getUsername());
        }
        out.put("users", sample(users));
        out.put("contacts", sample(contactRepo.findByTeamIdOrderByRoleAsc(teamId).stream().map(EscalationContact::getName).toList()));
        out.put("groups", sample(groupRepo.findByTeamIdOrderByNameAsc(teamId).stream().map(NotificationGroup::getName).toList()));
        long open = alertEventRepo.findAllOpenOrderBySeverity().stream().filter(a -> Objects.equals(a.getTeamId(), teamId)).count();
        out.put("open_alerts", open);
        // Takım belgeleri (2026-10-22, ADMIN-1): kanallar (raporlarıyla), şablon kütüphanesi, süren / yaklaşan / duraklatılmış
        // bakım pencereleri silmeyi ENGELLER ("Taşı" hepsini taşır). Bitmiş bakım penceresi engel değildir (taşımayla gider).
        List<String> channels = names("SELECT name FROM tech_error_channels WHERE team_id = ? ORDER BY name", teamId);
        Map<String, Object> channelSample = sample(channels);
        channelSample.put("reports", count("SELECT COUNT(*) FROM tech_error_reports WHERE team_id = ?", teamId));
        out.put("te_channels", channelSample);
        List<String> templates = names("SELECT name FROM tech_error_team_templates WHERE team_id = ? ORDER BY name", teamId);
        out.put("te_templates", sample(templates));
        List<String> windows = liveWindowNames(teamId, java.time.Instant.now());
        out.put("maintenance_windows", sample(windows));
        // Veritabanı sorgu izlemesi (2026-10-31): kayıtlı bağlantılar ve sorgu şablonları da takım belgesidir — silmeyi engeller.
        List<String> dbConnections = dbConnectionRepo == null ? List.of()
                : dbConnectionRepo.findByTeamIdOrderByNameAsc(teamId).stream().map(com.sitemonitor.model.DbConnectionProfile::getName).toList();
        out.put("dbq_connections", sample(dbConnections));
        List<String> dbTemplates = dbQueryTemplateRepo == null ? List.of()
                : dbQueryTemplateRepo.findByTeamIdOrderByNameAsc(teamId).stream().map(com.sitemonitor.model.DbQueryTemplate::getName).toList();
        out.put("dbq_templates", sample(dbTemplates));
        // Yönetici özeti takım ayarı / geçmişi: BİLGİ (engel değil) — takımla birlikte silinir (yetim kuralı), taşınmaz.
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("settings", count("SELECT COUNT(*) FROM executive_summary_team_settings WHERE team_id = ?", teamId) > 0);
        exec.put("reports", count("SELECT COUNT(*) FROM executive_summary_team_reports WHERE team_id = ?", teamId));
        out.put("executive_summary", exec);
        // AÇIK alarm da bağlı kayıttır (2026-09-28, Y1): damgası bu takımda kalan açık alarmın yeniden uyarısı,
        // eskalasyonu ve çözümü takım silinince hiçbir adrese/kişiye gitmezdi (sessiz susma). "Taşı" açık alarmları da
        // taşır; taşımadan silme 409 alır.
        boolean empty = inv.isEmpty() && ugInv.isEmpty() && monitorNames.isEmpty() && users.isEmpty() && open == 0
                && contactRepo.findByTeamIdOrderByRoleAsc(teamId).isEmpty() && groupRepo.findByTeamIdOrderByNameAsc(teamId).isEmpty()
                && channels.isEmpty() && templates.isEmpty() && windows.isEmpty()
                && dbConnections.isEmpty() && dbTemplates.isEmpty();
        out.put("empty", empty);
        return out;
    }

    /** Tek sütunlu ad listesi; JDBC yoksa (dilimli test) ya da tablo okunamazsa boş liste — eski davranış. */
    private List<String> names(String sql, Long teamId) {
        if (jdbc == null) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (String n : jdbc.queryForList(sql, String.class, teamId)) out.add(n == null ? "—" : n);
            return out;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TeamAdminService.class).warn("Takım etkisi okunamadı ({}): {}", sql, e.toString());
            throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                    "Takıma bağlı belgeler okunamadı; takım silinmedi. Birkaç saniye sonra yeniden deneyin.",
                    "The team's linked documents could not be read; the team was not deleted. Try again in a few seconds."));
        }
    }

    private long count(String sql, Long teamId) {
        if (jdbc == null) return 0;
        try {
            Long n = jdbc.queryForObject(sql, Long.class, teamId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TeamAdminService.class).debug("Takım etkisi sayılamadı ({}): {}", sql, e.toString());
            return 0;
        }
    }

    /** Takımın bitmemiş bakım pencereleri (süren, yaklaşan, duraklatılmış, tekrarlayan) — ad listesi. */
    private List<String> liveWindowNames(Long teamId, java.time.Instant now) {
        if (maintenanceRepo == null) return List.of();
        List<String> out = new ArrayList<>();
        for (com.sitemonitor.model.MaintenanceWindow w : maintenanceRepo.findAll()) {
            if (!Objects.equals(w.getTeamId(), teamId) || windowFinished(w, now)) continue;
            out.add(w.getName() == null ? "—" : w.getName());
        }
        return out;
    }

    /** Tek seferlik pencere bitti mi ({@code MaintenanceService.computeStatus} "completed" ile aynı kural). Tekrarlayan asla bitmez. */
    static boolean windowFinished(com.sitemonitor.model.MaintenanceWindow w, java.time.Instant now) {
        String rec = w.getRecurrence() == null ? "NONE" : w.getRecurrence();
        if (!"NONE".equals(rec)) return false;
        java.time.Instant start = parseIso(w.getStartAt());
        if (start == null) return false;
        int dur = w.getDurationMinutes() == null ? 60 : Math.max(1, w.getDurationMinutes());
        return now.isAfter(start.plus(java.time.Duration.ofMinutes(dur)));
    }

    private static java.time.Instant parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String v = iso.trim();
            if (v.endsWith("Z") || v.matches(".*[+-]\\d\\d:?\\d\\d$")) return java.time.Instant.parse(v);
            return java.time.LocalDateTime.parse(v).toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception e) { return null; }
    }

    private static Map<String, Object> sample(List<String> all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", all.size());
        m.put("items", all.size() > SAMPLE ? new ArrayList<>(all.subList(0, SAMPLE)) : all);
        return m;
    }

    /**
     * Tüm bağlı varlıkları {@code from} takımından {@code to} takımına taşır. Kullanıcı üyeliğinde {@code from}
     * yerine {@code to} yazılır (zaten üyeyse yalnız {@code from} düşer); birincil takım {@code from} ise {@code to}
     * olur. Üyelik admin tarafından değiştiği için LDAP'a karşı kilitlenir (updateUser sözleşmesi).
     */
    @Transactional
    public Map<String, Integer> moveAll(Long from, Long to) {
        if (from == null || to == null || from.equals(to)) throw new IllegalArgumentException("Kaynak ve hedef takım farklı olmalı");
        if (teamRepo.findById(to).isEmpty()) throw new IllegalArgumentException("Hedef takım bulunamadı: " + to);
        // Takım belgeleri ÖNCE denetlenir (2026-10-22, ADMIN-1): hedefte aynı adlı kanal / şablon ya da dolu sınır → 409,
        // hiçbir şey taşınmaz (işlem geri alınır). Kanal adı takım içinde tekildir (uk_tech_error_channel_team_name).
        DocumentMove docs = planDocumentMove(from, to);
        planDbQueryMove(from, to);   // veritabanı sorgu izlemesi: ad çakışması / şablon sınırı → 409, hiçbir şey taşınmaz
        Map<String, Integer> moved = new LinkedHashMap<>();
        int n = 0;
        for (CertificateInventory i : inventoryRepo.findByTeamIdOrderByDomainAsc(from)) {
            i.setTeamId(to);
            if (from.equals(i.getUgTeamId())) i.setUgTeamId(null);   // SY = UG idi: taşınınca hedef ikisi → UG gereksiz
            inventoryRepo.save(i); n++;
        }
        moved.put("domains", n);
        // UG bağı da taşınır (2026-09-28): eskiden yalnız SY taşınıyordu; takım silinince UG silinmiş takımı gösterip
        // kaydın UG alıcıları (adres + kendi kişileri) sessizce kayboluyordu. Hedef zaten SY ise UG boşa düşer
        // (aynı takım iki rolde anlamsız — SY yeter).
        n = 0;
        for (CertificateInventory i : inventoryRepo.findByUgTeamIdOrderByDomainAsc(from)) {
            if (!from.equals(i.getUgTeamId())) continue;   // yukarıda zaten taşındı
            i.setUgTeamId(to.equals(i.getTeamId()) ? null : to);
            inventoryRepo.save(i); n++;
        }
        moved.put("ug_domains", n);
        n = 0;
        n += moveMonitors(httpRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(portRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(pingRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(dnsRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(keywordRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(pageRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(pageSpeedRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(scriptedRepo, from, to, (m, t) -> m.setTeamId(t));
        n += moveMonitors(domainRepo, from, to, (m, t) -> m.setTeamId(t));
        if (dbQueryRepo != null) n += moveMonitors(dbQueryRepo, from, to, (m, t) -> m.setTeamId(t));
        moved.put("monitors", n);
        // Bağlantılar ve şablonlar izlemelerle AYNI işlemde taşınır (izleme başka takımın bağlantısına bağlı kalmasın).
        moved.put("dbq_connections", dbConnectionRepo == null ? 0 : dbConnectionRepo.moveTeam(from, to));
        moved.put("dbq_templates", dbQueryTemplateRepo == null ? 0 : dbQueryTemplateRepo.moveTeam(from, to));
        n = 0;
        for (AppUser u : userRepo.findAll()) {
            boolean primary = Objects.equals(u.getTeamId(), from);
            boolean member = u.getTeamIds() != null && u.getTeamIds().contains(from);
            if (!primary && !member) continue;
            LinkedHashSet<Long> ids = new LinkedHashSet<>(u.getTeamIds() == null ? List.of() : u.getTeamIds());
            boolean alreadyInTarget = ids.contains(to) || Objects.equals(u.getTeamId(), to);
            ids.remove(from);
            ids.add(to);
            u.setTeamIds(ids);
            if (primary || u.getTeamId() == null) u.setTeamId(to);
            u.setTeamLocked(true);
            userRepo.save(u);
            if (teamSources != null) {
                teamSources.forget(u.getId(), List.of(from));
                // Hedefe zaten üyeyse (ör. AD grubundan) mevcut izi EZME — üyelik taşımadan gelmedi.
                if (!alreadyInTarget) teamSources.record(u.getId(), to, com.sitemonitor.model.UserTeamSource.TEAM_MOVE,
                        "#" + from, TeamMembershipSourceService.currentActor());
            }
            n++;
        }
        moved.put("users", n);
        n = 0;
        for (EscalationContact c : contactRepo.findByTeamIdOrderByRoleAsc(from)) { c.setTeamId(to); contactRepo.save(c); n++; }
        moved.put("contacts", n);
        n = 0;
        for (NotificationGroup g : groupRepo.findByTeamIdOrderByNameAsc(from)) {
            // Hedefte aynı adlı grup varsa çakışmasın; varsayılan bayrağı hedefte ikinci varsayılan üretmesin.
            g.setTeamId(to);
            if (Boolean.TRUE.equals(g.getIsDefault())) g.setIsDefault(false);
            groupRepo.save(g); n++;
        }
        moved.put("groups", n);
        // AÇIK alarmların takım damgası da hedefe geçer (2026-09-28, Y1). Damga, yeniden uyarı / eskalasyon / çözüm /
        // tekrar bildir / fırtınada envanterden ÖNCE okunur; taşınmazsa açık alarm eski (ya da silinmiş) takıma
        // gitmeye devam ederdi — adresi ve kişileri artık hedefte. KAPANMIŞ olayların damgası tarih kaydıdır, dokunulmaz.
        // Bildirim grubu damgası taşınan gruba işaret ettiği için tutarlı kalır.
        // R2b · F (2026-10-23): TEK hedefli UPDATE (yalnız team_id, yalnız AÇIK alarm) — okunan alarmların bütünüyle kaydı,
        // okuma ile işlem sonu arasında kapanan alarmı (resolved = false geri yazılarak) yeniden açıyor, arada yazılmış onayı /
        // damgayı geri alıyordu; işlem içindeki yönetilen varlık da hiç okunmaz / kirlenmez. Yazıcı yokken (birim testi) eski yol.
        n = 0;
        if (alertFieldWriter != null) {
            n = alertFieldWriter.moveOpenAlertsToTeam(from, to);
        } else {
            for (AlertEvent a : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (!Objects.equals(a.getTeamId(), from) || Boolean.TRUE.equals(a.getResolved())) continue;
                a.setTeamId(to);
                alertEventRepo.save(a);
                n++;
            }
        }
        moved.put("open_alerts", n);
        applyDocumentMove(docs, from, to, moved);
        // Bakım pencereleri de takımla gider (2026-10-22, ADMIN-5): sahibi silinmiş pencere kimsenin listesinde görünmüyor,
        // kimse düzenleyemiyor ama hedeflerini susturmaya devam ediyordu. Susturma hedeflere bağlıdır — takım değişimi
        // yalnız görünürlüğü / yönetimi değiştirir.
        n = 0;
        if (maintenanceRepo != null) {
            String at = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            for (com.sitemonitor.model.MaintenanceWindow w : maintenanceRepo.findAll()) {
                if (!Objects.equals(w.getTeamId(), from)) continue;
                w.setTeamId(to);
                w.setUpdatedAt(at);
                maintenanceRepo.save(w);
                n++;
            }
        }
        moved.put("maintenance_windows", n);
        return moved;
    }

    /** Taşınacak takım belgeleri (kanallar sıralı, şablonlar) — denetimden geçmiş plan. */
    private record DocumentMove(List<Long> channelIds, boolean appendOrder, int nextOrder,
                                int templates, boolean dropSourceDefault) { }

    private static final int CLASH_HEAD = 5;

    /**
     * Kanal ve şablon kütüphanesi taşımasının ön denetimi. İki takımın satırı kilitlenir (kütüphane yazımlarıyla aynı
     * {@code teams FOR UPDATE} sırası, küçük kimlik önce) — eşzamanlı "yeni kanal / şablon" aynı adı ya da sınırı
     * kaçıramaz. Çakışma → {@link MoveConflictException} (409).
     */
    private DocumentMove planDocumentMove(Long from, Long to) {
        if (jdbc == null) return null;
        try {
            jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR UPDATE", Long.class, Math.min(from, to));
            jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR UPDATE", Long.class, Math.max(from, to));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TeamAdminService.class).debug("Takım satırı kilitlenemedi: {}", e.toString());
        }
        List<Map<String, Object>> src = jdbc.queryForList(
                "SELECT id, name, name_key, sort_order FROM tech_error_channels WHERE team_id = ? "
                        + "ORDER BY CASE WHEN sort_order IS NULL THEN 1 ELSE 0 END, sort_order, name, id", from);
        java.util.Set<String> targetKeys = new java.util.HashSet<>(
                jdbc.queryForList("SELECT name_key FROM tech_error_channels WHERE team_id = ?", String.class, to));
        List<String> clash = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        boolean srcUnordered = false;
        for (Map<String, Object> row : src) {
            ids.add(((Number) row.get("id")).longValue());
            if (row.get("sort_order") == null) srcUnordered = true;
            if (targetKeys.contains(String.valueOf(row.get("name_key")))) clash.add(String.valueOf(row.get("name")));
        }
        if (!clash.isEmpty()) {
            String head = head(clash);
            throw new MoveConflictException("TEAM_MOVE_CHANNEL_CLASH", com.sitemonitor.util.Msg.t(
                    "Hedef takımda aynı adlı teknik hata raporu kanalı var: " + head + ". Hiçbir şey taşınmadı; kanallardan birini yeniden adlandırıp tekrar deneyin.",
                    "The target team already has a technical error report channel with the same name: " + head + ". Nothing was moved; rename one of the channels and try again."));
        }
        int maxChannels = com.sitemonitor.service.techerror.TechErrorReportService.MAX_CHANNELS_PER_TEAM;
        if (!ids.isEmpty() && targetKeys.size() + ids.size() > maxChannels) {
            throw new MoveConflictException("TEAM_MOVE_CHANNEL_LIMIT", com.sitemonitor.util.Msg.t(
                    "Taşıma hedef takımı kanal sınırının (" + maxChannels + ") üstüne çıkarır; hiçbir şey taşınmadı. Kullanılmayan kanalları silip tekrar deneyin.",
                    "Moving would put the target team over its channel limit (" + maxChannels + "); nothing was moved. Delete unused channels and try again."));
        }
        Long targetUnordered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tech_error_channels WHERE team_id = ? AND sort_order IS NULL", Long.class, to);
        Integer targetMax = jdbc.queryForObject("SELECT MAX(sort_order) FROM tech_error_channels WHERE team_id = ?", Integer.class, to);
        boolean append = !srcUnordered && (targetUnordered == null || targetUnordered == 0);

        List<Map<String, Object>> tpl = jdbc.queryForList(
                "SELECT name, name_key, is_default FROM tech_error_team_templates WHERE team_id = ?", from);
        List<Map<String, Object>> tplTarget = jdbc.queryForList(
                "SELECT name_key, is_default FROM tech_error_team_templates WHERE team_id = ?", to);
        java.util.Set<String> tplKeys = new java.util.HashSet<>();
        boolean targetHasDefault = false;
        for (Map<String, Object> row : tplTarget) {
            tplKeys.add(String.valueOf(row.get("name_key")));
            if (Boolean.TRUE.equals(row.get("is_default"))) targetHasDefault = true;
        }
        List<String> tplClash = new ArrayList<>();
        boolean sourceHasDefault = false;
        for (Map<String, Object> row : tpl) {
            if (tplKeys.contains(String.valueOf(row.get("name_key")))) tplClash.add(String.valueOf(row.get("name")));
            if (Boolean.TRUE.equals(row.get("is_default"))) sourceHasDefault = true;
        }
        if (!tplClash.isEmpty()) {
            String head = head(tplClash);
            throw new MoveConflictException("TEAM_MOVE_TEMPLATE_CLASH", com.sitemonitor.util.Msg.t(
                    "Hedef takımın şablon kütüphanesinde aynı adlı şablon var: " + head + ". Hiçbir şey taşınmadı; şablonlardan birini yeniden adlandırıp tekrar deneyin.",
                    "The target team's template library already has a template with the same name: " + head + ". Nothing was moved; rename one of the templates and try again."));
        }
        int maxTemplates = com.sitemonitor.service.techerror.TechErrorTeamTemplateService.MAX_PER_TEAM;
        if (!tpl.isEmpty() && tplTarget.size() + tpl.size() > maxTemplates) {
            throw new MoveConflictException("TEAM_MOVE_TEMPLATE_LIMIT", com.sitemonitor.util.Msg.t(
                    "Taşıma hedef takımın şablon kütüphanesini sınırın (" + maxTemplates + ") üstüne çıkarır; hiçbir şey taşınmadı. Kullanılmayan şablonları silip tekrar deneyin.",
                    "Moving would put the target team's template library over its limit (" + maxTemplates + "); nothing was moved. Delete unused templates and try again."));
        }
        return new DocumentMove(ids, append, (targetMax == null ? 0 : targetMax) + 1, tpl.size(),
                sourceHasDefault && targetHasDefault);
    }

    /** Planı uygular: kanallar (+ rapor / görsel / geçmiş / şablon sürümü satırlarındaki takım kopyası) ve şablonlar. */
    private void applyDocumentMove(DocumentMove docs, Long from, Long to, Map<String, Integer> moved) {
        if (docs == null || jdbc == null) return;
        // KİLİT SIRASI (son inceleme 2026-10-22, CLAUDE.md "LOCK ORDER"): önce taşınan RAPOR satırları kimlik sırasıyla, sonra
        // KANAL satırları kimlik sırasıyla — kanal silme (raporlar → kanal) ve kanal sırası (kanallar kimlik sırasıyla) ile
        // aynı yön. Eskiden kanallar önce güncelleniyordu: kanal silme raporları tutup kanalı beklerken burada kanal tutulup
        // raporlar bekleniyordu (PostgreSQL 40P01 → 503 LOCK_CONFLICT).
        jdbc.queryForList("SELECT id FROM tech_error_reports WHERE team_id = ? ORDER BY id FOR UPDATE", Long.class, from);
        jdbc.queryForList("SELECT id FROM tech_error_channels WHERE team_id = ? ORDER BY id FOR UPDATE", Long.class, from);
        int order = docs.nextOrder();
        for (Long id : docs.channelIds()) {
            jdbc.update("UPDATE tech_error_channels SET team_id = ?, sort_order = ? WHERE id = ? AND team_id = ?",
                    to, docs.appendOrder() ? Integer.valueOf(order++) : null, id, from);
        }
        moved.put("te_channels", docs.channelIds().size());
        int reports = jdbc.update("UPDATE tech_error_reports SET team_id = ? WHERE team_id = ?", to, from);
        jdbc.update("UPDATE tech_error_report_images SET team_id = ? WHERE team_id = ?", to, from);
        jdbc.update("UPDATE tech_error_report_events SET team_id = ? WHERE team_id = ?", to, from);
        jdbc.update("UPDATE tech_error_template_versions SET team_id = ? WHERE team_id = ?", to, from);
        moved.put("te_reports", reports);
        // Hedefte zaten varsayılan şablon varsa taşınan varsayılan düşer — takım başına en çok bir varsayılan (uq_tett_one_default).
        if (docs.dropSourceDefault()) {
            jdbc.update("UPDATE tech_error_team_templates SET is_default = FALSE, version = version + 1 "
                    + "WHERE team_id = ? AND is_default = TRUE", from);
        }
        jdbc.update("UPDATE tech_error_team_templates SET team_id = ?, version = version + 1 WHERE team_id = ?", to, from);
        moved.put("te_templates", docs.templates());
    }

    /**
     * Veritabanı sorgu izlemesi taşımasının ön denetimi (2026-10-31): hedef takımda aynı adlı izleme (ad alarm anahtarıdır),
     * bağlantı ya da şablon varsa, ya da şablon kitaplığı sınırı aşılırsa 409 — hiçbir şey taşınmaz (işlem geri alınır).
     */
    private void planDbQueryMove(Long from, Long to) {
        if (dbQueryRepo != null) {
            java.util.Set<String> target = new java.util.HashSet<>();
            for (var m : dbQueryRepo.findByTeamId(to)) if (m.getName() != null) target.add(m.getName().toLowerCase(java.util.Locale.ROOT));
            List<String> clash = new ArrayList<>();
            for (var m : dbQueryRepo.findByTeamId(from))
                if (m.getName() != null && target.contains(m.getName().toLowerCase(java.util.Locale.ROOT))) clash.add(m.getName());
            if (!clash.isEmpty()) {
                String head = head(clash);
                throw new MoveConflictException("TEAM_MOVE_DBQUERY_NAME_CLASH", com.sitemonitor.util.Msg.t(
                        "Hedef takımda aynı adlı veritabanı sorgu izlemesi var (ad alarm anahtarıdır): " + head + ". Hiçbir şey taşınmadı; izlemelerden birini yeniden adlandırıp tekrar deneyin.",
                        "The target team already has a database query monitor with the same name (the name is the alarm key): " + head + ". Nothing was moved; rename one of the monitors and try again."));
            }
        }
        if (dbConnectionRepo != null) {
            java.util.Set<String> target = new java.util.HashSet<>();
            for (var p : dbConnectionRepo.findByTeamIdOrderByNameAsc(to)) target.add(p.getName().toLowerCase(java.util.Locale.ROOT));
            List<String> clash = new ArrayList<>();
            for (var p : dbConnectionRepo.findByTeamIdOrderByNameAsc(from))
                if (target.contains(p.getName().toLowerCase(java.util.Locale.ROOT))) clash.add(p.getName());
            if (!clash.isEmpty()) {
                String head = head(clash);
                throw new MoveConflictException("TEAM_MOVE_DBQ_CONNECTION_CLASH", com.sitemonitor.util.Msg.t(
                        "Hedef takımda aynı adlı veritabanı bağlantısı var: " + head + ". Hiçbir şey taşınmadı; bağlantılardan birini yeniden adlandırıp tekrar deneyin.",
                        "The target team already has a database connection with the same name: " + head + ". Nothing was moved; rename one of the connections and try again."));
            }
        }
        if (dbQueryTemplateRepo != null) {
            List<com.sitemonitor.model.DbQueryTemplate> src = dbQueryTemplateRepo.findByTeamIdOrderByNameAsc(from);
            List<com.sitemonitor.model.DbQueryTemplate> dst = dbQueryTemplateRepo.findByTeamIdOrderByNameAsc(to);
            java.util.Set<String> target = new java.util.HashSet<>();
            for (var t : dst) target.add(t.getName().toLowerCase(java.util.Locale.ROOT));
            List<String> clash = new ArrayList<>();
            for (var t : src) if (target.contains(t.getName().toLowerCase(java.util.Locale.ROOT))) clash.add(t.getName());
            if (!clash.isEmpty()) {
                String head = head(clash);
                throw new MoveConflictException("TEAM_MOVE_DBQ_TEMPLATE_CLASH", com.sitemonitor.util.Msg.t(
                        "Hedef takımda aynı adlı sorgu şablonu var: " + head + ". Hiçbir şey taşınmadı; şablonlardan birini yeniden adlandırıp tekrar deneyin.",
                        "The target team already has a query template with the same name: " + head + ". Nothing was moved; rename one of the templates and try again."));
            }
            int max = com.sitemonitor.service.dbquery.DbQueryTemplateService.MAX_PER_TEAM;
            if (!src.isEmpty() && dst.size() + src.size() > max) {
                throw new MoveConflictException("TEAM_MOVE_DBQ_TEMPLATE_LIMIT", com.sitemonitor.util.Msg.t(
                        "Taşıma hedef takımın sorgu şablonu sınırını (" + max + ") aşar; hiçbir şey taşınmadı. Kullanılmayan şablonları silip tekrar deneyin.",
                        "Moving would put the target team over its query template limit (" + max + "); nothing was moved. Delete unused templates and try again."));
            }
        }
    }

    private static String head(List<String> names) {
        return String.join(", ", names.subList(0, Math.min(CLASH_HEAD, names.size()))) + (names.size() > CLASH_HEAD ? " …" : "");
    }

    private static <M extends MonitorSchedule> int moveMonitors(JpaRepository<M, Long> repo, Long from, Long to, BiConsumer<M, Long> set) {
        int n = 0;
        for (M m : repo.findAll()) {
            if (Objects.equals(m.getTeamId(), from)) { set.accept(m, to); repo.save(m); n++; }
        }
        return n;
    }
}
