package com.sitemonitor.service.checks;

import java.util.concurrent.atomic.AtomicLong;

/** Elle ilerletilen veritabanı saati (paket A testleri). */
final class TestDbClock implements DbClock {

    final AtomicLong now;

    TestDbClock(long start) {
        this.now = new AtomicLong(start);
    }

    @Override
    public long nowMs() {
        return now.get();
    }

    @Override
    public boolean stale() {
        return false;
    }

    long advance(long ms) {
        return now.addAndGet(ms);
    }
}
