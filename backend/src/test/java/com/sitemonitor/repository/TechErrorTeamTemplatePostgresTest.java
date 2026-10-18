package com.sitemonitor.repository;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.service.techerror.TechErrorTeamTemplateService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Takım şablon kütüphanesi (2026-10-18, D17) GERÇEK PostgreSQL'de — H2'nin göremediği iki davranış: takım satırı kilidi
 * ({@code SELECT … FOR UPDATE}: aynı takıma iki yazım sırayla çalışır) ve kısmi tekil indeks {@code uq_tett_one_default}
 * (takım başına tek varsayılan; servisin kilidini atlayan bir yazımla yarışta ihlal 409 {@code CONCURRENT_MODIFICATION}
 * olur, ham 500 değil).
 */
@PostgresIntegration
class TechErrorTeamTemplatePostgresTest {

    private static final long WAIT_MS = 15_000;

    private static Long team(String tag) {
        Team t = new Team();
        t.setName(tag + " Dijital Kanallar");
        t.setActive(true);
        return PostgresIt.app().bean(TeamRepository.class).save(t).getId();
    }

    private static void cleanup(Long teamId) {
        PostgresIt.app().jdbc().update("DELETE FROM tech_error_team_templates WHERE team_id = ?", teamId);
        PostgresIt.app().bean(TeamRepository.class).deleteById(teamId);
    }

    private static TechErrorTeamTemplate row(Long teamId, String name, boolean def) {
        TechErrorTemplate.Template t = TechErrorTemplate.defaults("tr");
        TechErrorTeamTemplate e = new TechErrorTeamTemplate();
        e.setTeamId(teamId);
        e.setName(name);
        e.setNameKey(name.toLowerCase(java.util.Locale.ROOT));
        e.setTemplateJson(TechErrorTemplate.toJson(t));
        e.setTemplateLang("tr");
        e.setSectionCount(t.sections().size());
        e.setFieldCount(t.allFields().size());
        e.setSlotCount(t.allSlots().size());
        e.setDefaultEntry(def);
        return e;
    }

