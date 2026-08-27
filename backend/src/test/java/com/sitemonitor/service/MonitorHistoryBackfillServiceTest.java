package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Denetim kaydından geçmişe TEK SEFERLİK taşıma (K5).
 *
 * <p>Buradaki asıl risk çift yazım: backfill iki kez koşarsa her izlemenin geçmişi ikişerlenir ve
 * bunu geri almak elle temizlik gerektirir. Nişan satırı ve satır bazlı kontrol o yüzden var,
 * ikisi de burada pinleniyor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorHistoryBackfillServiceTest {

    @Mock AuditLogRepository auditRepo;
    @Mock MonitorChangeLogRepository changeRepo;
    @Mock MonitorHistoryService history;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks MonitorHistoryBackfillService service;

    private static AuditLog audit(String event, String resourceType, String id, String detail,
                                  String actor, String at) {
        AuditLog a = new AuditLog();
        a.setEventType(event);
        a.setResourceType(resourceType);
        a.setResourceId(id);
        a.setDetail(detail);
        a.setActor(actor);
        a.setActorId(42L);
        a.setActorTeamId(5L);
        a.setIpAddress("10.20.30.40");
        a.setEventTime(at);
        return a;
    }

    /** Gercekten yazildi diyen taklit — sayac artik YALNIZ yazilan satiri sayiyor. */
    private void writesEverything() {
        when(history.recordBackfill(anyString(), anyLong(), any(), any(), anyString(),
                any(), any(), any(), any(), any())).thenReturn(true);
    }

    /** Belirtilen kapsam surumunde bir nisan satiri. */
    private void markerAtVersion(int version) {
        MonitorChangeLog m = new MonitorChangeLog();
        m.setResourceKind("SYSTEM");
        m.setEventType("AUDIT_BACKFILL");
        m.setSeq(version);
        when(changeRepo.findFirstByResourceKindAndEventTypeOrderByIdDesc("SYSTEM", "AUDIT_BACKFILL"))
                .thenReturn(Optional.of(m));
    }

    @Test
    @DisplayName("Denetim satırları geçmişe taşınır; ad ve IP korunur")
    void movesAuditRowsIntoHistory() {
        writesEverything();
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "Ödeme portu", "N23456", "2026-01-01T09:00:00"),
                audit("MONITOR_UPDATE", "PORT_MONITOR", "7", "Ödeme portu", "N23456", "2026-02-01T09:00:00")));

        assertThat(service.runOnce()).isEqualTo(2);

        verify(history).recordBackfill("PORT", 7L, "Ödeme portu", 5L, "CREATE", null,
                "N23456", 42L, "10.20.30.40", "2026-01-01T09:00:00");
        verify(history).recordBackfill("PORT", 7L, "Ödeme portu", 5L, "UPDATE", null,
                "N23456", 42L, "10.20.30.40", "2026-02-01T09:00:00");
    }

    @Test
    @DisplayName("AYNI kapsam sürümünde ikinci koşu hiçbir şey yapmaz — geçmiş çiftlenmez")
    void secondRunIsNoOp() {
        markerAtVersion(2);

        assertThat(service.runOnce()).isEqualTo(-1);

        verify(auditRepo, never()).findByEventTypeInOrderByEventTimeAsc(any());
        verify(history, never()).recordBackfill(anyString(), anyLong(), any(), any(), anyString(),
                any(), any(), any(), any(), any());
        verify(changeRepo, never()).save(any());
    }

    @Test
    @DisplayName("Koşu bitince nişan satırı yazılır — kaç satır taşındığı da orada durur")
    void writesMarkerRow() {
        writesEverything();
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "N1", "2026-01-01T09:00:00")));

        service.runOnce();

        ArgumentCaptor<MonitorChangeLog> cap = ArgumentCaptor.forClass(MonitorChangeLog.class);
        verify(changeRepo).save(cap.capture());
        MonitorChangeLog marker = cap.getValue();
        assertThat(marker.getResourceKind()).isEqualTo("SYSTEM");
        assertThat(marker.getEventType()).isEqualTo("AUDIT_BACKFILL");
        assertThat(marker.getNote()).contains("1");
    }

    @Test
    @DisplayName("İlk CREATE satırı kaydın OLUŞTURANINI doldurur; sonraki CREATE'ler tekrar yazmaz")
    void stampsCreatorOnceFromOldestCreate() {
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "ilk-kullanici", "2026-01-01T09:00:00"),
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "sonraki", "2026-03-01T09:00:00")));

        service.runOnce();

        // Kolon zaten doluysa güncelleme SQL'i eşleşmez (WHERE created_by IS NULL) — ikinci çağrı
        // hiç yapılmıyor ki gereksiz yazma da olmasın.
        verify(jdbcTemplate).update(anyString(), any(), any(), any());
        verify(jdbcTemplate).update(
                "UPDATE port_monitors SET created_by = ?, created_ip = ? WHERE id = ? AND created_by IS NULL",
                "ilk-kullanici", "10.20.30.40", 7L);
    }

    @Test
    @DisplayName("Tanınmayan kaynak türü ve sayı olmayan kimlik ATLANIR, koşu düşmez")
    void skipsUnknownRows() {
        writesEverything();
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "TEAM", "7", "takım", "N1", "2026-01-01T09:00:00"),
                audit("MONITOR_CREATE", "PORT_MONITOR", "hepsi", "p", "N1", "2026-01-02T09:00:00"),
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "N1", "2026-01-03T09:00:00")));

        assertThat(service.runOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("Okuma patlarsa NİŞAN YAZILMAZ — bir sonraki açılış yeniden dener")
    void failureLeavesNoMarker() {
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any()))
                .thenThrow(new RuntimeException("tablo yok"));

        assertThat(service.runOnce()).isZero();

        verify(changeRepo, never()).save(any());
    }

    /**
     * Kullanici bildirimi (2026-08-27): degisiklik ekraninda PAGESPEED turu kart uretiyor ama
     * ozellik ONCESI degisiklikleri gorunmuyordu. Kok neden: tur, geri doldurma haritasina hic
     * yazilmamisti ve "kostu" nisani ikinci kosuyu sonsuza dek engelliyordu.
     */
    @Test
    @DisplayName("PAGESPEED denetim satirlari da tasinir (kapsam disinda kalmisti)")
    void movesPagespeedRows() {
        writesEverything();
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PAGESPEED_MONITOR", "9", "Kampanya sayfasi",
                        "N23456", "2026-03-01T09:00:00")));

        assertThat(service.runOnce()).isEqualTo(1);

        verify(history).recordBackfill("PAGESPEED", 9L, "Kampanya sayfasi", 5L, "CREATE", null,
                "N23456", 42L, "10.20.30.40", "2026-03-01T09:00:00");
        // Olusturan kunyesi de dogru tabloya yazilmali; tablo eslemesi de eksikti.
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("pagespeed_monitors"),
                any(), any(), any());
    }

    @Test
    @DisplayName("ESKI surum nisani kosuyu ENGELLEMEZ — kapsam buyudugunde bir kez daha kosulur")
    void olderMarkerVersionRunsAgain() {
        writesEverything();
        markerAtVersion(1);
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PAGESPEED_MONITOR", "9", "s", "N1", "2026-03-01T09:00:00")));

        assertThat(service.runOnce()).isEqualTo(1);

        verify(history).recordBackfill(org.mockito.ArgumentMatchers.eq("PAGESPEED"), anyLong(), any(),
                any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Nisan KAPSAM SURUMUNU tasir — yoksa sonraki genisleme bir daha kosamaz")
    void markerCarriesScopeVersion() {
        writesEverything();
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "N1", "2026-01-01T09:00:00")));

        service.runOnce();

        ArgumentCaptor<MonitorChangeLog> cap = ArgumentCaptor.forClass(MonitorChangeLog.class);
        verify(changeRepo).save(cap.capture());
        assertThat(cap.getValue().getSeq())
                .as("nisan surumsuz yazilirsa kapsam genisledi diye tekrar kosulamaz")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Ikinci kosuda satirlarin cogu satir-bazli kontrolle ATLANIR. Sayac atlananlari da sayarsa
     * nisan "N satir tasindi" derken yalan soyler ve loga bakan kisi tasima olmadigini goremez.
     */
    @Test
    @DisplayName("Sayac YALNIZ gercekten yazilan satiri sayar (atlananlar sayilmaz)")
    void countsOnlyWrittenRows() {
        when(history.recordBackfill(anyString(), anyLong(), any(), any(), anyString(),
                any(), any(), any(), any(), any())).thenReturn(false);
        when(auditRepo.findByEventTypeInOrderByEventTimeAsc(any())).thenReturn(List.of(
                audit("MONITOR_CREATE", "PORT_MONITOR", "7", "p", "N1", "2026-01-01T09:00:00"),
                audit("MONITOR_UPDATE", "PORT_MONITOR", "7", "p", "N1", "2026-02-01T09:00:00")));

        assertThat(service.runOnce()).isZero();
    }
}
