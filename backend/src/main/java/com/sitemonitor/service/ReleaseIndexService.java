package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.util.Semver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Yayın indeksi — "hangi sürüm ne zaman çıktı, ne içeriyordu" (K5, 2026-09-10).
 *
 * <p>Kaynak {@code docs/releases/index.json}: CI her release'te {@code scripts/gen-release-index.mjs --append}
 * ile büyütür, Dockerfile imaja {@code /app/releases.json} olarak kopyalar. Bu servis açılışta BİR kez
 * okur; ÇALIŞMA ANINDA GitHub'a asla çıkmaz (kurum ağında egress yok). Dosya yok/bozuk → boş liste +
 * tek WARN; uygulama çökmez, ekran "yayın dizini yok" der.
 */
@Slf4j
@Service
public class ReleaseIndexService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String[] CANDIDATES = {
            "releases.json", "docs/releases/index.json", "../docs/releases/index.json", "/app/releases.json" };

    public record Change(String type, String scope, boolean breaking, String sha, String subject) {}

    public record Release(String version, String tag, String releasedAt, String commit, String prevVersion,
                          String bump, boolean breaking, Map<String, Integer> counts, List<Change> changes,
                          boolean truncated, int omitted) {}

    private final Environment env;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<Release> releases = List.of();
    private volatile Map<String, Release> byVersion = Map.of();
    private volatile String loadedFrom = "";
    private volatile String sha256 = "";
    private volatile String generatedAt = "";
    private volatile String loadedAt = "";
    private volatile int schema = 0;

    public ReleaseIndexService(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void load() {
        List<Path> paths = new ArrayList<>();
        String configured = env.getProperty("site.monitor.releases.path", "");
        if (configured != null && !configured.isBlank()) paths.add(Path.of(configured.trim()));
        for (String c : CANDIDATES) paths.add(Path.of(c));
        for (Path p : paths) {
            try {
                if (!Files.isReadable(p)) continue;
                byte[] bytes = Files.readAllBytes(p);
                JsonNode root = mapper.readTree(bytes);
                List<Release> parsed = parse(root);
                this.releases = List.copyOf(parsed);
                Map<String, Release> m = new HashMap<>();
                for (Release r : parsed) m.put(r.version(), r);
                this.byVersion = Map.copyOf(m);
                this.loadedFrom = p.toAbsolutePath().normalize().toString();
                this.sha256 = sha256(bytes);
                this.generatedAt = root.path("generatedAt").asText("");
                this.schema = root.path("schema").asInt(0);
                this.loadedAt = ISO.format(Instant.now());
                log.info("Release index loaded: {} release(s) from {} (schema {}, generated {})",
                        parsed.size(), loadedFrom, schema, generatedAt);
                return;
            } catch (Exception e) {
                log.warn("Release index unreadable at {}: {}", p, e.toString());
            }
        }
        log.warn("Release index not found (candidates: {}) — release timeline will be empty", String.join(", ", CANDIDATES));
    }

    private static List<Release> parse(JsonNode root) {
        List<Release> out = new ArrayList<>();
        for (JsonNode n : root.path("releases")) {
            String version = n.path("version").asText("");
            if (version.isBlank()) continue;
            Map<String, Integer> counts = new LinkedHashMap<>();
            n.path("counts").fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt(0)));
            List<Change> changes = new ArrayList<>();
            for (JsonNode c : n.path("changes")) {
                changes.add(new Change(c.path("type").asText("other"), c.path("scope").isNull() ? null : c.path("scope").asText(null),
                        c.path("breaking").asBoolean(false), c.path("sha").asText(""), c.path("subject").asText("")));
            }
            out.add(new Release(version, n.path("tag").asText("v" + version), n.path("releasedAt").asText(""),
                    n.path("commit").asText(""), n.path("prevVersion").isNull() ? null : n.path("prevVersion").asText(null),
                    n.path("bump").asText("patch"), n.path("breaking").asBoolean(false), Map.copyOf(counts),
                    List.copyOf(changes), n.path("truncated").asBoolean(false), n.path("omitted").asInt(0)));
        }
        out.sort((a, b) -> Semver.compare(b.version(), a.version()).orElse(0));
        return out;
    }

    /** Azalan semver sıralı, değişmez. */
    public List<Release> all() { return releases; }

    public Optional<Release> find(String version) {
        if (version == null) return Optional.empty();
        return Optional.ofNullable(byVersion.get(version.trim().replaceFirst("^v", "")));
    }

    public Optional<Release> latest() { return releases.isEmpty() ? Optional.empty() : Optional.of(releases.get(0)); }

    public boolean loaded() { return !releases.isEmpty(); }
    public String loadedFrom() { return loadedFrom; }
    public String sha256() { return sha256; }
    public String generatedAt() { return generatedAt; }
    public String loadedAt() { return loadedAt; }
    public int schema() { return schema; }

    /** Sistem Sağlığı "indeks tazeliği" bloğu. */
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded());
        m.put("count", releases.size());
        m.put("newestVersion", latest().map(Release::version).orElse(null));
        m.put("generatedAt", generatedAt.isBlank() ? null : generatedAt);
        m.put("loadedFrom", loadedFrom.isBlank() ? null : loadedFrom);
        m.put("sha256", sha256.isBlank() ? null : sha256.substring(0, Math.min(16, sha256.length())));
        m.put("schema", schema);
        return m;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Testler için: içeriği doğrudan yükle (dosya yolu gerekmeden). */
    void loadFromString(String json, String label) throws Exception {
        JsonNode root = mapper.readTree(json.getBytes(StandardCharsets.UTF_8));
        List<Release> parsed = parse(root);
        this.releases = List.copyOf(parsed);
        Map<String, Release> m = new HashMap<>();
        for (Release r : parsed) m.put(r.version(), r);
        this.byVersion = Map.copyOf(m);
        this.loadedFrom = label;
        this.sha256 = sha256(json.getBytes(StandardCharsets.UTF_8));
        this.generatedAt = root.path("generatedAt").asText("");
        this.schema = root.path("schema").asInt(0);
        this.loadedAt = ISO.format(Instant.now());
    }
}