    /** Sorgu metni {@code fragment} içeren BAŞKA bir oturum kilit bekliyor mu (en çok {@link #WAIT_MS}). */
    private static boolean waitsOnLock(String fragment) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            Integer n = PostgresIt.app().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "AND wait_event_type = 'Lock' AND query ILIKE ?", Integer.class, "%" + fragment + "%");
            if (n != null && n > 0) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    @DisplayName("kısmi tekil indeks var (WHERE is_default) ve takım başına ikinci varsayılanı reddeder; başka takımın ve varsayılan olmayan kayıtlar serbest")
    void partialUniqueIndex() {
        String def = PostgresIt.app().jdbc().queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_tett_one_default'", String.class);
        assertThat(def).containsIgnoringCase("UNIQUE").containsIgnoringCase("WHERE").contains("is_default");
        TechErrorTeamTemplateRepository repo = PostgresIt.app().bean(TechErrorTeamTemplateRepository.class);
        String tag = "tett-it-" + UUID.randomUUID().toString().substring(0, 8);
        Long a = team(tag + "a");
        Long b = team(tag + "b");
        try {
            repo.saveAndFlush(row(a, "Ana", true));
            repo.saveAndFlush(row(a, "Yan", false));
            repo.saveAndFlush(row(a, "Yan 2", false));
            repo.saveAndFlush(row(b, "B ana", true));
            assertThatThrownBy(() -> repo.saveAndFlush(row(a, "İkinci varsayılan", true)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(String.valueOf(((DataIntegrityViolationException) e).getMostSpecificCause().getMessage()))
                            .contains("uq_tett_one_default"));
            // Servis yolu: yeni varsayılan öncekini aynı işlemde düşürür → ihlal yok, tek varsayılan
            TechErrorTeamTemplateService library = PostgresIt.app().bean(TechErrorTeamTemplateService.class);
            library.create(a, "Yeni varsayılan", TechErrorTemplate.defaults("tr"), true, null);
            assertThat(library.list(a)).filteredOn(m -> Boolean.TRUE.equals(m.getDefaultEntry()))
                    .extracting(TechErrorTeamTemplateRepository.Meta::getName).containsExactly("Yeni varsayılan");
            assertThat(library.list(b)).filteredOn(m -> Boolean.TRUE.equals(m.getDefaultEntry())).hasSize(1);
        } finally {
            cleanup(a);
            cleanup(b);
        }
    }

    @Test
    @DisplayName("takım satırı FOR UPDATE: başka bir oturum takım satırını kilitliyken kütüphane yazımı BEKLER, kilit kalkınca çalışır")
    void teamRowLockSerializesWrites() throws Exception {
        TechErrorTeamTemplateService library = PostgresIt.app().bean(TechErrorTeamTemplateService.class);
        String tag = "tett-it-" + UUID.randomUUID().toString().substring(0, 8);
        Long a = team(tag);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection raw = PostgresIt.app().newSession()) {
            raw.setAutoCommit(false);
            try (PreparedStatement ps = raw.prepareStatement("SELECT id FROM teams WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, a);
                ps.executeQuery().close();
            }
            Future<TechErrorTeamTemplate> write = pool.submit(() ->
                    library.create(a, "Kilitli yazım", TechErrorTemplate.defaults("tr"), true, null));
            assertThat(waitsOnLock("FOR UPDATE")).as("kütüphane yazımı takım satırı kilidini beklemeli").isTrue();
            assertThat(write.isDone()).isFalse();
            raw.commit();
            TechErrorTeamTemplate created = write.get(30, TimeUnit.SECONDS);
            assertThat(created.getId()).isNotNull();
            assertThat(created.isDefaultEntry()).isTrue();
        } finally {
            pool.shutdownNow();
            cleanup(a);
        }
    }

    @Test
    @DisplayName("varsayılan yarışı: kilidi atlayan (ham) bir işlem varsayılan eklerken servis yazımı tekil indekse takılır → 409 CONCURRENT_MODIFICATION (ham 500 değil); sonunda TEK varsayılan")
    void defaultIndexViolationIsConcurrentModification() throws Exception {
        TechErrorTeamTemplateService library = PostgresIt.app().bean(TechErrorTeamTemplateService.class);
        String tag = "tett-it-" + UUID.randomUUID().toString().substring(0, 8);
        Long a = team(tag);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection raw = PostgresIt.app().newSession()) {
            raw.setAutoCommit(false);
            try (PreparedStatement ps = raw.prepareStatement("INSERT INTO tech_error_team_templates (team_id, name, name_key, "
                    + "template_json, template_lang, section_count, field_count, slot_count, is_default, version) "
                    + "VALUES (?, 'Ham varsayılan', 'ham varsayılan', '{}', 'tr', 0, 0, 0, true, 0)")) {
                ps.setLong(1, a);
                ps.executeUpdate();
            }
            Future<TechErrorTeamTemplate> write = pool.submit(() ->
                    library.create(a, "Servis varsayılanı", TechErrorTemplate.defaults("tr"), true, null));
            // Servisin satır eklemesi, ham işlemin henüz onaylanmamış aynı anahtarını (team_id, is_default) bekler
            assertThat(waitsOnLock("tech_error_team_templates")).as("ekleme tekil indekste beklemeli").isTrue();
            raw.commit();
            assertThatThrownBy(() -> write.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(CodedConflictException.class)
                    .satisfies(c -> assertThat(((CodedConflictException) c).getCode()).isEqualTo("CONCURRENT_MODIFICATION"));
            List<String> defaults = PostgresIt.app().jdbc().queryForList(
                    "SELECT name FROM tech_error_team_templates WHERE team_id = ? AND is_default = true", String.class, a);
            assertThat(defaults).containsExactly("Ham varsayılan");
        } finally {
            pool.shutdownNow();
            cleanup(a);
        }
    }
}
