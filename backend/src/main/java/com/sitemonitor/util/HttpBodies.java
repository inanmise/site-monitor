package com.sitemonitor.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Giden HTTP yanıt gövdelerini TAVANLI okumak için ortak yardımcı.
 *
 * <p><b>Neden var.</b> {@code HttpResponse.BodyHandlers.ofString()} tavansızdır: hedef sunucu
 * ne gönderirse tamamı heap'e alınır. Üretim TEK POD çalıştığı için bir {@code OutOfMemoryError}
 * doğrudan kesinti demektir. Projede tavan zaten standarttı — {@code WebhookService} 8 KB,
 * {@code UserPushService} 64 KB okuyor — ama RDAP/WHOIS/GeoIP istemcileri bu desene hiç
 * geçmemişti. Üstelik oradaki hedeflerin bir kısmı YÖNETİCİ TARAFINDAN AYARLANABİLİR
 * ({@code rdap-bootstrap-url}, {@code tr-web-whois-providers}), yani yanlış ya da ele geçmiş
 * tek bir adres tüm uygulamayı düşürebilirdi.
 *
 * <p><b>Neden sessizce kırpmıyor.</b> Webhook gövdesi yalnız günlüğe yazılıyor, orada kırpmak
 * zararsız. Buradaki gövdeler ise AYRIŞTIRILIYOR (RDAP JSON, WHOIS metni): yarıda kesilmiş bir
 * gövde "geçersiz JSON" gibi görünür ve asıl neden (yanıt çok büyük) kaybolur. Tavan aşılırsa
 * bu yüzden açık bir {@link IOException} atılır — çağıranın hata yolu zaten var ve mesaj
 * teşhisi doğru yere götürür.
 */
public final class HttpBodies {

    private HttpBodies() {}

    /** İsteğin kendi zaman aşımı yoksa gövde bütçesi (ms). */
    static final long DEFAULT_BODY_BUDGET_MS = 30_000L;

    /**
     * Gövdeyi en fazla {@code maxBytes} bayt olarak okur. Okuma SÜRESİ de sınırlıdır: gövde,
     * isteğin başlıklar için tanıdığı süre ({@link #bodyBudgetMs}) kadar daha beklenir (N1).
     *
     * @throws IOException gövde tavanı AŞARSA (kısmi içerik döndürülmez), süre dolarsa
     *                     ({@link BodyDeadlineException}) ya da okuma hatasında
     */
    public static String readCapped(HttpResponse<InputStream> response, int maxBytes, String what)
            throws IOException {
        try (InputStream is = withDeadline(response.body(), bodyBudgetMs(response, DEFAULT_BODY_BUDGET_MS), what)) {
            return new String(readCapped(is, maxBytes, what), StandardCharsets.UTF_8);
        }
    }

    // ── Gövde SÜRE sınırı (prod kapısı 2026-09-25, N1) ───────────────────────────────────────
    //
    // java.net.http'de zaman aşımı yalnız BAŞLIKLARA kadar işler: MultiExchange zamanlayıcıyı başlıklar
    // gelince iptal eder, gövde zamanlayıcısız okunur ve istemcinin soket okuma zaman aşımı yoktur.
    // Başlığı gönderip gövdeyi hiç bitirmeyen bir hedef (SSE, MJPEG kamera, radyo akışı, chunked
    // long-poll) okuyan iş parçacığını SÜRESİZ tutar: izleme sweep'i donar, test uçları Tomcat
    // havuzunu tüketir. Proje bu sınıfı PortCheckerService.readStatusLine'da kapatmıştı; HTTP/Keyword/Sayfa/
    // Webhook/Push istemcileri dışarıda kalmıştı. (ChainValidationService'in OCSP/CRL okuması da AÇIKTI —
    // HttpURLConnection yolu; 2026-09-27'de aşağıdaki deadline() ile kapatıldı, BO8.)
    //
    // Mekanizma: ayrı bir bekçi iş parçacığı süre dolunca akışı KAPATIR. HttpResponseInputStream.close()
    // aboneliği iptal edip kuyruğa son-işaretçisi koyduğu için bloklu read() hemen uyanır; okuyan taraf
    // IOException'ı BodyDeadlineException'a çevirir (teşhis "closed" yerine gerçek nedeni söyler).

