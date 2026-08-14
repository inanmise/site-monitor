package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALTYAPI ATLAMASI ≠ ARIZA.
 *
 * <p>Sahada en pahalı yanlış-alarm kaynağı buydu: k6 havuzu ({@code pool-size}, varsayılan 2)
 * dolduğunda kontrol yürütülemiyor, sonuç {@code ERROR + ok=false} yazılıyor, oradan
 * {@code up=false} olup teyit zincirine giriyordu. 100 monitörlük bir filoda tek bir sweep'te
 * ~94 monitör bu yola düşebiliyor: hedeflerin hiçbirinde sorun yokken toplu alarm (ya da toplu
 * bastırma devreye girerse GERÇEK kesinti dâhil hiç alarm). Ayrıca {@code ok=false} satırı
 * uptime rollup'ını kirletiyordu.
 *
 * <p>Sözleşme: yürütülemeyen kontrol {@code SKIPPED} döner; kaydedilmez, alarm zincirine girmez,
 * teyit denemesinde "kanıt" sayılmaz.
 */
class ScriptedInfraSkipTest {

    private ScriptedCheckerService service() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var appSettings = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(appSettings.getInt(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, appSettings, registry, new ProxySettings());
        svc.init();
        return svc;
    }

    @Test
    @DisplayName("k6 yoksa sonuç SKIPPED'dır — ERROR değil (alarm zincirine girmesin)")
    void missingK6YieldsSkipped() {
        var svc = service();   // init() k6'yı arar; test ortamında PATH'te yok

        var res = svc.run(new com.sitemonitor.model.ScriptedMonitor());

        assertThat(res.status()).isEqualTo(ScriptedCheckerService.STATUS_SKIPPED);
        assertThat(ScriptedCheckerService.isSkipped(res)).isTrue();
        assertThat(res.error()).contains("k6");
        // Koşum HİÇ olmadı: 0 ms "çok hızlı koştu" değil "koşamadı" demektir.
        assertThat(res.durationMs()).isNull();
        assertThat(res.exitCode()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Atlanan kontrol scripted.k6.skipped sayacını artırır (kapasite darlığının tek sinyali)")
    void skipIncrementsCounter() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var appSettings = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(appSettings.getInt(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, appSettings, registry, new ProxySettings());
        svc.init();

        svc.run(new com.sitemonitor.model.ScriptedMonitor());
        svc.run(new com.sitemonitor.model.ScriptedMonitor());

        assertThat(registry.get("scripted.k6.skipped").counter().count()).isEqualTo(2.0);
        // k6 yokken gauge 0 olmalı: altyapı bunu sondadan öğrenebilsin.
        assertThat(registry.get("scripted.k6.available").gauge().value()).isZero();
    }

    @Test
    @DisplayName("isSkipped yalnız SKIPPED'a true der — gerçek arıza statüleri etkilenmez")
    void isSkippedDiscriminates() {
        var r = new ProcessProbe.Result("out", 1, false);
        var s = new ScriptedCheckerService.Summary();
        assertThat(ScriptedCheckerService.isSkipped(
                ScriptedCheckerService.buildResult("FAIL", 10L, r, s, "out", "x", false))).isFalse();
        assertThat(ScriptedCheckerService.isSkipped(
                ScriptedCheckerService.buildResult("TIMEOUT", 10L, r, s, "out", "x", false))).isFalse();
        assertThat(ScriptedCheckerService.isSkipped(
                ScriptedCheckerService.buildResult("ERROR", 10L, r, s, "out", "x", false))).isFalse();
        assertThat(ScriptedCheckerService.isSkipped(null)).isFalse();
    }

    @Test
    @DisplayName("Sweep beklemesi permit beklemesinden KISA olamaz — sonuç sessizce kaybolmasın")
    void sweepWaitCoversPermitWait() {
        // 2026-08: sweep 200 sn beklerken permit beklemesi timeout(180)+30 = 210 sn'ye çıkabiliyordu.
        // Fark açıkken koşum arka planda tamamlanıyor, sonucu hiçbir yere yazılmıyordu: satır yok,
        // checked_at eskiyor, alarm da çıkmıyor → monitör ekranda sessizce donuyordu.
        int maxTimeout = 180;                                  // ABS_MAX_TIMEOUT
        assertThat(ScriptedCheckerService.maxWaitSeconds()).isGreaterThanOrEqualTo(maxTimeout + 30);
    }
}
