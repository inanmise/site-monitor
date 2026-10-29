package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.checks.DbClock;

import java.util.concurrent.atomic.AtomicLong;

/** Elle ilerletilen veritabanı saati (çok pod 3-4 paket A testleri; {@code stale} ayarlanabilir). */
final class CertSweepTestClock implements DbClock {

    final AtomicLong now;
    volatile boolean stale;

    CertSweepTestClock(long start) {
        this.now = new AtomicLong(start);
    }

    @Override
    public long nowMs() {
        return now.get();
    }

    @Override
    public boolean stale() {
        return stale;
    }

    long advance(long ms) {
        return now.addAndGet(ms);
    }
}
