package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına KAPANIŞ YARIŞI (2026-10-09): yeni alarm, yaşam döngüsü fırtınayı kapatırken (FLOOR / SEALED) ona bağlanıp
 * ~24 saat sessiz kalabiliyordu — kapanış üye listesini okuduktan sonra bağlanan alarm ne duyuruluyor ne bağı
 * koparılıyordu; {@code lastReAlertAt=now} damgası bireysel ilk bildirimi bir yeniden uyarı aralığı erteliyordu.
 *
 * <p>Üç kapı: (1) {@code evaluate}'in koşullu sayacı 0 satır döndürürse (fırtına çoktan kapandı) bağlanma YOK →
 * bireysel; (2) çağıran {@code storm_id}'yi kaydettikten sonra {@link StormService#closedAfterAttach} fırtına
 * kapandıysa bağı hedefli koşullu UPDATE ile koparır; (3) kapanış {@code resolved=true}'yu yazdıktan sonra listede
 * olmayan, hâlâ bağlı açık olayları (geç katılanlar) ayırır. Yarış yokken davranış aynıdır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormCloseRaceTest {

    @Mock AlertStormRepository stormRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock NotificationGroupService notificationGroups;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String BUMP = "UPDATE alert_storms SET member_count = COALESCE";
    private StormService storm;

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, notificationGroups);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private static AlertEvent down(long id, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain("host" + id + ".example.com"); e.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        e.setAlertLevel("WARNING"); e.setTeamId(14L); e.setResolved(false); e.setCreatedAt(createdAt);
        return e;
    }

    private static AlertStorm teamStorm(long id, String createdAt, String lastMemberAt, String lastReAlertAt, boolean resolved) {
        AlertStorm s = new AlertStorm();
        s.setId(id); s.setScopeKey("TEAM:14"); s.setScopeType("TEAM"); s.setResolved(resolved);
        s.setCreatedAt(createdAt); s.setLastMemberAt(lastMemberAt); s.setLastReAlertAt(lastReAlertAt);
        return s;
    }

    private void lockOk() {
        when(jdbcTemplate.update(startsWith("DELETE FROM scheduler_lock"), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any())).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> leaveRows() {
        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(eq(StormService.SQL_MEMBER_LEFT), cap.capture());
        List<Object[]> all = new ArrayList<>();
        for (List<Object[]> l : cap.getAllValues()) all.addAll(l);
        return all;
    }

    // ── (1) evaluate: fırtına bağlanma anında kapanmış → bağlanma yok ─────────────────────────────────────────

    @Test
    @DisplayName("Koşullu sayaç 0 satır (fırtına bulunduktan sonra kapandı) → SEND_INDIVIDUAL, stormId yok, üyelik satırı yok")
    void evaluate_stormClosedBeforeBump_sendsIndividually() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.of(teamStorm(5, ago(10), ago(1), ago(10), false)));
        when(jdbcTemplate.update(startsWith(BUMP), any(Object[].class))).thenReturn(0);

        AlertEvent e = down(77, ago(0));
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);

        assertThat(e.getStormId()).isNull();
        verify(jdbcTemplate, never()).update(eq(StormService.SQL_MEMBER_INSERT), any(Object[].class));
    }

    @Test
    @DisplayName("Koşullu sayaç yazılamadı (DB hatası) → güvenli taraf: SEND_INDIVIDUAL, stormId yok")
    void evaluate_bumpFails_sendsIndividually() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.of(teamStorm(5, ago(10), ago(1), ago(10), false)));
        when(jdbcTemplate.update(startsWith(BUMP), any(Object[].class))).thenThrow(new RuntimeException("bağlantı koptu"));

        AlertEvent e = down(78, ago(0));
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SEND_INDIVIDUAL);
        assertThat(e.getStormId()).isNull();
    }

    @Test
    @DisplayName("Yarış yok: sayaç 1 satır → eskisi gibi SUPPRESSED, stormId damgalanır, ATTACH üyelik satırı yazılır")
    void evaluate_normalAttach_unchanged() {
        when(stormRepo.findByScopeKeyAndResolvedFalse("TEAM:14"))
                .thenReturn(Optional.of(teamStorm(5, ago(10), ago(1), ago(10), false)));
        when(jdbcTemplate.update(startsWith(BUMP), any(Object[].class))).thenReturn(1);

        AlertEvent e = down(79, ago(0));
        assertThat(storm.evaluate(e, null)).isEqualTo(StormService.StormAction.SUPPRESSED);
        assertThat(e.getStormId()).isEqualTo(5L);
        verify(jdbcTemplate).update(eq(StormService.SQL_MEMBER_INSERT), eq(5L), eq(79L), anyString(), eq(AlertStormMember.JOIN_ATTACH));
    }

    // ── (2) çağıranın bağlanma sonrası denetimi ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("closedAfterAttach: fırtına hâlâ aktif → false, hiçbir yazma yok, olay olduğu gibi")
    void closedAfterAttach_stormActive_noop() {
        when(stormRepo.findById(5L)).thenReturn(Optional.of(teamStorm(5, ago(10), ago(0), ago(10), false)));
        AlertEvent e = down(80, ago(0));
        e.setStormId(5L);
        e.setLastReAlertAt(ago(0));

        assertThat(storm.closedAfterAttach(e)).isFalse();

        assertThat(e.getStormId()).isEqualTo(5L);
        assertThat(e.getLastReAlertAt()).isNotNull();
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(anyLong(), anyLong());
        verify(jdbcTemplate, never()).batchUpdate(eq(StormService.SQL_MEMBER_LEFT), anyList());
    }

    @Test
    @DisplayName("closedAfterAttach: fırtına kaydedildikten sonra kapanmış → hedefli koşullu ayırma (storm_id + last_re_alert_at NULL), UNLINKED ayrılışı")
    void closedAfterAttach_stormClosed_unlinks() {
        when(stormRepo.findById(5L)).thenReturn(Optional.of(teamStorm(5, ago(10), ago(0), ago(10), true)));
        when(alertEventRepo.unlinkFromStormIfLinked(81L, 5L)).thenReturn(1);
        AlertEvent e = down(81, ago(0));
        e.setStormId(5L);
        e.setLastReAlertAt(ago(0));

        assertThat(storm.closedAfterAttach(e)).isTrue();

        verify(alertEventRepo).unlinkFromStormIfLinked(81L, 5L);
        verify(alertEventRepo, never()).save(any());   // varlık save'i YOK — bayat varlık alanları geri sarmaz
        assertThat(e.getStormId()).isNull();
        assertThat(e.getLastReAlertAt()).isNull();
        List<Object[]> rows = leaveRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo(AlertStormMember.LEAVE_UNLINKED);
        assertThat(rows.get(0)[2]).isEqualTo(5L);
        assertThat(rows.get(0)[3]).isEqualTo(81L);
    }

    @Test
    @DisplayName("closedAfterAttach: kapanış geç katılanı zaten ayırmış (0 satır) → true, ikinci ayrılış satırı yazılmaz")
    void closedAfterAttach_alreadyUnlinkedByLifecycle() {
        when(stormRepo.findById(5L)).thenReturn(Optional.of(teamStorm(5, ago(10), ago(0), ago(10), true)));
        when(alertEventRepo.unlinkFromStormIfLinked(82L, 5L)).thenReturn(0);
        AlertEvent e = down(82, ago(0));
        e.setStormId(5L);

        assertThat(storm.closedAfterAttach(e)).isTrue();
        verify(jdbcTemplate, never()).batchUpdate(eq(StormService.SQL_MEMBER_LEFT), anyList());
    }

    @Test
    @DisplayName("closedAfterAttach: bağsız olay → false, sorgu yok")
    void closedAfterAttach_unlinkedEvent_noQuery() {
        assertThat(storm.closedAfterAttach(down(83, ago(0)))).isFalse();
        verifyNoInteractions(stormRepo);
    }

    // ── (3) kapanış: listeden sonra bağlanan geç katılan ayrılır ──────────────────────────────────────────────

    @Test
    @DisplayName("SEALED kapanış: üye listesi okunduktan sonra bağlanan alarm (listede yok, hâlâ bağlı) ayrılır → sonraki tur bireysel İLK")
    void sealedClose_lateJoinerUnlinked() {
        lockOk();
        String announcedAt = ago(120);
        AlertStorm s = teamStorm(7, ago(600), ago(45), announcedAt, false);   // mühürlü (son üye 45 dk önce)
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent announced = down(1, ago(600));
        AlertEvent announced3 = down(3, ago(300));
        AlertEvent unannounced = down(2, ago(60));
        // Kapanışın gördüğü liste: 3 hâlâ-down üye (histerezis tabanının üstü) → mühür kapatır.
        when(alertEventRepo.findByStormId(7L)).thenReturn(List.of(announced, announced3, unannounced));
        when(alertEventRepo.releaseFromStormAsNotified(anyLong(), eq(7L), eq(announcedAt))).thenReturn(1);
        AlertEvent late = down(414, ago(0));
        late.setStormId(7L);
        late.setLastReAlertAt(ago(0));
        // Kapanış YAZILDIKTAN sonraki okuma: geç katılan hâlâ bağlı (bilinen üyeler artık bağsız).
        when(alertEventRepo.findByStormIdAndResolvedFalse(7L)).thenReturn(List.of(late));
        when(alertEventRepo.unlinkFromStormIfLinked(414L, 7L)).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_SEALED);
        verify(alertEventRepo).unlinkFromStormIfLinked(414L, 7L);
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(eq(1L), anyLong());
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(eq(2L), anyLong());
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(eq(3L), anyLong());
        verify(alertEventRepo).unlinkFromStorm(2L, 7L);   // duyurulmamış bilinen üye: mevcut yol
        // Sıra: önce fırtına kapanışı kaydedilir, sonra geç katılan okunur.
        var order = inOrder(stormRepo, alertEventRepo);
        order.verify(stormRepo).save(s);
        order.verify(alertEventRepo).findByStormIdAndResolvedFalse(7L);
        assertThat(leaveRows()).anySatisfy(r -> {
            assertThat(r[1]).isEqualTo(AlertStormMember.LEAVE_UNLINKED);
            assertThat(r[3]).isEqualTo(414L);
        });
    }

    @Test
    @DisplayName("FLOOR kapanışı, yarış yok: geç katılan okuması boş → ek ayırma yazılmaz (davranış aynı)")
    void floorClose_noRace_noExtraWrites() {
        lockOk();
        AlertStorm s = teamStorm(8, ago(30), ago(1), ago(20), false);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent a = down(1, ago(30));
        AlertEvent b = down(2, ago(30)); b.setResolved(true); b.setResolvedAt(ago(2));
        when(alertEventRepo.findByStormId(8L)).thenReturn(List.of(a, b));

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_FLOOR);
        verify(alertEventRepo).findByStormIdAndResolvedFalse(8L);
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Özellik kapatıldı (disband): listeden sonra bağlanan üye de ayrılır")
    void disband_lateJoinerUnlinked() {
        lockOk();
        when(appSettings.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(false);
        AlertStorm s = teamStorm(9, ago(30), ago(1), ago(20), false);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(s));
        AlertEvent known = down(1, ago(30));
        AlertEvent late = down(415, ago(0));
        late.setStormId(9L);
        when(alertEventRepo.findByStormIdAndResolvedFalse(9L))
                .thenReturn(List.of(known))           // disband başındaki okuma
                .thenReturn(List.of(known, late));    // kapanış yazıldıktan sonraki okuma
        when(alertEventRepo.findByStormId(9L)).thenReturn(List.of(known));
        when(alertEventRepo.unlinkFromStormIfLinked(415L, 9L)).thenReturn(1);

        storm.lifecycleSweep();

        assertThat(s.getResolved()).isTrue();
        assertThat(s.getResolveReason()).isEqualTo(StormService.RESOLVE_DISABLED);
        verify(alertEventRepo).unlinkFromStormIfLinked(415L, 9L);
        verify(alertEventRepo, never()).unlinkFromStormIfLinked(eq(1L), anyLong());
    }
}
