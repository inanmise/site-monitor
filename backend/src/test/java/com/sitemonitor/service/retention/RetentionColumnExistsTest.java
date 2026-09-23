package com.sitemonitor.service.retention;

import com.sitemonitor.model.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: her retention politikasının (tablo, zaman kolonu) çifti ŞEMADA gerçekten var.
 *
 * <p><b>Neden.</b> 2026-09-23 denetimi {@code login-issue-images} politikasının
 * {@code timeColumn = "resolved_at"} dediğini, ama o kolonun ÇOCUK tabloda
 * ({@code login_issue_report_images}: id, report_id, content_type, data_base64) bulunmadığını
 * gösterdi. Silme doğruydu ({@code AGE_VIA_PARENT} modunda WHERE açıkça ebeveyn alt sorgusudur,
 * {@code {t}} yer tutucusu kullanılmaz), ama {@code RetentionService.bounds()} kolonu çocuk
 * tabloda arayıp hatayı {@code catch (Exception)} ile YUTUYORDU: Ayarlar → Veri Saklama ekranında
 * o kural için "en eski/en yeni kayıt" kalıcı olarak boş görünüyor ve operatör "bu tabloda veri
 * yok" sanıyordu.
 *
 * <p><b>Asıl risk buydu:</b> yanlış kolonun sessizce yutulması. Aynı hata {@code {t}} yer tutucusu
 * KULLANAN bir kurala kopyalansaydı DELETE cümlesi yanlış kolona kurulur ve gerçek veri kaybı
 * üretirdi. Bu tarama hiçbir testte yoktu.
 *
 * <p><b>Kapsam.</b> Kolon adları JPA entity'lerinden toplanır ({@code @Table(name=…)} +
 * {@code @Column(name=…)} / alan adının snake_case karşılığı). Entity'si olmayan (ham DDL ile
 * kurulan) tablolar taramaya girmez — {@link #TABLES_WITHOUT_ENTITY} onları gerekçesiyle listeler.
 * {@code AGE_VIA_PARENT} kuralları da muaf: onların zaman kolonu EBEVEYN tabloya aittir.
 */
class RetentionColumnExistsTest {

    /** Entity'si olmayan tablolar (ham DDL / jdbcTemplate ile kurulur) — kolon haritası çıkarılamaz. */
    private static final Map<String, String> TABLES_WITHOUT_ENTITY = Map.of(
            "system_heartbeat",     "applySchemaPatches ile kurulur (CREATE TABLE IF NOT EXISTS), entity yok",
            "http_metric_minute",   "applySchemaPatches ile kurulur, entity yok",
            "page_usage_daily",     "applySchemaPatches ile kurulur, entity yok",
            // Rollup tabloları SchedulerService:1153/1169'da ham DDL ile kuruluyor (JPA değil):
            // ham seriler önce özete alınıp sonra siliniyor, bileşik PK'lı bu iki tablonun
            // entity karşılığı yok.
            "monitor_check_daily",  "SchedulerService:1153 ham DDL (bileşik PK), entity yok",
            "monitor_check_hourly", "SchedulerService:1169 ham DDL (bileşik PK), entity yok");

    /** camelCase alan adı → snake_case kolon adı (JPA varsayılan stratejisi). */
    private static String snake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** tablo adı → kolon adları, JPA entity'lerinden. */
    private static Map<String, Set<String>> schema() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        // Spring'in kendi tarayıcısı — yeni bağımlılık eklemeden (reflections kütüphanesi yok).
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var bd : scanner.findCandidateComponents(CertificateInventory.class.getPackageName())) {
            Class<?> c;
            try { c = Class.forName(bd.getBeanClassName()); }
            catch (ClassNotFoundException e) { continue; }
            Table t = c.getAnnotation(Table.class);
            String table = (t != null && !t.name().isBlank() ? t.name() : c.getSimpleName())
                    .toLowerCase(Locale.ROOT);
            Set<String> cols = out.computeIfAbsent(table, k -> new LinkedHashSet<>());
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Column col = f.getAnnotation(Column.class);
                    cols.add((col != null && !col.name().isBlank() ? col.name() : snake(f.getName()))
                            .toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("Tarama vakum DEGIL — entity'lerden kayda deger sayida tablo cikiyor")
    void scanIsNotVacuous() {
        Map<String, Set<String>> schema = schema();
        assertThat(schema).as("entity taramasi bos — paket adi mi degisti?").hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("KAPI: politikanin zaman kolonu tablosunda GERCEKTEN var")
    void everyPolicyTimeColumnExists() {
        Map<String, Set<String>> schema = schema();
        List<String> offenders = new ArrayList<>();

        for (RetentionPolicy p : RetentionCatalog.executable()) {
            if (p.timeColumn() == null) continue;                               // öksüz kural
            if (p.mode() == RetentionPolicy.Mode.AGE_VIA_PARENT) continue;      // kolon EBEVEYNDE
            String table = p.table().toLowerCase(Locale.ROOT);
            if (TABLES_WITHOUT_ENTITY.containsKey(table)) continue;
            Set<String> cols = schema.get(table);
            if (cols == null) {
                offenders.add(p.id() + ": tablo '" + table + "' hicbir entity'ye ait degil "
                        + "(entity'siz ise TABLES_WITHOUT_ENTITY'e GEREKCESIYLE ekleyin)");
            } else if (!cols.contains(p.timeColumn().toLowerCase(Locale.ROOT))) {
                offenders.add(p.id() + ": '" + table + "' tablosunda '" + p.timeColumn() + "' kolonu YOK");
            }
        }

        assertThat(offenders)
                .as("Retention politikasi var OLMAYAN bir zaman kolonu gosteriyor. bounds() bunu "
                  + "catch(Exception) ile YUTAR ve Veri Saklama ekraninda aralik kalici bos kalir; "
                  + "ayni hata {t} yer tutucusu KULLANAN bir kurala kopyalanirsa DELETE yanlis "
                  + "kolona kurulur ve VERI KAYBI olur.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi OLU kayit tasimaz — circir yalnizca kuculur")
    void exemptionsAreAllAlive() {
        Set<String> used = new LinkedHashSet<>();
        for (RetentionPolicy p : RetentionCatalog.executable())
            used.add(p.table().toLowerCase(Locale.ROOT));
        List<String> dead = new ArrayList<>();
        TABLES_WITHOUT_ENTITY.forEach((t, why) -> { if (!used.contains(t)) dead.add(t); });
        assertThat(dead).as("Bu tablolarin artik bir retention kurali yok — muafiyet DUSMELI").isEmpty();
    }
}
