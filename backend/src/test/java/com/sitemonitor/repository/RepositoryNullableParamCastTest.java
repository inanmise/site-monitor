package com.sitemonitor.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAPI KAPISI: NULL geçilebilen bir metin parametresi bir SQL FONKSİYONUNA girecekse
 * {@code CAST(:x AS string)} ile sarılmak ZORUNDA.
 *
 * <p><b>Neden var (2026-08-22, kullanıcının bildirdiği üretim hatası).</b> Yönetici konsolu
 * açılır açılmaz "Değişiklik geçmişi yüklenemedi" veriyordu. Sebep
 * {@code MonitorChangeLogRepository#search} içindeki iki satırdı:
 * {@code LOWER(:actor)} ve {@code CONCAT('%', :q, '%')}. Süzgeç boşken bu parametreler null
 * gider; PostgreSQL null bir parametreyi <b>tip bağlamı olmadan</b> {@code bytea} kabul eder ve
 * {@code lower(bytea)} diye bir fonksiyon yoktur → {@code SQLGrammarException}, HTTP 500.
 * Karşılaştırmalarda ({@code c.teamId = :teamId}) sorun çıkmaz, çünkü tipi karşı taraftan
 * çıkarır; tipi çıkaracak bağlamın olmadığı yer FONKSİYON çağrısıdır.
 *
 * <p><b>Neden hiçbir test yakalamadı.</b> Controller testleri repository'yi mock'lar — JPQL hiç
 * çalışmaz. H2 üzerinde koşan bir test de yakalamazdı: {@code lower(null)} orada sorunsuz çalışır,
 * yani hata YALNIZ üretimin veritabanında görünür. Bu yüzden koruma çalışma zamanında değil,
 * sorgu METNİNDE aranıyor.
 *
 * <p><b>Kural.</b> Bir sorgu bir parametrenin null olabileceğini kendisi söylüyorsa
 * ({@code :x IS NULL} kalıbı) ve aynı sorgu o parametreyi {@code LOWER/UPPER/TRIM/CONCAT}
 * içine koyuyorsa, o kullanım {@code CAST(:x AS string)} taşımalıdır. Çözüm kodda zaten vardı
 * ({@code AlertEventRepository#search}); eksik olan, unutulduğunda haber veren kapıydı.
 */
class RepositoryNullableParamCastTest {

    /** Parametreyi tipsiz bırakan fonksiyon çağrıları — Postgres burada tip çıkaramaz. */
    private static final Pattern FUNCTION_USE = Pattern.compile(
            "(?i)\\b(LOWER|UPPER|TRIM|CONCAT)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    private static final Pattern PARAM = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    @DisplayName("NULL geçilebilen metin parametresi fonksiyona CAST'siz girmiyor")
    void nullableTextParamsAreCastInsideFunctions() {
        List<String> violations = new ArrayList<>();

        for (Class<?> repo : repositoryInterfaces()) {
            for (Method m : repo.getDeclaredMethods()) {
                Query q = m.getAnnotation(Query.class);
                if (q == null || q.value().isBlank()) continue;
                String jpql = q.value();

                // Sorgunun KENDİSİ hangi parametrelerin null olabileceğini söylüyor.
                Set<String> nullable = nullableParams(jpql);
                if (nullable.isEmpty()) continue;

                Matcher fn = FUNCTION_USE.matcher(jpql);
                while (fn.find()) {
                    String args = fn.group(2);
                    Matcher p = PARAM.matcher(args);
                    while (p.find()) {
                        String name = p.group(1);
                        if (!nullable.contains(name)) continue;          // zorunlu parametre — sorun yok
                        if (isCast(args, name)) continue;                // zaten sarılmış
                        violations.add(repo.getSimpleName() + "#" + m.getName()
                                + " → " + fn.group(1).toUpperCase() + "(… :" + name + " …)");
                    }
                }
            }
        }

        assertThat(violations)
                .as("""
                    NULL geçilebilen metin parametresi CAST'siz bir SQL fonksiyonuna giriyor.
                    PostgreSQL bunu bytea olarak bağlar ve sorgu çalışma zamanında 500 ile düşer
                    (H2 üzerinde koşan testler bunu YAKALAMAZ). Düzeltme: :x yerine
                    CAST(:x AS string) yazın — örnek: AlertEventRepository#search.
                    """)
                .isEmpty();
    }

    /** `:x IS NULL` kalıbı = sorgunun kendi beyanı: bu parametre null gelebilir. */
    private static Set<String> nullableParams(String jpql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?i):([A-Za-z_][A-Za-z0-9_]*)\\s+IS\\s+NULL").matcher(jpql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Parametre CAST(:x AS …) içinde mi? (boşluk toleranslı, büyük/küçük harf duyarsız) */
    private static boolean isCast(String fragment, String param) {
        return Pattern.compile("(?i)CAST\\s*\\(\\s*:" + Pattern.quote(param) + "\\s+AS\\b")
                .matcher(fragment).find();
    }

    private static List<Class<?>> repositoryInterfaces() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<Class<?>> found = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.sitemonitor.repository")) {
            try {
                found.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Repository arayüzü yüklenemedi: " + bd.getBeanClassName(), e);
            }
        }
        assertThat(found).as("Repository taraması hiçbir şey bulamadı — tarayıcı bozulmuşsa bu "
                + "test sessizce YEŞİL kalır ve hiçbir şeyi korumaz.").hasSizeGreaterThan(20);
        return found;
    }

    @Test
    @DisplayName("Kapının kendisi çalışıyor: CAST'siz örnek YAKALANIR, CAST'li örnek geçer")
    void ruleItselfIsSound() {
        String bad = """
                SELECT c FROM X c WHERE (:q IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')))
                """;
        String good = """
                SELECT c FROM X c WHERE (:q IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
                """;
        String required = "SELECT c FROM X c WHERE LOWER(c.name) = LOWER(:name)";

        assertThat(findViolations(bad)).as("CAST'siz kullanım yakalanmalı").isNotEmpty();
        assertThat(findViolations(good)).as("CAST'li kullanım temiz sayılmalı").isEmpty();
        // Zorunlu (null geçilemeyen) parametre kuralın DIŞINDA — aksi halde onlarca eşitlik
        // sorgusu gereksiz yere cast'lenirdi.
        assertThat(findViolations(required)).as("Zorunlu parametre kurala girmemeli").isEmpty();
    }

    /** Test-of-the-test yardımcı: tek bir JPQL metni için ihlalleri döner. */
    private static List<String> findViolations(String jpql) {
        List<String> out = new ArrayList<>();
        Set<String> nullable = nullableParams(jpql);
        if (nullable.isEmpty()) return out;
        Matcher fn = FUNCTION_USE.matcher(jpql);
        while (fn.find()) {
            Matcher p = PARAM.matcher(fn.group(2));
            while (p.find()) {
                if (nullable.contains(p.group(1)) && !isCast(fn.group(2), p.group(1))) out.add(p.group(1));
            }
        }
        return out;
    }
}
