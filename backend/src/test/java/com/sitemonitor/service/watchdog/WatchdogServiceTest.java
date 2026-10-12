package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.WatchdogState;
import com.sitemonitor.repository.WatchdogStateRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.mail.WatchdogMail;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kendi kendini izleme servisi (2026-10-11): onaylı geçişler bir turda TEK bildirimde birleşir, durum yazılır, DB
 * yazılamazsa bellek; dış uç genel durumu (kritik / değerlendirme durmuş → DOWN).
 */
class WatchdogServiceTest {

    static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");

    WatchdogStateRepository repo;
    WatchdogProbes probes;
    WatchdogNotifier notifier;
    AppSettingsService settings;
    WatchdogService svc;
    final Map<String, WatchdogState> table = new LinkedHashMap<>();
    final Map<WatchdogCheck, Result> results = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(WatchdogStateRepository.class);
        probes = mock(WatchdogProbes.class);
        notifier = mock(WatchdogNotifier.class);
        settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(table.values()));
        when(repo.saveAll(any())).thenAnswer(i -> {
            for (WatchdogState s : (Collection<WatchdogState>) i.getArgument(0)) table.put(s.getCheckKey(), s);
            return null;
        });
        for (WatchdogCheck c : WatchdogCheck.values()) results.put(c, Result.OK);
        when(probes.runAll(any())).thenAnswer(i -> {
            List<WatchdogProbes.Probe> out = new ArrayList<>();
            results.forEach((c, r) -> out.add(new WatchdogProbes.Probe(c, r, c == WatchdogCheck.DATABASE && r == Result.PROBLEM
                    ? "CRITICAL" : c.severity().name(), Map.of("status", "DOWN"))));
            return out;
        });
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.DELIVERED, "SENT", "SKIPPED", "SKIPPED", 1));
        svc = new WatchdogService(repo, probes, notifier, settings);
    }

    @Test
    @DisplayName("iki sorunlu tur → iki kontrol TEK bildirimde; sağlıklı turlar bildirim üretmez; durum kalıcı")
    @SuppressWarnings("unchecked")
    void combinedNotification() {
        svc.evaluate(T0);
        verify(notifier, never()).send(anyList(), anyBoolean());
        results.put(WatchdogCheck.CERT_SWEEP, Result.PROBLEM);
        results.put(WatchdogCheck.NOTIFICATIONS, Result.PROBLEM);
        svc.evaluate(T0.plusSeconds(60));
        verify(notifier, never()).send(anyList(), anyBoolean());
        WatchdogService.Round r = svc.evaluate(T0.plusSeconds(120));
        assertThat(r.alerts()).isEqualTo(2);
        ArgumentCaptor<List<WatchdogMail.Item>> items = ArgumentCaptor.forClass(List.class);
        verify(notifier, times(1)).send(items.capture(), eq(false));
        assertThat(items.getValue()).extracting(WatchdogMail.Item::label).containsExactly("Sertifika taraması", "Bildirim gönderimi");
        assertThat(table.get("cert_sweep").getStatus()).isEqualTo("PROBLEM");
        assertThat(table.get("cert_sweep").getNotifiedKind()).isEqualTo("ALERT");
        // Sürüyor: yeni bildirim yok
        svc.evaluate(T0.plusSeconds(180));
        verify(notifier, times(1)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("genel durum: orta sorun → DEGRADED, kritik → DOWN; değerlendirme durduysa DOWN; kapalı → DISABLED")
    void overall() {
        results.put(WatchdogCheck.PUSH_OUTBOX, Result.PROBLEM);
        svc.evaluate(T0);
        svc.evaluate(T0.plusSeconds(60));
        assertThat(svc.publicStatus(T0.plusSeconds(61))).containsEntry("status", "DEGRADED");
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0.plusSeconds(120));
        svc.evaluate(T0.plusSeconds(180));
        Map<String, Object> pub = svc.publicStatus(T0.plusSeconds(181));
        assertThat(pub).containsEntry("status", "DOWN").doesNotContainKey("reason");
        assertThat(pub.toString()).doesNotContain("detail").doesNotContain("facts");
        // Değerlendirme 10 dk koşmadı → DOWN + gerekçe
        results.replaceAll((c, r) -> Result.OK);
        svc.evaluate(T0.plusSeconds(240));
        svc.evaluate(T0.plusSeconds(300));
        assertThat(svc.publicStatus(T0.plusSeconds(301))).containsEntry("status", "UP");
        assertThat(svc.publicStatus(T0.plus(Duration.ofMinutes(20)))).containsEntry("status", "DOWN").containsEntry("reason", "evaluation_stale");
        when(settings.getBoolean(eq(WatchdogSettings.KEY_ENABLED), anyBoolean())).thenReturn(false);
        assertThat(svc.publicStatus(T0.plusSeconds(301))).containsEntry("status", "DISABLED");
    }

    @Test
    @DisplayName("durum tablosu okunamazsa bellek; bildirim yine gider; sonraki turda bellekten devam")
    void databaseFailureFallsBackToMemory() {
        when(repo.findAll()).thenThrow(new RuntimeException("DB yok"));
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        assertThat(svc.evaluate(T0).local()).isTrue();
        WatchdogService.Round r = svc.evaluate(T0.plusSeconds(60));
        assertThat(r.alerts()).isEqualTo(1);
        verify(notifier).send(anyList(), eq(false));
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("2026-10-12: DB kesintisinde bellekte açılan veritabanı sorunu, DB dönünce eski satırı ezer → düzelme bildirilir")
    @SuppressWarnings("unchecked")
    void databaseRecoveryIsNotified() {
        WatchdogState stale = new WatchdogState();
        stale.setCheckKey(WatchdogCheck.DATABASE.key());
        stale.setStatus("OK");
        stale.setEvaluatedAt("2026-10-11T11:00:00");
        table.put(stale.getCheckKey(), stale);
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        assertThat(svc.evaluate(T0.plusSeconds(60)).alerts()).isEqualTo(1);
        down.set(false);
        results.put(WatchdogCheck.DATABASE, Result.OK);
        svc.evaluate(T0.plusSeconds(120));
        WatchdogService.Round r = svc.evaluate(T0.plusSeconds(180));
        assertThat(r.recoveries()).isEqualTo(1);
        ArgumentCaptor<List<WatchdogMail.Item>> items = ArgumentCaptor.forClass(List.class);
        verify(notifier, times(2)).send(items.capture(), eq(false));
        assertThat(items.getAllValues().get(1)).extracting(WatchdogMail.Item::kind).containsExactly("RECOVER");
        assertThat(table.get(WatchdogCheck.DATABASE.key()).getStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("2026-10-12: RECENT ile atlayan pod, kayıtlı satırdan eski bellek durumunu siler (duyurduğu sorun için 'düzeldi' gönderir) — sonraki DB kesintisinde bayat 'sorun' yeniden kullanılmaz")
    @SuppressWarnings("unchecked")
    void staleMemoryPrunedOnRecentSkip() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        when(probes.run(eq(WatchdogCheck.DATABASE), any())).thenAnswer(i -> new WatchdogProbes.Probe(WatchdogCheck.DATABASE,
                results.get(WatchdogCheck.DATABASE), "CRITICAL", Map.of("status", "DOWN")));
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        assertThat(svc.evaluate(T0.plusSeconds(60)).alerts()).isEqualTo(1);   // bu pod kesintide uyardı (bellek)
        // DB döndü; BAŞKA pod hemen değerlendirip yazdı → bu pod'un turu RECENT ile atlanır
        down.set(false);
        WatchdogState other = new WatchdogState();
        other.setCheckKey(WatchdogCheck.DATABASE.key());
        other.setStatus("OK");
        other.setLastResult("OK");
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(150)));
        table.put(other.getCheckKey(), other);
        assertThat(svc.evaluate(T0.plusSeconds(160), false).outcome()).isEqualTo("RECENT");
        // Bu pod'un duyurduğu "veritabanı çöktü" budanırken "düzeldi" bildirimi gider (eskiden kayboluyordu)
        ArgumentCaptor<List<WatchdogMail.Item>> items = ArgumentCaptor.forClass(List.class);
        verify(notifier, times(2)).send(items.capture(), eq(false));
        assertThat(items.getAllValues().get(1)).extracting(WatchdogMail.Item::kind).containsExactly("RECOVER");
        // Yeni kısa kesinti: tek sorunlu yerel ölçüm onay beklemeden bildirim üretmemeli (bellek temizlendi)
        down.set(true);
        svc.evaluateLocalDatabase(T0.plusSeconds(400));
        verify(notifier, times(2)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("2026-10-12: budanan bellek sorunu kayıtlı satırda da SORUN ise ayrı 'düzeldi' gönderilmez (kayıtlı durum kendisi kapatır)")
    void prunedProblemStillOpenIsNotRecovered() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        svc.evaluate(T0.plusSeconds(60));
        down.set(false);
        WatchdogState other = new WatchdogState();
        other.setCheckKey(WatchdogCheck.DATABASE.key());
        other.setStatus("PROBLEM");
        other.setNotifiedKind("ALERT");
        other.setNotifiedAt(WatchdogTransition.ISO.format(T0.plusSeconds(140)));
        other.setSince(WatchdogTransition.ISO.format(T0.plusSeconds(100)));
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(150)));
        table.put(other.getCheckKey(), other);
        assertThat(svc.evaluate(T0.plusSeconds(160), false).outcome()).isEqualTo("RECENT");
        verify(notifier, times(1)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("inceleme 5: kayıtlı satır OK ama son ölçümü PROBLEM / UNKNOWN ise 'düzeldi' gönderilmez; OK / NA ise gönderilir")
    void storedHealthyNeedsHealthyLastResult() {
        WatchdogState s = new WatchdogState();
        s.setStatus("OK");
        s.setLastResult("PROBLEM");
        assertThat(WatchdogService.storedHealthy(s)).isFalse();
        s.setLastResult("UNKNOWN");
        assertThat(WatchdogService.storedHealthy(s)).isFalse();
        s.setLastResult(null);
        assertThat(WatchdogService.storedHealthy(s)).isFalse();
        s.setLastResult("NA");
        assertThat(WatchdogService.storedHealthy(s)).isTrue();
        s.setLastResult("OK");
        assertThat(WatchdogService.storedHealthy(s)).isTrue();
        s.setStatus("PROBLEM");
        assertThat(WatchdogService.storedHealthy(s)).isFalse();
    }

    @Test
    @DisplayName("inceleme 6: kesintide duyurulan sorun, kayıtlı satır 'OK ama son ölçüm doğrulanmamış hata' iken budansa da "
            + "KAYBOLMAZ — satır sağlıklı olunca TEK 'düzeldi' gider; sonraki turlarda tekrar gitmez")
    @SuppressWarnings("unchecked")
    void pendingRecoverySurvivesPruning() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        when(probes.run(eq(WatchdogCheck.DATABASE), any())).thenAnswer(i -> new WatchdogProbes.Probe(WatchdogCheck.DATABASE,
                results.get(WatchdogCheck.DATABASE), "CRITICAL", Map.of("status", "DOWN")));
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        assertThat(svc.evaluate(T0.plusSeconds(60)).alerts()).isEqualTo(1);   // kesintide duyuruldu (bellek)
        down.set(false);
        // Öbür pod DB dönünce ilk turunda doğrulanmamış bir hata gördü: durum OK, son ölçüm PROBLEM
        WatchdogState other = new WatchdogState();
        other.setCheckKey(WatchdogCheck.DATABASE.key());
        other.setStatus("OK");
        other.setLastResult("PROBLEM");
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(150)));
        table.put(other.getCheckKey(), other);
        assertThat(svc.evaluate(T0.plusSeconds(160), false).outcome()).isEqualTo("RECENT");
        verify(notifier, times(1)).send(anyList(), anyBoolean());             // henüz "düzeldi" yok (dalgalanma olmasın)
        // Öbür pod'un sonraki turu sağlıklı: bekleyen düzelme şimdi duyurulur
        other.setLastResult("OK");
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(220)));
        assertThat(svc.evaluate(T0.plusSeconds(230), false).outcome()).isEqualTo("RECENT");
        ArgumentCaptor<List<WatchdogMail.Item>> items = ArgumentCaptor.forClass(List.class);
        verify(notifier, times(2)).send(items.capture(), eq(false));
        assertThat(items.getAllValues().get(1)).extracting(WatchdogMail.Item::kind).containsExactly("RECOVER");
        // tek kez: sonraki turlar ikinci "düzeldi" göndermez
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(290)));
        svc.evaluate(T0.plusSeconds(300), false);
        verify(notifier, times(2)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("inceleme 6: bekleyen düzelme, kayıtlı satır SORUNA dönerse düşer (sorunu artık o satır duyurur ve kapatır)")
    void pendingRecoveryDroppedWhenStoredBecomesProblem() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        svc.evaluate(T0.plusSeconds(60));
        down.set(false);
        WatchdogState other = new WatchdogState();
        other.setCheckKey(WatchdogCheck.DATABASE.key());
        other.setStatus("OK");
        other.setLastResult("UNKNOWN");
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(150)));
        table.put(other.getCheckKey(), other);
        svc.evaluate(T0.plusSeconds(160), false);
        other.setStatus("PROBLEM");                                           // öbür pod sorunu doğruladı
        other.setLastResult("PROBLEM");
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(220)));
        svc.evaluate(T0.plusSeconds(230), false);
        other.setStatus("OK");                                                // öbür pod kendisi kapattı (kendi RECOVER'ı)
        other.setLastResult("OK");
        other.setNotifiedKind("RECOVER");
        other.setNotifiedAt(WatchdogTransition.ISO.format(T0.plusSeconds(280)));
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(280)));
        svc.evaluate(T0.plusSeconds(290), false);
        verify(notifier, times(1)).send(anyList(), anyBoolean());             // yalnız kesintideki açılış; ikinci "düzeldi" yok
    }

    @Test
    @DisplayName("inceleme 4: kayıtlı satır durumsuzsa (sağlıklı olduğu bilinmiyor) kesintide duyurulan sorun için 'düzeldi' GÖNDERİLMEZ")
    void orphanRecoverNeedsStoredOk() {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(repo.findAll()).thenAnswer(i -> {
            if (down.get()) throw new RuntimeException("DB yok");
            return new ArrayList<>(table.values());
        });
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        svc.evaluate(T0);
        svc.evaluate(T0.plusSeconds(60));
        verify(notifier, times(1)).send(anyList(), anyBoolean());       // kesintide açılış duyuruldu
        down.set(false);
        WatchdogState other = new WatchdogState();
        other.setCheckKey(WatchdogCheck.DATABASE.key());
        other.setStatus(null);                                            // durum bilinmiyor
        other.setEvaluatedAt(WatchdogTransition.ISO.format(T0.plusSeconds(150)));
        table.put(other.getCheckKey(), other);
        assertThat(svc.evaluate(T0.plusSeconds(160), false).outcome()).isEqualTo("RECENT");
        verify(notifier, times(1)).send(anyList(), anyBoolean());       // ikinci (RECOVER) gönderim YOK
    }

    @Test
    @DisplayName("2026-10-12: sistem bakımı susturken açılan sorun ALERT_MUTED; susturma sürerken yinelenmez, bitince sorun sürüyorsa BİR KEZ bildirilir")
    void mutedProblemIsSentAfterMaintenance() {
        when(notifier.muted()).thenReturn(true);
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.MUTED, "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", 1));
        results.put(WatchdogCheck.CERT_SWEEP, Result.PROBLEM);
        svc.evaluate(T0);
        assertThat(svc.evaluate(T0.plusSeconds(60)).alerts()).isEqualTo(1);
        assertThat(table.get("cert_sweep").getNotifiedKind()).isEqualTo("ALERT_MUTED");
        svc.evaluate(T0.plusSeconds(1200));                                   // susturma sürüyor → yeniden deneme yok
        verify(notifier, times(1)).send(anyList(), anyBoolean());

        when(notifier.muted()).thenReturn(false);
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.DELIVERED, "SENT", "SKIPPED", "SKIPPED", 1));
        assertThat(svc.evaluate(T0.plusSeconds(1300)).alerts()).isEqualTo(1);
        assertThat(table.get("cert_sweep").getNotifiedKind()).isEqualTo("ALERT");
        svc.evaluate(T0.plusSeconds(1400));
        verify(notifier, times(2)).send(anyList(), anyBoolean());

        // Kanal yok → "gitti" denmez (ALERT_NOCHANNEL), yeniden denenmez
        results.put(WatchdogCheck.NOTIFICATIONS, Result.PROBLEM);
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.NO_CHANNEL, "SKIPPED: alıcı yok", "SKIPPED", "SKIPPED", 0));
        svc.evaluate(T0.plusSeconds(1500));
        svc.evaluate(T0.plusSeconds(1600));
        assertThat(table.get("notifications").getNotifiedKind()).isEqualTo("ALERT_NOCHANNEL");
        svc.evaluate(T0.plusSeconds(2700));
        verify(notifier, times(3)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("2026-10-12: zamanlanmış tur, başka pod son aralıkta değerlendirdiyse atlanır (RECENT); elle değerlendirme atlanmaz")
    void scheduledRoundSkipsRecentEvaluation() {
        svc.evaluate(T0);
        assertThat(svc.evaluate(T0.plusSeconds(30), false).outcome()).isEqualTo("RECENT");
        assertThat(svc.evaluate(T0.plusSeconds(30), true).outcome()).isNotEqualTo("RECENT");
        assertThat(svc.evaluate(T0.plusSeconds(100), false).outcome()).isNotEqualTo("RECENT");
    }

    @Test
    @DisplayName("2026-10-12: kapalıyken elle değerlendirme hiç koşmaz (bildirim gönderemez)")
    void manualEvaluationWhenDisabled() {
        when(settings.getBoolean(eq(WatchdogSettings.KEY_ENABLED), anyBoolean())).thenReturn(false);
        assertThat(svc.enabled()).isFalse();
        assertThat(svc.evaluateManually(T0)).isEmpty();
        verify(probes, never()).runAll(any());
        when(settings.getBoolean(eq(WatchdogSettings.KEY_ENABLED), anyBoolean())).thenReturn(true);
        assertThat(svc.evaluateManually(T0)).isPresent();
    }

    @Test
    @DisplayName("yönetici durumu: ölçülmemiş kontrol PENDING; ölçülende ayrıntı cümlesi + kanal özeti")
    void adminStatus() {
        Map<String, Object> before = svc.adminStatus(T0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) before.get("checks");
        assertThat(checks).hasSize(WatchdogCheck.values().length).allSatisfy(c -> assertThat(c).containsEntry("status", "PENDING"));
        svc.evaluate(T0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) svc.adminStatus(T0.plusSeconds(1)).get("checks");
        Map<String, Object> dbRow = after.stream().filter(c -> "database".equals(c.get("key"))).findFirst().orElseThrow();
        assertThat(dbRow).containsEntry("status", "OK").containsEntry("detail", "Veritabanı durumu: DOWN.");
        assertThat(svc.adminStatus(T0)).containsKey("channels");
    }

    @Test
    @DisplayName("bildirim hiçbir kanaldan gidemezse ALERT_FAILED; kanal yoksa / susturulmuşsa yeniden denenmez")
    void failedDeliveryMarked() {
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.FAILED, "FAILED: smtp", "SKIPPED", "SKIPPED", 1));
        results.put(WatchdogCheck.CERT_SWEEP, Result.PROBLEM);
        svc.evaluate(T0);
        svc.evaluate(T0.plusSeconds(60));
        assertThat(table.get("cert_sweep").getNotifiedKind()).isEqualTo("ALERT_FAILED");
        when(notifier.send(anyList(), anyBoolean())).thenReturn(
                new WatchdogNotifier.Report(WatchdogNotifier.Outcome.NO_CHANNEL, "SKIPPED: alıcı yok", "SKIPPED", "SKIPPED", 0));
        svc.evaluate(T0.plus(Duration.ofMinutes(20)));
        // 2026-10-12: kanal yok → yeniden denenmez ama "gitti" de denmez
        assertThat(table.get("cert_sweep").getNotifiedKind()).isEqualTo("ALERT_NOCHANNEL");
        svc.evaluate(T0.plus(Duration.ofMinutes(40)));
        verify(notifier, times(2)).send(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("2026-10-12: başka pod düzelmeyi ZATEN duyurduysa (kayıtlı satır RECOVER) budanan bellek sorunu ikinci 'düzeldi' göndermez")
    void noSecondRecoverWhenAlreadyAnnounced() {
        WatchdogState mem = new WatchdogState();
        mem.setCheckKey("database");
        mem.setStatus("PROBLEM");
        mem.setNotifiedKind("ALERT");
        mem.setNotifiedAt("2026-10-11T12:01:00");
        WatchdogState stored = new WatchdogState();
        stored.setCheckKey("database");
        stored.setStatus("OK");
        stored.setNotifiedKind("RECOVER");
        stored.setNotifiedAt("2026-10-11T12:05:00");
        assertThat(WatchdogService.recoveryAnnounced(stored, mem)).isTrue();
        stored.setNotifiedKind("ALERT");
        stored.setNotifiedAt("2026-10-11T11:00:00");
        assertThat(WatchdogService.recoveryAnnounced(stored, mem)).isFalse();
        // Teslim edilemeyen / susturulan hatırlatma da "duyurulmuş" sayılır; kanalsız hatırlatma sayılmaz
        mem.setNotifiedKind("REMIND_FAILED");
        assertThat(WatchdogService.announcedProblem(mem)).isTrue();
        mem.setNotifiedKind("REMIND_MUTED");
        assertThat(WatchdogService.announcedProblem(mem)).isTrue();
        mem.setNotifiedKind("REMIND_NOCHANNEL");
        assertThat(WatchdogService.announcedProblem(mem)).isFalse();
        mem.setNotifiedKind("ALERT_FAILED");
        assertThat(WatchdogService.announcedProblem(mem)).isFalse();
    }
}
