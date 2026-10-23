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

    /** Canlılık bilgili STARTUP satırı (çok pod türetimi, 2026-10-23 MP-37e). */
    private static DeploymentHistory live(long id, String startedAt, String version, String pod, Integer helmRev,
                                          String lastSeenAt, String endedAt) {
        DeploymentHistory d = row(id, startedAt, version);
        d.setPodName(pod);
        d.setHostname(pod);
        d.setHelmRevision(helmRev);
        d.setLastSeenAt(lastSeenAt);
        d.setEndedAt(endedAt);
        return d;
    }

    @Test
    @DisplayName("MP-37e: eski ReplicaSet'in geç kalan pod'u (daha yüksek revizyonlu canlı pod var) RESTART — sahte ROLLBACK yok, "
            + "sürüm ilerlemez; ardından açılan yeni pod ek replika")
    void stragglerOfOlderRevisionIsNotARollback() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                live(1, "2026-10-23T10:00:00Z", "1.0.0", "pod-a", 7, "2026-10-23T10:20:30Z", "2026-10-23T10:20:30Z"),
                live(2, "2026-10-23T10:20:00Z", "1.1.0", "pod-c", 8, "2026-10-23T11:00:00Z", null),
                live(3, "2026-10-23T10:20:40Z", "1.0.0", "pod-x", 7, "2026-10-23T11:00:00Z", null),
                live(4, "2026-10-23T10:21:00Z", "1.1.0", "pod-d", 8, "2026-10-23T11:00:00Z", null)));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.UPGRADE, Kind.RESTART, Kind.RESTART);
        assertThat(out.get(2).countedRestart()).as("geç kalan pod yeniden başlatma sayılmaz").isFalse();
        assertThat(out.get(3).replica()).isTrue();
        assertThat(out.get(2).previousVersion()).as("ortamın sürümü 1.1.0 kaldı").isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("MP-37e: pod adı olmayan yerel kurulum (sabit makine adı) — gerçek geri alma ROLLBACK kalır; aynı sürümle "
            + "yeniden açılış sayılan RESTART")
    void sameHostnameAcrossVersions_realTransitionsKept() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                live(1, "2026-10-01T10:00:00Z", "1.0.0", "laptop", null, "2026-10-01T18:00:00Z", "2026-10-01T18:00:00Z"),
                live(2, "2026-10-02T10:00:00Z", "1.1.0", "laptop", null, "2026-10-02T18:00:00Z", "2026-10-02T18:00:00Z"),
                live(3, "2026-10-03T10:00:00Z", "1.0.0", "laptop", null, "2026-10-03T18:00:00Z", "2026-10-03T18:00:00Z"),
                live(4, "2026-10-04T10:00:00Z", "1.0.0", "laptop", null, "2026-10-04T18:00:00Z", null)));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.UPGRADE, Kind.ROLLBACK, Kind.RESTART);
        assertThat(out.get(3).countedRestart()).isTrue();
    }

    @Test
    @DisplayName("MP-37e: 2 replikalı ilk kurulum + dağıtım — yeni sürümün ikinci pod'u ek replika (sayılmaz); tek pod'un "
            + "kapanıp yenisinin açılması (canlı aynı sürüm yok) sayılır")
    void replicaVersusRestart() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                live(1, "2026-10-23T09:00:00Z", "1.0.0", "pod-a", 7, "2026-10-23T10:05:00Z", "2026-10-23T10:05:00Z"),
                live(2, "2026-10-23T09:00:20Z", "1.0.0", "pod-b", 7, "2026-10-23T10:06:00Z", "2026-10-23T10:06:00Z"),
                live(3, "2026-10-23T10:04:00Z", "1.1.0", "pod-c", 8, "2026-10-23T12:00:00Z", "2026-10-23T12:00:00Z"),
                live(4, "2026-10-23T10:05:30Z", "1.1.0", "pod-d", 8, "2026-10-23T12:00:00Z", "2026-10-23T12:00:00Z"),
                live(5, "2026-10-23T12:30:00Z", "1.1.0", "pod-e", 8, "2026-10-23T13:00:00Z", null)));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.RESTART, Kind.UPGRADE, Kind.RESTART, Kind.RESTART);
        assertThat(out.get(1).countedRestart()).as("ilk kurulumun ikinci replikası").isFalse();
        assertThat(out.get(3).countedRestart()).as("dağıtımın ikinci yeni pod'u").isFalse();
        assertThat(out.get(4).countedRestart()).as("hepsi kapandıktan sonra açılan pod — yeniden başlatma").isTrue();
    }

    @Test
    @DisplayName("inceleme W-1: v2 hazır olamadı, eski v1 pod'u canlıyken yeni Helm revizyonuyla v1 açıldı → ROLLBACK (ek replika "
            + "değil); geri almanın ikinci pod'u ek replika; Helm'siz kurulumda eski kural (geçiş)")
    void rollbackWhileOldVersionPodAlive() {
        List<Derived> out = DeploymentHistoryService.deriveKinds(List.of(
                live(1, "2026-10-23T09:00:00Z", "1.0.0", "pod-p", 5, "2026-10-23T11:00:00Z", null),
                live(2, "2026-10-23T10:00:00Z", "1.1.0", "pod-q", 6, "2026-10-23T10:20:00Z", "2026-10-23T10:20:00Z"),
                live(3, "2026-10-23T10:10:00Z", "1.0.0", "pod-r", 7, "2026-10-23T11:00:00Z", null),
                live(4, "2026-10-23T10:11:00Z", "1.0.0", "pod-s", 7, "2026-10-23T11:00:00Z", null)));
        assertThat(kinds(out)).containsExactly(Kind.FIRST_SEEN, Kind.UPGRADE, Kind.ROLLBACK, Kind.RESTART);
        assertThat(out.get(2).previousVersion()).isEqualTo("1.1.0");
        assertThat(out.get(3).replica()).isTrue();

        List<Derived> noHelm = DeploymentHistoryService.deriveKinds(List.of(
                live(1, "2026-10-23T09:00:00Z", "1.0.0", "pod-p", null, "2026-10-23T11:00:00Z", null),
                live(2, "2026-10-23T10:00:00Z", "1.1.0", "pod-q", null, "2026-10-23T11:00:00Z", null),
                live(3, "2026-10-23T10:10:00Z", "1.0.0", "pod-r", null, "2026-10-23T11:00:00Z", null)));
        assertThat(kinds(noHelm)).containsExactly(Kind.FIRST_SEEN, Kind.UPGRADE, Kind.ROLLBACK);
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
