package com.sitemonitor.service.retention;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BEKÇİ TESTİ — "yeni izleme türü eklendi, temizliği unutuldu" hata sınıfını KALICI olarak kapatır.
 *
 * <p>Bu proje aynı hatayı defalarca yaşadı: {@code http_checks}, {@code page_*}, {@code scripted_checks},
 * {@code login_issue_*} hepsi "meğer hiç temizlenmiyormuş" diye SONRADAN eklendi; {@code incident_images}
 * ise 2026-08 taramasına kadar hiç fark edilmedi (satır başına 5 MB'a kadar BYTEA).
 *
 * <p>Kural: her kalıcı tablo ya {@link RetentionCatalog}'da bir satıra sahip olmalı, ya da aşağıdaki
 * {@link #EXEMPT} haritasında GEREKÇESİYLE muaf tutulmalı. Üçüncü seçenek yok — yeni bir tablo
 * ekleyip hiçbir şey yapmazsanız bu test kırmızıya döner.
 */
class RetentionCoverageTest {

    /**
     * {@code @Entity} olmayan, yalnız {@code SchedulerService.applySchemaPatches} içinde raw SQL ile
     * oluşturulan tablolar. Sınıf taraması bunları göremediği için elle listelenir.
     */
    private static final List<String> RAW_DDL_TABLES = List.of(
            "scheduler_lock", "login_anomaly_state", "monitor_check_daily", "app_user_teams", "page_usage_daily", "login_anomaly_ack");

    /** Muafiyetler — HER BİRİ gerekçeli. Gerekçesiz muafiyet eklenemez (değer boş olamaz). */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            // Yapılandırma / referans: satır sayısı monitör, kullanıcı veya takım sayısıyla sınırlı.
            Map.entry("alert_thresholds", "Tekil eşik kaydı (1 satır)."),
            Map.entry("app_settings", "Küratörlü ayar kataloğu kadar satır."),
            Map.entry("app_users", "Kullanıcı sayısı kadar; silme kullanıcı yönetiminden yapılır."),
            Map.entry("app_user_teams", "Kullanıcı×takım üyeliği; kullanıcı silinince JPA temizler."),
            Map.entry("teams", "Takım sayısı kadar."),
            Map.entry("permission_grants", "Rol×kaynak matrisi — sabit boyut."),
            Map.entry("escalation_contacts", "Eskalasyon kontakları — elle yönetilir."),
            Map.entry("notification_groups", "Bildirim grubu — takım başına birkaç satır, yumuşak silinir (elle yönetilir)."),
            Map.entry("maintenance_windows", "Bakım pencereleri — elle yönetilir."),
            Map.entry("user_push_scopes", "Kişi-webhook tip/takım aç-kapa matrisi — tip sayısı + takım sayısı kadar satır, elle yönetilir."),
            Map.entry("certificate_inventory", "Envanter: izlenen domain sayısı kadar (soft delete)."),
            Map.entry("latest_checks", "Domain başına TEK satır (PK = domain)."),
            Map.entry("smtp_settings", "Tekil ayar satırı."),
            Map.entry("ldap_settings", "Tekil ayar satırı."),
            Map.entry("scheduler_lock", "Aktif iş sayısı kadar; kilit bırakılınca silinir."),
            Map.entry("login_anomaly_state", "Tarama imleci — tek satır."),
            Map.entry("incident_options", "Olay seçenek listeleri — elle yönetilir."),
            Map.entry("monitoring_groups", "İzleme grubu kaydı — takım×tür×ad kadar."),
            Map.entry("monitor_guide", "İzleme başına tek rehber satırı."),
            Map.entry("pinned_cas", "host:port başına tek pin."),
            Map.entry("guide_links", "Yardım bağlantıları — elle yönetilir."),
            // Monitör tanımları: izleme sayısı kadar, soft delete ile yönetilir.
            Map.entry("port_monitors", "Monitör tanımı (izleme sayısı kadar)."),
            Map.entry("dns_monitors", "Monitör tanımı."),
            Map.entry("http_monitors", "Monitör tanımı."),
            Map.entry("keyword_monitors", "Monitör tanımı."),
            Map.entry("ping_monitors", "Monitör tanımı."),
            Map.entry("domain_monitors", "Monitör tanımı."),
            Map.entry("page_monitors", "Monitör tanımı."),
            Map.entry("pagespeed_monitors", "Monitör tanımı."),
            Map.entry("scripted_monitors", "Monitör tanımı."),
            Map.entry("scripted_script_versions",
                    "k6 script sürüm geçmişi — monitör tanımının parçası ve geri dönüş kaynağı; "
                    + "kullanıcı kararıyla süresiz saklanır (metinler KB mertebesinde). "
                    + "Monitörü silinen satırlar 'scripted-versions-orphan' kuralıyla temizlenir."),
            Map.entry("scripted_templates",
                    "Şablon kütüphanesi — küratörlü/kullanıcı katkılı içerik, zaman serisi DEĞİL. "
                    + "Satır sayısı yerleşik set + takımların yazdığı şablon kadar (onlarca mertebesi); "
                    + "silme soft delete ile yapılır, kalıcı silme admin'in açık eylemidir."),
            Map.entry("scripted_template_versions",
                    "Şablon sürüm geçmişi — 'kim ne zaman ne değiştirdi' denetim izi ve geri dönüş "
                    + "kaynağı; scripted_script_versions ile aynı gerekçeyle süresiz saklanır. "
                    + "Şablon KALICI silindiğinde geçmişi de silinir (soft delete'te korunur)."),
            // İçerik: kullanıcı üretimi, soft delete ile yönetilen kayıtlar.
            Map.entry("certificate_notes", "Kullanıcı notu — soft delete; revizyonlarının politikası var."),
            Map.entry("monitor_notes", "Kullanıcı notu — soft delete."),
            Map.entry("weak_algo_exception", "Kullanıcı kararı (istisna, alan başına TEK satır, UNIQUE domain) — süresi dolunca satır SİLİNMEZ, raporda \"süresi doldu\" olarak görünür; kullanıcı kaldırır. Zaman serisi değil, birikmez."),
            Map.entry("weekly_reports", "Haftalık rapor metni: takım×hafta; görsel ve mailleri ayrı politikada."),
            Map.entry("password_history", "UserService her şifre değişiminde kullanıcı başına son N'e kırpar."),
            Map.entry("remember_me_tokens", "RememberMeService saatlik olarak süresi dolanları siler.")
    );

    /** Katalogda politikası olan veya muaf olan tablolar dışında hiçbir tablo kalmamalı. */
    @Test
    @DisplayName("Her kalıcı tablo ya saklama politikasına ya da GEREKÇELİ muafiyete sahiptir")
    void everyTableIsCoveredByPolicyOrJustifiedExemption() {
        Set<String> covered = new HashSet<>(RetentionCatalog.tables().stream().map(String::toLowerCase).toList());
        Set<String> exempt = new HashSet<>(EXEMPT.keySet());

        List<String> uncovered = new ArrayList<>();
        for (String table : allPersistedTables()) {
            String t = table.toLowerCase();
            if (!covered.contains(t) && !exempt.contains(t)) uncovered.add(t);
        }

        assertThat(uncovered)
                .as("Saklama politikası OLMAYAN tablo(lar) bulundu. RetentionCatalog'a bir satır ekleyin "
                    + "ya da RetentionCoverageTest.EXEMPT'e GEREKÇESİYLE muafiyet yazın: %s", uncovered)
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyetlerin hepsi gerekçelidir ve gerçekten var olan tabloları işaret eder")
    void exemptionsAreJustifiedAndReal() {
        assertThat(EXEMPT.values()).allSatisfy(reason ->
                assertThat(reason).as("Gerekçesiz muafiyet olamaz").isNotBlank());

        Set<String> real = new HashSet<>(allPersistedTables().stream().map(String::toLowerCase).toList());
        List<String> stale = EXEMPT.keySet().stream().map(String::toLowerCase)
                .filter(t -> !real.contains(t)).sorted().toList();
        assertThat(stale).as("Artık var olmayan tablolar muafiyet listesinde duruyor: %s", stale).isEmpty();
    }

    @Test
    @DisplayName("Katalog tutarlı: id'ler tekil, süreli kuralların ayar anahtarı var, tabanlar makul")
    void catalogIsInternallyConsistent() {
        List<String> ids = RetentionCatalog.ALL.stream().map(RetentionPolicy::id).toList();
        assertThat(ids).as("politika id'leri tekil olmalı").doesNotHaveDuplicates();

        for (RetentionPolicy p : RetentionCatalog.executable()) {
            assertThat(p.where()).as("%s: WHERE gövdesi zorunlu", p.id()).isNotBlank();
            if (p.mode() == RetentionPolicy.Mode.ORPHAN_ONLY) {
                assertThat(p.timeColumn()).as("%s: öksüz kuralda zaman kolonu olmamalı", p.id()).isNull();
            } else {
                assertThat(p.timeColumn()).as("%s: zaman kolonu zorunlu", p.id()).isNotBlank();
                assertThat(p.settingKey()).as("%s: süreli kural ayarlanabilir olmalı", p.id()).isNotBlank();
                assertThat(p.defaultDays()).as("%s: varsayılan negatif olamaz", p.id()).isNotNegative();
                if (!p.zeroMeansNever()) {
                    assertThat(p.minDays()).as("%s: taban 1'den küçük olamaz", p.id()).isGreaterThanOrEqualTo(1);
                    assertThat(p.defaultDays()).as("%s: varsayılan tabanın altında olamaz", p.id())
                            .isGreaterThanOrEqualTo(p.minDays());
                }
            }
            assertThat(p.rationale()).as("%s: gerekçe zorunlu (dokümana basılır)", p.id()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Katalogdaki her ayar anahtarı AppSettingsCatalog'da da tanımlıdır (canlı düzenlenebilir)")
    void everySettingKeyIsInAppSettingsCatalog() {
        Set<String> known = new HashSet<>(
                com.sitemonitor.service.AppSettingsCatalog.ALL.stream()
                        .map(com.sitemonitor.service.AppSettingsCatalog.Setting::key).toList());
        List<String> missing = RetentionCatalog.configurable().stream()
                .map(RetentionPolicy::settingKey)
                .filter(k -> !known.contains(k))
                .distinct().sorted().toList();
        assertThat(missing).as("AppSettingsCatalog'a eklenmemiş anahtarlar: %s", missing).isEmpty();
    }

    /** @Entity sınıflarının tablo adları + raw-DDL tablolar. */
    private static List<String> allPersistedTables() {
        List<String> out = new ArrayList<>(RAW_DDL_TABLES);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.sitemonitor.model")) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                Table t = c.getAnnotation(Table.class);
                out.add(t != null && !t.name().isBlank() ? t.name() : camelToSnake(c.getSimpleName()));
            } catch (ClassNotFoundException ignored) { /* sınıf yüklenemezse atla */ }
        }
        return out;
    }

    /** @Table yoksa Hibernate varsayılanı: CamelCase → snake_case. */
    private static String camelToSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
