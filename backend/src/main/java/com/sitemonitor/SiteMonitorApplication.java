package com.sitemonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class SiteMonitorApplication {

    private static final Logger log = LoggerFactory.getLogger(SiteMonitorApplication.class);

    /** Rename sonrası eski env adları — properties'teki ${SITE_MONITOR_X:${CERT_MONITOR_X:...}}
     *  zinciri sayesinde ÇALIŞMAYA DEVAM eder; bu kontrol yalnız görünürlük için tek WARN basar
     *  ki dağıtımlar yeni adlara planlı geçebilsin. */
    private static final String[] LEGACY_ENV_VARS = {   // geriye-uyum: eski env adları
            "CERT_MONITOR_USERNAME", "CERT_MONITOR_PASSWORD", "CERT_MONITOR_SECRET_KEY",
            "CERT_MONITOR_EMAIL_ENABLED", "CERT_MONITOR_EMAIL_FROM",
    };

    public static void main(String[] args) {
        warnOnLegacyEnvNames();
        SpringApplication.run(SiteMonitorApplication.class, args);
    }

    private static void warnOnLegacyEnvNames() {
        StringBuilder found = new StringBuilder();
        for (String name : LEGACY_ENV_VARS) {
            String newName = "SITE_MONITOR_" + name.substring("CERT_MONITOR_".length());
            // Yeni ad da tanımlıysa alias zaten devre dışı — gürültü çıkarma.
            if (System.getenv(name) != null && System.getenv(newName) == null) {
                if (found.length() > 0) found.append(", ");
                found.append(name);
            }
        }
        if (found.length() > 0) {
            log.warn("Eski ortam değişkeni adları kullanılıyor: {} — geriye-uyum ile çalışmaya devam ediyor; "
                    + "lütfen dağıtımı SITE_MONITOR_* adlarına geçirin (bir sonraki major'da eski adlar kalkabilir).",
                    found);
        }
    }
}
