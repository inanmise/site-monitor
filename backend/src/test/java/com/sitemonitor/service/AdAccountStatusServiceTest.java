package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.sitemonitor.service.LdapDirectoryService.AccountLookup;
import com.sitemonitor.service.LdapDirectoryService.AccountPresence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H paketi B4 (2026-11-26): beni-hatırla / kodla girişte AD hesap durumu kararı. Yerel hesap sorulmaz; etkin → izin + damga;
 * devre dışı / yok → {@code AD_ACCOUNT_DISABLED}; ulaşılamadı → son 24 saatte AD doğrulaması varsa izin, yoksa
 * {@code AD_UNVERIFIED}. İstek en çok kendi zaman aşımı kadar bekler; aynı kullanıcı tek aramayı paylaşır; sonuç bellekte;
 * kuyruk doluysa beklemeden "bilinmiyor".
 */
class AdAccountStatusServiceTest {

    private LdapDirectoryService directory;
    private AppUserRepository userRepo;
    private AdAccountStatusService svc;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);   // 2027-01-15T08:00:00Z
    private final List<CountDownLatch> toRelease = new ArrayList<>();

    @BeforeEach
    void setUp() {
        directory = mock(LdapDirectoryService.class);
        userRepo = mock(AppUserRepository.class);
        svc = new AdAccountStatusService(directory, userRepo);
        svc.clock = now::get;
    }

    @AfterEach
    void tearDown() {
        toRelease.forEach(CountDownLatch::countDown);
        svc.shutdown();
    }

    private static AppUser ad(long id, String username, String adVerifiedAt) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setAuthSource("LDAP");
        u.setActive(true);
        u.setAdVerifiedAt(adVerifiedAt);
        return u;
    }

    private static String iso(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(ms));
    }

    @Test
    @DisplayName("yerel hesap: AD'ye HİÇ sorulmaz, izin")
    void localAccount_notAsked() {
        AppUser local = ad(1, "admin", null);
        local.setAuthSource("LOCAL");
        assertThat(svc.checkForLogin(local).allowed()).isTrue();
        AppUser legacy = ad(2, "eski", null);
        legacy.setAuthSource(null);
        assertThat(svc.checkForLogin(legacy).allowed()).isTrue();
        verify(directory, never()).lookupAccountState(anyString());
    }

    @Test
    @DisplayName("AD etkin → izin + ad_verified_at damgası; devre dışı (514) ve yok → AD_ACCOUNT_DISABLED, damga yok")
    void activeDisabledNotFound() {
        when(directory.lookupAccountState("N1")).thenReturn(new AccountLookup(AccountPresence.FOUND, "512"));
        when(directory.lookupAccountState("N2")).thenReturn(new AccountLookup(AccountPresence.FOUND, "514"));
        when(directory.lookupAccountState("N3")).thenReturn(new AccountLookup(AccountPresence.NOT_FOUND, null));

        var a = svc.checkForLogin(ad(1, "N1", null));
        assertThat(a.allowed()).isTrue();
        assertThat(a.status()).isEqualTo(AdAccountStatusService.Status.ACTIVE);
        verify(userRepo).markAdVerified(eq(1L), anyString());

        var d = svc.checkForLogin(ad(2, "N2", iso(now.get())));
        assertThat(d.allowed()).as("yeni damga devre dışı hesabı KURTARMAZ").isFalse();
        assertThat(d.code()).isEqualTo(AdAccountStatusService.CODE_DISABLED);

        var n = svc.checkForLogin(ad(3, "N3", iso(now.get())));
        assertThat(n.allowed()).isFalse();
        assertThat(n.code()).isEqualTo(AdAccountStatusService.CODE_DISABLED);
        verify(userRepo, times(1)).markAdVerified(anyLong(), anyString());
    }

    @Test
    @DisplayName("AD'ye ulaşılamadı → son AD doğrulaması 24 saat içindeyse izin, değilse ya da hiç yoksa AD_UNVERIFIED")
    void unknown_twentyFourHourRule() {
        when(directory.lookupAccountState(anyString()))
                .thenThrow(new LdapDirectoryService.DirectoryUnavailableException("connect timed out", null));
        assertThat(svc.checkForLogin(ad(1, "A", iso(now.get() - 3_600_000L))).allowed()).isTrue();
        assertThat(svc.checkForLogin(ad(2, "B", iso(now.get() - 25L * 3_600_000L))).code())
                .isEqualTo(AdAccountStatusService.CODE_UNVERIFIED);
        assertThat(svc.checkForLogin(ad(3, "C", null)).code()).isEqualTo(AdAccountStatusService.CODE_UNVERIFIED);
        assertThat(svc.checkForLogin(ad(4, "D", "bozuk")).code()).isEqualTo(AdAccountStatusService.CODE_UNVERIFIED);
        verify(userRepo, never()).markAdVerified(anyLong(), anyString());
    }

    @Test
    @DisplayName("bağımsız inceleme: belirsizlik yok — dizin İLK kaydın durumunu verir (parola girişiyle aynı kural); 48 sa önce doğrulanmış hesap bile o kayıt etkinse girer")
    void firstHitDecides_likePasswordLogin() {
        assertThat(AccountPresence.values()).as("AMBIGUOUS kaldırıldı").containsExactly(AccountPresence.FOUND,
                AccountPresence.NOT_FOUND);
        doReturn(new AccountLookup(AccountPresence.FOUND, "512")).when(directory).lookupAccountState("TWO");
        var d = svc.checkForLogin(ad(5, "TWO", iso(now.get() - 48L * 3_600_000L)));
        assertThat(d.allowed()).isTrue();
        assertThat(d.status()).isEqualTo(AdAccountStatusService.Status.ACTIVE);
        verify(userRepo).markAdVerified(eq(5L), anyString());
    }

    @Test
    @DisplayName("kapanan pod: yeni arama yok → bilinmiyor + 24 sa kuralı (yakın zamanda doğrulanan geçer, eskisi AD_UNVERIFIED); önbellekteki kesin sonuç geçerli")
    void closingPod_unknownWithTwentyFourHourRule() {
        when(directory.lookupAccountState("K1")).thenReturn(new AccountLookup(AccountPresence.FOUND, "514"));
        assertThat(svc.checkForLogin(ad(1, "K1", null)).code()).isEqualTo(AdAccountStatusService.CODE_DISABLED);
        svc.onClosing();
        assertThat(svc.checkForLogin(ad(1, "K1", iso(now.get()))).code())
                .as("önbellekteki 'devre dışı' kapanışta da geçerli").isEqualTo(AdAccountStatusService.CODE_DISABLED);
        var recent = svc.checkForLogin(ad(2, "K2", iso(now.get() - 3_600_000L)));
        assertThat(recent.status()).isEqualTo(AdAccountStatusService.Status.UNKNOWN);
        assertThat(recent.allowed()).isTrue();
        assertThat(svc.checkForLogin(ad(3, "K3", iso(now.get() - 25L * 3_600_000L))).code())
                .isEqualTo(AdAccountStatusService.CODE_UNVERIFIED);
        verify(directory, times(1)).lookupAccountState(anyString());
    }

    @Test
    @DisplayName("istek en çok kendi süresi kadar bekler: AD takılırsa 'bilinmiyor' döner, istek bloklanmaz")
    void slowDirectory_boundedWait() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        toRelease.add(block);
        when(directory.lookupAccountState("SLOW")).thenAnswer(inv -> {
            block.await(10, TimeUnit.SECONDS);
            return new AccountLookup(AccountPresence.FOUND, "512");
        });
        svc.lookupTimeoutMs = 150;
        long t0 = System.nanoTime();
        var d = svc.checkForLogin(ad(1, "SLOW", null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(d.status()).isEqualTo(AdAccountStatusService.Status.UNKNOWN);
        assertThat(d.code()).isEqualTo(AdAccountStatusService.CODE_UNVERIFIED);
        assertThat(ms).isLessThan(3_000L);
        assertThat(AdAccountStatusService.LOOKUP_TIMEOUT_MS).as("üretim bekleme sınırı").isLessThanOrEqualTo(5_000L);
    }

    @Test
    @DisplayName("bellek: kesin sonuç 5 dk, bilinmeyen 30 sn tutulur — her girişte AD'ye gidilmez")
    void cache() {
        when(directory.lookupAccountState("C1")).thenReturn(new AccountLookup(AccountPresence.FOUND, "512"));
        svc.checkForLogin(ad(1, "C1", null));
        svc.checkForLogin(ad(1, "c1", null));   // ad harf duyarsız
        verify(directory, times(1)).lookupAccountState(anyString());
        now.addAndGet(AdAccountStatusService.CACHE_MS + 1);
        svc.checkForLogin(ad(1, "C1", null));
        verify(directory, times(2)).lookupAccountState(anyString());

        when(directory.lookupAccountState("U1"))
                .thenThrow(new LdapDirectoryService.DirectoryUnavailableException("down", null));
        svc.checkForLogin(ad(2, "U1", null));
        svc.checkForLogin(ad(2, "U1", null));
        verify(directory, times(1)).lookupAccountState("U1");
        now.addAndGet(AdAccountStatusService.UNKNOWN_CACHE_MS + 1);
        svc.checkForLogin(ad(2, "U1", null));
        verify(directory, times(2)).lookupAccountState("U1");
    }

    @Test
    @DisplayName("aynı kullanıcı için eşzamanlı istekler TEK AD aramasını paylaşır")
    void singleFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        toRelease.add(release);
        AtomicInteger calls = new AtomicInteger();
        when(directory.lookupAccountState("SF")).thenAnswer(inv -> {
            calls.incrementAndGet();
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new AccountLookup(AccountPresence.FOUND, "512");
        });
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<AdAccountStatusService.Decision>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) fs.add(ex.submit(() -> svc.checkForLogin(ad(1, "SF", null))));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            release.countDown();
            for (var f : fs) assertThat(f.get(5, TimeUnit.SECONDS).allowed()).isTrue();
        } finally {
            ex.shutdownNow();
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("kuyruk doluysa beklemeden 'bilinmiyor'; reddedilen kullanıcı takılı kalmaz (sonraki istek gerçekten sorar)")
    void queueFull_unknownAtOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        toRelease.add(release);
        when(directory.lookupAccountState(anyString())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return new AccountLookup(AccountPresence.FOUND, "512");
        });
        svc.lookupTimeoutMs = 5;
        int busy = AdAccountStatusService.THREADS + AdAccountStatusService.QUEUE;
        for (int i = 0; i < busy; i++) svc.checkForLogin(ad(100 + i, "Q" + i, null));
        long t0 = System.nanoTime();
        var over = svc.checkForLogin(ad(1, "OVER", null));
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_000L);
        assertThat(over.status()).isEqualTo(AdAccountStatusService.Status.UNKNOWN);

        release.countDown();
        svc.lookupTimeoutMs = 5_000;
        // Kuyruk boşalırken yeni istek bir kez daha reddedilebilir — sınırlı yeniden deneme (ret önbelleğe yazılmaz).
        AdAccountStatusService.Status st = null;
        for (int i = 0; i < 100 && st != AdAccountStatusService.Status.ACTIVE; i++) {
            st = svc.checkForLogin(ad(1, "OVER", null)).status();
            if (st != AdAccountStatusService.Status.ACTIVE) Thread.sleep(20);
        }
        assertThat(st).isEqualTo(AdAccountStatusService.Status.ACTIVE);
    }

    @Test
    @DisplayName("kapanışta yeni arama başlamaz")
    void closing_noLookup() {
        svc.onClosing();
        assertThat(svc.checkForLogin(ad(1, "Z", null)).status()).isEqualTo(AdAccountStatusService.Status.UNKNOWN);
        verify(directory, never()).lookupAccountState(anyString());
    }

    @Test
    @DisplayName("userAccountControl ACCOUNTDISABLE biti ve 24 saat sınırı; ret gövdesi kararlı kod taşır")
    void truthTables() {
        assertThat(LdapDirectoryService.accountDisabled("514")).isTrue();
        assertThat(LdapDirectoryService.accountDisabled("66050")).isTrue();
        assertThat(LdapDirectoryService.accountDisabled("512")).isFalse();
        assertThat(LdapDirectoryService.accountDisabled("66048")).isFalse();
        assertThat(LdapDirectoryService.accountDisabled(null)).isFalse();
        assertThat(LdapDirectoryService.accountDisabled("")).isFalse();
        assertThat(LdapDirectoryService.accountDisabled("abc")).isFalse();
        assertThat(LdapDirectoryService.accountDisabled("9".repeat(30))).isFalse();

        long t = now.get();
        assertThat(AdAccountStatusService.recentlyVerified(iso(t - 23L * 3_600_000L), t)).isTrue();
        assertThat(AdAccountStatusService.recentlyVerified(iso(t - 25L * 3_600_000L), t)).isFalse();
        assertThat(AdAccountStatusService.recentlyVerified(iso(t + 3_600_000L), t)).as("gelecek damga sayılmaz").isFalse();
        assertThat(AdAccountStatusService.recentlyVerified(iso(t + 5L * 60_000L), t))
                .as("saati birkaç dk ileride pod'un damgası sayılır").isTrue();
        assertThat(AdAccountStatusService.recentlyVerified(null, t)).isFalse();

        assertThat(AdAccountStatusService.refusalBody("AD_ACCOUNT_DISABLED"))
                .containsEntry("code", "AD_ACCOUNT_DISABLED").containsEntry("error_code", "AD_ACCOUNT_DISABLED")
                .containsEntry("success", false).containsKey("error");
        assertThat(AdAccountStatusService.refusalBody("AD_UNVERIFIED")).containsEntry("code", "AD_UNVERIFIED");
    }
}
