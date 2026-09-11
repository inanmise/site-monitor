package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.repository.DeploymentHistoryRepository;
import com.sitemonitor.service.DeploymentHistoryService.Current;
import com.sitemonitor.service.DeploymentHistoryService.Derived;
import com.sitemonitor.service.DeploymentHistoryService.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Geçiş türetimi ({@code deriveKinds}) — SAF fonksiyon: ortam bazında {@code started_at,id} artan
 * sıralı satırlardan yükseltme / geri alma / yeniden başlatma türetilir. Tür DB'de saklanmaz;
 * sonradan eklenen BACKFILL/MANUAL satırı türü bayatlatamaz. Sürümsüz satır (BACKFILL) UNKNOWN'dır
 * ve öncekini İLERLETMEZ — "tahmin yok" ilkesi.
 */
class DeploymentTransitionTest {

    private static DeploymentHistory row(long id, String startedAt, String version) {
        DeploymentHistory d = new DeploymentHistory();
        d.setId(id);
        d.setStartedAt(startedAt);
        d.setRecordedAt(startedAt);
        d.setEnvironment("prod");
        d.setVersion(version);
        d.setSource(version == null ? DeploymentHistory.SOURCE_BACKFILL : DeploymentHistory.SOURCE_STARTUP);
        return d;
    }

    private static List<Kind> kinds(List<Derived> d) {
        return d.stream().map(Derived::kind).toList();
    }