    /**
     * Gövde okuma süresi doldu. {@link java.net.http.HttpTimeoutException} (dolayısıyla IOException) alt
     * sınıfı: çağıranların mevcut hata yolu (kontrol DOWN + mesaj) değişmeden işler.
     */
    public static final class BodyDeadlineException extends java.net.http.HttpTimeoutException {
        public BodyDeadlineException(String message) { super(message); }
    }

    /** Tek, daemon bekçi iş parçacığı. İptal edilen görevler kuyruktan hemen düşer (her kontrol bir görev). */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

    private static java.util.concurrent.ScheduledThreadPoolExecutor newWatchdog() {
        java.util.concurrent.ScheduledThreadPoolExecutor ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "http-body-deadline");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /**
     * Gövde bütçesi: isteğin kendi {@code HttpRequest.timeout} süresi (başlıklar için tanınan süre kadar
     * gövdeye de tanınır); isteğe süre verilmemişse {@code fallbackMs}.
     */
    public static long bodyBudgetMs(HttpResponse<?> response, long fallbackMs) {
        try {
            return response.request().timeout().map(java.time.Duration::toMillis).orElse(fallbackMs);
        } catch (RuntimeException e) {
            return fallbackMs;
        }
    }

    /**
     * Akışı SÜRE bekçisiyle sarar: {@code timeoutMs} dolunca alttaki akış kapatılır, süren/sonraki
     * okuma {@link BodyDeadlineException} fırlatır. Mevcut okuma kodu (readNBytes, döngüler) aynen çalışır;
     * yalnız {@code resp.body()} yerine bu sarmal kullanılır. Kapatınca bekçi görevi iptal edilir.
     */
    public static InputStream withDeadline(InputStream body, long timeoutMs, String what) {
        return new DeadlineInputStream(body, Math.max(1L, timeoutMs), what);
    }

    /**
     * Gövdeyi sonuna kadar TÜKETİR (bağlantı havuza dönsün — {@code BodyHandlers.discarding()}
     * eşleniği) ama en çok {@code timeoutMs}. Süre dolarsa akış kesilir ve {@code false} döner; durum
     * kodu zaten başlıktadır. Süre DIŞI okuma hataları aynen fırlatılır.
     */
    public static boolean drain(InputStream body, long timeoutMs, String what) throws IOException {
        try (InputStream is = withDeadline(body, timeoutMs, what)) {
            is.transferTo(java.io.OutputStream.nullOutputStream());
            return true;
        } catch (BodyDeadlineException te) {
            return false;
        }
    }

