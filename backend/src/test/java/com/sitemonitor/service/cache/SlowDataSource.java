package com.sitemonitor.service.cache;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/** Test: bağlantı başına bekleyen (havuz beklemesi / asılı veritabanı benzetimi) ve bağlantı sayan veri kaynağı. */
final class SlowDataSource extends DelegatingDataSource {

    volatile long delayMs;
    final AtomicInteger connections = new AtomicInteger();

    SlowDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        connections.incrementAndGet();
        long d = delayMs;
        if (d > 0) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return super.getConnection();
    }
}