    @Test
    @DisplayName("ilk satır FIRST_SEEN, aynı sürüm RESTART, büyük sürüm UPGRADE, küçük sürüm ROLLBACK")
    void basicTransitions() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-02T10:00:00Z", "1.0.0"),
                row(3, "2026-01-03T10:00:00Z", "1.1.0"),
                row(4, "2026-01-04T10:00:00Z", "1.0.0")));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.RESTART, Kind.UPGRADE, Kind.ROLLBACK);
        assertThat(out.get(0).previousVersion()).isNull();
        assertThat(out.get(2).previousVersion()).isEqualTo("1.0.0");
        assertThat(out.get(3).previousVersion()).isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("ayrıştırılamayan sürüme/sürümden geçiş CHANGED (yön bilinmiyor)")
    void unparseableIsChanged() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-02T10:00:00Z", "unknown"),
                row(3, "2026-01-03T10:00:00Z", "1.2.0")));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.CHANGED, Kind.CHANGED);
        assertThat(out.get(2).previousVersion()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("sürümsüz (BACKFILL) satır UNKNOWN ve öncekini İLERLETMEZ — sonraki geçiş yine gerçek öncekine bakar")
    void nullVersionIsUnknownAndDoesNotAdvancePrev() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-02T10:00:00Z", null),
                row(3, "2026-01-03T10:00:00Z", "  "),
                row(4, "2026-01-04T10:00:00Z", "1.0.0"),
                row(5, "2026-01-05T10:00:00Z", "1.1.0")));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.UNKNOWN, Kind.UNKNOWN, Kind.RESTART, Kind.UPGRADE);
        assertThat(out.get(1).previousVersion()).isEqualTo("1.0.0");
        assertThat(out.get(4).previousVersion()).isEqualTo("1.0.0");

        // Yalnız BACKFILL satırları: hiçbiri "ilk görülen" sayılmaz
        List<Derived> onlyUnknown = DeploymentHistoryService.deriveKinds(List.of(
                row(1, "2026-01-01T10:00:00Z", null), row(2, "2026-01-02T10:00:00Z", null)));
        assertThat(kinds(onlyUnknown)).containsExactly(Kind.UNKNOWN, Kind.UNKNOWN);
        assertThat(onlyUnknown.get(1).previousVersion()).isNull();
    }

    @Test
    @DisplayName("aynı sürümde birden çok pod (rolling) → ilk FIRST_SEEN/UPGRADE, diğerleri RESTART")
    void multiPodSameVersion() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-01T10:00:00Z", "1.0.0"),   // aynı saniye — id sırası
                row(3, "2026-01-01T10:00:05Z", "1.0.0"),
                row(4, "2026-01-02T10:00:00Z", "1.1.0"),
                row(5, "2026-01-02T10:00:00Z", "1.1.0")));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.RESTART, Kind.RESTART, Kind.UPGRADE, Kind.RESTART);
    }

    @Test
    @DisplayName("boş liste → boş çıktı")
    void emptyInput() {
        assertThat(DeploymentHistoryService.deriveKinds(List.of())).isEmpty();
    }

    // ── current(): koşan sürüm ve canlıya geçiş anı ──────────────────────────────────

    private static DeploymentHistoryService serviceWith(List<DeploymentHistory> rows) {
        DeploymentHistoryRepository repo = mock(DeploymentHistoryRepository.class);
        when(repo.findByEnvironmentOrderByStartedAtAscIdAsc("prod")).thenReturn(rows);
        return new DeploymentHistoryService(repo, mock(BuildInfo.class), mock(ReleaseIndexService.class),
                mock(JdbcTemplate.class), mock(ApplicationEventPublisher.class), new MockEnvironment());
    }

    @Test
    @DisplayName("current: liveSince = koşan sürümün KOŞUSUNDAKİ ilk geçiş satırı; restartsSince o koşudaki RESTART sayısı")
    void current_liveSinceIsFirstRowOfCurrentRun() {
        DeploymentHistoryService svc = serviceWith(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-02T10:00:00Z", "1.1.0"),   // ← 1.1.0 canlıya geçti
                row(3, "2026-01-03T10:00:00Z", null),      // backfill gürültüsü — sayılmaz
                row(4, "2026-01-04T10:00:00Z", "1.1.0"),   // restart
                row(5, "2026-01-05T10:00:00Z", "1.1.0")));  // restart
        Current c = svc.current("prod");
        assertThat(c.version()).isEqualTo("1.1.0");
        assertThat(c.liveSince()).isEqualTo("2026-01-02T10:00:00Z");
        assertThat(c.liveKind()).isEqualTo(Kind.UPGRADE);
        assertThat(c.previousVersion()).isEqualTo("1.0.0");
        assertThat(c.restartsSince()).isEqualTo(2);
        assertThat(c.lastStartedAt()).isEqualTo("2026-01-05T10:00:00Z");
        assertThat(c.rowId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("current: aynı sürüme geri dönüş (1.0→1.1→1.0) ikinci koşuyu başlatır — liveSince eski koşuya uzanmaz")
    void current_returnToOldVersionStartsNewRun() {
        DeploymentHistoryService svc = serviceWith(List.of(
                row(1, "2026-01-01T10:00:00Z", "1.0.0"),
                row(2, "2026-01-02T10:00:00Z", "1.1.0"),
                row(3, "2026-01-03T10:00:00Z", "1.0.0"),
                row(4, "2026-01-04T10:00:00Z", "1.0.0")));
        Current c = svc.current("prod");
        assertThat(c.version()).isEqualTo("1.0.0");
        assertThat(c.liveSince()).isEqualTo("2026-01-03T10:00:00Z");
        assertThat(c.liveKind()).isEqualTo(Kind.ROLLBACK);
        assertThat(c.restartsSince()).isEqualTo(1);
    }

    @Test
    @DisplayName("current: hiç kayıt yok ya da yalnız sürümsüz satır → boş Current (null sürüm, 0 restart)")
    void current_emptyOrUnknownOnly() {
        assertThat(serviceWith(List.of()).current("prod").version()).isNull();
        Current c = serviceWith(List.of(row(1, "2026-01-01T10:00:00Z", null))).current("prod");
        assertThat(c.version()).isNull();
        assertThat(c.liveSince()).isNull();
        assertThat(c.restartsSince()).isZero();
    }
}
