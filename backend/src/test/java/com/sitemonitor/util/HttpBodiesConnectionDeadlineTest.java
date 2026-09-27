package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * BO8 (bug regresyon 2026-09-27) — {@link HttpBodies#deadline} bekçisi PAYLAŞILAN tek iş parçacıklı zamanlayıcıyı
 * KİLİTLEYEMEZ.
 *
 * <p>JDK'da {@code MeteredStream.read} okuma sürerken kilidi tutar; {@code close()} / {@code disconnect()} aynı
 * kilidi bekler — yani bekçi kapatmayı KENDİ iş parçacığında yapsaydı, bloklu bir OCSP/CRL okuması bitene dek
 * (okuma zaman aşımı, 10 sn) HTTP/Keyword/Sayfa kontrollerinin gövde süreleri de dahil TÜM süre bekçileri
 * gecikirdi. Kapatma/kesme ayrı (sanal) iş parçacığında yapılır; bu test onu pinler.
 */
class HttpBodiesConnectionDeadlineTest {

    /** close()/disconnect() {@code release} açılana dek BLOKLAR (JDK kilit çekişmesinin modeli). */
    private static final class StuckConnection extends HttpURLConnection {
        final CountDownLatch release;
        StuckConnection(CountDownLatch release) throws Exception {
            super(URI.create("http://ocsp.example.com/").toURL());
            this.release = release;
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { awaitQuietly(release); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() { awaitQuietly(release); return -1; }
                @Override public void close() { awaitQuietly(release); }
            };
        }
    }

    /** close() çağrılana dek bloklayan akış; kapatılınca okuma IOException ile düşer. */
    private static final class BlockingStream extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public int read() throws IOException {
            awaitQuietly(closed);
            throw new IOException("closed");
        }
        @Override public void close() { closed.countDown(); }
    }

    private static void awaitQuietly(CountDownLatch l) {
        try { l.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    @DisplayName("KAPI: takılan bir bağlantı kapatması, ORTAK bekçinin başka akışların süresini işletmesini engellemez")
    void stuckCloseDoesNotBlockSharedWatchdog() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try {
            StuckConnection stuck = new StuckConnection(release);
            HttpBodies.ConnectionDeadline dl = HttpBodies.deadline(stuck, 50, "OCSP");
            dl.body(stuck.getInputStream());   // gövde bekçisi de ~50 ms'de "kapatır"
            Thread.sleep(200);                 // iki bekçi de tetiklendi, kapatmaları takıldı

            InputStream other = HttpBodies.withDeadline(new BlockingStream(), 300, "HTTP");
            long t0 = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(other::read).isInstanceOf(HttpBodies.BodyDeadlineException.class));
            assertThat((System.nanoTime() - t0) / 1_000_000L).as("başka akışın süresi zamanında dolmalı").isLessThan(3_000L);
            assertThat(dl.expired()).isTrue();
        } finally {
            release.countDown();   // takılı kapatmaları serbest bırak (ortak bekçi sonraki testlere temiz kalsın)
        }
    }

    @Test
    @DisplayName("open(): bekçi başlık aşamasında bağlantıyı kestiyse ham hata yerine açık süre istisnası")
    void open_translatesWatchdogAbortToDeadline() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpURLConnection hanging = new HttpURLConnection(URI.create("http://crl.example.com/x.crl").toURL()) {
            @Override public void connect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public InputStream getInputStream() throws IOException {
                awaitQuietly(disconnected);
                throw new java.net.SocketException("Socket closed");
            }
        };
        try (HttpBodies.ConnectionDeadline dl = HttpBodies.deadline(hanging, 100, "CRL")) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(dl::open).isInstanceOf(HttpBodies.BodyDeadlineException.class)
                            .hasMessageContaining("CRL").hasMessageContaining("100 ms"));
        }
    }
}
