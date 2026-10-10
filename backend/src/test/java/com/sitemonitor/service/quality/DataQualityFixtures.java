package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.quality.DataQualitySource.Escalation;
import com.sitemonitor.service.quality.DataQualitySource.Facts;
import com.sitemonitor.service.quality.DataQualitySource.MonitorFact;
import com.sitemonitor.service.quality.DataQualitySource.NocState;
import com.sitemonitor.service.quality.DataQualitySource.TeamFact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Veri kalitesi testleri için girdi kurucusu — varsayılan: kusursuz takım, 7/24 kullanılabilir. */
final class DataQualityFixtures {

    final List<TeamFact> teams = new ArrayList<>();
    final Map<Long, Integer> members = new HashMap<>();
    final Map<Long, Escalation> escalation = new HashMap<>();
    final Set<Long> groupAddress = new HashSet<>();
    final List<CertificateInventory> inventory = new ArrayList<>();
    Map<String, Set<String>> hygiene = new HashMap<>();
    final Map<String, String> hygieneErrors = new HashMap<>();
    Map<String, Integer> errorCounts = new HashMap<>();
    final List<MonitorFact> monitors = new ArrayList<>();
    final Map<String, String> paused = new HashMap<>();
    NocState noc = new NocState(true, null,
            new NocConfigService.Config(Set.of(), "HIGH", true, null, null, null), true);

    /** Kusursuz aktif takım: üyesi, aktif lideri, e-postası, YÜKSEK + KRİTİK eskalasyonu var. */
    DataQualityFixtures team(long id, String name) {
        teams.add(new TeamFact(id, name, true, true, true, true, false, false));
        members.put(id, 3);
        escalation.put(id, new Escalation(true, true));
        return this;
    }

    DataQualityFixtures teamFact(TeamFact t) {
        teams.add(t);
        return this;
    }

    /** Kusursuz aktif envanter kaydı: takımlı, katmanlı (2), sorumlusu dolu, kontrol temiz, 7/24 açık. */
    CertificateInventory inv(long id, String domain, Long teamId) {
        CertificateInventory r = new CertificateInventory();
        r.setId(id);
        r.setDomain(domain);
        r.setPort(443);
        r.setTeamId(teamId);
        r.setActive(true);
        r.setTier(2);
        r.setNocNotify(true);
        inventory.add(r);
        hygiene.put(domain.toLowerCase(), new LinkedHashSet<>());
        return r;
    }

    /** Hijyen kodu ekle (no_contacts, never_checked, stale, error). */
    DataQualityFixtures hygiene(String domain, String... codes) {
        hygiene.computeIfAbsent(domain.toLowerCase(), k -> new LinkedHashSet<>()).addAll(List.of(codes));
        return this;
    }

    MonitorFact monitor(NocType type, long id, String name, String target, Long teamId, boolean active,
                        String group, String dupKey) {
        MonitorFact m = new MonitorFact(type, id, name, target, teamId, active, true, group, dupKey, null);
        monitors.add(m);
        return m;
    }

    DataQualityFixtures add(MonitorFact m) {
        monitors.add(m);
        return this;
    }

    Facts facts() {
        return new Facts(teams, members, escalation, groupAddress, inventory, hygiene, hygieneErrors, errorCounts,
                monitors, paused, noc);
    }

    static NocType ssl() {
        return NocType.SSL;
    }
}
