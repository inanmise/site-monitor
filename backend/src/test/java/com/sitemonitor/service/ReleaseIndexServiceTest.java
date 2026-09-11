package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Yayın indeksi ({@code docs/releases/index.json}, schema 1) okuyucusu. Dosya bozuk/yoksa uygulama
 * ÇÖKMEZ — boş liste + WARN; ekran "yayın dizini yok" der. Sıralama azalan semver'dir (dosya sırası
 * ne olursa olsun).
 */
class ReleaseIndexServiceTest {

    /** Kasıtlı olarak sürüm sırası KARIŞIK — servis kendisi sıralamalı. */
    private static final String FIXTURE = """
            {
              "schema": 1,
              "generatedAt": "2026-09-10T19:24:53Z",
              "repo": "example/site-monitor",
              "count": 3,
              "releases": [
                {
                  "version": "20.53.1", "tag": "v20.53.1", "releasedAt": "2026-09-09T10:00:00Z",
                  "commit": "1111111111111111111111111111111111111111", "prevVersion": "20.53.0",
                  "bump": "patch", "breaking": false,
                  "counts": {"feat": 0, "fix": 1, "perf": 0, "refactor": 0, "docs": 0, "other": 0},
                  "changes": [ {"type": "fix", "scope": "cache", "breaking": false, "sha": "11111111", "subject": "önbellek süresi"} ],
                  "truncated": false, "omitted": 0
                },
                {
                  "version": "20.54.0", "tag": "v20.54.0", "releasedAt": "2026-09-10T18:19:32Z",
                  "commit": "2222222222222222222222222222222222222222", "prevVersion": "20.53.1",
                  "bump": "minor", "breaking": false,
                  "counts": {"feat": 1, "fix": 0, "perf": 0, "refactor": 0, "docs": 0, "other": 0},
                  "changes": [ {"type": "feat", "scope": null, "breaking": false, "sha": "22222222", "subject": "sürüm geçmişi"} ],
                  "truncated": true, "omitted": 4
                },
                {
                  "version": "20.53.0", "tag": "v20.53.0", "releasedAt": "2026-09-08T10:00:00Z",
                  "commit": "3333333333333333333333333333333333333333", "prevVersion": null,
                  "bump": "patch", "breaking": true,
                  "counts": {"feat": 0, "fix": 0, "perf": 0, "refactor": 0, "docs": 0, "other": 1},
                  "changes": [],
                  "truncated": false, "omitted": 0
                },
                { "version": "", "tag": "bozuk" }
              ]
            }
            """;

    private static ReleaseIndexService loaded() throws Exception {
        ReleaseIndexService s = new ReleaseIndexService(new MockEnvironment());
        s.loadFromString(FIXTURE, "fixture");
        return s;
    }

    @Test
    @DisplayName("all(): azalan semver sıralı, sürümsüz kayıt atlanır; latest() en yenisi")
    void allSortedDescending() throws Exception {
        ReleaseIndexService s = loaded();
        assertThat(s.all()).extracting(ReleaseIndexService.Release::version)
                .containsExactly("20.54.0", "20.53.1", "20.53.0");
        assertThat(s.latest()).map(ReleaseIndexService.Release::version).contains("20.54.0");
        assertThat(s.loaded()).isTrue();
        assertThat(s.schema()).isEqualTo(1);
        assertThat(s.generatedAt()).isEqualTo("2026-09-10T19:24:53Z");
    }

