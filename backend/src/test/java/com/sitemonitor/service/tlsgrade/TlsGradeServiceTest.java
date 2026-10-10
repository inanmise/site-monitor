package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.model.TlsGradeStatus;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.repository.TlsGradeStatusRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TLS notu karşılaştırması (2026-10-10): ilk görüş olay üretmez, düşüş günlüğe + durum damgasına + etkinlik akışına
 * yazılır, yükseliş düşüş damgasını temizler, değişmeyen satır yazılmaz; liste satırına not + düşüş göstergesi.
 */
class TlsGradeServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private TlsProfileRepository profileRepo;
    private TlsGradeStatusRepository statusRepo;
    private TlsGradeChangeRepository changeRepo;
    private ActivityLogService activity;
    private TlsGradeService service;
    private final Map<Long, TlsGradeStatus> statuses = new HashMap<>();
    private final List<TlsGradeChange> changes = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        profileRepo = mock(TlsProfileRepository.class);
        statusRepo = mock(TlsGradeStatusRepository.class);
        changeRepo = mock(TlsGradeChangeRepository.class);
        activity = mock(ActivityLogService.class);
        when(statusRepo.findAllById(any())).thenAnswer(i -> {
            List<TlsGradeStatus> out = new ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) if (statuses.containsKey(id)) out.add(copy(statuses.get(id)));
            return out;
        });
        when(statusRepo.saveAll(any())).thenAnswer(i -> {
            for (TlsGradeStatus s : (Iterable<TlsGradeStatus>) i.getArgument(0)) statuses.put(s.getInventoryId(), copy(s));
            return List.of();
        });
        when(statusRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(statuses.get(i.getArgument(0))));
        when(changeRepo.saveAll(any())).thenAnswer(i -> {
            for (TlsGradeChange c : (Iterable<TlsGradeChange>) i.getArgument(0)) changes.add(c);
            return List.of();
        });
        service = new TlsGradeService(profileRepo, statusRepo, changeRepo);
        service.setActivityLog(activity);
    }

    private static TlsGradeStatus copy(TlsGradeStatus s) {
        TlsGradeStatus c = new TlsGradeStatus();
        c.setInventoryId(s.getInventoryId()); c.setDomain(s.getDomain()); c.setGrade(s.getGrade()); c.setReasons(s.getReasons());
        c.setEvaluatedAt(s.getEvaluatedAt()); c.setPreviousGrade(s.getPreviousGrade()); c.setChangedAt(s.getChangedAt());
        c.setDroppedFrom(s.getDroppedFrom()); c.setDroppedAt(s.getDroppedAt());
        return c;
    }

    private static CertificateInventory inv(long id, String domain) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id); i.setDomain(domain); i.setTeamId(7L); i.setUgTeamId(9L); i.setActive(true);
        return i;
    }

    private TlsGradeService.ReconcileResult run(CertificateInventory inv, String grade, String... codes) {
        return service.reconcile(List.of(new TlsGradeService.Evaluated(inv, grade, List.of(codes))));
    }

    @Test
    @DisplayName("İlk görüş: durum yazılır, değişim/etkinlik YOK")
    void firstSightingIsBaseline() {
        TlsGradeService.ReconcileResult r = run(inv(1, "a.example.com"), "A", "NO_TLS13");
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.changed()).isFalse();
        assertThat(changes).isEmpty();
        assertThat(statuses.get(1L).getGrade()).isEqualTo("A");
        assertThat(statuses.get(1L).getReasons()).isEqualTo("NO_TLS13");
        verify(activity, never()).recordLifecycle(anyString(), any(), anyString(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Düşüş: günlük satırı + düşüş damgası + TLS_GRADE_DROPPED etkinliği")
    void dropIsRecorded() {
        CertificateInventory i = inv(1, "a.example.com");
        run(i, "A", "NO_TLS13");
        TlsGradeService.ReconcileResult r = run(i, "B", "TLS10_ENABLED", "NO_TLS13");
        assertThat(r.drops()).isEqualTo(1);
        assertThat(changes).hasSize(1);
        TlsGradeChange c = changes.get(0);
        assertThat(c.getFromGrade()).isEqualTo("A");
        assertThat(c.getToGrade()).isEqualTo("B");
        assertThat(c.getDirection()).isEqualTo(TlsGradeChange.DROP);
        assertThat(c.getTeamId()).isEqualTo(7L);
        assertThat(c.getUgTeamId()).isEqualTo(9L);
        assertThat(statuses.get(1L).getDroppedFrom()).isEqualTo("A");
        assertThat(statuses.get(1L).getDroppedAt()).isNotBlank();
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(1L), eq("a.example.com"), eq("a.example.com"),
                eq(7L), eq("TLS_GRADE_DROPPED"), eq("system"), anyString());
    }

    @Test
    @DisplayName("Yükseliş düşüş damgasını (önceki düzeye dönünce) temizler; etkinlik yazılmaz")
    void riseClearsDrop() {
        CertificateInventory i = inv(1, "a.example.com");
        run(i, "A", "NO_TLS13");
        run(i, "C", "NO_TLS12");
        assertThat(statuses.get(1L).getDroppedFrom()).isEqualTo("A");
        run(i, "B", "TLS10_ENABLED");
        assertThat(statuses.get(1L).getDroppedFrom()).as("B < A → düşüş hâlâ geçerli").isEqualTo("A");
        TlsGradeService.ReconcileResult r = run(i, "A+");
        assertThat(r.rises()).isEqualTo(1);
        assertThat(statuses.get(1L).getDroppedFrom()).isNull();
        assertThat(changes).extracting(TlsGradeChange::getDirection).containsExactly("DROP", "RISE", "RISE");
    }

    @Test
    @DisplayName("Not ve nedenler aynıysa hiçbir şey yazılmaz")
    void unchangedWritesNothing() {
        CertificateInventory i = inv(1, "a.example.com");
        run(i, "A", "NO_TLS13");
        org.mockito.Mockito.clearInvocations(statusRepo);
        TlsGradeService.ReconcileResult r = run(i, "A", "NO_TLS13");
        assertThat(r.updated()).isZero();
        verify(statusRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("Notlanmamış (geçersiz) not karşılaştırmaya girmez — geçici hata düşüş sayılmaz")
    void invalidGradeIgnored() {
        TlsGradeService.ReconcileResult r = run(inv(1, "a.example.com"), null);
        assertThat(r.created()).isZero();
        verify(statusRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("Satıra not, neden kodları ve (pencere içindeki) düşüş göstergesi yazılır")
    void applyToRow() {
        CertificateInventory i = inv(5, "www.example.com");
        TlsGradeStatus st = new TlsGradeStatus();
        st.setInventoryId(5L); st.setGrade("B"); st.setDroppedFrom("A");
        st.setDroppedAt(ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        TlsProfile p = TlsGradeRulesTest.perfectProfile();
        p.setTls10(TlsProfile.YES);
        TlsGradeService.Snapshot snap = new TlsGradeService.Snapshot(Map.of("www.example.com", p), Map.of(5L, st));
        LatestCheck lc = TlsGradeRulesTest.perfectCheck();
        CertificateDto dto = CertificateDto.from(lc, List.of("www.example.com"), List.of(), List.of());
        service.apply(dto, lc, i, snap);
        assertThat(dto.getTlsGrade()).isEqualTo("B");
        assertThat(dto.getTlsGradeReasons()).containsExactly("TLS10_ENABLED");
        assertThat(dto.getTlsGradeDrop()).containsEntry("from", "A").containsEntry("to", "B");
    }

    @Test
    @DisplayName("Düşüş göstergesi: pencere dışı ya da toparlanmış → yok")
    void dropWindow() {
        TlsGradeStatus st = new TlsGradeStatus();
        st.setDroppedFrom("A");
        st.setDroppedAt(ISO.format(Instant.now().minus(10, ChronoUnit.DAYS)));
        assertThat(service.dropOf(st, "B", Instant.now())).isNull();
        st.setDroppedAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        assertThat(service.dropOf(st, "A", Instant.now())).as("toparlandı").isNull();
        assertThat(service.dropOf(st, "C", Instant.now())).isNotNull();
    }

    @Test
    @DisplayName("Elle yüklenen satıra not yazılmaz")
    void manualRowNotGraded() {
        CertificateInventory i = inv(6, "upload-key");
        i.setCertSource(CertificateInventory.SOURCE_MANUAL);
        LatestCheck lc = TlsGradeRulesTest.perfectCheck();
        CertificateDto dto = CertificateDto.from(lc, List.of(), List.of(), List.of());
        service.apply(dto, lc, i, TlsGradeService.Snapshot.EMPTY);
        assertThat(dto.getTlsGrade()).isNull();
        assertThat(dto.getTlsGradeReasons()).isNull();
    }

    @Test
    @DisplayName("Son düşüşler: kapsam SY ya da UG takımına göre süzülür; güncel not + toparlanma bilgisi")
    void dropsScoped() {
        TlsGradeChange mine = change(1L, "mine.example.com", 7L, null, "A", "C");
        TlsGradeChange ug = change(2L, "ug.example.com", 3L, 9L, "A+", "B");
        TlsGradeChange other = change(3L, "other.example.com", 4L, 5L, "A", "F");
        when(changeRepo.findRecent(anyString(), eq("DROP"), any())).thenReturn(List.of(mine, ug, other));
        TlsGradeStatus now = new TlsGradeStatus();
        now.setInventoryId(2L); now.setGrade("A+");
        statuses.put(2L, now);
        List<Map<String, Object>> rows = service.drops(Set.of(7L, 9L), 30, 50, Map.of(7L, "Ödeme"));
        assertThat(rows).extracting(m -> m.get("domain")).containsExactly("mine.example.com", "ug.example.com");
        assertThat(rows.get(0).get("team_name")).isEqualTo("Ödeme");
        assertThat(rows.get(1).get("recovered")).isEqualTo(true);
        assertThat(service.drops(List.of(), 30, 50, Map.of())).isEmpty();
        assertThat(service.drops(null, 30, 50, Map.of())).hasSize(3);
    }

    @Test
    @DisplayName("Kapsama: bekleyen = profil olmayan ağ kaydı; elle yüklenen sayılmaz")
    void coverage() {
        TlsProfile ok = TlsGradeRulesTest.perfectProfile();
        ok.setDomain("a.example.com");
        ok.setProbedAt("2026-10-10T08:00:00");
        TlsProfile failed = new TlsProfile();
        failed.setDomain("b.example.com");
        failed.setStatus(TlsProfile.STATUS_FAILED);
        when(profileRepo.findByDomainIn(anyCollection())).thenReturn(List.of(ok, failed));
        CertificateInventory manual = inv(9, "upload");
        manual.setCertSource(CertificateInventory.SOURCE_MANUAL);
        Map<String, Object> c = service.coverage(List.of(inv(1, "a.example.com"), inv(2, "b.example.com"),
                inv(3, "c.example.com"), manual));
        assertThat(c).containsEntry("endpoints", 3).containsEntry("ok", 1).containsEntry("failed", 1)
                .containsEntry("pending", 1).containsEntry("latest_probe_at", "2026-10-10T08:00:00");
    }

    private static TlsGradeChange change(Long invId, String domain, Long team, Long ug, String from, String to) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(invId); c.setDomain(domain); c.setTeamId(team); c.setUgTeamId(ug);
        c.setFromGrade(from); c.setToGrade(to); c.setDirection("DROP"); c.setReasons("NO_TLS12");
        c.setChangedAt("2026-10-09T10:00:00");
        return c;
    }

    @SuppressWarnings("unused")
    private static Collection<Long> ids(Long... v) { return List.of(v); }
}
