package com.sitemonitor.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Servislerin YUTTUĞU SQL hatalarını görünür kılar (2026-10-02, PostgreSQL entegrasyon testleri).
 *
 * <p>Yoklanan ekranların servisleri ({@code MonitoringOverviewService}, {@code StatusPageService}, {@code AlertNoiseService}
 * …) her sorguyu try/catch ile sarar ve hatayı çoğunlukla DEBUG'da yazıp boş sonuçla devam eder — ekran çökmesin diye.
 * Bu, PostgreSQL'de bozulan bir sorgunun testte "boş liste" olarak YEŞİL görünmesi demektir. Bu yardımcı, kapsamı
 * süresince {@code com.sitemonitor} günlüğünü DEBUG'a alır (konsola taşmasın diye o süre additivity kapalı) ve
 * Hibernate'in JDBC hata satırlarını ({@code org.hibernate.orm.jdbc.error}) da toplar; YALNIZ çağıran iş parçacığının SQL'e
 * benzeyen hata izlerini döndürür (arka planda koşan zamanlanmış işlerin günlükleri karışmasın).
 */
public final class SwallowedSqlErrors implements AutoCloseable {

    /** Spring / Hibernate / pgjdbc hata metinlerinde geçen işaretler (büyük/küçük harf duyarsız). */
    private static final List<String> MARKERS = List.of(
            "bad sql grammar", "sqlstate", "sql [", "jdbc exception", "could not execute", "could not prepare",
            "psqlexception", "invalidpathexception", "querysyntaxexception", "semanticexception",
            "uncategorized sqlexception", "dataintegrityviolation");
    /** PostgreSQL sunucu mesajı öneki — sunucu dili ({@code lc_messages}) Türkçe ise "HATA:" gelir; harf duyarlı. */
    private static final List<String> SERVER_PREFIXES = List.of("ERROR: ", "HATA: ");

    private final String thread = Thread.currentThread().getName();
    private final Logger app = (Logger) LoggerFactory.getLogger("com.sitemonitor");
    private final Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
    private final Level previousLevel;
    private final boolean previousAdditive;
    private final List<String> hits = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent e) {
            if (!thread.equals(e.getThreadName())) return;
            String text = e.getFormattedMessage() + throwableText(e.getThrowableProxy());
            // Hibernate 7 JDBC hata kategorisi (eski adı SqlExceptionHelper); jdbc.warn NOTICE'ları hata değildir.
            boolean hibernateSql = (e.getLoggerName().startsWith("org.hibernate.orm.jdbc.error")
                    || e.getLoggerName().startsWith("org.hibernate.engine.jdbc.spi.SqlExceptionHelper"))
                    && e.getLevel().isGreaterOrEqual(Level.WARN);
            String lower = text.toLowerCase(Locale.ROOT);
            boolean marker = MARKERS.stream().anyMatch(lower::contains) || SERVER_PREFIXES.stream().anyMatch(text::contains);
            if (hibernateSql || marker) hits.add(e.getLevel() + " " + e.getLoggerName() + " — " + text);
        }
    };

    private SwallowedSqlErrors() {
        appender.setContext(app.getLoggerContext());
        appender.setName("postgres-it-swallowed-sql");
        appender.start();
        previousLevel = app.getLevel();
        previousAdditive = app.isAdditive();
        app.setLevel(Level.DEBUG);
        app.setAdditive(false);
        app.addAppender(appender);
        hibernate.addAppender(appender);
    }

    /** Kapsamı başlatır — {@code try (var sql = SwallowedSqlErrors.capture()) { … }}. */
    public static SwallowedSqlErrors capture() {
        return new SwallowedSqlErrors();
    }

    /** Şu ana dek yakalanan SQL hata izleri (bu iş parçacığı). */
    public synchronized List<String> errors() {
        return List.copyOf(hits);
    }

    @Override
    public void close() {
        app.detachAppender(appender);
        hibernate.detachAppender(appender);
        app.setLevel(previousLevel);
        app.setAdditive(previousAdditive);
        appender.stop();
    }

    private static String throwableText(IThrowableProxy t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        for (IThrowableProxy p = t; p != null; p = p.getCause()) {
            sb.append(" | ").append(p.getClassName()).append(": ").append(p.getMessage());
        }
        return sb.toString();
    }
}
