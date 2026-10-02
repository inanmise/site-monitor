package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Çevrimiçi kullanıcı özeti — kenar çubuğundaki logonun yanındaki yeşil gösterge (2026-10-02, kullanıcı isteği:
 * "o an sistemde kaç kişi online ise gösterelim; tıklanınca hangi takımdan kaç kişi var görünsün; bütün kullanıcılar
 * görsün; 30 sn'de bir tazelensin").
 *
 * <p><b>"Çevrimiçi" tanımı</b> {@link UserService#hasLiveSession} ile AYNI: aktif hesap, kayıtlı oturum (TERMINATED
 * nöbetçisi değil) ve {@code site.monitor.session.active-window-seconds} (120 sn) içinde taze {@code lastSeenAt}.
 * SPA ~15 sn'de bir ping atıyor, lastSeenAt 60 sn debounce'lu yazılıyor → pencere 120 sn. Login'deki "başka yerde
 * açık oturum var" kararı da bu ölçütü kullanır; iki yer farklı sayı göstermesin.
 *
 * <p><b>Takım</b> = kullanıcının BİRİNCİL takımı ({@code app_users.team_id}); herkes bir kez sayılır, takım
 * toplamları genel toplamla tutar. Takımsızlar ayrı kovada. Yalnız SAYILAR döner — isim/kullanıcı adı yok.
 *
 * <p><b>Maliyet:</b> tek gruplu sorgu + takım adları; sonuç kuruluş geneli tek anahtarla {@code cache-ms} (10 sn)
 * hafızada — her istemcinin 30 sn'lik yoklaması ayrı sorgu açmaz.
 */
@Slf4j
@Service
public class PresenceService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Collator TR = Collator.getInstance(Locale.forLanguageTag("tr"));

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final TtlMemo<Map<String, Object>> memo = new TtlMemo<>(4);

    @Value("${site.monitor.session.active-window-seconds:120}")
    private long windowSeconds = 120;

    @Value("${site.monitor.presence.cache-ms:10000}")
    private long cacheMs = 10_000;

    public PresenceService(AppUserRepository userRepo, TeamRepository teamRepo) {
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
    }

    /** Kuruluş geneli çevrimiçi özeti (önbellekli). */
    public Map<String, Object> online() {
        return memo.get("org", cacheMs, false, this::compute);
    }

    Map<String, Object> compute() {
        Instant now = Instant.now();
        String threshold = ISO.format(now.minusSeconds(windowSeconds));
        List<Object[]> rows = userRepo.countOnlineByPrimaryTeam(threshold);

        Map<Long, Long> byTeam = new HashMap<>();
        long noTeam = 0;
        long total = 0;
        for (Object[] r : rows) {
            long c = r[1] instanceof Number n ? n.longValue() : 0L;
            if (c <= 0) continue;
            total += c;
            if (r[0] instanceof Number id) byTeam.merge(id.longValue(), c, Long::sum);
            else noTeam += c;
        }

        Map<Long, String> names = new HashMap<>();
        if (!byTeam.isEmpty()) {
            for (Team t : teamRepo.findAllById(byTeam.keySet())) names.put(t.getId(), t.getName());
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map.Entry<Long, Long> e : byTeam.entrySet()) {
            String name = names.get(e.getKey());
            if (name == null) {
                // Silinmiş takıma bağlı birincil takım kimliği: kişi yine sayılır, takımsız kovaya düşer.
                noTeam += e.getValue();
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team_id", e.getKey());
            row.put("team_name", name);
            row.put("count", e.getValue());
            teams.add(row);
        }
        // Çoktan aza; eşitlikte ad (Türkçe sıralama) — liste her yenilemede zıplamasın.
        teams.sort(Comparator.<Map<String, Object>>comparingLong(m -> -((Long) m.get("count")))
                .thenComparing(m -> (String) m.get("team_name"), TR));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("teams", teams);
        out.put("no_team", noTeam);
        out.put("window_seconds", windowSeconds);
        out.put("generated_at", ISO.format(now) + "Z");
        return out;
    }
}
