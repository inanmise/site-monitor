package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.MaintenanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 7/24 teslim satırının YENİDEN sahiplenmesi iki pod'da (2026-10-21, çok pod — N-10), GERÇEK sorgular (H2, işlem dışı).
 * Var olan FAILED satırını iki pod aynı anda yeniden dener: ikisi de satırı "engellemiyor" diye okur, yalnız biri
 * koşullu UPDATE'i kazanır → 7/24 postası TEK kez. Eskiden okunan varlık koşulsuz kaydediliyordu (iki posta).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:nocreclaim;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NocReclaimRaceTest {

    private static final String KEY = "alert:62:OPEN";

    @Autowired NocDeliveryRepository deliveries;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM noc_deliveries");
    }

    private NocDelivery failedRow(String updatedAt) {
        NocDelivery d = new NocDelivery();
        d.setDedupeKey(KEY);
        d.setAlertEventId(62L);
        d.setPhase(NocDelivery.OPEN);
        d.setStatus("FAILED: Connection refused");
        d.setAttempts(1);
        d.setCreatedAt("2026-01-10T20:55:00");
        d.setUpdatedAt(updatedAt);
        return deliveries.save(d);
    }

    private static <T> List<T> race(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (var t : tasks) fs.add(pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return t.call(); }));
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Depo: iki pod aynı (durum, damga) ile yeniden sahiplenir → tek satır güncellenir; sayaç bir artar")
    void reclaim_singleWinner() throws Exception {
        NocDelivery row = failedRow("2026-01-10T20:56:00");
        List<Integer> rows = race(List.of(
                () -> deliveries.reclaim(row.getId(), row.getStatus(), row.getUpdatedAt(), "2026-01-10T21:10:00"),
                () -> deliveries.reclaim(row.getId(), row.getStatus(), row.getUpdatedAt(), "2026-01-10T21:10:01")));
        assertThat(rows).containsExactlyInAnyOrder(1, 0);
        NocDelivery now = deliveries.findByDedupeKey(KEY).orElseThrow();
        assertThat(now.getStatus()).isEqualTo("SENDING");
        assertThat(now.getAttempts()).isEqualTo(2);
        assertThat(deliveries.reclaim(row.getId(), row.getStatus(), row.getUpdatedAt(), "2026-01-10T21:11:00"))
                .as("okunan durum artık geçerli değil").isZero();
    }

    @Test
    @DisplayName("Depo: damgası / durumu NULL eski satır boş metinle eşleşir (sayaçtan önceki satırlar yeniden denenebilir)")
    void reclaim_legacyNulls() {
        NocDelivery d = new NocDelivery();
        d.setDedupeKey(KEY);
        d.setPhase(NocDelivery.OPEN);
        d.setCreatedAt("2026-01-10T20:55:00");
        d = deliveries.save(d);
        assertThat(deliveries.reclaim(d.getId(), "", "", "2026-01-10T21:00:00")).isEqualTo(1);
        assertThat(deliveries.findByDedupeKey(KEY).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Servis: iki pod aynı FAILED açılış satırını aynı anda yeniden dener → 7/24 postası TEK kez, satır SENT")
    void twoPodsRetrySameFailedRow_singleMail() throws Exception {
        failedRow("2026-01-10T20:56:00");
        EmailNotificationService email = mock(EmailNotificationService.class);
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn("SENT");
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        CyclicBarrier bothRead = new CyclicBarrier(2);
        NocNotificationService a = service(email, bothRead);
        NocNotificationService b = service(email, bothRead);
        AlertEvent e = new AlertEvent();
        e.setId(62L); e.setAlertType("PING_DOWN"); e.setAlertLevel("CRITICAL"); e.setDomain("h62.example.com");
        e.setTeamId(10L); e.setCreatedAt("2026-01-10T21:05:00");

        race(List.of(
                () -> { a.onAlertDispatched(e, 10L, e.getDomain(), "CRITICAL", "PING_DOWN", "DAILY_REALERT", Map.of("monitor_id", 62L)); return true; },
                () -> { b.onAlertDispatched(e, 10L, e.getDomain(), "CRITICAL", "PING_DOWN", "DAILY_REALERT", Map.of("monitor_id", 62L)); return true; }));

        long mails = mockingDetails(email).getInvocations().stream().filter(i -> i.getMethod().getName().equals("sendHtml")).count();
        assertThat(mails).as("aynı 7/24 postası iki kez gitmedi").isEqualTo(1);
        NocDelivery row = deliveries.findByDedupeKey(KEY).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getAttempts()).isEqualTo(2);
    }

    /** Bir "pod": gerçek teslim deposu, diğer bağımlılıklar sahte. {@code bothRead}: iki pod satırı okuyup engellemiyor bulduktan sonra buluşur. */
    private NocNotificationService service(EmailNotificationService email, CyclicBarrier bothRead) {
        NocConfigService config = mock(NocConfigService.class);
        when(config.get()).thenReturn(new NocConfigService.Config(Set.of(), "CRITICAL", true, "Sırayla arayın.", null, null));
        NocNotificationGroupRepository groupRepo = mock(NocNotificationGroupRepository.class);
        NocNotificationGroup g = new NocNotificationGroup();
        g.setId(1L); g.setName("NOC Ana"); g.setActive(true); g.setIsDefault(true); g.setEmails("noc@example.com");
        when(groupRepo.findAllByOrderByNameAsc()).thenReturn(List.of(g));
        NocCallListService callLists = mock(NocCallListService.class);
        when(callLists.forMail(any())).thenReturn(new NocMailComposer.TeamBlock("Takım A",
                List.of(new NocMailComposer.Person("Kişi A", "Uzman", "+90 555 000 00 00")), true, null, List.of()));
        NocMonitorDirectory directory = mock(NocMonitorDirectory.class);
        NocMonitorDirectory.Row row = new NocMonitorDirectory.Row(NocType.PING, 62L, "izleme-62", "h62.example.com", 10L, null,
                true, true, null, false);
        when(directory.forAlert(eq(NocType.PING), any(), any())).thenAnswer(i -> {
            bothRead.await(10, TimeUnit.SECONDS);   // iki pod da önceki satırı okudu, ikisi de "yeniden denenebilir" buldu
            return row;
        });
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), any())).thenReturn("https://sitemonitor.example.com");
        return new NocNotificationService(config, new NocGroupService(groupRepo, null), callLists, directory, deliveries,
                email, mock(NotificationLogRepository.class), mock(MaintenanceService.class), mock(ActivityLogService.class), settings);
    }
}
