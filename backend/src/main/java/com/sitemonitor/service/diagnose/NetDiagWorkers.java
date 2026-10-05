package com.sitemonitor.service.diagnose;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ping / Port / DNS tanılamasının iş parçacıkları (2026-10-05): SANAL iş parçacıkları — izleme taramasının
 * {@code certCheckExecutor} havuzu KULLANILMAZ (tanılama taramayı aç bırakamaz). Her alt görev kendi zaman aşımıyla
 * sınırlı; toplayıcı {@link #await} koşunun tavanında bırakır ve görevi iptal eder.
 */
@Slf4j
@Component
public class NetDiagWorkers {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public <T> Future<T> submit(Callable<T> task) {
        return pool.submit(task);
    }

    /** Sonucu {@code deadline}'a (epoch ms) kadar bekler; dolarsa / patlarsa {@code null} (görev iptal edilir). */
    public static <T> T await(Future<T> f, long deadline) {
        if (f == null) return null;
        try {
            long rem = Math.max(1L, deadline - System.currentTimeMillis());
            return f.get(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return null;
        } catch (Exception e) {
            f.cancel(true);
            log.debug("Ağ tanılaması: görev süre sınırında bitmedi/patladı: {}", e.toString());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
