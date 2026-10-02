package com.sitemonitor.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gerçek PostgreSQL'e karşı koşan entegrasyon testi (2026-10-02, onaylı öneri 28).
 *
 * <p>İki şey birden yapar:
 * <ul>
 *   <li><b>Etiket</b> {@code @Tag("postgres")} — surefire varsayılan koşumda bu etiketi dışarıda bırakır
 *       ({@code backend/pom.xml} {@code surefire.excludedGroups}); yalnız {@code mvn -Ppostgres-it test} koşar.</li>
 *   <li><b>Uzantı</b> {@link PostgresIt} — {@code IT_DB_URL} / {@code IT_DB_USER} / {@code IT_DB_PASSWORD} yoksa sınıfı
 *       açık bir mesajla ATLAR; varsa BOŞ veritabanında tam uygulama bağlamını iki kez açar (ikinci açılış idempotentlik
 *       kanıtı) ve ikinci bağlamı tüm postgres testlerine paylaştırır ({@link PostgresIt#app()}).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag(PostgresIt.TAG)
@ExtendWith(PostgresIt.class)
public @interface PostgresIntegration {
}
