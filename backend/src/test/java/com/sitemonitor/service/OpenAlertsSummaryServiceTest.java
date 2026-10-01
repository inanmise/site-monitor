package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.sitemonitor.repository.AlertEventRepository;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İzleme menüsü rozetleri (2026-09-30): türe göre açık alarm özeti — kapsam Alarm Geçmişi ile aynı; kapsamsız kullanıcı
 * hiçbir şey görmez.
 *
 * <p>Performans (2026-10-01): sayı + seviye kırılımı + sahiplenilmemiş TEK gruplu sorgudan (kesin, {@code sampled} hep
 * false); özet satırları dar projeksiyondan (takım adı sorguda, ayrı takım sorgusu yok); pencereyi başka sekme
 * doldurduysa eksik sekme kendi tipleriyle tamamlanır; {@link OpenAlertsSummaryService#cached} kapsam anahtarı başına
 * bellek + {@code fresh} ile atlama.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenAlertsSummaryServiceTest {

    @Mock AlertEventRepository alertEventRepo;

    /** {@code findOpenSummaryItems} satırı: [id, domain, alertType, alertLevel, createdAt, acknowledged, teamId, stormId, teamName]. */
    private static Object[] item(long id, String type, String level, Long team, boolean acked, String teamName) {
        return new Object[]{ id, "t" + id, type, level, "2026-09-30T13:4" + (id % 10) + ":00", acked, team, null, teamName };
    }

    /** {@code countOpenByTypeLevelAck} satırı: [alertType, alertLevel, acknowledged, count]. */
    private static Object[] grp(String type, String level, boolean acked, long n) {
        return new Object[]{ type, level, acked, n };
    }

    private OpenAlertsSummaryService svc() {
        return new OpenAlertsSummaryService(alertEventRepo);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Kapsamlı kullanıcı: sayılar sekmeye toplanır, KESİN seviye kırılımı + sahiplenilmemiş, en yeni 5 örnek, takım adı sorgudan")
    void scopedUser_summaryPerTab() {
        when(alertEventRepo.countOpenByTypeLevelAck(eq(true), eq(List.of(14L)))).thenReturn(List.of(
                grp("SCRIPTED_FAIL", "WARNING", false, 2), grp("SCRIPTED_FAIL", "CRITICAL", true, 1),
                grp("SCRIPTED_SLOW", "HIGH", false, 1),
                grp("HTTP_DOWN", "CRITICAL", false, 1), grp("HTTP_DOWN", "WARNING", false, 1),
                grp("ACCESSIBILITY", "WARNING", false, 1), grp("BOGUS", "CRITICAL", false, 9)));
        when(alertEventRepo.findOpenSummaryItems(anyCollection(), eq(true), eq(List.of(14L)), any(Pageable.class))).thenReturn(List.of(
                item(414, "SCRIPTED_FAIL", "WARNING", 14L, false, "SY-Kurumsal Mimari"), item(413, "SCRIPTED_FAIL", "CRITICAL", 14L, true, "SY-Kurumsal Mimari"),
                item(412, "SCRIPTED_FAIL", "WARNING", 14L, false, "SY-Kurumsal Mimari"), item(411, "SCRIPTED_SLOW", "HIGH", 14L, false, "SY-Kurumsal Mimari"),
                item(300, "HTTP_DOWN", "CRITICAL", 14L, false, "SY-Kurumsal Mimari"), item(299, "HTTP_DOWN", "WARNING", null, false, null),
                item(298, "ACCESSIBILITY", "WARNING", null, false, null)));

        Map<String, Object> out = svc().build(false, List.of(14L));

        assertThat(out.get("total")).isEqualTo(7L);   // BOGUS (bilinmeyen tür) sayılmaz
        assertThat(out.get("sampled")).isEqualTo(false);
        Map<String, Map<String, Object>> tabs = (Map<String, Map<String, Object>>) out.get("tabs");
        assertThat(tabs.keySet()).containsAll(List.of("http", "ping", "port", "dns", "domain", "keyword", "page", "pagespeed", "scripted"));
        Map<String, Object> scripted = tabs.get("scripted");
        assertThat(scripted.get("count")).isEqualTo(4L);
        assertThat(scripted.get("unacked")).isEqualTo(3L);
        Map<String, Long> levels = (Map<String, Long>) scripted.get("levels");
        assertThat(levels).containsEntry("critical", 1L).containsEntry("high", 1L).containsEntry("warning", 2L).containsEntry("other", 0L);
        List<Map<String, Object>> items = (List<Map<String, Object>>) scripted.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0)).containsEntry("id", 414L).containsEntry("team_name", "SY-Kurumsal Mimari").containsEntry("acknowledged", false)
                .containsEntry("domain", "t414").containsEntry("alert_type", "SCRIPTED_FAIL").containsEntry("alert_level", "WARNING")
                .containsEntry("team_id", 14L).containsEntry("storm_id", null).containsEntry("created_at", "2026-09-30T13:44:00");
        assertThat(items.get(0).keySet()).containsExactly("id", "domain", "alert_type", "alert_level", "created_at", "acknowledged",
                "team_id", "team_name", "storm_id");
        assertThat(items.get(1)).containsEntry("acknowledged", true);
        assertThat(tabs.get("http").get("count")).isEqualTo(3L);   // HTTP_DOWN + ACCESSIBILITY aynı sekme
        assertThat((List<Map<String, Object>>) tabs.get("http").get("items")).extracting(m -> m.get("team_name"))
                .containsExactly("SY-Kurumsal Mimari", null, null);
        assertThat(tabs.get("ping").get("count")).isEqualTo(0L);
        assertThat(((List<?>) tabs.get("ping").get("items"))).isEmpty();
        // Satır sorgusu yalnız katalogdaki (görülen) tipleri ister — bilinmeyen tip pencereyi işgal etmez
        verify(alertEventRepo).findOpenSummaryItems(argThat((Collection<String> c) -> !c.contains("BOGUS") && c.contains("SCRIPTED_FAIL")
                && c.contains("ACCESSIBILITY")), eq(true), eq(List.of(14L)), argThat((Pageable p) -> p.getPageSize() == OpenAlertsSummaryService.ITEMS_WINDOW));
    }

    @Test
    @DisplayName("Kapsamsız kullanıcı (görüş takımı yok): sorgu atılmaz, her sekme 0 — cached() da sorgusuz")
    void noScope_nothing() {
        OpenAlertsSummaryService svc = svc();
        Map<String, Object> out = svc.build(false, List.of());
        assertThat(out.get("total")).isEqualTo(0L);
        svc.cacheMs = 60_000;
        assertThat(svc.cached(false, null, false).get("total")).isEqualTo(0L);
        assertThat(svc.cached(false, List.of(), true).get("total")).isEqualTo(0L);
        verifyNoInteractions(alertEventRepo);
    }

    @Test
    @DisplayName("Global görüntüleyici: kapsam süzgeci kapalı (scoped=false); açık alarm yoksa satır sorgusu hiç atılmaz")
    void globalViewer_unscoped_noItemsWhenEmpty() {
        when(alertEventRepo.countOpenByTypeLevelAck(eq(false), anyList())).thenReturn(List.of());
        Map<String, Object> out = svc().build(true, null);
        assertThat(out.get("total")).isEqualTo(0L);
        verify(alertEventRepo, never()).findOpenSummaryItems(anyCollection(), anyBoolean(), anyList(), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Kesin kırılım: 500 açık alarmda da seviye/sahiplenilmemiş sayıları tam (örneklem yok, sampled=false)")
    void exactBreakdown_noSampling() {
        when(alertEventRepo.countOpenByTypeLevelAck(eq(false), anyList())).thenReturn(List.of(
                grp("PING_DOWN", "CRITICAL", false, 300), grp("PING_DOWN", "CRITICAL", true, 100),
                grp("PING_SLOW", "WARNING", false, 60), grp("PING_SLOW", null, true, 40)));
        when(alertEventRepo.findOpenSummaryItems(anyCollection(), eq(false), anyList(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(item(1, "PING_DOWN", "CRITICAL", 3L, false, "Ağ")));
        Map<String, Object> out = svc().build(true, null);
        assertThat(out.get("total")).isEqualTo(500L);
        assertThat(out.get("sampled")).isEqualTo(false);
        Map<String, Object> ping = ((Map<String, Map<String, Object>>) out.get("tabs")).get("ping");
        assertThat(ping).containsEntry("count", 500L).containsEntry("unacked", 360L);
        assertThat((Map<String, Long>) ping.get("levels")).containsEntry("critical", 400L).containsEntry("warning", 60L)
                .containsEntry("other", 40L).containsEntry("high", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Pencere başka sekmeyle dolduysa eksik sekme kendi tipleriyle tamamlanır (tek ek sorgu); pencere dolu değilse ek sorgu yok")
    void starvedTab_isFilledWithOwnTypes() {
        when(alertEventRepo.countOpenByTypeLevelAck(eq(false), anyList())).thenReturn(List.of(
                grp("SCRIPTED_FAIL", "WARNING", false, 200), grp("HTTP_DOWN", "CRITICAL", false, 3)));
        List<Object[]> window = new ArrayList<>();
        for (int i = 0; i < OpenAlertsSummaryService.ITEMS_WINDOW; i++) window.add(item(1000 - i, "SCRIPTED_FAIL", "WARNING", 1L, false, "A"));
        when(alertEventRepo.findOpenSummaryItems(argThat((Collection<String> c) -> c != null && c.size() == 2), eq(false), anyList(), any(Pageable.class)))
                .thenReturn(window);
        when(alertEventRepo.findOpenSummaryItems(argThat((Collection<String> c) -> c != null && c.size() == 1 && c.contains("HTTP_DOWN")), eq(false), anyList(), any(Pageable.class)))
                .thenReturn(List.of(item(5, "HTTP_DOWN", "CRITICAL", 2L, false, "B"), item(4, "HTTP_DOWN", "CRITICAL", 2L, false, "B"),
                        item(3, "HTTP_DOWN", "CRITICAL", 2L, false, "B")));

        Map<String, Object> out = svc().build(true, null);
        Map<String, Map<String, Object>> tabs = (Map<String, Map<String, Object>>) out.get("tabs");
        assertThat((List<Map<String, Object>>) tabs.get("scripted").get("items")).hasSize(OpenAlertsSummaryService.TOP)
                .extracting(m -> m.get("id")).containsExactly(1000L, 999L, 998L, 997L, 996L);
        assertThat((List<Map<String, Object>>) tabs.get("http").get("items")).extracting(m -> m.get("id")).containsExactly(5L, 4L, 3L);
        // 1 pencere + yalnız HTTP için 1 tamamlama (scripted zaten dolu)
        verify(alertEventRepo, times(2)).findOpenSummaryItems(anyCollection(), eq(false), anyList(), any(Pageable.class));
        verify(alertEventRepo).findOpenSummaryItems(argThat((Collection<String> c) -> c != null && c.size() == 1 && c.contains("HTTP_DOWN")),
                eq(false), anyList(), argThat((Pageable p) -> p.getPageSize() == OpenAlertsSummaryService.TOP));
    }

    @Test
    @DisplayName("Pencere dolmadıysa (tüm açık alarmlar okundu) tamamlama sorgusu atılmaz")
    void windowNotFull_noExtraQuery() {
        when(alertEventRepo.countOpenByTypeLevelAck(eq(false), anyList())).thenReturn(List.of(
                grp("SCRIPTED_FAIL", "WARNING", false, 2), grp("HTTP_DOWN", "CRITICAL", false, 1)));
        when(alertEventRepo.findOpenSummaryItems(anyCollection(), eq(false), anyList(), any(Pageable.class)))
                .thenReturn(List.of(item(2, "SCRIPTED_FAIL", "WARNING", 1L, false, "A"), item(1, "SCRIPTED_FAIL", "WARNING", 1L, false, "A")));
        svc().build(true, null);
        verify(alertEventRepo, times(1)).findOpenSummaryItems(anyCollection(), eq(false), anyList(), any(Pageable.class));
    }

    // ── Sunucu tarafı bellek (2026-10-01) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bellek: aynı kapsam cacheMs içinde tek hesaplama (sıra farkı aynı anahtar); fresh=true atlar ve tazeler; farklı kapsam ayrı")
    void cached_memoPerScope_freshBypass() {
        when(alertEventRepo.countOpenByTypeLevelAck(anyBoolean(), anyList())).thenReturn(List.<Object[]>of(grp("PING_DOWN", "HIGH", false, 1)));
        when(alertEventRepo.findOpenSummaryItems(anyCollection(), anyBoolean(), anyList(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(item(1, "PING_DOWN", "HIGH", 3L, false, "Ağ")));
        OpenAlertsSummaryService svc = svc();
        svc.cacheMs = 60_000;

        Map<String, Object> a = svc.cached(false, List.of(14L, 3L), false);
        Map<String, Object> b = svc.cached(false, List.of(3L, 14L), false);   // aynı küme, farklı sıra → aynı anahtar
        assertThat(b).isSameAs(a);
        verify(alertEventRepo, times(1)).countOpenByTypeLevelAck(anyBoolean(), anyList());

        Map<String, Object> c = svc.cached(false, List.of(14L, 3L), true);    // fresh → yeniden hesap
        assertThat(c).isNotSameAs(a);
        verify(alertEventRepo, times(2)).countOpenByTypeLevelAck(anyBoolean(), anyList());
        assertThat(svc.cached(false, List.of(3L, 14L), false)).isSameAs(c);   // taze sonuç belleğe yazıldı

        svc.cached(false, List.of(14L), false);                                // farklı kapsam
        svc.cached(true, List.of(14L), false);                                 // global → "ALL"
        svc.cached(true, null, false);                                         // yine "ALL" → bellekten
        verify(alertEventRepo, times(4)).countOpenByTypeLevelAck(anyBoolean(), anyList());
    }

    @Test
    @DisplayName("Bellek kapalıyken (cacheMs=0, birim testi varsayılanı) her çağrı hesaplar")
    void cached_disabledWhenZero() {
        when(alertEventRepo.countOpenByTypeLevelAck(anyBoolean(), anyList())).thenReturn(List.of());
        OpenAlertsSummaryService svc = svc();
        svc.cached(true, null, false);
        svc.cached(true, null, false);
        verify(alertEventRepo, times(2)).countOpenByTypeLevelAck(anyBoolean(), anyList());
    }
}
