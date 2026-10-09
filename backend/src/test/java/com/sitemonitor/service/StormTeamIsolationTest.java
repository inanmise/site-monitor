package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * FIRTINA TAKIM YALITIMI (ürün kararı 2026-09-29, prod olayı).
 *
 * <p>Prod push'u: "SiteMonitor — 15 monitör birden erişilemez - Tüm monitörler · kök-neden: Sentetik Senaryo
 * Başarısız". Kaynak {@code StormService}: kapsam {@code ACCOUNT} (tüm takımlar TEK sayaç), eşik kuruluşun
 * tüm açık DOWN alarmlarından sayılıyor; fırtına üyesi olan HER takıma hesap geneli sayı ve "Tüm monitörler"
 * etiketiyle e-posta + push gidiyor; fırtına sürerken BAŞKA takımın alarmı da o fırtınaya bağlanıp bireysel
 * bildirimi yutuluyordu.
 *
 * <p>Kural (kullanıcı): fırtına TAKIM BAZINDA değerlendirilir — eşik her takımın kendi kümesinde; bildirim
 * (e-posta + push + webhook) yalnız o takıma; "Tüm monitörler" / kuruluş geneli fırtına yok; kök-neden etiketi
 * takım kümesinden. Sahipsiz alarm fırtınaya girmez. Fırtına push'u bireysel alarmın ulaşacağından GENİŞ bir
 * kitleye (seviye yükseltmesiyle) gitmez.
 *
 * <p>Depolar veritabanı gibi davranan sahtelerle kurulur (kapsam anahtarından bağımsız): böylece test, eski
 * {@code ACCOUNT} uygulamasında da yeni takım kapsamlı uygulamada da AYNI soruyu sorar.
 */
class StormTeamIsolationTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long A = 7L, B = 8L;

    private AlertEventRepository alertEventRepo;
    private AlertStormRepository stormRepo;
    private HttpMonitorRepository httpRepo;
    private StormService storm;
    private boolean perGroup;

    private final List<AlertEvent> events = new ArrayList<>();
    private final Map<String, AlertStorm> active = new LinkedHashMap<>();
    private final Map<Long, AlertStorm> byId = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(500);
    private final List<Object[]> inserts = new ArrayList<>();
    private final List<List<String>> mailTo = new ArrayList<>();
    private final List<String> mailSubjects = new ArrayList<>();
    private final List<Object[]> pushCalls = new ArrayList<>();
    /** pushCalls ile aynı sırada: çağrının taşıdığı ESKİ fırtına kimliği (yoksa null). */
    private final List<Long> pushLegacyIds = new ArrayList<>();
    /** Bireysel push çağrıları (2026-10-03): [olay, e-posta tetiği, yedek takım]. */
    private final List<Object[]> memberPushes = new ArrayList<>();
    /**
     * {@code site.monitor.storm.push-individual} (2026-10-03, ürün varsayılanı AÇIK). Bu sınıfın çoğu testi TOPLU fırtına
     * push'unun takım yalıtımını pinler — o yalnız ayar KAPALIYKEN üretilir; bu yüzden fikstür varsayılanı BİLEREK kapalı.
     * Açık kipin yalıtımı aşağıdaki "bireysel push" testlerinde.
     */
    private boolean pushIndividual = false;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        alertEventRepo = mock(AlertEventRepository.class);
        stormRepo = mock(AlertStormRepository.class);
        httpRepo = mock(HttpMonitorRepository.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TeamRepository teamRepo = mock(TeamRepository.class);

        EmailNotificationService email = mock(EmailNotificationService.class, inv -> {
            Object[] a = inv.getArguments();
            if (inv.getMethod().getName().startsWith("sendHtml") && a.length > 2 && a[0] instanceof String[] to) {
                mailTo.add(Arrays.asList(to));
                mailSubjects.add(String.valueOf(a[2]));
            }
            return Answers.RETURNS_DEFAULTS.answer(inv);
        });
        UserPushService push = mock(UserPushService.class, inv -> {
            // 2026-10-04 (öneri 5): StormService iki dilli aşırı yüklemeyi çağırır (her zaman 7 bağımsız değişken, eski kimlik null olabilir).
            if (inv.getMethod().getName().equals("enqueueStormNotice") || inv.getMethod().getName().equals("enqueueStormNoticeLocalized")) {
                Object[] a = inv.getArguments();
                // 7 bağımsız değişkenli aşırı yükleme (O-3 taşıması): [fırtına, ESKİ fırtına, takım, …] → ortak düzene indir.
                if (a.length == 7) { pushLegacyIds.add((Long) a[1]); a = new Object[]{a[0], a[2], a[3], a[4], a[5], a[6]}; }
                else pushLegacyIds.add(null);
                pushCalls.add(a);
            }
            if (inv.getMethod().getName().equals("enqueueAlert")) {
                Object[] a = inv.getArguments();
                memberPushes.add(new Object[]{a[0], a[1], a[2]});
            }
            return Answers.RETURNS_DEFAULTS.answer(inv);
        });

        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AlertEventRepository.class, alertEventRepo);
        provided.put(AlertStormRepository.class, stormRepo);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(EmailNotificationService.class, email);
        provided.put(JdbcTemplate.class, jdbc);
        provided.put(TeamRepository.class, teamRepo);
        provided.put(HttpMonitorRepository.class, httpRepo);
        storm = ManualCheckNoAlarmTest.build(StormService.class, provided);
        ReflectionTestUtils.setField(storm, "userPushService", push);
        ReflectionTestUtils.setField(storm, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(storm, "pageRepo", mock(com.sitemonitor.repository.PageMonitorRepository.class));
        ReflectionTestUtils.setField(storm, "scriptedRepo", mock(com.sitemonitor.repository.ScriptedMonitorRepository.class));
        ReflectionTestUtils.setField(storm, "pageSpeedRepo", mock(com.sitemonitor.repository.PageSpeedMonitorRepository.class));

        // Ayarlar: fırtına AÇIK, SAYI eşiği 3, pencere 5 dk; grup kipi teste göre.
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String k = i.getArgument(0);
            if (StormService.KEY_ENABLED.equals(k)) return true;
            if (StormService.KEY_PER_GROUP.equals(k)) return perGroup;
            if (StormService.KEY_PUSH_INDIVIDUAL.equals(k)) return pushIndividual;
            return i.getArgument(1);
        });
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i ->
                StormService.KEY_UNIT.equals(i.getArgument(0)) ? "COUNT" : i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> {
            String k = i.getArgument(0);
            if (StormService.KEY_VALUE.equals(k)) return 3;
            if (StormService.KEY_WINDOW.equals(k)) return 5;
            return i.getArgument(1);
        });

        Team ta = new Team(); ta.setId(A); ta.setName("Takım A"); ta.setEmail("a@example.com");
        Team tb = new Team(); tb.setId(B); tb.setName("Takım B"); tb.setEmail("b@example.com");
        lenient().when(teamRepo.findById(A)).thenReturn(Optional.of(ta));
        lenient().when(teamRepo.findById(B)).thenReturn(Optional.of(tb));

        // ── Veritabanı gibi davranan sahte depolar ──
        lenient().when(alertEventRepo.findOpenDownSince(anyCollection(), anyString()))
                .thenAnswer(i -> openDown(i.getArgument(0), null));
        lenient().when(alertEventRepo.findOpenDownSinceInGroup(anyCollection(), anyString(), anyString()))
                .thenAnswer(i -> openDown(i.getArgument(0), i.getArgument(2)));
        lenient().when(alertEventRepo.linkToStormIfOpen(anyLong(), anyLong())).thenAnswer(i -> {
            AlertEvent e = event(i.getArgument(0));
            if (e == null || Boolean.TRUE.equals(e.getResolved()) || e.getStormId() != null) return 0;
            e.setStormId(i.getArgument(1));
            return 1;
        });
        lenient().when(alertEventRepo.unlinkFromStorm(anyLong(), anyLong())).thenAnswer(i -> {
            AlertEvent e = event(i.getArgument(0));
            // Yalnız verilen fırtınanın üyesi (2026-10-09) — başka fırtınaya geçmiş satıra dokunulmaz.
            if (e == null || Boolean.TRUE.equals(e.getResolved()) || !Objects.equals(e.getStormId(), i.getArgument(1))) return 0;
            e.setStormId(null);
            e.setLastReAlertAt(null);
            return 1;
        });
        lenient().when(alertEventRepo.moveToStormIfOpen(anyLong(), anyLong(), anyLong())).thenAnswer(i -> {
            AlertEvent e = event(i.getArgument(0));
            if (e == null || Boolean.TRUE.equals(e.getResolved()) || !Objects.equals(e.getStormId(), i.getArgument(1))) return 0;
            e.setStormId(i.getArgument(2));
            return 1;
        });
        lenient().when(alertEventRepo.releaseFromStormAsNotified(anyLong(), anyLong(), anyString())).thenAnswer(i -> {
            AlertEvent e = event(i.getArgument(0));
            if (e == null || Boolean.TRUE.equals(e.getResolved()) || !Objects.equals(e.getStormId(), i.getArgument(1))) return 0;
            e.setStormId(null);
            e.setLastReAlertAt(i.getArgument(2));
            return 1;
        });
        lenient().when(alertEventRepo.findByStormId(anyLong())).thenAnswer(i -> events.stream()
                .filter(e -> Objects.equals(e.getStormId(), i.getArgument(0))).toList());
        lenient().when(alertEventRepo.findByStormIdAndResolvedFalse(anyLong())).thenAnswer(i -> events.stream()
                .filter(e -> Objects.equals(e.getStormId(), i.getArgument(0)) && !Boolean.TRUE.equals(e.getResolved()))
                .toList());
        lenient().when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(stormRepo.findByScopeKeyAndResolvedFalse(anyString()))
                .thenAnswer(i -> Optional.ofNullable(active.get((String) i.getArgument(0))));
        lenient().when(stormRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(byId.get((Long) i.getArgument(0))));
        lenient().when(stormRepo.findByResolvedFalse()).thenAnswer(i -> new ArrayList<>(active.values()));
        lenient().when(stormRepo.save(any())).thenAnswer(i -> {
            AlertStorm s = i.getArgument(0);
            if (Boolean.TRUE.equals(s.getResolved())) active.values().removeIf(x -> x == s);
            return s;
        });
        // INSERT … ON CONFLICT (scope_key) WHERE resolved = false DO NOTHING — kapsam başına tek aktif fırtına.
        doAnswer(i -> {
            Object[] a = i.getArguments();
            String sql = String.valueOf(a[0]);
            // Günlük toplu tekrarın gönderim ÖNCESİ sahiplenmesi (2026-10-09): damga okunan değerdeyse ileri alınır.
            if (sql.equals(StormService.SQL_REALERT_CLAIM) || sql.equals(StormService.SQL_REALERT_CLAIM_UNSTAMPED)) {
                AlertStorm s = byId.get((Long) a[2]);
                if (s == null || Boolean.TRUE.equals(s.getResolved())) return 0;
                if (!Objects.equals(s.getLastReAlertAt(), a.length > 3 ? a[3] : null)) return 0;
                s.setLastReAlertAt(String.valueOf(a[1]));
                return 1;
            }
            if (!sql.startsWith("INSERT INTO alert_storms")) return 0;
            String scopeKey = String.valueOf(a[1]);
            inserts.add(a);
            if (active.containsKey(scopeKey)) return 0;
            AlertStorm s = new AlertStorm();
            s.setId(seq.incrementAndGet());
            s.setScopeKey(scopeKey);
            s.setScopeType(String.valueOf(a[2]));
            s.setResolved(false);
            s.setMemberCount(a[3] instanceof Number n ? n.intValue() : null);
            s.setRootCause(a[4] == null ? null : String.valueOf(a[4]));
            s.setCreatedAt(a.length > 5 ? String.valueOf(a[5]) : ISO.format(Instant.now()));
            s.setLastReAlertAt(a.length > 6 ? String.valueOf(a[6]) : s.getCreatedAt());
            active.put(scopeKey, s);
            byId.put(s.getId(), s);
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
    }

    // ── Sahte veritabanı yardımcıları ──────────────────────────────────────────────────────

    private List<AlertEvent> openDown(Collection<String> types, String group) {
        return events.stream()
                .filter(e -> !Boolean.TRUE.equals(e.getResolved()))
                .filter(e -> types.contains(e.getAlertType()))
                .filter(e -> group == null || group.equals(e.getGroupName()))
                .toList();
    }

    private AlertEvent event(Long id) {
        return events.stream().filter(e -> Objects.equals(e.getId(), id)).findFirst().orElse(null);
    }

    private AlertEvent ev(long id, String type, Long teamId, String level) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(type.startsWith("SCRIPTED") ? "Senaryo " + id : "https://t" + teamId + "-h" + id + ".example.com");
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(teamId);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(1, ChronoUnit.MINUTES)));   // pencere içi (5 dk)
        events.add(e);
        return e;
    }

    private AlertEvent http(long id, Long teamId) {
        return ev(id, EscalationService.TYPE_HTTP_DOWN, teamId, "WARNING");
    }

    private List<String> allRecipients() {
        return mailTo.stream().flatMap(List::stream).toList();
    }

    private List<Long> pushTeams() {
        return pushCalls.stream().map(a -> (Long) a[1]).toList();
    }

    private String pushMessageFor(long teamId) {
        // 2026-10-04 (öneri 5): metin iki dilli taşınır — Türkçe metin (bugünkü) doğrulanır.
        return pushCalls.stream().filter(a -> Objects.equals(a[1], teamId))
                .map(a -> a[a.length - 1] instanceof UserPushService.LocalizedText l ? l.tr() : String.valueOf(a[a.length - 1]))
                .findFirst().orElse(null);
    }

    // ── Testler ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A takımı eşiği aşar → fırtına bildirimi YALNIZ A'ya; B (e-posta/push) hiçbir şey almaz, B'nin alarmı bağlanmaz")
    void teamA_exceedsThreshold_onlyTeamANotified() {
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);
        AlertEvent b1 = http(4, B);

        assertThat(storm.evaluate(a3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        assertThat(allRecipients()).contains("a@example.com").doesNotContain("b@example.com");
        assertThat(pushTeams()).containsOnly(A);
        assertThat(b1.getStormId()).as("B'nin alarmı A'nın fırtınasına bağlanmamalı").isNull();
    }

    @Test
    @DisplayName("A'nın fırtınası sürerken B'nin (eşik altı) yeni alarmı A'nın fırtınasına BAĞLANMAZ — bireysel gider")
    void teamB_belowThreshold_notAttachedToTeamAStorm() {
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);
        http(4, B);
        assertThat(storm.evaluate(a3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        AlertEvent b2 = http(5, B);   // B'de toplam 2 < eşik 3
        assertThat(storm.evaluate(b2, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        assertThat(b2.getStormId()).isNull();
    }

    @Test
    @DisplayName("A + B karışık: her takım eşiği KENDİ kümesinde aşar → iki ayrı fırtına, her biri yalnız kendi takımına ve kendi sayısıyla")
    void mixedFailures_eachTeamGetsOwnStorm() {
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);
        http(4, B); http(5, B); AlertEvent b3 = http(6, B);

        assertThat(storm.evaluate(a3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(storm.evaluate(b3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        assertThat(a3.getStormId()).isNotNull();
        assertThat(b3.getStormId()).isNotNull().isNotEqualTo(a3.getStormId());
        // Hiçbir e-posta iki takımı BİRDEN içermez.
        assertThat(mailTo).allSatisfy(to -> assertThat(to.contains("a@example.com") && to.contains("b@example.com")).isFalse());
        assertThat(allRecipients()).contains("a@example.com", "b@example.com");
        assertThat(pushMessageFor(A)).startsWith("3 ");
        assertThat(pushMessageFor(B)).startsWith("3 ");
    }

    @Test
    @DisplayName("Fırtına push metni takım kapsamlı: 'Tüm monitörler' YOK, takımın adı ve KENDİ sayısı var")
    void pushText_isTeamScoped() {
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);
        http(4, B); http(5, B);   // B eşik altı ama hesap geneli sayaç 5 derdi

        storm.evaluate(a3, null);

        String msg = pushMessageFor(A);
        assertThat(msg).isNotNull().doesNotContain("Tüm monitörler").contains("Takım A").startsWith("3 ");
        assertThat(mailSubjects).allSatisfy(s -> assertThat(s).doesNotContain("Tüm monitörler"));
    }

    @Test
    @DisplayName("Kök-neden etiketi TAKIM kümesinden: A'nın 3 sentetik arızası 'Sentetik Senaryo Başarısız' — B'nin HTTP'leri 'Karışık' yapmaz")
    void rootCause_derivesFromTeamSet() {
        ev(1, EscalationService.TYPE_SCRIPTED_FAIL, A, "WARNING");
        ev(2, EscalationService.TYPE_SCRIPTED_FAIL, A, "WARNING");
        AlertEvent a3 = ev(3, EscalationService.TYPE_SCRIPTED_FAIL, A, "WARNING");
        http(4, B); http(5, B);

        storm.evaluate(a3, null);

        assertThat(inserts).isNotEmpty();
        assertThat(inserts.get(0)[4]).isEqualTo(EscalationService.TYPE_SCRIPTED_FAIL);
        assertThat(pushMessageFor(A)).contains("Sentetik Senaryo Başarısız").doesNotContain("Karışık");
    }

    @Test
    @DisplayName("Sahipsiz alarm (takım yok) fırtınaya GİRMEZ — başka takımların sayacına da eklenmez")
    void ownerlessEvent_neverStorms() {
        http(1, A); http(2, A); http(3, A);
        AlertEvent orphan = http(4, null);

        assertThat(storm.evaluate(orphan, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        assertThat(orphan.getStormId()).isNull();
        assertThat(inserts).isEmpty();
    }

    @Test
    @DisplayName("Fırtına push'u bireysel alarmdan GENİŞ kitleye gitmez: WARNING üyeli fırtına WARNING seviyesiyle çözümlenir (CRITICAL'a yükseltilmez)")
    void pushLevel_notWiderThanMembers() {
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);

        storm.evaluate(a3, null);

        assertThat(pushCalls).isNotEmpty();
        assertThat(pushCalls).allSatisfy(a -> assertThat(a[3]).isEqualTo("WARNING"));
    }

    @Test
    @DisplayName("Grup kipinde de yalıtım: aynı grup adında B'nin alarmı A'nın grup fırtınasına girmez")
    void perGroupMode_isAlsoTeamIsolated() {
        perGroup = true;
        HttpMonitor g = new HttpMonitor();
        g.setGroupName("Ödeme");
        lenient().when(httpRepo.findFirstByUrlOrderByIdAsc(anyString())).thenReturn(Optional.of(g));
        AlertEvent a1 = http(1, A), a2 = http(2, A), a3 = http(3, A), b1 = http(4, B);
        for (AlertEvent e : List.of(a1, a2, a3, b1)) e.setGroupName("Ödeme");   // önceki değerlendirmelerde damgalandı

        assertThat(storm.evaluate(a3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        assertThat(allRecipients()).contains("a@example.com").doesNotContain("b@example.com");
        assertThat(b1.getStormId()).isNull();
    }

    @Test
    @DisplayName("Eski (ACCOUNT kapsamlı) fırtına emeklilik penceresinden sonra takım bazında çözülür — kuruluş geneli günlük toplu tekrar GİTMEZ, üyeler BİLDİRİLDİ sayılır")
    void legacyAccountStorm_isDisbanded_noOrgWideRealert() {
        storm.startedAtMs = 0L;   // emeklilik penceresi (ilk izleme turları) doldu
        AlertStorm legacy = new AlertStorm();
        legacy.setId(900L);
        legacy.setScopeKey("ACCOUNT");
        legacy.setScopeType("ACCOUNT");
        legacy.setResolved(false);
        legacy.setRootCause(EscalationService.TYPE_SCRIPTED_FAIL);
        String twoDaysAgo = ISO.format(Instant.now().minus(2, ChronoUnit.DAYS));
        legacy.setCreatedAt(twoDaysAgo);
        legacy.setLastReAlertAt(twoDaysAgo);
        active.put("ACCOUNT", legacy);
        byId.put(900L, legacy);
        AlertEvent a1 = ev(1, EscalationService.TYPE_SCRIPTED_FAIL, A, "WARNING");
        AlertEvent a2 = ev(2, EscalationService.TYPE_SCRIPTED_FAIL, A, "WARNING");
        AlertEvent b1 = ev(3, EscalationService.TYPE_SCRIPTED_FAIL, B, "WARNING");
        for (AlertEvent e : List.of(a1, a2, b1)) e.setStormId(900L);

        storm.lifecycleSweep();

        assertThat(pushCalls).noneSatisfy(a -> assertThat(a[2]).isEqualTo("DAILY_REALERT"));
        assertThat(mailSubjects).noneSatisfy(s -> assertThat(s).contains("RE-ALERT"));
        assertThat(legacy.getResolved()).as("eski kapsamlı fırtına kapatılmalı").isTrue();
        assertThat(List.of(a1, a2, b1)).allSatisfy(e -> assertThat(e.getStormId()).isNull());
        // O-3: eşiği aşmayan takımların üyeleri "fırtına postasıyla bildirildi" sayılır → sonraki turda TEK TEK INITIAL yok.
        assertThat(List.of(a1, a2, b1)).allSatisfy(e -> assertThat(e.getLastReAlertAt()).isEqualTo(twoDaysAgo));
    }

    // ── O-3: eski fırtınanın emekliliği (ilk turlardan sonra, takım bazında) ─────────────────

    private AlertStorm legacyStorm(String lastNotified) {
        AlertStorm legacy = new AlertStorm();
        legacy.setId(901L);
        legacy.setScopeKey("ACCOUNT");
        legacy.setScopeType("ACCOUNT");
        legacy.setResolved(false);
        legacy.setRootCause(EscalationService.TYPE_HTTP_DOWN);
        legacy.setCreatedAt(lastNotified);
        legacy.setLastReAlertAt(lastNotified);
        active.put("ACCOUNT", legacy);
        byId.put(901L, legacy);
        return legacy;
    }

    @Test
    @DisplayName("O-3: emeklilik penceresi (ilk izleme turları) dolmadan eski fırtınaya DOKUNULMAZ — toplu tekrar / çözüm / bağ kopması YOK")
    void legacyStorm_untouchedDuringGrace() {
        AlertStorm legacy = legacyStorm(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        AlertEvent a1 = http(1, A);
        a1.setStormId(901L);
        storm.startedAtMs = System.currentTimeMillis();   // yeni açıldı

        storm.lifecycleSweep();

        assertThat(legacy.getResolved()).isFalse();
        assertThat(a1.getStormId()).isEqualTo(901L);   // fırtına aktif kalır → hayalet kapanırsa bireysel e-posta bastırılır
        assertThat(pushCalls).isEmpty();
        assertThat(mailTo).isEmpty();
    }

    @Test
    @DisplayName("O-3: eşiği aşan takımın hâlâ düşük üyeleri takım fırtınasına SESSİZCE taşınır (açılış postası/push YOK, toplu tekrar kadansı eski postadan)")
    void legacyStorm_membersAboveThreshold_moveSilentlyToTeamStorm() {
        String notified = ISO.format(Instant.now().minus(3, ChronoUnit.HOURS));
        legacyStorm(notified);
        AlertEvent a1 = http(1, A), a2 = http(2, A), a3 = http(3, A), b1 = http(4, B);
        for (AlertEvent e : List.of(a1, a2, a3, b1)) e.setStormId(901L);
        storm.startedAtMs = 0L;

        storm.lifecycleSweep();

        AlertStorm team = active.get("TEAM:7");
        assertThat(team).as("A takımının fırtınası").isNotNull();
        assertThat(List.of(a1, a2, a3)).allSatisfy(e -> assertThat(e.getStormId()).isEqualTo(team.getId()));
        assertThat(team.getLastReAlertAt()).as("toplu tekrar kadansı eski postadan sürer").isEqualTo(notified);
        assertThat(team.getLegacyStormId()).as("çözüm push'u / 7-24 açılışı eski fırtınanın kimliğini de sayar (D-b6)")
                .isEqualTo(901L);
        assertThat(b1.getStormId()).isNull();
        assertThat(b1.getLastReAlertAt()).as("B tekrar INITIAL almaz").isEqualTo(notified);
        assertThat(pushCalls).as("taşıma sessizdir").isEmpty();
        assertThat(mailTo).isEmpty();
    }

    @Test
    @DisplayName("O-3: eski fırtınanın KURTULAN üyelerinin toplu çözümü YALNIZ kendi takımına — hâlâ düşük üyesi olan başka takıma çözüm postası/push GİTMEZ")
    void legacyStorm_recoveryGoesOnlyToOwningTeam() {
        legacyStorm(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        AlertEvent recoveredA = http(1, A);
        recoveredA.setStormId(901L);
        recoveredA.setResolved(true);
        AlertEvent stillDownB = http(2, B);
        stillDownB.setStormId(901L);
        storm.startedAtMs = 0L;

        storm.lifecycleSweep();

        assertThat(allRecipients()).contains("a@example.com").doesNotContain("b@example.com");
        assertThat(pushCalls).allSatisfy(a -> {
            assertThat(a[1]).isEqualTo(A);
            assertThat(a[2]).isEqualTo("RESOLVE");
        });
    }

    @Test
    @DisplayName("O-3 (D-b6): taşınan üyeler kurtulunca takım fırtınasının ÇÖZÜM push'u eski fırtınanın kimliğini taşır — açılışı eski kimlikle alan 'düzeldi'yi de alır")
    void legacyMovedStorm_resolvePushCarriesLegacyId() {
        legacyStorm(ISO.format(Instant.now().minus(3, ChronoUnit.HOURS)));
        AlertEvent a1 = http(1, A), a2 = http(2, A), a3 = http(3, A);
        for (AlertEvent e : List.of(a1, a2, a3)) e.setStormId(901L);
        storm.startedAtMs = 0L;
        storm.lifecycleSweep();                       // emeklilik: A'nın üyeleri takım fırtınasına SESSİZCE taşınır
        AlertStorm team = active.get("TEAM:7");
        assertThat(team).isNotNull();
        assertThat(pushCalls).isEmpty();
        for (AlertEvent e : List.of(a1, a2, a3)) { e.setResolved(true); e.setResolvedAt(ISO.format(Instant.now())); }

        storm.lifecycleSweep();                       // eşik altı → takım fırtınası çözülür

        assertThat(team.getResolved()).isTrue();
        assertThat(pushCalls).as("takıma çözüm push'u").isNotEmpty();
        for (int i = 0; i < pushCalls.size(); i++) {
            assertThat(pushCalls.get(i)[1]).isEqualTo(A);
            assertThat(pushCalls.get(i)[2]).isEqualTo("RESOLVE");
            assertThat(pushLegacyIds.get(i)).as("önceden alanlar eski fırtınanın push'larından da okunur").isEqualTo(901L);
        }
    }

    @Test
    @DisplayName("O-3 (D-b7): üst üste binen emeklilik koşusu, okunduktan sonra başka fırtınaya taşınmış üyeyi fırtınasından KOPARMAZ")
    void legacyRelease_doesNotDetachMemberMovedElsewhere() {
        legacyStorm(ISO.format(Instant.now().minus(3, ChronoUnit.HOURS)));
        AlertEvent b1 = http(4, B);
        b1.setStormId(901L);
        // Üye listesi okunduktan SONRA (ilk koşu kilidi aşarken) üye başka bir fırtınaya taşındı.
        lenient().when(alertEventRepo.findByStormId(901L)).thenAnswer(i -> {
            List<AlertEvent> snapshot = events.stream().filter(e -> Objects.equals(e.getStormId(), 901L)).toList();
            if (b1.getStormId() != null && b1.getStormId() == 901L) b1.setStormId(777L);
            return snapshot;
        });
        storm.startedAtMs = 0L;

        storm.lifecycleSweep();

        assertThat(b1.getStormId()).isEqualTo(777L);
        assertThat(b1.getLastReAlertAt()).as("bildirildi damgası da basılmaz").isNull();
    }

    /** Olayın kopyası (fırtına testlerinin kullandığı alanlar) — "okunmuş satır" anlık görüntüsü. */
    private static AlertEvent copyOf(AlertEvent e) {
        AlertEvent c = new AlertEvent();
        c.setId(e.getId()); c.setDomain(e.getDomain()); c.setAlertType(e.getAlertType()); c.setAlertLevel(e.getAlertLevel());
        c.setTeamId(e.getTeamId()); c.setResolved(e.getResolved()); c.setResolvedAt(e.getResolvedAt());
        c.setResolvedSilently(e.getResolvedSilently()); c.setStormId(e.getStormId()); c.setGroupName(e.getGroupName());
        c.setAcknowledged(e.getAcknowledged()); c.setCreatedAt(e.getCreatedAt()); c.setLastReAlertAt(e.getLastReAlertAt());
        return c;
    }

    @Test
    @DisplayName("D-b7(a): üye listesi okunduktan SONRA (emeklilik sürerken) çözülen üye toplu çözüme girer — bireysel e-postası "
            + "'fırtına aktif' diye bastırılmıştı, eskiden hiçbir çözüm bildirimi gitmiyordu")
    void legacyRetire_memberResolvedDuringRetire_getsRecovery() {
        legacyStorm(ISO.format(Instant.now().minus(3, ChronoUnit.HOURS)));
        AlertEvent b1 = http(4, B);
        b1.setStormId(901L);
        AtomicLong reads = new AtomicLong();
        lenient().when(alertEventRepo.findByStormId(901L)).thenAnswer(i -> {
            // İlk okuma KOPYA döner (JPA gibi: sonradan başka işlemin yaptığı değişiklik bu listeye yansımaz).
            boolean first = reads.incrementAndGet() == 1;
            List<AlertEvent> snapshot = events.stream().filter(e -> Objects.equals(e.getStormId(), 901L))
                    .map(e -> first ? copyOf(e) : e).toList();
            if (first) {             // ilk okumadan hemen sonra izleme turu üyeyi çözer
                b1.setResolved(true);
                b1.setResolvedAt("2020-01-01T00:00:00");   // fırtına kapanmadan ÖNCE (geçmiş — kayan pencere değil)
            }
            return snapshot;
        });
        storm.startedAtMs = 0L;

        storm.lifecycleSweep();

        assertThat(allRecipients()).as("B takımına toplu çözüm").contains("b@example.com");
        assertThat(pushCalls).isNotEmpty().allSatisfy(a -> {
            assertThat(a[1]).isEqualTo(B);
            assertThat(a[2]).isEqualTo("RESOLVE");
        });
    }

    @Test
    @DisplayName("D-b7(a) + O-b2: fırtına kapandıktan SONRA çözülen üye (bireysel çözüm yolu) ve SESSİZ kapanan üye toplu çözüme GİRMEZ")
    void legacyRetire_lateOrSilentResolution_notCounted() {
        legacyStorm(ISO.format(Instant.now().minus(3, ChronoUnit.HOURS)));
        AlertEvent b1 = http(4, B), b2 = http(5, B);
        b1.setStormId(901L);
        b2.setStormId(901L);
        AtomicLong reads = new AtomicLong();
        lenient().when(alertEventRepo.findByStormId(901L)).thenAnswer(i -> {
            // İlk okuma KOPYA döner (JPA gibi: sonradan başka işlemin yaptığı değişiklik bu listeye yansımaz).
            boolean first = reads.incrementAndGet() == 1;
            List<AlertEvent> snapshot = events.stream().filter(e -> Objects.equals(e.getStormId(), 901L))
                    .map(e -> first ? copyOf(e) : e).toList();
            if (first) {
                b1.setResolved(true);
                b1.setResolvedAt("2999-01-01T00:00:00");   // fırtına kapandıktan sonra → bireysel "ÇÖZÜLDÜ" gider, çift olmasın
                b2.setResolved(true);
                b2.setResolvedAt("2020-01-01T00:00:00");
                b2.setResolvedSilently(true);               // izleme silindi / tür kapatıldı — kurtulmadı
            }
            return snapshot;
        });
        storm.startedAtMs = 0L;

        storm.lifecycleSweep();

        assertThat(mailTo).isEmpty();
        assertThat(pushCalls).isEmpty();
    }

    // ── O-4: fırtına = ÇOK HEDEF birden ─────────────────────────────────────────────────

    @Test
    @DisplayName("O-4: tek host'un ACCESSIBILITY + PORT_DOWN + DNS_FAILURE + HTTP_DOWN alarmları TEK hedeftir — fırtına olmaz, bireysel gider")
    void sameHostMultipleTypes_isOneTarget_noStorm() {
        ev(1, EscalationService.TYPE_ACCESSIBILITY, A, "WARNING").setDomain("host1.example.com");
        ev(2, EscalationService.TYPE_PORT_DOWN, A, "WARNING").setDomain("host1.example.com");
        ev(3, EscalationService.TYPE_DNS_FAILURE, A, "WARNING").setDomain("HOST1.example.com");
        AlertEvent httpSame = ev(4, EscalationService.TYPE_HTTP_DOWN, A, "WARNING");
        httpSame.setDomain("https://host1.example.com:8443/giris?x=1");

        assertThat(storm.evaluate(httpSame, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        assertThat(inserts).isEmpty();
        assertThat(StormService.distinctTargets(events)).isEqualTo(1);
    }

    @Test
    @DisplayName("O-4: YÜZDE kipinde küçük takımda eşik mutlak tabanın (3 hedef) altına İNMEZ — 2 host arızası fırtına değildir")
    void percentMode_smallTeam_floorIsThreeTargets() {
        com.sitemonitor.repository.HttpMonitorRepository teamHttp = httpRepo;
        org.mockito.Mockito.lenient().when(teamHttp.countByTeamIdAndActiveTrue(A)).thenReturn(10L);   // takım: 10 izleme
        AppSettingsService settings = (AppSettingsService) ReflectionTestUtils.getField(storm, "appSettings");
        lenient().when(settings.getString(eq(StormService.KEY_UNIT), any())).thenReturn("PERCENT");
        lenient().when(settings.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(10);   // %10 × 10 = 1

        assertThat(storm.computeThreshold(A)).isEqualTo(StormService.PERCENT_MIN_TARGETS);
        http(1, A); AlertEvent a2 = http(2, A);
        assertThat(storm.evaluate(a2, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
    }

    // ── 2026-10-03: push fırtınaya DEVREDİLMEZ (ürün varsayılanı) — yalıtım bireysel push'ta da geçerli ──────────────

    @Test
    @DisplayName("Bireysel push kipi: A'nın fırtınası açılınca TOPLU push hiçbir takıma gitmez; e-posta yine yalnız A'ya")
    void pushIndividual_stormOpens_noAggregatedPushToAnyTeam() {
        pushIndividual = true;
        http(1, A); http(2, A); AlertEvent a3 = http(3, A);
        AlertEvent b1 = http(4, B);

        assertThat(storm.evaluate(a3, null)).isEqualTo(StormService.StormAction.SUPPRESSED);

        assertThat(allRecipients()).contains("a@example.com").doesNotContain("b@example.com");
        assertThat(pushCalls).as("toplu fırtına push'u yok — üyelerin push'u alarm başına kendi hattından gider").isEmpty();
        assertThat(memberPushes).as("açılış push'unu fırtına motoru değil EscalationService gönderir").isEmpty();
        assertThat(b1.getStormId()).isNull();
    }

    @Test
    @DisplayName("Bireysel push kipi: fırtınanın günlük tekrarı push'u YALNIZ fırtına takımının açık + onaysız üyelerine, açılış sırasıyla; B'ye hiçbir şey")
    void pushIndividual_dailyRealert_onlyOwnTeamMembers_inCreationOrder() {
        pushIndividual = true;
        String twoDaysAgo = ISO.format(Instant.now().minus(2, ChronoUnit.DAYS));
        AlertStorm s = new AlertStorm();
        s.setId(950L);
        s.setScopeKey("TEAM:" + A);
        s.setScopeType("TEAM");
        s.setTeamId(A);
        s.setResolved(false);
        s.setCreatedAt(twoDaysAgo);
        s.setLastReAlertAt(twoDaysAgo);
        s.setLastMemberAt(ISO.format(Instant.now()));   // taze — mühürlü değil
        active.put(s.getScopeKey(), s);
        byId.put(950L, s);
        AlertEvent a1 = http(1, A), a2 = http(2, A), a3 = http(3, A), acked = http(4, A);
        a1.setCreatedAt(ISO.format(Instant.now().minus(30, ChronoUnit.MINUTES)));
        a2.setCreatedAt(ISO.format(Instant.now().minus(40, ChronoUnit.MINUTES)));   // en eski → ilk
        a3.setCreatedAt(ISO.format(Instant.now().minus(20, ChronoUnit.MINUTES)));
        acked.setAcknowledged(true);
        for (AlertEvent e : List.of(a1, a2, a3, acked)) e.setStormId(950L);
        http(5, B);   // başka takımın açık alarmı — fırtına üyesi değil

        storm.lifecycleSweep();

        assertThat(mailSubjects).anySatisfy(sub -> assertThat(sub).contains("RE-ALERT"));
        assertThat(allRecipients()).doesNotContain("b@example.com");
        assertThat(pushCalls).as("toplu tekrar push'u yok").isEmpty();
        assertThat(memberPushes).extracting(a -> a[0]).containsExactly(2L, 1L, 3L);
        assertThat(memberPushes).allSatisfy(a -> {
            assertThat(a[1]).isEqualTo("DAILY_REALERT");
            assertThat(a[2]).isEqualTo(A);
        });
    }
}