    /**
     * YALNIZ günlük/önizleme metni için: en çok {@code maxBytes} bayt, en çok {@code timeoutMs}. Süre
     * dolarsa istisna YERİNE o ana kadar okunanı döndürür — webhook/push yanıtında başarı durum koduyla
     * belli olmuştur; gövdesini bitirmeyen bir uç teslimatı "başarısız" saydırıp YİNELETMEMELİ.
     */
    public static byte[] readPreview(InputStream body, int maxBytes, long timeoutMs, String what) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(Math.max(maxBytes, 0), 8192));
        try (InputStream is = withDeadline(body, timeoutMs, what)) {
            byte[] buf = new byte[4096];
            while (out.size() < maxBytes) {
                int n = is.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
                if (n < 0) break;
                out.write(buf, 0, n);
            }
        } catch (BodyDeadlineException te) {
            // süre doldu — okunan kısım yeterli (önizleme)
        }
        return out.toByteArray();
    }

    // ── HttpURLConnection için TOPLAM süre sınırı (BO8, bug regresyon 2026-09-27) ────────────────────
    //
    // setConnectTimeout/setReadTimeout yalnız TEK bir okumayı (okumalar ARASI süreyi) sınırlar. Her okumada bir
    // bayt damlatan sunucu hem başlık hem gövde aşamasını süresiz uzatır; OCSP/CRL adresi izlenen sunucunun
    // sertifikasından (AIA / CRL-DP) okunduğu için bu karşı tarafın seçebildiği bir hedefti: her tarama
    // certCheckExecutor'dan bir iş parçacığı daha rehin alıyordu (tek pod'da havuz doyar, tarama durur).
    //
    // Mekanizma: (1) bekçi süre dolunca conn.disconnect() çağırır — JDK'da disconnect() kilit ALMAZ; başlık
    // aşamasında soketi kapatır (http.closeServer), bloklu okuma hemen düşer. (2) Gövde, KALAN süreyle
    // withDeadline sarmalından okunur: her read öncesi süre denetlenir, bloklu tek okuma da readTimeout ile
    // sınırlı — yani toplam en çok süre + bir readTimeout. Kapı: HttpBodyDeadlineGateTest (HttpURLConnection
    // gövde okumaları da taranır).

    /**
     * {@code conn} için toplam süre bekçisi başlatır; {@code try-with-resources} ile kapatılınca bekçi iptal
     * edilir. Gövde {@link ConnectionDeadline#body} ile okunmalıdır.
     */
    public static ConnectionDeadline deadline(java.net.HttpURLConnection conn, long timeoutMs, String what) {
        return new ConnectionDeadline(conn, Math.max(1L, timeoutMs), what);
    }

    /** Bkz. {@link #deadline}. */
    public static final class ConnectionDeadline implements AutoCloseable {
        private final java.net.HttpURLConnection conn;
        private final long timeoutMs;
        private final long deadlineNanos;
        private final String what;
        private final java.util.concurrent.ScheduledFuture<?> watchdog;
        private volatile boolean expired;

        ConnectionDeadline(java.net.HttpURLConnection conn, long timeoutMs, String what) {
            this.conn = conn;
            this.timeoutMs = timeoutMs;
            this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            this.what = what == null ? "HTTP" : what;
            this.watchdog = WATCHDOG.schedule(() -> {
                expired = true;
                closeOffWatchdog(() -> {
                    try { conn.disconnect(); } catch (RuntimeException ignore) { /* zaten kapalı */ }
                });
            }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /** Süre doldu mu (bekçi tetiklendi ya da saat geçti)? Hata iletisini doğru nedene götürmek için. */
        public boolean expired() {
            return expired || System.nanoTime() - deadlineNanos >= 0;
        }

        /** Süre dolmuşsa açıklayıcı istisna — çağıranın catch'i "Socket closed" yerine gerçek nedeni görür. */
        public BodyDeadlineException timeout() {
            return new BodyDeadlineException(what + " isteği " + timeoutMs
                    + " ms içinde tamamlanmadı — bağlantı kesildi (sunucu yanıtı çok yavaş gönderiyor)");
        }

        /** Gövde akışını KALAN süreyle sarar (toplam bütçe başlık + gövde için ortak). */
        public InputStream body(InputStream raw) throws BodyDeadlineException {
            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (expired || remainingMs <= 0) {
                try { raw.close(); } catch (IOException | RuntimeException ignore) { /* kapatılıyor */ }
                throw timeout();
            }
            // asyncClose: HttpURLConnection akışının close()'u süren okumanın kilidini bekler (MeteredStream) —
            // bekçi iş parçacığında yapılırsa ORTAK bekçi o süre boyunca başka hiçbir süreyi işletemezdi.
            return new DeadlineInputStream(raw, Math.max(1L, remainingMs), what, true);
        }

        /**
         * {@code conn.getInputStream()} (başlık aşaması) + {@link #body} (gövde). Bekçi başlık beklenirken bağlantıyı
         * kestiyse ham "Socket closed" yerine {@link #timeout()} fırlatılır.
         */
        public InputStream open() throws IOException {
            InputStream raw;
            try {
                raw = conn.getInputStream();
            } catch (IOException | RuntimeException e) {
                if (expired()) throw timeout();
                throw e;
            }
            return body(raw);
        }

        @Override
        public void close() {
            watchdog.cancel(false);
        }
    }

    /**
     * Olası BLOKLAYAN kapatma/kesme işini ortak bekçi iş parçacığından ayırır (sanal iş parçacığı). Bekçi tek
     * iş parçacığıdır; orada takılan bir kapatma TÜM gövde sürelerini geciktirirdi.
     */
    private static void closeOffWatchdog(Runnable closer) {
        try {
            Thread.ofVirtual().name("http-body-close").start(closer);
        } catch (RuntimeException e) {
            closer.run();   // sanal iş parçacığı açılamazsa (beklenmez) eski davranış
        }
    }

    /** Süre bekçili akış sarmalı — bkz. {@link #withDeadline}. */
    private static final class DeadlineInputStream extends java.io.FilterInputStream {
        private final long timeoutMs;
        private final long deadlineNanos;
        private final String what;
        private final boolean asyncClose;
        private final java.util.concurrent.ScheduledFuture<?> watchdog;
        private volatile boolean expired;

        DeadlineInputStream(InputStream in, long timeoutMs, String what) {
            this(in, timeoutMs, what, false);
        }

        /** {@code asyncClose}: süre dolunca alttaki akış bekçi iş parçacığında DEĞİL ayrı iş parçacığında kapatılır. */
        DeadlineInputStream(InputStream in, long timeoutMs, String what, boolean asyncClose) {
            super(in);
            this.timeoutMs = timeoutMs;
            this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            this.what = what == null ? "HTTP" : what;
            this.asyncClose = asyncClose;
            this.watchdog = WATCHDOG.schedule(this::expire, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private void expire() {
            expired = true;
            Runnable closer = () -> {
                try { in.close(); } catch (IOException | RuntimeException ignore) { /* zaten kapalı */ }
            };
            if (asyncClose) closeOffWatchdog(closer); else closer.run();
        }

        private BodyDeadlineException timeout() {
            return new BodyDeadlineException(what + " yanıt gövdesi " + timeoutMs
                    + " ms içinde tamamlanmadı — akış kesildi (sunucu başlığı gönderip gövdeyi bitirmedi)");
        }

        /** Bekçi henüz tetiklenmemiş olsa da süre geçtiyse okuma YAPILMAZ (hızlı sonsuz akış). */
        private void checkDeadline() throws BodyDeadlineException {
            if (expired || System.nanoTime() - deadlineNanos >= 0) {
                expired = true;
                throw timeout();
            }
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            try {
                return super.read();
            } catch (IOException e) {
                if (expired) throw timeout();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkDeadline();
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                if (expired) throw timeout();
                throw e;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            checkDeadline();
            try {
                return super.skip(n);
            } catch (IOException e) {
                if (expired) throw timeout();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            watchdog.cancel(false);
            super.close();
        }
    }

    /**
     * Aynı tavan, ham bayt olarak — {@code HttpURLConnection} kullanan ve gövdeyi METİN değil
     * İKİLİ ayrıştıran çağıranlar için (OCSP yanıtı, DER/PEM CRL).
     *
     * <p>Akışı KAPATMAZ: {@code HttpURLConnection} çağıranları bağlantıyı kendi
     * {@code try-with-resources}/{@code disconnect()} akışlarında yönetiyor.
     *
     * @throws IOException gövde tavanı AŞARSA (kısmi içerik döndürülmez) ya da okuma hatasında
     */
    public static byte[] readCapped(InputStream body, int maxBytes, String what) throws IOException {
        // Tavandan BİR fazlasını iste: dönen uzunluk tavanı geçiyorsa gövde kesilmiş demektir.
        byte[] buf = body.readNBytes(maxBytes + 1);
        if (buf.length > maxBytes) {
            throw new IOException(what + " yanıt gövdesi çok büyük (> " + maxBytes
                    + " bayt) — okuma reddedildi");
        }
        return buf;
    }
}
