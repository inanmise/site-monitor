package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.repository.AlertThresholdRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Açılış tohumlaması aynı anda açılan iki pod'da (2026-10-21, çok pod R1-A, MP-20): ilk kurulum replicaCount 2 ya da sürüm
 * + replika aynı {@code helm upgrade}'de iki yeni pod'u birlikte başlatır. Eskiden "boş mu → ekle" adımları yarışıyordu:
 * kaybedenin benzersizlik istisnası {@code ApplicationReadyEvent}'ten kaçıp açılışı düşürüyor, benzersizlik kısıtı olmayan
 * varsayılan eşik İKİ satır oluyordu. Artık tohumlama SMSCHEMA kilidi (PostgreSQL) / JVM izleyicisi (H2) altında sırayla,
 * her adım kendi try/catch'inde.
 */
class BootstrapConcurrencyTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    @Test
    @DisplayName("iki pod aynı anda tohumlar → istisna yok, TEK varsayılan eşik, kaybeden pod'un benzersizlik hatası sonraki adımları durdurmaz")
    void twoPodsSeedConcurrently() {
        AtomicInteger thresholds = new AtomicInteger();
        AlertThresholdRepository repo = mp.mock(AlertThresholdRepository.class);
        when(repo.count()).thenAnswer(i -> {
            Thread.sleep(80);                                  // yarış penceresini genişlet: ikisi de "boş" görsün diye
            return (long) thresholds.get();
        });
        when(repo.save(any(AlertThreshold.class))).thenAnswer(i -> {
            thresholds.incrementAndGet();
            return i.getArgument(0);
        });
        UserService users = mp.mock(UserService.class);
        AtomicInteger admins = new AtomicInteger();
        doAnswer(i -> {
            if (admins.incrementAndGet() > 1) throw new DataIntegrityViolationException("uq_app_users_username");
            return null;
        }).when(users).ensureBootstrapped(anyString(), anyString());

        SchedulerService a = mp.pod(1, BootstrapConcurrencyTest::credentials).scheduler();
        SchedulerService b = mp.pod(2, BootstrapConcurrencyTest::credentials).scheduler();
        assertThatCode(() -> mp.race(() -> { a.runBootstrapSeeding(); return null; },
                () -> { b.runBootstrapSeeding(); return null; })).doesNotThrowAnyException();

        assertThat(thresholds).as("tek varsayılan eşik satırı").hasValue(1);
        assertThat(admins).hasValue(2);
        PermissionService perms = mp.mock(PermissionService.class);
        verify(perms, times(2)).seedDefaultsIfEmpty();
        verify(perms, times(2)).seedMissingDefaults();
        verify(perms, times(2)).applyPolicyUpgrades();
        verify(mp.mock(IncidentService.class), times(2)).seedOptions();
    }

    @Test
    @DisplayName("bir tohumlama adımı fırlatırsa sonraki adımlar yine koşar (açılış düşmez)")
    void failingStepDoesNotStopTheRest() {
        PermissionService perms = mp.mock(PermissionService.class);
        doAnswer(i -> { throw new IllegalStateException("izin tablosu kilitli"); }).when(perms).seedDefaultsIfEmpty();
        SchedulerService a = mp.pod(1, BootstrapConcurrencyTest::credentials).scheduler();
        assertThatCode(a::runBootstrapSeeding).doesNotThrowAnyException();
        verify(perms).seedMissingDefaults();
        verify(perms).applyPolicyUpgrades();
        verify(mp.mock(IncidentService.class)).seedOptions();
        verify(mp.mock(AlertThresholdRepository.class)).count();
    }

    static void credentials(SchedulerService s) {
        org.springframework.test.util.ReflectionTestUtils.setField(s, "adminUsername", "admin");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "adminPassword", "parola");
    }

    @Test
    @DisplayName("tohumlama sırası korunur: yönetici → izinler → olay seçenekleri → eşik")
    void orderPreserved() {
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mp.mock(UserService.class), mp.mock(PermissionService.class),
                mp.mock(IncidentService.class), mp.mock(AlertThresholdRepository.class));
        mp.pod(1, BootstrapConcurrencyTest::credentials).scheduler().runBootstrapSeeding();
        order.verify(mp.mock(UserService.class)).ensureBootstrapped("admin", "parola");
        order.verify(mp.mock(PermissionService.class)).seedDefaultsIfEmpty();
        order.verify(mp.mock(PermissionService.class)).seedMissingDefaults();
        order.verify(mp.mock(PermissionService.class)).applyPolicyUpgrades();
        order.verify(mp.mock(IncidentService.class)).seedOptions();
        order.verify(mp.mock(AlertThresholdRepository.class)).count();
    }
}
