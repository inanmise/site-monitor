package com.sitemonitor.service.techerror;

import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Teknik hata raporu ayarları (12. sürüm, 2026-10-17 — C9 / C10 / E21): aralıklar (hangi uçtan yazılırsa yazılsın aynı
 * kural), alanlar-arası posta sınırı kuralı, canlı okuma (DB override aralık içindeyse o, değilse önyükleme değeri),
 * hatırlatma takımları (boş = kapalı) ve katalog / GLOBAL_ONLY kaydı.
 */
class TechErrorReportSettingsTest {

    @Test
    @DisplayName("aralıklar: gün 1–28, tekrar 0–14, onay 1–14, posta 5–50 MB, gövde 0–20 MB; sınırlar kabul, dışı 400; boş = varsayılana dön")
    void ranges() {
        String[][] ok = {
                {TechErrorReportSettings.KEY_REMINDER_DAY, "1"}, {TechErrorReportSettings.KEY_REMINDER_DAY, "28"},
                {TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "0"}, {TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "14"},
                {TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, "1"}, {TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, "14"},
                {TechErrorReportSettings.KEY_MAX_MAIL_MB, "5"}, {TechErrorReportSettings.KEY_MAX_MAIL_MB, "50"},
                {TechErrorReportSettings.KEY_MAIL_INLINE_MB, "0"}, {TechErrorReportSettings.KEY_MAIL_INLINE_MB, "20"},
                {TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "3, 5"}, {TechErrorReportSettings.KEY_REMINDER_DAY, ""},
        };
        for (String[] c : ok) assertThatCode(() -> TechErrorReportSettings.validate(c[0], c[1])).as(c[0] + "=" + c[1]).doesNotThrowAnyException();
        String[][] bad = {
                {TechErrorReportSettings.KEY_REMINDER_DAY, "0"}, {TechErrorReportSettings.KEY_REMINDER_DAY, "29"},
                {TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "-1"}, {TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, "15"},
                {TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, "0"}, {TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, "15"},
                {TechErrorReportSettings.KEY_MAX_MAIL_MB, "4"}, {TechErrorReportSettings.KEY_MAX_MAIL_MB, "51"},
                {TechErrorReportSettings.KEY_MAIL_INLINE_MB, "-1"}, {TechErrorReportSettings.KEY_MAIL_INLINE_MB, "21"},
                {TechErrorReportSettings.KEY_REMINDER_DAY, "beş"}, {TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "3,x"},
        };
        for (String[] c : bad) {
            assertThatThrownBy(() -> TechErrorReportSettings.validate(c[0], c[1])).as(c[0] + "=" + c[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TechErrorReportSettings.rangeMessage(TechErrorReportSettings.REMINDER_DAY)).contains("1").contains("28");
    }

    @Test
    @DisplayName("alanlar-arası: gövde bütçesi posta sınırından KÜÇÜK olmalı; eşit / büyük ileti döner; çözülemeyen değer kuralı atlar")
    void mailLimits() {
        assertThat(TechErrorReportSettings.mailLimitsProblem(15, 6)).isNull();
        assertThat(TechErrorReportSettings.mailLimitsProblem(5, 0)).isNull();
        assertThat(TechErrorReportSettings.mailLimitsProblem(10, 10)).contains("10 MB");
        assertThat(TechErrorReportSettings.mailLimitsProblem(10, 12)).isNotNull();
        assertThat(TechErrorReportSettings.mailLimitsProblem(null, 12)).isNull();
    }

    @Test
    @DisplayName("canlı okuma: aralık içi DB override kazanır; override yok / aralık dışı / bozuk / servis yok → önyükleme değeri")
    void liveInt() {
        AppSettingsService s = mock(AppSettingsService.class);
        IntRangeCase.check(s, null, 15, 15);
        IntRangeCase.check(s, "25", 15, 25);
        IntRangeCase.check(s, "60", 15, 15);
        IntRangeCase.check(s, "abc", 15, 15);
        assertThat(TechErrorReportSettings.liveInt(null, TechErrorReportSettings.MAX_MAIL_MB, 9)).isEqualTo(9);
        when(s.getInt(eq(TechErrorReportSettings.KEY_REMINDER_DAY), anyInt())).thenReturn(40);
        assertThat(TechErrorReportSettings.effectiveInt(s, TechErrorReportSettings.REMINDER_DAY)).as("aralık dışı → varsayılan").isEqualTo(5);
        when(s.getInt(eq(TechErrorReportSettings.KEY_REMINDER_DAY), anyInt())).thenReturn(12);
        assertThat(TechErrorReportSettings.effectiveInt(s, TechErrorReportSettings.REMINDER_DAY)).isEqualTo(12);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: aralık dışı ortam / kayıtlı değer sessiz düşmez — pod başına anahtar başına BİR WARN (değer, kaynak, varsayılan); outOfRange kartın notu için kaynağı ve değeri söyler")
    void outOfRangeIsWarnedOnceAndReported() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TechErrorReportSettings.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app = new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        try {
            TechErrorReportSettings.resetWarnings();
            AppSettingsService s = mock(AppSettingsService.class);
            // Ortam değeri 60 MB (aralık 5–50): override yok → önyükleme değeri aralık dışı → varsayılan 15
            for (int i = 0; i < 5; i++) {
                assertThat(TechErrorReportSettings.liveInt(s, TechErrorReportSettings.MAX_MAIL_MB, 60)).isEqualTo(15);
            }
            when(s.getInt(eq(TechErrorReportSettings.KEY_MAX_MAIL_MB), anyInt())).thenReturn(60);
            assertThat(TechErrorReportSettings.effectiveInt(s, TechErrorReportSettings.MAX_MAIL_MB)).isEqualTo(15);
            java.util.List<String> warns = app.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).singleElement().satisfies(m -> assertThat(m).contains(TechErrorReportSettings.KEY_MAX_MAIL_MB)
                    .contains("= 60").contains("ortam").contains("5–50").contains("varsayılan 15"));
            TechErrorReportSettings.OutOfRange env = TechErrorReportSettings.outOfRange(s, TechErrorReportSettings.MAX_MAIL_MB);
            assertThat(env).isEqualTo(new TechErrorReportSettings.OutOfRange(TechErrorReportSettings.SOURCE_ENV, "60", 15));
            // Bozuk kayıtlı değer: kaynak "stored", ayrı anahtar → kendi WARN'ı
            when(s.getOverride(TechErrorReportSettings.KEY_MAIL_INLINE_MB)).thenReturn(" 99 ");
            assertThat(TechErrorReportSettings.liveInt(s, TechErrorReportSettings.MAIL_INLINE_MB, 6)).isEqualTo(6);
            assertThat(TechErrorReportSettings.outOfRange(s, TechErrorReportSettings.MAIL_INLINE_MB))
                    .isEqualTo(new TechErrorReportSettings.OutOfRange(TechErrorReportSettings.SOURCE_STORED, "99", 6));
            assertThat(app.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)).hasSize(2);
            // Aralık içi değer: not yok, uyarı yok
            when(s.getInt(eq(TechErrorReportSettings.KEY_REMINDER_DAY), anyInt())).thenReturn(12);
            assertThat(TechErrorReportSettings.outOfRange(s, TechErrorReportSettings.REMINDER_DAY)).isNull();
            assertThat(TechErrorReportSettings.outOfRange(null, TechErrorReportSettings.REMINDER_DAY)).isNull();
            assertThat(TechErrorReportSettings.liveInt(s, TechErrorReportSettings.REMINDER_DAY, 12)).isEqualTo(12);
            assertThat(app.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)).hasSize(2);
        } finally {
            logger.detachAppender(app);
            TechErrorReportSettings.resetWarnings();
        }
    }

    private static final class IntRangeCase {
        static void check(AppSettingsService s, String override, int fallback, int expected) {
            when(s.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn(override);
            assertThat(TechErrorReportSettings.liveInt(s, TechErrorReportSettings.MAX_MAIL_MB, fallback)).as(String.valueOf(override)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("hatırlatma takımları: boş / bozuk / okunamaz = boş küme (KAPALI — opt-in); push varsayılan açık")
    void reminderTeams() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(eq(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS), anyString())).thenReturn("");
        assertThat(TechErrorReportSettings.reminderTeamIds(s)).isEmpty();
        when(s.getString(eq(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS), anyString())).thenReturn("3, 5 x");
        assertThat(TechErrorReportSettings.reminderTeamIds(s)).containsExactly(3L, 5L);
        when(s.getString(eq(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS), anyString())).thenThrow(new IllegalStateException("db"));
        assertThat(TechErrorReportSettings.reminderTeamIds(s)).isEmpty();
        assertThat(TechErrorReportSettings.reminderTeamIds(null)).isEmpty();
        when(s.getBoolean(eq(TechErrorReportSettings.KEY_REMINDER_PUSH), anyBoolean())).thenReturn(false);
        assertThat(TechErrorReportSettings.reminderPush(s)).isFalse();
        assertThat(TechErrorReportSettings.reminderPush(null)).isTrue();
    }

    @Test
    @DisplayName("katalog: yeni anahtarlar 'tech-error-report' grubunda ve GLOBAL_ONLY (kapsamlı müdür yazamaz); isKey hepsini tanır")
    void catalogAndGlobalOnly() {
        Set<String> keys = Set.of(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, TechErrorReportSettings.KEY_REMINDER_DAY,
                TechErrorReportSettings.KEY_REMINDER_REPEAT_DAYS, TechErrorReportSettings.KEY_REMINDER_PUSH,
                TechErrorReportSettings.KEY_APPROVAL_REMINDER_DAYS, TechErrorReportSettings.KEY_MAX_MAIL_MB,
                TechErrorReportSettings.KEY_MAIL_INLINE_MB, TechErrorReportSettings.KEY_TEAM_IDS);
        for (String k : keys) {
            AppSettingsCatalog.Setting st = AppSettingsCatalog.byKey(k);
            assertThat(st).as(k).isNotNull();
            assertThat(st.group()).as(k).isEqualTo(TechErrorReportSettings.GROUP);
            assertThat(AppSettingsCatalog.isGlobalOnly(k)).as(k).isTrue();
            assertThat(TechErrorReportSettings.isKey(k)).as(k).isTrue();
        }
        assertThat(TechErrorReportSettings.isKey("site.monitor.tech-error-report.other")).isFalse();
    }

    @Test
    @DisplayName("2026-10-18 push anahtarları: katalogda BOOL, 'tech-error-report' grubunda, GLOBAL_ONLY; varsayılan AÇIK (okunamazsa da açık); validate boolean anahtarları geçirir")
    void pushKeys() {
        assertThat(TechErrorReportSettings.PUSH_KEYS).hasSize(5).allMatch(k -> k.startsWith("site.monitor.tech-error-report.push."));
        for (String k : TechErrorReportSettings.PUSH_KEYS) {
            AppSettingsCatalog.Setting st = AppSettingsCatalog.byKey(k);
            assertThat(st).as(k).isNotNull();
            assertThat(st.group()).as(k).isEqualTo(TechErrorReportSettings.GROUP);
            assertThat(st.type()).as(k).isEqualTo(AppSettingsCatalog.Type.BOOL);
            assertThat(AppSettingsCatalog.isGlobalOnly(k)).as(k).isTrue();
            assertThat(TechErrorReportSettings.isKey(k)).as(k).isTrue();
            assertThatCode(() -> TechErrorReportSettings.validate(k, "false")).doesNotThrowAnyException();
        }
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        assertThat(TechErrorReportSettings.pushOn(s, TechErrorReportSettings.KEY_PUSH_FAILED)).isTrue();
        when(s.getBoolean(eq(TechErrorReportSettings.KEY_PUSH_FAILED), anyBoolean())).thenReturn(false);
        assertThat(TechErrorReportSettings.pushOn(s, TechErrorReportSettings.KEY_PUSH_FAILED)).isFalse();
        when(s.getBoolean(eq(TechErrorReportSettings.KEY_PUSH_DECISION), anyBoolean())).thenThrow(new IllegalStateException("db"));
        assertThat(TechErrorReportSettings.pushOn(s, TechErrorReportSettings.KEY_PUSH_DECISION)).isTrue();
        assertThat(TechErrorReportSettings.pushOn(null, TechErrorReportSettings.KEY_PUSH_DECISION)).isTrue();
    }
}
