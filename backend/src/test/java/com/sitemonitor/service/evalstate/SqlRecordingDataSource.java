package com.sitemonitor.service.evalstate;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test veri kaynağı — her bağlantıda hazırlanan / doğrudan koşturulan SQL metnini ve iş parçacığını kaydeder (çok pod Sürüm
 * 3-2 A: ifade dökümü, "ayar kapalıyken 3-1 ifade kümesi", yükleyicinin ifade sayısı). İsteğe bağlı bir {@link #failWhen}
 * koşulu eşleşen ifadede {@link SQLException} fırlatır (okuma hatası benzetimi); {@link #blockWhen} eşleşen ifadeyi
 * {@link #release()} çağrılana dek bekletir (yavaş veritabanı).
 */
public final class SqlRecordingDataSource extends DelegatingDataSource {

    /** Kaydedilen bir ifade. */
    public record Executed(String sql, String thread) {}

    public final List<Executed> executed = new CopyOnWriteArrayList<>();
    public volatile Predicate<String> failWhen = s -> false;
    public volatile Predicate<String> blockWhen = s -> false;
    private final Object gate = new Object();
    private volatile boolean released;

    public SqlRecordingDataSource(DataSource target) {
        super(target);
    }

    /** Bekletilen ifadeleri serbest bırakır. */
    public void release() {
        synchronized (gate) {
            released = true;
            gate.notifyAll();
        }
    }

    public void clear() {
        executed.clear();
    }

    public List<String> statements() {
        List<String> out = new ArrayList<>();
        for (Executed e : executed) out.add(e.sql());
        return out;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private void seen(String sql) throws SQLException {
        if (sql == null) return;
        executed.add(new Executed(sql, Thread.currentThread().getName()));
        if (failWhen.test(sql)) throw new SQLException("benzetilmiş okuma hatası", "08006");
        if (blockWhen.test(sql)) {
            synchronized (gate) {
                long until = System.currentTimeMillis() + 30_000L;
                while (!released && System.currentTimeMillis() < until) {
                    try {
                        gate.wait(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("kesildi", "57014");
                    }
                }
            }
        }
    }

    private Connection wrap(Connection real) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall")) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                seen(sql);
            }
            Object out;
            try {
                out = method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (name.equals("createStatement") && out instanceof Statement st) return wrapStatement(st);
            return out;
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, h);
    }

    private Statement wrapStatement(Statement real) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if ((name.startsWith("execute") || name.equals("addBatch")) && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                seen(sql);
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, h);
    }
}
