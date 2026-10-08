package com.sitemonitor.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PostgreSQL JDBC soket okuma zaman aşımı (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi).
 *
 * <p><b>Neden.</b> pgjdbc'nin {@code socketTimeout} varsayılanı 0'dır (SINIRSIZ). Ağda sessizce düşen bir veritabanı
 * bağlantısı (NAT/güvenlik duvarı durum tablosu, yük dengeleyici, DB yük devri) kullanımdaki bir sorguyu sonsuza dek
 * bekletir: Hikari'nin {@code connection-timeout}'u yalnız havuzdan bağlantı ALMAYI, {@code keepalive-time}'ı yalnız
 * BOŞTAKİ bağlantıyı korur. Sonuç: Tomcat/zamanlayıcı iş parçacıkları ve onların tuttuğu kilitler (scheduler_lock)
 * süresiz rehin kalır.
 *
 * <p><b>Ne yapar.</b> JDBC URL'si PostgreSQL ise ve ne URL'de ne {@code data-source-properties}'te zaten bir
 * {@code socketTimeout} yoksa, Hikari veri kaynağına {@code socketTimeout} (saniye) ekler. Değer
 * {@code site.monitor.db.socket-timeout-seconds} (env {@code DB_SOCKET_TIMEOUT_SECONDS}, varsayılan 1800 = 30 dk);
 * 0 ya da negatif = kapalı (eski davranış). Cömert seçildi: açılıştaki şema yamaları büyük tablolarda
 * {@code CREATE INDEX} koşabilir ve yarıda kesilen bir {@code CONCURRENTLY} derlemesi GEÇERSİZ indeks bırakır; saklama
 * işleri 10k'lık dilimlerle silse de tek bir ifadenin dakikalar sürmesi meşrudur. Bilerek EKLENMEYENLER:
 * {@code statement_timeout} / işlem zaman aşımı (uzun saklama/rollup işleri var).
 *
 * <p><b>Neden properties dosyasında değil.</b> {@code spring.datasource.hikari.data-source-properties.socketTimeout}
 * her sürücüye gider: testlerin H2 sürücüsü bilinmeyen bağlantı ayarını REDDEDER. Bu işlemci yalnız PostgreSQL
 * URL'sinde devreye girer; H2'de (testler) hiçbir şey yapmaz.
 */
@Slf4j
@Component
public class PostgresSocketTimeoutPostProcessor implements BeanPostProcessor {

    /** pgjdbc bağlantı özelliği (saniye). */
    static final String PROPERTY = "socketTimeout";

    /** Ayar anahtarı ve varsayılanı (saniye). */
    static final String SETTING_KEY = "site.monitor.db.socket-timeout-seconds";
    static final long DEFAULT_SECONDS = 1800L;

    private final Environment env;

    public PostgresSocketTimeoutPostProcessor(Environment env) {
        this.env = env;
    }

    /**
     * Bağlama (@ConfigurationProperties, PriorityOrdered) bu işlemciden ÖNCE koşar → URL ve özellikler okunabilir;
     * havuz ilk {@code getConnection}'a dek başlamadığı için yapılandırma henüz mühürlenmemiştir.
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource ds) {
            try {
                apply(ds, seconds());
            } catch (RuntimeException e) {
                // Zaman aşımı eklenemedi — veri kaynağı eskisi gibi (sınırsız) kurulur; açılış ASLA bu yüzden düşmez.
                log.warn("PostgreSQL socketTimeout eklenemedi ({}): {}", beanName, e.toString());
            }
        }
        return bean;
    }

    long seconds() {
        try {
            Long v = env.getProperty(SETTING_KEY, Long.class);
            return v != null ? v : DEFAULT_SECONDS;
        } catch (RuntimeException e) {
            log.warn("{} geçersiz ({}), varsayılan {} sn kullanılıyor", SETTING_KEY, e.getMessage(), DEFAULT_SECONDS);
            return DEFAULT_SECONDS;
        }
    }

    /**
     * Koşullar sağlanırsa {@code socketTimeout}'u ekler; eklediyse true. Yalnız PostgreSQL URL'si, açık bir değer
     * ({@code seconds > 0}) ve URL'de/özelliklerde önceden tanımlı bir {@code socketTimeout} YOKKEN — operatörün
     * verdiği değer asla ezilmez.
     */
    static boolean apply(HikariConfig cfg, long seconds) {
        if (seconds <= 0) return false;
        String url = cfg.getJdbcUrl();
        if (url == null || !url.regionMatches(true, 0, "jdbc:postgresql:", 0, "jdbc:postgresql:".length())) return false;
        if (url.toLowerCase(Locale.ROOT).contains(PROPERTY.toLowerCase(Locale.ROOT) + "=")) return false;
        for (String k : cfg.getDataSourceProperties().stringPropertyNames()) {
            if (k.equalsIgnoreCase(PROPERTY)) return false;
        }
        cfg.addDataSourceProperty(PROPERTY, Long.toString(seconds));
        log.info("PostgreSQL JDBC socketTimeout={} sn (sessizce düşen bağlantı iş parçacığını süresiz tutmasın)", seconds);
        return true;
    }
}
