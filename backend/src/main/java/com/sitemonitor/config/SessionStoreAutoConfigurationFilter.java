package com.sitemonitor.config;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * Oturum deposu anahtarı (2026-10-09, kullanıcı isteği: "oturumlar veritabanında tutulmuyor konusunu uçtan uca
 * inceleyelim; mevcut çalışmayı etkilemeden geliştirelim").
 *
 * <p><b>Kök neden.</b> Spring Boot 3.3.6 → 4.1.0 yükseltmesinde (2026-06-17) oturum otomatik yapılandırması
 * {@code spring-boot-autoconfigure}'dan ayrı {@code spring-boot-session(-jdbc)} modüllerine taşındı; o modül yoktu ve
 * {@code spring.session.store-type} Boot 3'ten beri tanınmıyordu. Sonuç: prod profili "jdbc" dediği hâlde oturumlar pod
 * BELLEĞİNDEYDİ — her dağıtım / yeniden başlatma herkesi düşürüyordu, çok pod'da oturum paylaşılmıyordu
 * (yerel veride {@code spring_session}'daki son satır 2026-06-16).
 *
 * <p><b>Anahtar.</b> Modül sınıf yolunda olunca Boot onu VERİ KAYNAĞI olan her bağlamda açar (açma/kapama özelliği
 * yok). Bu süzgeç oturum otomatik yapılandırmalarını {@code site.monitor.session.store=jdbc} değilse HİÇ yüklemez:
 * <ul>
 *   <li>{@code memory} (varsayılan; yerel geliştirme + testler): bugünkü davranış BİREBİR — Tomcat oturumu,
 *       {@code JSESSIONID} çerezi, oturum tablosuna dokunulmaz.</li>
 *   <li>{@code jdbc} (prod profili, {@code SPRING_SESSION_STORE_TYPE} ile ezilebilir): Spring Session JDBC —
 *       {@code SESSION} çerezi, {@code spring_session} tablosu; oturum pod ölümünden sağ çıkar, pod'lar arasında
 *       paylaşılır.</li>
 * </ul>
 * Tanınmayan / boş değer ({@code none} dâhil) = memory: yanlış yazım bugünkü davranışa düşer, açılışı bozmaz.
 * Kayıt: {@code META-INF/spring.factories} ({@code AutoConfigurationImportFilter}).
 */
public class SessionStoreAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    public static final String PROPERTY = "site.monitor.session.store";
    public static final String JDBC = "jdbc";
    /** Boot 4 oturum modüllerinin otomatik yapılandırma paket öneki. */
    static final String SESSION_AUTOCONFIG_PREFIX = "org.springframework.boot.session.";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /** Ortamdaki değere göre JDBC deposu isteniyor mu (yalnız tam "jdbc", büyük/küçük harf duyarsız). */
    static boolean jdbcRequested(Environment env) {
        if (env == null) return false;
        String v = env.getProperty(PROPERTY, "");
        return JDBC.equals(v.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        boolean jdbc = jdbcRequested(environment);
        boolean[] out = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String c = autoConfigurationClasses[i];
            out[i] = jdbc || c == null || !c.startsWith(SESSION_AUTOCONFIG_PREFIX);
        }
        return out;
    }
}
