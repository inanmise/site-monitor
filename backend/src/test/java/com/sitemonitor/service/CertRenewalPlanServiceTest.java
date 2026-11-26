package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sertifika yenileme planı kuralları (H paketi 2026-11-26, sertifika B4 / B5 / B7). */
class CertRenewalPlanServiceTest {

    private final CertificateInventoryRepository repo = mock(CertificateInventoryRepository.class);
    private final LatestCheckRepository latest = mock(LatestCheckRepository.class);
    private final MonitorHistoryService history = mock(MonitorHistoryService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final CertRenewalPlanService svc = new CertRenewalPlanService(repo, latest, history, activity);

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "ops");
        s.setAttribute("displayName", "Ops Kişi");
        return s;
    }

    private static CertificateInventory inv() {
        CertificateInventory i = new CertificateInventory();
        i.setId(7L);
        i.setDomain("a.example.com");
        i.setTeamId(3L);
        return i;
    }

    /**
     * isDone doğruluk tablosu. Sütunlar: plan günü | taban parmak izi | taban bitiş | sunulan parmak izi | sunulan verilme |
     * sunulan bitiş | beklenen. Boş hücre = null.
     */
    @ParameterizedTest(name = "[{index}] {7}")
    @CsvSource(delimiter = '|', value = {
            // Tabanlı plan: farklı + daha uzun geçerli → tamamlandı (plan gününden ÖNCE verilmiş erken yenileme dahil)
            "2026-12-01|AAAA|2026-12-10T00:00:00|BBBB|2026-11-01T00:00:00|2027-12-10T00:00:00|true |erken yenileme",
            "2026-12-01|AAAA|2026-12-10T00:00:00|BBBB|2026-12-05T00:00:00|2027-12-10T00:00:00|true |plan sonrası yenileme",
            "2026-12-01|AAAA|2026-12-10T00:00:00|aaaa|2026-12-05T00:00:00|2027-12-10T00:00:00|false|aynı sertifika (harf farkı)",
            "2026-12-01|AAAA|2026-12-10T00:00:00|BBBB|2026-12-05T00:00:00|2026-12-10T00:00:00|false|farklı ama daha uzun değil",
            "2026-12-01|AAAA|2026-12-10T00:00:00|BBBB|2026-12-05T00:00:00|2026-06-01T00:00:00|false|farklı ve daha kısa (geri dönüş)",
            "2026-12-01|AAAA|2026-12-10T00:00:00|    |2026-12-05T00:00:00|2027-12-10T00:00:00|false|sunulan parmak izi okunamadı",
            "2026-12-01|AAAA|2026-12-10T00:00:00|BBBB|2026-12-05T00:00:00|           |false|sunulan bitiş okunamadı",
            // Tabansız (eski) plan: İstanbul günü kuralı — 21:30 UTC = ertesi gün İstanbul
            "2026-12-01|    |                   |BBBB|2026-11-30T21:30:00|2027-12-10T00:00:00|true |eski kural, İstanbul günü 12-01",
            "2026-12-01|    |                   |BBBB|2026-11-30T20:30:00|2027-12-10T00:00:00|false|eski kural, İstanbul günü 11-30",
            "2026-12-01|    |                   |BBBB|                   |2027-12-10T00:00:00|false|eski kural, verilme bilinmiyor",
            "          |AAAA|2026-12-10T00:00:00|BBBB|2026-12-05T00:00:00|2027-12-10T00:00:00|false|plan yok",
    })
    void isDoneTruthTable(String plannedAt, String baseFp, String baseNotAfter, String servedFp, String nb, String na,
                          boolean expected, String label) {
        assertThat(CertRenewalPlanService.isDone(plannedAt, baseFp, baseNotAfter, servedFp, nb, na)).as(label).isEqualTo(expected);
        // Eski 6 argümanlı biçim = yazılma anı olmayan (eski sürüm) plan
        assertThat(CertRenewalPlanService.isDone(plannedAt, null, baseFp, baseNotAfter, servedFp, nb, na)).as(label).isEqualTo(expected);
    }

    /**
     * Bu sürümle yazılmış plan (yazılma anı dolu). Sütunlar: plan günü | taban parmak izi | taban bitiş | sunulan parmak izi |
     * sunulan verilme | sunulan bitiş | beklenen. Bağımsız inceleme (H paketi): tabansız YENİ plan eski "verilme günü ≥ plan günü"
     * kuralıyla kapanmaz — geçmiş tarihli yeni plan ilk kontrolde kendiliğinden kapanıyordu.
     */
    @ParameterizedTest(name = "[{index}] {7}")
    @CsvSource(delimiter = '|', value = {
            "2026-11-01|    |                   |BBBB|2026-11-20T00:00:00|2027-12-10T00:00:00|false|tabansız yeni plan, geçmiş tarih: kapanmaz",
            "2026-11-01|    |                   |BBBB|2026-12-20T00:00:00|2027-12-10T00:00:00|false|tabansız yeni plan, plan sonrası verilme: yine kapanmaz",
            "2026-11-01|AAAA|                   |BBBB|2026-11-20T00:00:00|2027-12-10T00:00:00|true |benimsenmiş taban (bitiş okunamamış), farklı sertifika",
            "2026-11-01|AAAA|                   |aaaa|2026-11-20T00:00:00|2027-12-10T00:00:00|false|benimsenmiş taban, aynı sertifika",
            "2026-11-01|AAAA|                   |    |2026-11-20T00:00:00|2027-12-10T00:00:00|false|benimsenmiş taban, sunulan okunamadı",
            "2026-11-01|AAAA|2026-12-10T00:00:00|BBBB|2026-11-20T00:00:00|2027-12-10T00:00:00|true |tam tabanlı yeni plan: taban kuralı",
            "2026-11-01|AAAA|2026-12-10T00:00:00|BBBB|2026-11-20T00:00:00|2026-06-01T00:00:00|false|tam tabanlı yeni plan, daha kısa",
    })
    void isDoneNewPlanTruthTable(String plannedAt, String baseFp, String baseNotAfter, String servedFp, String nb, String na,
                                 boolean expected, String label) {
        assertThat(CertRenewalPlanService.isDone(plannedAt, "2026-11-26T08:00:00", baseFp, baseNotAfter, servedFp, nb, na))
                .as(label).isEqualTo(expected);
    }

    @Test
    @DisplayName("onCheckResult: tabansız YENİ plan kapanmaz — ilk kontrol tabanı koşullu benimser; sonraki farklı + uzun sertifika kapatır")
    void newBaselessPlanAdoptsBaseThenClosesOnRealRenewal() {
        CertificateInventory i = inv();
        i.setRenewalPlannedAt("2026-11-01");          // geçmiş tarih
        i.setRenewalPlannedRecordedAt("2026-11-26T08:00:00");
        when(repo.adoptRenewalPlanBase(7L, "2026-11-01", "", "AAAA", "2026-12-10T00:00:00")).thenReturn(1);

        // İlk kontrol: plan gününden SONRA verilmiş sertifika — eski kural kapatırdı; şimdi yalnız taban benimsenir
        assertThat(svc.onCheckResult(i, "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isFalse();
        verify(repo).adoptRenewalPlanBase(7L, "2026-11-01", "", "AAAA", "2026-12-10T00:00:00");
        verify(repo, never()).closeRenewalPlanIfUnchanged(any(), any(), any(), any());
        verifyNoInteractions(history, activity);
        assertThat(i.getRenewalPlannedAt()).isEqualTo("2026-11-01");
        assertThat(i.getRenewalPlannedFingerprint()).isEqualTo("AAAA");
        assertThat(i.getRenewalPlannedNotAfter()).isEqualTo("2026-12-10T00:00:00");

        // Aynı sertifika yeniden görülür → hiçbir şey yazılmaz (taban artık var, benimseme bir daha denenmez)
        assertThat(svc.onCheckResult(i, "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isFalse();
        verify(repo, times(1)).adoptRenewalPlanBase(any(), any(), any(), any(), any());

        // Gerçek yenileme → koşullu kapanış
        when(repo.closeRenewalPlanIfUnchanged(7L, "2026-11-01", "", "AAAA")).thenReturn(1);
        assertThat(svc.onCheckResult(i, "BBBB", "2026-12-01T00:00:00", "2027-12-10T00:00:00")).isTrue();
        assertThat(i.getRenewalPlannedAt()).isNull();
        assertThat(i.getRenewalPlannedRecordedAt()).isNull();
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(7L), any(), any(), eq(3L),
                eq("RENEWAL_DETECTED"), eq("scheduler"), anyString());
    }

    @Test
    @DisplayName("onCheckResult: tabansız yeni planda sunulan parmak izi okunamazsa benimseme yok; arada plan değişti (0 satır) → bellek değişmez")
    void newBaselessPlanAdoptionGuards() {
        CertificateInventory i = inv();
        i.setRenewalPlannedAt("2026-11-01");
        i.setRenewalPlannedRecordedAt("2026-11-26T08:00:00");
        assertThat(svc.onCheckResult(i, " ", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isFalse();
        verify(repo, never()).adoptRenewalPlanBase(any(), any(), any(), any(), any());

        when(repo.adoptRenewalPlanBase(any(), any(), any(), any(), any())).thenReturn(0);
        assertThat(svc.onCheckResult(i, "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isFalse();
        assertThat(i.getRenewalPlannedFingerprint()).as("0 satır: arada plan değişti, bellek yazılmaz").isNull();
        verify(repo, never()).closeRenewalPlanIfUnchanged(any(), any(), any(), any());

        // ESKİ plan (yazılma anı yok, taban yok): eski kural aynen — plan gününden sonra verilme kapatır
        CertificateInventory legacy = inv();
        legacy.setRenewalPlannedAt("2026-11-01");
        when(repo.closeRenewalPlanIfUnchanged(7L, "2026-11-01", "", "")).thenReturn(1);
        assertThat(svc.onCheckResult(legacy, "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isTrue();
        verify(repo, times(1)).adoptRenewalPlanBase(any(), any(), any(), any(), any());   // yalnız yeni plandaki 0 satırlık deneme
    }

    @Test
    @DisplayName("plan: yazılma anı varlığa da yazılır (bu sürümün planı)")
    void planStampsRecordedAt() {
        when(latest.findById(anyString())).thenReturn(Optional.empty());
        when(repo.writeRenewalPlan(any(), any(), any(), any(), any(), isNull(), isNull(), any())).thenReturn(1);
        CertificateInventory i = svc.plan(inv(), "2026-12-01", null, session());
        assertThat(i.getRenewalPlannedRecordedAt()).isNotBlank();
    }

    @Test
    @DisplayName("plan: gerçek gün olmayan tarih → 400 alan date; 501 karakterlik not → 400 alan note; hiçbir şey yazılmaz")
    void planValidation() {
        assertThatThrownBy(() -> svc.plan(inv(), "2026-02-31", null, session()))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields()).containsKey("date"));
        assertThatThrownBy(() -> svc.plan(inv(), "next week", null, session()))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.plan(inv(), "2026-12-01", "x".repeat(501), session()))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields()).containsKey("note"));
        verifyNoInteractions(history, activity);
        verify(repo, never()).writeRenewalPlan(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("plan: taban = şu an sunulan sertifika; hedefli yazım + geçmiş + aktivite")
    void planWritesBaseFromLatest() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("a.example.com");
        lc.setFingerprint("AAAA");
        lc.setNotAfter("2026-12-10T00:00:00");
        when(latest.findById("a.example.com")).thenReturn(Optional.of(lc));
        when(repo.writeRenewalPlan(eq(7L), eq("2026-12-01"), eq("ops"), eq("Ops Kişi"), eq("not"), eq("AAAA"),
                eq("2026-12-10T00:00:00"), anyString())).thenReturn(1);
        CertificateInventory i = svc.plan(inv(), " 2026-12-01 ", " not ", session());
        assertThat(i.getRenewalPlannedAt()).isEqualTo("2026-12-01");
        assertThat(i.getRenewalPlannedFingerprint()).isEqualTo("AAAA");
        verify(history).record(eq(MonitorHistoryService.INVENTORY), eq(7L), eq("a.example.com"), eq(3L),
                eq(MonitorHistoryService.UPDATE), anyMap(), anyMap(), eq("renewal-plan"), any());
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(7L), eq("a.example.com"), eq("a.example.com"), eq(3L),
                eq("RENEWAL_PLANNED"), eq("ops"), contains("2026-12-01"));
    }

    @Test
    @DisplayName("plan: kontrol yoksa taban boş (eski kural kullanılır); kayıt silinmişse 0 satır → NoSuchElement")
    void planWithoutLatestAndDeletedRecord() {
        when(latest.findById(anyString())).thenReturn(Optional.empty());
        when(repo.writeRenewalPlan(any(), any(), any(), any(), any(), isNull(), isNull(), any())).thenReturn(1);
        CertificateInventory i = svc.plan(inv(), "2026-12-01", "", session());
        assertThat(i.getRenewalPlannedFingerprint()).isNull();
        assertThat(i.getRenewalPlannedNote()).as("boş not saklanmaz").isNull();

        when(repo.writeRenewalPlan(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> svc.plan(inv(), "2026-12-01", null, session()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("onCheckResult: tamamlanınca koşullu kapanış + 'Sistem' geçmişi + RENEWAL_DETECTED; 0 satır → iz yok; hata yutulur")
    void onCheckResultClosesConditionally() {
        CertificateInventory i = inv();
        i.setRenewalPlannedAt("2026-12-01");
        i.setRenewalPlannedFingerprint("AAAA");
        i.setRenewalPlannedNotAfter("2026-12-10T00:00:00");
        when(repo.closeRenewalPlanIfUnchanged(7L, "2026-12-01", "", "AAAA")).thenReturn(1);
        assertThat(svc.onCheckResult(i, "BBBB", "2026-11-20T00:00:00", "2027-12-10T00:00:00")).isTrue();
        assertThat(i.getRenewalPlannedAt()).isNull();
        verify(history).record(eq(MonitorHistoryService.INVENTORY), eq(7L), any(), eq(3L), eq(MonitorHistoryService.UPDATE),
                anyMap(), anyMap(), startsWith("renewal-detected"), isNull());
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(7L), any(), any(), eq(3L),
                eq("RENEWAL_DETECTED"), eq("scheduler"), anyString());

        // Arada plan değişti: 0 satır → geçmiş / aktivite yok
        CertificateInventory j = inv();
        j.setRenewalPlannedAt("2026-12-01");
        j.setRenewalPlannedFingerprint("AAAA");
        j.setRenewalPlannedNotAfter("2026-12-10T00:00:00");
        when(repo.closeRenewalPlanIfUnchanged(any(), any(), any(), any())).thenReturn(0);
        clearInvocations(history, activity);
        assertThat(svc.onCheckResult(j, "BBBB", "2026-11-20T00:00:00", "2027-12-10T00:00:00")).isFalse();
        verifyNoInteractions(history, activity);

        // Tamamlanmadı (aynı sertifika) → hiç yazılmaz
        assertThat(svc.onCheckResult(j, "AAAA", "2026-01-01T00:00:00", "2026-12-10T00:00:00")).isFalse();

        // Depo hatası taramayı kırmaz
        when(repo.closeRenewalPlanIfUnchanged(any(), any(), any(), any())).thenThrow(new RuntimeException("db"));
        assertThat(svc.onCheckResult(j, "BBBB", "2026-11-20T00:00:00", "2027-12-10T00:00:00")).isFalse();
    }

    @Test
    @DisplayName("unplan: plan yoksa false (yazım yok); koşullu kaldırma 1 satır → geçmiş + RENEWAL_PLAN_CLEARED")
    void unplan() {
        assertThat(svc.unplan(inv(), session())).isFalse();
        verify(repo, never()).clearRenewalPlanIfUnchanged(any(), any(), any(), any(), any(), any(), any());
        CertificateInventory i = inv();
        i.setRenewalPlannedAt("2026-12-01");
        i.setRenewalPlannedNote("not");
        when(repo.clearRenewalPlanIfUnchanged(eq(7L), eq("2026-12-01"), eq("not"), eq(""), anyString(), eq("ops"), eq("Ops Kişi")))
                .thenReturn(1);
        assertThat(svc.unplan(i, session())).isTrue();
        assertThat(i.getRenewalPlannedAt()).isNull();
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(7L), any(), any(), eq(3L),
                eq("RENEWAL_PLAN_CLEARED"), eq("ops"), contains("2026-12-01"));
    }
}
