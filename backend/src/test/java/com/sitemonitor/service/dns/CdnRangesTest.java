package com.sitemonitor.service.dns;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DnsCheckerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * CDN uç aralıkları + CDN farkındalıklı DNS değişiklik tespiti (2026-11-18, kullanıcı raporu: Cloudflare arkasındaki
 * bir alan adı her sorguda farklı adres çifti döndürüyordu — 188.114.96.3/97.3 → 96.5/97.5 — ve kümeler ayrık
 * olduğu için her seferinde DNS_CHANGED alarmı açılıyordu).
 */
class CdnRangesTest {

    @Test
    @DisplayName("katalog: her aralık okunur, sağlayıcılar çakışmaz, kaynak adresi ve alınma tarihi yazılı")
    void catalogueParses() {
        assertThat(CdnRanges.PROVIDERS).extracting(CdnRanges.Provider::name).containsExactly("Cloudflare", "Fastly");
        assertThat(CdnRanges.PROVIDERS.get(0).ranges()).hasSize(CdnRanges.CLOUDFLARE.size()).hasSize(22);
        assertThat(CdnRanges.PROVIDERS.get(1).ranges()).hasSize(CdnRanges.FASTLY.size()).hasSize(24);
        assertThat(CdnRanges.PROVIDERS).allSatisfy(p -> assertThat(p.source()).startsWith("https://"));
        assertThat(CdnRanges.RETRIEVED).matches("\\d{4}-\\d{2}-\\d{2}");
        for (String c : CdnRanges.CLOUDFLARE) {
            IpRanges.Range r = IpRanges.parse(c);
            for (IpRanges.Range f : CdnRanges.PROVIDERS.get(1).ranges()) {
                assertThat(f.contains(r.network()) || r.contains(f.network())).as("%s ↔ Fastly", c).isFalse();
            }
        }
    }

    @Test
    @DisplayName("tek değer: Cloudflare / Fastly adresleri tanınır, başka ağ ve IP olmayan değer null")
    void providerOf() {
        assertThat(CdnRanges.providerOf("188.114.96.3")).isEqualTo("Cloudflare");
        assertThat(CdnRanges.providerOf("188.114.97.5")).isEqualTo("Cloudflare");
        assertThat(CdnRanges.providerOf("104.21.32.1")).isEqualTo("Cloudflare");
        assertThat(CdnRanges.providerOf("2a06:98c1:3120::3")).isEqualTo("Cloudflare");
        assertThat(CdnRanges.providerOf("::ffff:188.114.96.3")).isEqualTo("Cloudflare");
        assertThat(CdnRanges.providerOf("151.101.1.140")).isEqualTo("Fastly");
        assertThat(CdnRanges.providerOf("2a04:4e42::81")).isEqualTo("Fastly");
        assertThat(CdnRanges.providerOf("52.216.8.1")).isNull();
        assertThat(CdnRanges.providerOf("10.0.0.1")).isNull();
        assertThat(CdnRanges.providerOf("site.cdn.cloudflare.net.")).isNull();
        assertThat(CdnRanges.providerOf("")).isNull();
        assertThat(CdnRanges.providerOf(null)).isNull();
    }

    @Test
    @DisplayName("küme: hepsi aynı sağlayıcıdaysa adı; karışık, aralık dışı, boş ya da çok büyük küme null")
    void sameProvider() {
        assertThat(CdnRanges.sameProvider(List.of("188.114.96.3", "188.114.97.3", "188.114.96.5"))).isEqualTo("Cloudflare");
        assertThat(CdnRanges.sameProvider(List.of("188.114.96.3", "2a06:98c1:3120::3"))).isEqualTo("Cloudflare");
        assertThat(CdnRanges.sameProvider(List.of("188.114.96.3", "151.101.1.140"))).isNull();
        assertThat(CdnRanges.sameProvider(List.of("188.114.96.3", "52.216.8.1"))).isNull();
        assertThat(CdnRanges.sameProvider(List.of("188.114.96.3", "site.example.com."))).isNull();
        assertThat(CdnRanges.sameProvider(List.of())).isNull();
        assertThat(CdnRanges.sameProvider(null)).isNull();
        List<String> big = new ArrayList<>(Collections.nCopies(CdnRanges.MAX_VALUES + 1, "188.114.96.3"));
        assertThat(CdnRanges.sameProvider(big)).isNull();
        assertThat(CdnRanges.sameProviderOfLines("188.114.96.3\n188.114.97.3", "188.114.96.5\n\n188.114.97.5"))
                .isEqualTo("Cloudflare");
        assertThat(CdnRanges.sameProviderOfLines("188.114.96.3", null)).isEqualTo("Cloudflare");
        assertThat(CdnRanges.sameProviderOfLines("", "")).isNull();
        assertThat(CdnRanges.sameProviderOfLines("1.2.3.4", "188.114.96.3")).isNull();
    }

