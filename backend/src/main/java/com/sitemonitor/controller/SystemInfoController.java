package com.sitemonitor.controller;

import com.sitemonitor.service.BuildInfo;
import com.sitemonitor.service.DeploymentHistoryService;
import com.sitemonitor.service.ReleaseIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Kimlik doğrulamalı HERKESE açık sürüm yüzeyi (K9): Nav sürüm çipi popover'ı ve Yardım → Yenilikler.
 * AuthInterceptor /api/** için oturum ister; PUBLIC listesinde DEĞİL (commit/ortam giriş öncesi sızmaz).
 * Yanıtlar diğer /api uçları gibi no-store (WebConfigTest.publicEndpoints_areNoStore).
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemInfoController {

    private final BuildInfo buildInfo;
    private final ReleaseIndexService releaseIndex;
    private final DeploymentHistoryService deployments;

    /** "En son geçerli sürüm hangisi ve ne zaman devreye alındı?" — tek yanıt. */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        BuildInfo.Snapshot b = buildInfo.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", b.version());
        m.put("versionSource", b.versionSource());
        m.put("commit", nul(b.commit()));
        m.put("commitShort", nul(b.commitShort()));
        m.put("buildTime", nul(b.buildTime()));
        m.put("imageVersion", nul(b.imageVersion()));
        m.put("imageRef", nul(b.imageRef()));
        m.put("mismatch", b.mismatch());
        m.put("environment", b.environment());
        Map<String, Object> helm = new LinkedHashMap<>();
        helm.put("release", nul(b.helmRelease()));
        helm.put("revision", b.helmRevision());
        helm.put("chartVersion", nul(b.helmChartVersion()));
        m.put("helm", helm);
        Map<String, Object> inst = new LinkedHashMap<>();
        inst.put("id", b.instanceId());
        inst.put("hostname", b.hostname());
        inst.put("pod", nul(b.podName()));
        inst.put("node", nul(b.nodeName()));
        m.put("instance", inst);
        m.put("startedAt", b.jvmStartedAt());
        m.put("uptimeSeconds", b.uptimeSeconds());
        DeploymentHistoryService.Current c = deployments.current(b.environment());
        m.put("live", c.version() == null ? null : deployments.currentToMap(c));
        Optional<ReleaseIndexService.Release> rel = releaseIndex.find(b.version());
        if (rel.isPresent()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("version", rel.get().version());
            r.put("releasedAt", rel.get().releasedAt());
            r.put("bump", rel.get().bump());
            r.put("breaking", rel.get().breaking());
            r.put("counts", rel.get().counts());
            List<Map<String, Object>> hl = new ArrayList<>();
            rel.get().changes().stream()
                    .sorted(Comparator.comparingInt(ch -> "feat".equals(ch.type()) ? 0 : "fix".equals(ch.type()) ? 1 : 2))
                    .limit(3)
                    .forEach(ch -> { Map<String, Object> h = new LinkedHashMap<>(); h.put("type", ch.type()); h.put("scope", ch.scope()); h.put("subject", ch.subject()); hl.add(h); });
            r.put("highlights", hl);
            m.put("release", r);
            m.put("releaseLagSeconds", c.liveSince() == null ? null : deployments.leadTimeSeconds(b.version(), c.liveSince()));
        } else {
            m.put("release", null);
            m.put("releaseLagSeconds", null);
        }
        m.put("releaseIndex", releaseIndex.meta());
        return ResponseEntity.ok(Map.of("success", true, "data", m));
    }

    /** Yayın listesi (Yardım → Yenilikler). density=deployed|all, type=feat|fix|…|all. */
    @GetMapping("/releases")
    public ResponseEntity<Map<String, Object>> releases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "deployed") String density,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) String q) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, DeploymentHistoryService.MAX_PAGE));
        String d = "all".equals(density) ? "all" : "deployed";
        return ResponseEntity.ok(Map.of("success", true,
                "data", deployments.releases(buildInfo.get().environment(), d, type, q, p, s)));
    }

    /** E1: "son ziyaretinden beri" — since sürümünden sonra çıkanlar (koşan sürüme kadar). */
    @GetMapping("/releases/notes")
    public ResponseEntity<Map<String, Object>> notes(@RequestParam(required = false) String since) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentVersion", buildInfo.get().version());
        m.put("since", since);
        m.put("items", deployments.notesSince(since));
        return ResponseEntity.ok(Map.of("success", true, "data", m));
    }

    private static String nul(String s) { return s == null || s.isBlank() ? null : s; }
}
