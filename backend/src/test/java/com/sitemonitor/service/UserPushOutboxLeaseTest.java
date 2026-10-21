package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Push outbox kirası İKİ POD'da (2026-10-21, çok pod — MP-05), GERÇEK sorgular (H2, işlem dışı — servisin yazımları
 * üretimdeki gibi commit olur) ve gerçek HTTP ağ geçidi taklidi. Tur kirası 50 satırın SIRALI gönderimini kapsar; kira dolunca
 * diğer pod satırları yeniden kiralar. Batch başına kira tazelemesi sayesinde her satır TAM BİR KEZ POST edilir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:pushlease;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPushOutboxLeaseTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired UserPushDeliveryRepository repo;
    @Autowired JdbcTemplate jdbc;

    private HttpServer server;
    private ExecutorService serverPool;
    private volatile long gatewayDelayMs = 300;
    /** Kullanıcı → POST sayısı; kullanıcı → hangi pod (pipeline) gönderdi. */
    private final Map<String, AtomicInteger> posts = new ConcurrentHashMap<>();
    private final Map<String, List<String>> senders = new ConcurrentHashMap<>();
    private final CountDownLatch firstRequest = new CountDownLatch(1);
    private final List<UserPushService> pods = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newFixedThreadPool(8);
        server.setExecutor(serverPool);
        server.createContext("/notify", ex -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(buf);
            JsonNode body = MAPPER.readTree(buf.toString(StandardCharsets.UTF_8));
            for (JsonNode u : body.path("userIds")) {
                posts.computeIfAbsent(u.asText(), k -> new AtomicInteger()).incrementAndGet();
                senders.computeIfAbsent(u.asText(), k -> new CopyOnWriteArrayList<>()).add(body.path("pipeline").asText());
            }
            firstRequest.countDown();
            try { Thread.sleep(gatewayDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] resp = "{\"notificationId\": 7}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        for (UserPushService p : pods) p.shutdown();
        server.stop(0);
        serverPool.shutdownNow();
        jdbc.update("DELETE FROM user_push_deliveries");
    }

    private UserPushService pod(String name, long leaseSeconds) {
        return pod(name, leaseSeconds, repo);
    }

    private UserPushService pod(String name, long leaseSeconds, UserPushDeliveryRepository deliveries) {
        AppSettingsService settings = mock(AppSettingsService.class, withSettings().defaultAnswer(inv -> {
            Object[] a = inv.getArguments();
            if (inv.getMethod().getName().startsWith("get") && a.length == 2 && a[0] instanceof String) return a[1];
            return RETURNS_DEFAULTS.answer(inv);
        }));
        when(settings.getBoolean("site.monitor.userpush.enabled", false)).thenReturn(true);
        when(settings.getString("site.monitor.userpush.url", ""))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");
        when(settings.getString("site.monitor.userpush.pipeline", "")).thenReturn(name);
        TrustEvaluator trust = mock(TrustEvaluator.class);
        when(trust.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        UserPushService s = new UserPushService(settings, deliveries, mock(UserPushScopeRepository.class),
                mock(UserPushRecipientResolver.class), mock(AlertEventRepository.class), mock(SecretCipher.class),
                trust, mock(CaAutoPinService.class));
        s.leaseSecondsOverride = leaseSeconds;
        pods.add(s);
        return s;
    }

    private void rows(int n) {
        String now = ISO.format(Instant.now());
        for (int i = 1; i <= n; i++) {
            UserPushDelivery d = new UserPushDelivery();
            d.setAlertEventId((long) i);
            d.setTrigger("OPEN");
            d.setDedupeKey("OPEN");
            d.setUsername(String.format("N%05d", i));
            d.setDisplayName("Kişi " + i);
            d.setTitle("Site Monitor");
            d.setMessage("mesaj " + i);   // satır başına ayrı mesaj → her satır kendi isteği (batch)
            d.setStatus("PENDING");
            d.setAttempts(0);
            d.setCreatedAt(now);
            d.setBatchId("b" + i);
            repo.save(d);
        }
    }

    private int pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'PENDING'", Integer.class);
    }

    @Test
    @DisplayName("İki pod, 40 tek satırlık batch, 300 ms'lik ağ geçidi, 2 sn kira: kira el değiştirir ama her satır TAM BİR KEZ gönderilir")
    void twoPods_shortLease_eachRowPostedExactlyOnce() throws Exception {
        rows(40);
        UserPushService a = pod("podA", 2);
        UserPushService b = pod("podB", 2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        ExecutorService loops = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (UserPushService p : List.of(a, b)) {
                fs.add(loops.submit(() -> {
                    while (System.nanoTime() < deadline && pending() > 0) {
                        p.drainOutbox();
                        try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    }
                }));
            }
            for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        } finally {
            loops.shutdownNow();
        }

        assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'SENT'", Integer.class)).isEqualTo(40);
        assertThat(posts).hasSize(40);
        assertThat(posts.values()).allSatisfy(c -> assertThat(c.get()).as("aynı push iki kez gitmedi").isEqualTo(1));
        java.util.Set<String> pipelines = new java.util.HashSet<>();
        senders.values().forEach(pipelines::addAll);
        assertThat(pipelines).as("kira gerçekten el değiştirdi (iki pod da gönderdi)").containsExactlyInAnyOrder("podA", "podB");
    }

    @Test
    @DisplayName("Kapanış: süren istek bitirilir (kesilmez, SENT yazılır), turun kalan batch'leri gönderilmez ve kiraları HEMEN bırakılır")
    void shutdown_finishesInFlight_releasesRest() throws Exception {
        rows(3);
        gatewayDelayMs = 1_000;
        UserPushService a = pod("podA", 120);

        a.sweepOutbox();   // worker iş parçacığında tur
        assertThat(firstRequest.await(10, TimeUnit.SECONDS)).isTrue();
        a.shutdown();

        assertThat(posts.values().stream().mapToInt(AtomicInteger::get).sum()).as("yalnız süren istek").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE status = 'SENT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT next_attempt_at FROM user_push_deliveries WHERE status = 'PENDING'", String.class))
                .as("gönderilmeyen satırların kirası bırakıldı — başka pod beklemeden alır").hasSize(2).containsOnlyNulls();

        UserPushService b = pod("podB", 120);
        gatewayDelayMs = 0;
        b.drainOutbox();
        assertThat(pending()).isZero();
        assertThat(posts.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
    }

    @Test
    @DisplayName("Gönderildi ama sonuç bir kez yazılamadı (inceleme F4): satırın kirası ileri alınır — kira dolduktan sonra başka pod satırı ALMAZ (ikinci POST yok); sonraki süpürme SENT yazar")
    void outcomeWriteFailsOnce_noSecondPostFromAnotherPod() throws Exception {
        rows(1);
        gatewayDelayMs = 0;
        UserPushDeliveryRepository failing = mock(UserPushDeliveryRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repo));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı koptu"))
                .doAnswer(org.mockito.AdditionalAnswers.delegatesTo(repo)).when(failing).saveAll(any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı koptu"))
                .doAnswer(org.mockito.AdditionalAnswers.delegatesTo(repo))
                .when(failing).updateOutcome(any(), any(), any(), any(), any(), any(), any());
        UserPushService a = pod("podA", 1, failing);

        a.drainOutbox();

        assertThat(posts.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(1);
        assertThat(pending()).as("sonuç yazılamadı — satır PENDING").isEqualTo(1);
        String held = jdbc.queryForObject("SELECT next_attempt_at FROM user_push_deliveries", String.class);
        assertThat(held).as("kira 1 sn'lik tur kirasından çok ileri alındı")
                .isGreaterThan(ISO.format(Instant.now().plusSeconds(UserPushService.SENT_UNRECORDED_HOLD_SEC - 60)));

        Thread.sleep(1_500);   // A'nın ilk (1 sn) kirası çoktan doldu
        UserPushService b = pod("podB", 1);
        b.drainOutbox();
        assertThat(posts.values().stream().mapToInt(AtomicInteger::get).sum()).as("başka pod ikinci kez GÖNDERMEDİ").isEqualTo(1);
        assertThat(senders.values().stream().flatMap(List::stream).toList()).containsExactly("podA");

        a.flushSentUnrecorded();   // A'nın sonraki süpürmesi: sonuç yazılır, yeniden gönderilmez
        assertThat(jdbc.queryForObject("SELECT status FROM user_push_deliveries", String.class)).isEqualTo("SENT");
        assertThat(a.sentUnrecorded).isEmpty();
        b.drainOutbox();
        a.drainOutbox();
        assertThat(posts.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sonuç yazımı süpürmelerde de düşmeye devam ederse kira her süpürmede yeniden ileri alınır (satır hiçbir pod'ca yeniden kiralanmaz)")
    void outcomeWriteKeepsFailing_leaseKeptAlive() throws Exception {
        rows(1);
        gatewayDelayMs = 0;
        UserPushDeliveryRepository failing = mock(UserPushDeliveryRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repo));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı koptu")).when(failing).saveAll(any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı koptu"))
                .when(failing).updateOutcome(any(), any(), any(), any(), any(), any(), any());
        UserPushService a = pod("podA", 1, failing);

        a.drainOutbox();
        String first = jdbc.queryForObject("SELECT next_attempt_at FROM user_push_deliveries", String.class);
        Thread.sleep(1_100);
        a.flushSentUnrecorded();
        String second = jdbc.queryForObject("SELECT next_attempt_at FROM user_push_deliveries", String.class);

        assertThat(second).as("kira ileri alındı").isGreaterThan(first);
        assertThat(a.sentUnrecorded).hasSize(1);
        pod("podB", 1).drainOutbox();
        assertThat(posts.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(1);
    }
}