    @Test
    @DisplayName("geçmiş satırı: rotasyon satırı ait olduğu CDN'i söyler (rotation_cdn); değişmiş ya da CDN dışı satır söylemez")
    void dnsRecordRotationCdn() {
        com.sitemonitor.model.DnsRecord r = new com.sitemonitor.model.DnsRecord();
        r.setRotated(true);
        r.setPreviousValue("188.114.96.3\n188.114.97.3");
        r.setValue("188.114.96.5\n188.114.97.5");
        assertThat(r.getRotationCdn()).isEqualTo("Cloudflare");
        r.setRotated(false);
        assertThat(r.getRotationCdn()).isNull();
        r.setRotated(true);
        r.setPreviousValue("1.2.3.4\n5.6.7.8");
        r.setValue("5.6.7.8\n9.9.9.9");
        assertThat(r.getRotationCdn()).as("kesişen küme rotasyonu, CDN değil").isNull();
    }

    // ── DnsCheckerService.detectChange(prev, cur, cdnAware) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("rapordaki olay: Cloudflare çifti tamamen değişti → CDN farkındalığıyla ROTATED, kapalıyken CHANGED")
    void detectChange_cloudflarePairSwap() {
        String before = "188.114.96.3\n188.114.97.3";
        String after = "188.114.96.5\n188.114.97.5";
        assertThat(DnsCheckerService.detectChange(before, after, true)).isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
        assertThat(DnsCheckerService.detectChange(before, after, false)).isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange(before, after)).isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
    }

    @Test
    @DisplayName("gerçek değişiklik CDN farkındalığında da CHANGED: CDN'den çıkış, CDN'e giriş, sağlayıcı değişimi")
    void detectChange_realMovesStillChanged() {
        assertThat(DnsCheckerService.detectChange("188.114.96.3", "52.216.8.1", true))
                .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange("52.216.8.1", "188.114.96.3", true))
                .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange("188.114.96.3", "151.101.1.140", true))
                .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange("site.a.example.", "site.b.example.", true))
                .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange("1.2.3.4", "9.9.9.9", true))
                .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
    }

    @Test
    @DisplayName("CDN farkındalığı eski sonuçları değiştirmez: aynı küme NONE, kesişen küme ROTATED, boş taraf NONE")
    void detectChange_otherOutcomesUnchanged() {
        assertThat(DnsCheckerService.detectChange(null, "188.114.96.3", true)).isEqualTo(DnsCheckerService.ChangeKind.NONE);
        assertThat(DnsCheckerService.detectChange("188.114.96.3", "188.114.96.3", true))
                .isEqualTo(DnsCheckerService.ChangeKind.NONE);
        assertThat(DnsCheckerService.detectChange("188.114.96.3\n188.114.97.3", "188.114.97.3\n188.114.96.3", true))
                .isEqualTo(DnsCheckerService.ChangeKind.NONE);
        assertThat(DnsCheckerService.detectChange("1.2.3.4\n5.6.7.8", "5.6.7.8\n9.9.9.9", true))
                .isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
        assertThat(DnsCheckerService.detectChange("", "188.114.96.3", true)).isEqualTo(DnsCheckerService.ChangeKind.NONE);
        assertThat(DnsCheckerService.detectChange("188.114.96.3", "", true)).isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("ayar: site.monitor.dns.cdn-rotation canlı okunur, ayar servisi yoksa açık sayılır")
    void cdnRotationSetting() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(DnsCheckerService.CDN_ROTATION_KEY, true)).thenReturn(false);
        assertThat(new DnsCheckerService(settings).cdnRotationEnabled()).isFalse();
        when(settings.getBoolean(DnsCheckerService.CDN_ROTATION_KEY, true)).thenReturn(true);
        assertThat(new DnsCheckerService(settings).cdnRotationEnabled()).isTrue();
        assertThat(new DnsCheckerService(null).cdnRotationEnabled()).isTrue();
        assertThat(DnsCheckerService.CDN_ROTATION_KEY).isEqualTo("site.monitor.dns.cdn-rotation");
    }

    // ── checkPropagation: çözümleyiciler aynı CDN'in farklı adreslerini verirse tutarsız DEĞİL ─────────────────────

    private static Map<String, Object> ok(String... values) {
        return Map.of("success", true, "values", List.of(values));
    }

    private DnsCheckerService propagationService(boolean cdnRotation) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(DnsCheckerService.CDN_ROTATION_KEY, true)).thenReturn(cdnRotation);
        return spy(new DnsCheckerService(settings));
    }

    @Test
    @DisplayName("yayılım: üç çözümleyici farklı Cloudflare adresi → tutarsız değil, cdn=Cloudflare")
    void propagation_cloudflareIsConsistent() {
        DnsCheckerService svc = propagationService(true);
        doReturn(ok("188.114.96.3", "188.114.97.3")).when(svc).checkVia(eq("site.example"), eq("A"), eq("1.1.1.1"));
        doReturn(ok("188.114.96.7", "188.114.97.7")).when(svc).checkVia(eq("site.example"), eq("A"), eq("8.8.8.8"));
        doReturn(ok("188.114.96.8", "188.114.97.8")).when(svc).checkVia(eq("site.example"), eq("A"), eq("9.9.9.9"));
        Map<String, Object> r = svc.checkPropagation("site.example", "A", List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"));
        assertThat(r).containsEntry("inconsistent", false).containsEntry("cdn", "Cloudflare").containsEntry("ok_count", 3);
    }

    @Test
    @DisplayName("yayılım: ayar kapalıyken, ya da adresler CDN dışındaysa farklı kümeler eskisi gibi tutarsız")
    void propagation_offOrNonCdnIsInconsistent() {
        DnsCheckerService off = propagationService(false);
        doReturn(ok("188.114.96.3")).when(off).checkVia(eq("site.example"), eq("A"), eq("1.1.1.1"));
        doReturn(ok("188.114.96.7")).when(off).checkVia(eq("site.example"), eq("A"), eq("8.8.8.8"));
        Map<String, Object> r1 = off.checkPropagation("site.example", "A", List.of("1.1.1.1", "8.8.8.8"));
        assertThat(r1).containsEntry("inconsistent", true).doesNotContainKey("cdn");

        DnsCheckerService on = propagationService(true);
        doReturn(ok("188.114.96.3")).when(on).checkVia(eq("site.example"), eq("A"), eq("1.1.1.1"));
        doReturn(ok("52.216.8.1")).when(on).checkVia(eq("site.example"), eq("A"), eq("8.8.8.8"));
        Map<String, Object> r2 = on.checkPropagation("site.example", "A", List.of("1.1.1.1", "8.8.8.8"));
        assertThat(r2).containsEntry("inconsistent", true).doesNotContainKey("cdn");
    }

    @Test
    @DisplayName("yayılım: aynı kümeler ya da tek başarılı çözümleyici cdn alanı yazmaz (yalnız fark açıklanır)")
    void propagation_sameSetsNoCdnField() {
        DnsCheckerService svc = propagationService(true);
        doReturn(ok("188.114.96.3")).when(svc).checkVia(eq("site.example"), eq("A"), eq("1.1.1.1"));
        doReturn(ok("188.114.96.3")).when(svc).checkVia(eq("site.example"), eq("A"), eq("8.8.8.8"));
        doReturn(Map.of("success", false, "error", "SERVFAIL")).when(svc).checkVia(eq("site.example"), eq("A"), eq("9.9.9.9"));
        Map<String, Object> r = svc.checkPropagation("site.example", "A", List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"));
        assertThat(r).containsEntry("inconsistent", false).doesNotContainKey("cdn").containsEntry("ok_count", 2);
    }
}
