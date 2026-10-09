package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Oturum deposu anahtarı (2026-10-09): yalnız {@code jdbc} oturum otomatik yapılandırmasını yükler. */
class SessionStoreAutoConfigurationFilterTest {

    private static final String[] CLASSES = {
            "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
            "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
            "org.springframework.boot.session.autoconfigure.SessionsEndpointAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            null,
    };

    private static boolean[] match(String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) env.setProperty(SessionStoreAutoConfigurationFilter.PROPERTY, value);
        SessionStoreAutoConfigurationFilter f = new SessionStoreAutoConfigurationFilter();
        f.setEnvironment(env);
        return f.match(CLASSES, null);
    }

    @Test
    @DisplayName("memory / boş / none / tanınmayan → oturum sınıfları YÜKLENMEZ; öteki otomatik yapılandırmalar etkilenmez")
    void memoryFiltersOnlySessionClasses() {
        for (String v : new String[] { null, "", "memory", "none", "redis", "jdbcx" }) {
            assertThat(match(v)).as(String.valueOf(v)).containsExactly(false, false, false, true, true);
        }
    }

    @Test
    @DisplayName("jdbc (büyük/küçük harf ve boşluk duyarsız) → hepsi yüklenir")
    void jdbcAllowsAll() {
        for (String v : new String[] { "jdbc", "JDBC", " Jdbc " }) {
            assertThat(match(v)).as(v).containsExactly(true, true, true, true, true);
        }
    }

    @Test
    @DisplayName("süzgeç spring.factories'te kayıtlı — kayıt kaybolursa anahtar sessizce işlevsiz kalırdı")
    void registeredInSpringFactories() throws Exception {
        boolean found = false;
        var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            Properties p = new Properties();
            try (var in = urls.nextElement().openStream()) { p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
            String v = p.getProperty("org.springframework.boot.autoconfigure.AutoConfigurationImportFilter", "");
            if (v.contains(SessionStoreAutoConfigurationFilter.class.getName())) found = true;
        }
        assertThat(found).isTrue();
    }
}
