package com.sitemonitor.service.activity;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/** Hazırlanan / yürütülen ifadeleri sayan veri kaynağı — "sorgu sayısı sabit" kapıları için (test). */
final class CountingDataSource extends DelegatingDataSource {

    final AtomicInteger statements = new AtomicInteger();

    CountingDataSource(DataSource target) {
        super(target);
    }

    int reset() { return statements.getAndSet(0); }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection c) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, args) -> {
                    String n = m.getName();
                    if (n.equals("prepareStatement") || n.equals("createStatement") || n.equals("prepareCall")) statements.incrementAndGet();
                    try {
                        return m.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