    @Test
    @DisplayName("find(): 'v' öneki ve boşluk tolere edilir; alanlar birebir taşınır; bilinmeyen sürüm empty")
    void findParsesFields() throws Exception {
        ReleaseIndexService s = loaded();
        ReleaseIndexService.Release r = s.find(" v20.54.0 ").orElseThrow();
        assertThat(r.tag()).isEqualTo("v20.54.0");
        assertThat(r.releasedAt()).isEqualTo("2026-09-10T18:19:32Z");
        assertThat(r.commit()).startsWith("2222");
        assertThat(r.prevVersion()).isEqualTo("20.53.1");
        assertThat(r.bump()).isEqualTo("minor");
        assertThat(r.breaking()).isFalse();
        assertThat(r.counts()).containsEntry("feat", 1).containsEntry("fix", 0);
        assertThat(r.changes()).hasSize(1);
        assertThat(r.changes().get(0).type()).isEqualTo("feat");
        assertThat(r.changes().get(0).scope()).isNull();
        assertThat(r.changes().get(0).subject()).isEqualTo("sürüm geçmişi");
        assertThat(r.truncated()).isTrue();
        assertThat(r.omitted()).isEqualTo(4);

        ReleaseIndexService.Release first = s.find("20.53.0").orElseThrow();
        assertThat(first.prevVersion()).isNull();
        assertThat(first.breaking()).isTrue();
        assertThat(first.changes()).isEmpty();

        assertThat(s.find("9.9.9")).isEmpty();
        assertThat(s.find(null)).isEmpty();
    }

    @Test
    @DisplayName("meta(): Sistem Sağlığı tazelik bloğu — sayı, en yeni sürüm, kısaltılmış sha256, kaynak")
    void metaBlock() throws Exception {
        Map<String, Object> m = loaded().meta();
        assertThat(m.get("loaded")).isEqualTo(true);
        assertThat(m.get("count")).isEqualTo(3);
        assertThat(m.get("newestVersion")).isEqualTo("20.54.0");
        assertThat(m.get("generatedAt")).isEqualTo("2026-09-10T19:24:53Z");
        assertThat(m.get("loadedFrom")).isEqualTo("fixture");
        assertThat(String.valueOf(m.get("sha256"))).hasSize(16);
        assertThat(m.get("schema")).isEqualTo(1);
    }

    @Test
    @DisplayName("yüklenmemiş servis: boş liste, latest/find empty, meta loaded=false — istisna yok")
    void notLoadedIsEmpty() {
        ReleaseIndexService s = new ReleaseIndexService(new MockEnvironment());
        assertThat(s.all()).isEmpty();
        assertThat(s.latest()).isEmpty();
        assertThat(s.find("1.0.0")).isEmpty();
        assertThat(s.loaded()).isFalse();
        Map<String, Object> m = s.meta();
        assertThat(m.get("loaded")).isEqualTo(false);
        assertThat(m.get("count")).isEqualTo(0);
        assertThat(m.get("newestVersion")).isNull();
        assertThat(m.get("loadedFrom")).isNull();
        assertThat(m.get("sha256")).isNull();
    }

    @Test
    @DisplayName("releases dizisi boş ya da yok → boş liste (schema alanı yine okunur)")
    void emptyReleasesArray() throws Exception {
        ReleaseIndexService s = new ReleaseIndexService(new MockEnvironment());
        s.loadFromString("{\"schema\":1,\"releases\":[]}", "empty");
        assertThat(s.all()).isEmpty();
        assertThat(s.loaded()).isFalse();
        assertThat(s.schema()).isEqualTo(1);

        s.loadFromString("{}", "no-array");
        assertThat(s.all()).isEmpty();
        assertThat(s.schema()).isZero();
    }

    @Test
    @DisplayName("load(): yapılandırılmış yol bozuk/yok → istisna fırlatmaz, meta tutarlı kalır")
    void load_brokenConfiguredPathDoesNotThrow(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("index.json");
        Files.writeString(broken, "{ bu json değil", StandardCharsets.UTF_8);
        MockEnvironment env = new MockEnvironment().withProperty("site.monitor.releases.path", broken.toString());
        ReleaseIndexService s = new ReleaseIndexService(env);
        assertThatCode(s::load).doesNotThrowAnyException();
        // Bozuk dosya ASLA kaynak olarak seçilmez; adaylardan biri çözülürse o, çözülmezse boş.
        assertThat(s.loadedFrom()).doesNotContain(broken.toString());
        assertThat(s.meta().get("loaded")).isEqualTo(s.loaded());
        assertThat(s.meta().get("count")).isEqualTo(s.all().size());

        MockEnvironment missing = new MockEnvironment()
                .withProperty("site.monitor.releases.path", dir.resolve("yok.json").toString());
        assertThatCode(() -> new ReleaseIndexService(missing).load()).doesNotThrowAnyException();
    }
}
