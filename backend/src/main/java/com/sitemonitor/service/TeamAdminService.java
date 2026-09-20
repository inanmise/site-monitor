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

    /** İzleme türü → repo; tür adı UI etiketi anahtarıdır (todayMonitorRows ile aynı sözlük). */
    private Map<String, JpaRepository<? extends MonitorSchedule, Long>> monitorRepos() {
        Map<String, JpaRepository<? extends MonitorSchedule, Long>> m = new LinkedHashMap<>();
        m.put("HTTP", httpRepo); m.put("PORT", portRepo); m.put("PING", pingRepo); m.put("DNS", dnsRepo);
        m.put("KEYWORD", keywordRepo); m.put("PAGE", pageRepo); m.put("PAGESPEED", pageSpeedRepo);
        m.put("SCRIPTED", scriptedRepo); m.put("DOMAIN", domainRepo);
        return m;
    }

    /** Takım id → {members, domains, monitors, open_alerts, contacts, groups}. Tek geçiş, takım başına sorgu yok. */
    public Map<Long, Map<String, Object>> stats() {
        Map<Long, int[]> c = new LinkedHashMap<>();   // [members, domains, monitors, open_alerts, contacts, groups]
        for (var t : teamRepo.findAll()) c.put(t.getId(), new int[6]);
        for (AppUser u : userRepo.findAll()) {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            if (u.getTeamId() != null) ids.add(u.getTeamId());
            if (u.getTeamIds() != null) ids.addAll(u.getTeamIds());
            for (Long id : ids) bump(c, id, 0);
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
        boolean empty = inv.isEmpty() && monitorNames.isEmpty() && users.isEmpty()
                && contactRepo.findByTeamIdOrderByRoleAsc(teamId).isEmpty() && groupRepo.findByTeamIdOrderByNameAsc(teamId).isEmpty();
        out.put("empty", empty);
        return out;
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
        Map<String, Integer> moved = new LinkedHashMap<>();
        int n = 0;
        for (CertificateInventory i : inventoryRepo.findByTeamIdOrderByDomainAsc(from)) { i.setTeamId(to); inventoryRepo.save(i); n++; }
        moved.put("domains", n);
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
        moved.put("monitors", n);
        n = 0;
        for (AppUser u : userRepo.findAll()) {
            boolean primary = Objects.equals(u.getTeamId(), from);
            boolean member = u.getTeamIds() != null && u.getTeamIds().contains(from);
            if (!primary && !member) continue;
            LinkedHashSet<Long> ids = new LinkedHashSet<>(u.getTeamIds() == null ? List.of() : u.getTeamIds());
            ids.remove(from);
            ids.add(to);
            u.setTeamIds(ids);
            if (primary || u.getTeamId() == null) u.setTeamId(to);
            u.setTeamLocked(true);
            userRepo.save(u);
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
        return moved;
    }

    private static <M extends MonitorSchedule> int moveMonitors(JpaRepository<M, Long> repo, Long from, Long to, BiConsumer<M, Long> set) {
        int n = 0;
        for (M m : repo.findAll()) {
            if (Objects.equals(m.getTeamId(), from)) { set.accept(m, to); repo.save(m); n++; }
        }
        return n;
    }
}
