import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  certChunksModel, scanChunkModel, certRunTone, certRunFellBack, newerScanAfter, CERT_CHUNK_STATES, CERT_RUN_STATES, CERT_RUN_OUTCOMES,
  CERT_SWEEP_MODES, CERT_CHUNKS_TONE, CERT_MODE_TONE, CERT_CHUNK_OLDEST_WARN_MS, CERT_COORDINATOR_ALIVE_MS, CERT_NEWER_RUN_SLACK_MS,
} from '../components/admin/health/clusterRuntimeModel.js'
import { clusterModel } from '../components/admin/health/healthModel.js'
import { TR } from '../i18n/index.jsx'
import { EN } from '../i18n/en.js'

/**
 * Küme kartının çok pod Sürüm 3-4 modeli (2026-10-29, paket C1; tasarım R3-4 §7.2): parçalı sertifika taraması. Kapalı gri, açık
 * bilgi (ASLA yeşil değil), okunamayan bilinmiyor; en eski bekleyen > 30 sn / aykırı pod / ikinci görüş / sessiz koordinatör /
 * son tur FAILED uyarı (kartı uyarıya çeker); ABANDONED tek başına uyarı değil. Sabitler sunucuyla iki yönde eşit.
 */
const SWEEP = resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/certsweep')
const javaConsts = (file, prefix) => {
  const src = readFileSync(resolve(SWEEP, file), 'utf8')
  return [...src.matchAll(new RegExp(`String ${prefix}[A-Z_]+ = "([A-Za-z_]+)"`, 'g'))].map((m) => m[1])
}

const RUN = (over = {}) => ({
  run_id: 'r-open', kind: 'hourly', state: 'RUNNING', coordinator: 'pod-b-0000000b', coordinator_pod: 'pod-b', created_at: '2026-10-29T09:00:00',
  age_s: 45, heartbeat_age_s: 3, domains_total: 400, chunks_total: 20, chunk_size: 20, evict_caches: true,
  chunks: { pending: 6, claimed: 4, done: 9, cancelled: 1 }, domains_measured: 200, oldest_pending_age_s: 4, oldest_pending_age_ms: 4_000,
  per_pod: [
    { instance_id: 'pod-a-0000000a', pod: 'pod-a', self: true, chunks: 7, domains: 140, done_domains: 100, network_errors: 1, network_error_rate: 0.01, outlier: false },
    { instance_id: 'pod-b-0000000b', pod: 'pod-b', self: false, chunks: 6, domains: 120, done_domains: 100, network_errors: 0, network_error_rate: 0, outlier: false },
  ],
  outlier_pods: 0, takeovers: null, reclaims: null, second_opinions: null, ...over,
})
const LAST = (over = {}) => ({ run_id: 'r-1', kind: 'hourly', state: 'DONE', outcome: 'DECIDED', coordinator_pod: 'pod-b',
  created_at: '2026-10-29T08:00:00', finished_at: '2026-10-29T08:01:10', duration_ms: 70_000, domains_total: 400, chunks_total: 20,
  pods: 2, workers: [], takeovers: 0, reclaims: 0, second_opinions: 0, ...over })
const CC = (over = {}) => ({ enabled: true, tables_ready: true, at: '2026-10-29T09:00:45', db_clock_stale: false, run: RUN(),
  last_runs: [LAST()], min_errors: 3, error_rate_threshold: 0.5, last_mode: 'chunked',
  this_pod: { pod: 'pod-a', worker: { enabled: true, started: true, inflight_chunks: 2, inflight_domains: 40, budget: 40, leader_yielding: false,
    claimed: 9, completed: 7, late: 0, released: 0, discarded: 0, domains_measured: 140, last_claim_at: '2026-10-29T09:00:40', db_clock_stale: false },
  coordinator: { chunked_runs: 3, classic_fallback: 1, takeovers: 0, remeasured: 0, second_opinions: 0, abandoned: 0 } }, ...over })

describe('certChunksModel — parçalı sertifika taraması (R3-4)', () => {
  it('eski sunucu (alan yok) → null; kapalı → gri "off"; {error} → bilinmiyor', () => {
    expect(certChunksModel({})).toBeNull()
    expect(certChunksModel({ cert_chunks: 'x' })).toBeNull()
    const off = certChunksModel({ cert_chunks: { enabled: false } })
    expect(off).toMatchObject({ enabled: false, status: 'off', level: 'off', run: null })
    expect(CERT_CHUNKS_TONE[off.status]).toBe('muted')
    const err = certChunksModel({ cert_chunks: { error: 'IllegalStateException' } })
    expect(err).toMatchObject({ status: 'unknown', level: 'unknown' })
    expect(CERT_CHUNKS_TONE.unknown).toBe('muted')
  })

  it('açık koşu → "running" (bilgi, ASLA yeşil); ilerleme = DONE + devralınan; pod satırları; bu pod sayaçları', () => {
    const m = certChunksModel({ cert_chunks: CC() })
    expect(m.status).toBe('running')
    expect(m.level).toBe('on')
    expect(Object.values(CERT_CHUNKS_TONE)).not.toContain('success')
    expect(m.run.measuredChunks).toBe(10)
    expect(m.run.pods.map((p) => p.pod)).toEqual(['pod-a', 'pod-b'])
    expect(m.run.pods[0].self).toBe(true)
    expect(m.thisPod.worker.budget).toBe(40)
    expect(m.thisPod.coordinator.classicFallback).toBe(1)
    expect(m.lastMode).toBe('chunked')
    expect(m.warnings).toEqual([])
  })

  it('kuyruk işleniyor — uyarı yok (OB-M-1): bekleyen 18, ölçülen 5, en eski 45 sn → bekleyen uyarısı ve kutucuk tonu YOK', () => {
    const m = certChunksModel({ cert_chunks: CC({ run: RUN({ chunks: { pending: 18, claimed: 5, done: 10, cancelled: 0 },
      oldest_pending_age_ms: 45_000, oldest_pending_age_s: 45 }) }) })
    expect(m.run.pendingWarn).toBe(false)
    expect(m.warnings).toEqual([])
    expect(m.status).toBe('running')
  })

  it('kimse almıyor (OB-M-1): bekleyen 5, ölçülen 0, en eski 45 sn → uyarı; tam 30 sn ya da bekleyen 0 → uyarı yok', () => {
    const idleQueue = { pending: 5, claimed: 0, done: 10, cancelled: 0 }
    const pend = certChunksModel({ cert_chunks: CC({ run: RUN({ chunks: idleQueue, oldest_pending_age_ms: 45_000, oldest_pending_age_s: 45 }) }) })
    expect(pend.status).toBe('warn')
    expect(pend.warnings).toEqual(['pending'])
    expect(pend.run.pendingWarn).toBe(true)
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ chunks: idleQueue, oldest_pending_age_ms: CERT_CHUNK_OLDEST_WARN_MS }) }) }).status).toBe('running')
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ chunks: { ...idleQueue, pending: 0 }, oldest_pending_age_ms: 45_000 }) }) }).run.pendingWarn).toBe(false)
  })

  it('uyarılar: aykırı pod, ikinci görüş > 0, koordinatör nabzı > 60 sn, son tur FAILED → warn', () => {
    const outl = certChunksModel({ cert_chunks: CC({ run: RUN({ per_pod: [{ ...RUN().per_pod[0], outlier: true }] }) }) })
    expect(outl.warnings).toEqual(['outlier'])
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ second_opinions: 12 }) }) }).warnings).toEqual(['secondOpinion'])
    const silent = certChunksModel({ cert_chunks: CC({ run: RUN({ heartbeat_age_s: CERT_COORDINATOR_ALIVE_MS / 1000 + 1 }) }) })
    expect(silent.warnings).toEqual(['coordinatorSilent'])
    const failed = certChunksModel({ cert_chunks: CC({ run: null, last_runs: [LAST({ state: 'FAILED', outcome: 'FAILED' }), LAST()] }) })
    expect(failed.status).toBe('warn')
    expect(failed.warnings).toEqual(['lastFailed'])
    // ABANDONED tek başına uyarı DEĞİL (dağıtımda olağan) — satır uyarı tonunda
    const ab = certChunksModel({ cert_chunks: CC({ run: null, last_runs: [LAST({ state: 'ABANDONED', outcome: 'SHUTDOWN' })] }) })
    expect(ab.status).toBe('idle')
    expect(ab.lastRuns[0].tone).toBe('warning')
    // oldest_pending_age_ms yoksa saniyeden
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ chunks: { pending: 3, claimed: 0, done: 0, cancelled: 0 },
      oldest_pending_age_ms: undefined, oldest_pending_age_s: 31 }) }) }).run.pendingWarn).toBe(true)
  })

  it('sahipsiz tur (DECIDING dahil) uyarı değil, açık tur değil (OB-L1): ilerleme / bekleyen / sessiz koordinatör uyarısı yok, durum idle', () => {
    const stalled = RUN({ orphan_suspected: true, coordinator_live: false, heartbeat_age_s: 3_600, age_s: 3_660,
      chunks: { pending: 9, claimed: 0, done: 11, cancelled: 0 }, oldest_pending_age_ms: 3_600_000, second_opinions: null,
      per_pod: [{ ...RUN().per_pod[0], outlier: true }] })
    const m = certChunksModel({ cert_chunks: CC({ run: stalled }) })
    expect(m.run.orphan).toBe(true)
    expect(m.run.pendingWarn).toBe(false)
    expect(m.run.coordinatorSilent).toBe(false)
    expect(m.warnings).toEqual([])
    expect(m.status).toBe('idle')
    expect(m.level).toBe('on')
    const deadDeciding = certChunksModel({ cert_chunks: CC({ run: RUN({ state: 'DECIDING', orphan_suspected: true, coordinator_live: false,
      heartbeat_age_s: 7_200, chunks: { pending: 0, claimed: 0, done: 20, cancelled: 0 } }) }) })
    expect(deadDeciding.run.orphan).toBe(true)
    expect(deadDeciding.run.deciding).toBe(false)
    expect(deadDeciding.status).toBe('idle')
    // tarama kartı
    expect(scanChunkModel({ last_mode: 'classic', chunked: { chunks_done: 3, chunks_total: 20, orphan_suspected: true, coordinator_pod: 'pod-b' } }).chunked.orphan).toBe(true)
    expect(scanChunkModel({ last_mode: 'classic', chunked: { chunks_done: 3, chunks_total: 20 } }).chunked.orphan).toBe(false)
  })

  it('kilidi tutan koordinatör sessiz sayılmaz (OB-L5): coordinator_live true → nabız eski olsa da uyarı yok; alan yoksa scheduler.running_on', () => {
    const old = { heartbeat_age_s: 300 }
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ ...old, coordinator_live: true }) }) }).warnings).toEqual([])
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ ...old, coordinator_live: null }) }) }).warnings).toEqual(['coordinatorSilent'])
    // eski yanıt (alan yok): zamanlayıcının canlı kilit sahibi koordinatörse canlı
    expect(certChunksModel({ scheduler: { running_on: 'pod-b-0000000b' }, cert_chunks: CC({ run: RUN(old) }) }).warnings).toEqual([])
    expect(certChunksModel({ scheduler: { running_on: 'pod-c-0000000c' }, cert_chunks: CC({ run: RUN(old) }) }).warnings).toEqual(['coordinatorSilent'])
  })

  it('son tur FAILED uyarısı yalnız ARDINDAN daha yeni bir tarama turu bitmediyse (OB-L1); yerel / bozuk zaman → uyarı sürer', () => {
    const failed = (scan) => certChunksModel({ scan, cert_chunks: CC({ run: null, last_runs: [LAST({ state: 'FAILED', outcome: 'FAILED',
      finished_at: '2026-10-29T08:01:10' })] }) })
    expect(failed(undefined).warnings).toEqual(['lastFailed'])
    expect(failed({ source: 'cluster', last_run: '2026-10-29T08:01:30' }).warnings).toEqual(['lastFailed'])
    expect(failed({ source: 'cluster', last_run: '2026-10-29T08:30:00' }).warnings).toEqual([])
    expect(failed({ source: 'cluster', last_run: '2026-10-29T08:30:00' }).status).toBe('idle')
    expect(failed({ source: 'local', last_run: '2026-10-29T08:30:00' }).warnings).toEqual(['lastFailed'])
    expect(failed({ source: 'cluster', last_run: 'bozuk' }).warnings).toEqual(['lastFailed'])
    expect(newerScanAfter('2026-10-29T08:00:00', { last_run: '2026-10-29T08:01:01' })).toBe(true)
    expect(newerScanAfter('2026-10-29T08:00:00', { last_run: '2026-10-29T08:01:00' })).toBe(false)
    expect(CERT_NEWER_RUN_SLACK_MS).toBe(60_000)
  })

  it('ikinci görüş uyarısı daha yeni tur gelene kadar sürer (OB-L4): kapanmış son tur > 0 → uyarı (açık RUNNING turda sayı yokken de); '
    + 'açık turun sayısı biliniyorsa o karar verir; daha yeni tur → kalkar', () => {
    const lastSo = [LAST({ second_opinions: 14, finished_at: '2026-10-29T08:01:10' })]
    expect(certChunksModel({ cert_chunks: CC({ run: null, last_runs: lastSo }) }).warnings).toEqual(['secondOpinionLast'])
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ second_opinions: null }), last_runs: lastSo }) }).warnings).toEqual(['secondOpinionLast'])
    expect(certChunksModel({ cert_chunks: CC({ run: RUN({ state: 'DECIDING', second_opinions: 0 }), last_runs: lastSo }) }).warnings).toEqual([])
    expect(certChunksModel({ scan: { source: 'cluster', last_run: '2026-10-29T09:00:00' }, cert_chunks: CC({ run: null, last_runs: lastSo }) }).warnings).toEqual([])
    expect(certChunksModel({ cert_chunks: CC({ run: null, last_runs: [LAST({ second_opinions: 0 })] }) }).warnings).toEqual([])
    const m = certChunksModel({ cert_chunks: CC({ run: null, last_runs: lastSo }) })
    expect(m.status).toBe('warn')
  })

  it('koordinatör sayacı bölünmüş (OB-L6): classic_by_rule varsa ayrı; eski yanıtta null (bugünkü tek satır)', () => {
    const split = certChunksModel({ cert_chunks: CC({ this_pod: { ...CC().this_pod, coordinator: { ...CC().this_pod.coordinator,
      classic_by_rule: 280, classic_fallback: 2 } } }) })
    expect(split.thisPod.coordinator.classicByRule).toBe(280)
    expect(split.thisPod.coordinator.classicFallback).toBe(2)
    expect(certChunksModel({ cert_chunks: CC() }).thisPod.coordinator.classicByRule).toBeNull()
  })

  it('karar aşaması (DECIDING): koordinatör nabız yazmaz → eski nabız uyarı DEĞİL; "karar veriliyor" bayrağı (paket B notu)', () => {
    const m = certChunksModel({ cert_chunks: CC({ run: RUN({ state: 'DECIDING', heartbeat_age_s: 300,
      chunks: { pending: 0, claimed: 0, done: 19, cancelled: 1 }, oldest_pending_age_ms: null, oldest_pending_age_s: null }) }) })
    expect(m.run.deciding).toBe(true)
    expect(m.run.coordinatorSilent).toBe(false)
    expect(m.warnings).toEqual([])
    expect(m.status).toBe('running')
    expect(m.run.measuredChunks).toBe(20)
  })

  it('durum okunamadı → bilinmiyor (gri), koşu ve son turlar YOK (asla "tur yok" ya da yeşil); açık tur yok → "idle"', () => {
    const bad = certChunksModel({ cert_chunks: { enabled: true, status_error: 'QueryTimeoutException', run: null, last_runs: null, last_mode: 'unknown', this_pod: { pod: 'a', worker: null, coordinator: null } } })
    expect(bad).toMatchObject({ status: 'unknown', level: 'unknown', statusError: 'QueryTimeoutException', run: null, lastRuns: [], lastMode: 'unknown' })
    expect(bad.thisPod.worker).toBeNull()
    const idle = certChunksModel({ cert_chunks: CC({ run: null }) })
    expect(idle.status).toBe('idle')
    expect(CERT_CHUNKS_TONE.idle).toBe('info')
    expect(certChunksModel({ cert_chunks: CC({ last_mode: 'nonsense' }) }).lastMode).toBe('unknown')
  })

  it('koşu tonları: DONE + DECIDED yeşil, DONE + STORE_FAILED bilgi, FAILED / ABANDONED uyarı, bilinmeyen gri; kip tonları', () => {
    expect(certRunTone('DONE', 'DECIDED')).toBe('success')
    expect(certRunTone('DONE', 'STORE_FAILED')).toBe('info')
    expect(certRunTone('DONE', null)).toBe('muted')
    expect(certRunTone('FAILED', 'FAILED')).toBe('warning')
    expect(certRunTone('ABANDONED', 'ORPHANED')).toBe('warning')
    // OB-L3: iç hata → klasik toplamaya dönen tur bilgi tonu (sonuçlar kaydedildi, karar verildi)
    expect(certRunTone('ABANDONED', 'FAILED')).toBe('info')
    expect(certRunFellBack('ABANDONED', 'FAILED')).toBe(true)
    expect(certRunFellBack('FAILED', 'FAILED')).toBe(false)
    expect(certChunksModel({ cert_chunks: CC({ run: null, last_runs: [LAST({ state: 'ABANDONED', outcome: 'FAILED' })] }) }).lastRuns[0])
      .toMatchObject({ fellBack: true, tone: 'info' })
    expect(certChunksModel({ cert_chunks: CC({ run: null, last_runs: [LAST({ state: 'ABANDONED', outcome: 'FAILED' })] }) }).warnings).toEqual([])
    expect(certRunTone('???', null)).toBe('muted')
    expect(CERT_MODE_TONE).toEqual({ chunked: 'info', classic: 'muted', unknown: 'muted' })
  })

  it('Küme kartı seviyesi: parçalı tarama uyarısı kartı uyarıya çeker; bilgi / kapalı / bilinmiyor çekmez', () => {
    const base = { timestamp: '2026-10-29T09:00:45', scheduler: { sweep_leader: { owner: 'pod-b-0000000b', owner_pod: 'pod-b', until: '2026-10-29T09:03:00',
      expired: false, held_by_me: false }, held_locks: [], skipped_rounds: [] } }
    expect(clusterModel({ ...base, cert_chunks: CC() }).level).toBe('ok')
    expect(clusterModel({ ...base, cert_chunks: CC({ run: RUN({ second_opinions: 2 }) }) }).level).toBe('warn')
    expect(clusterModel({ ...base, cert_chunks: { enabled: true, status_error: 'x' } }).level).toBe('ok')
    expect(clusterModel({ ...base, cert_chunks: { enabled: false } }).certChunks.status).toBe('off')
    expect(clusterModel({ cert_chunks: CC() }).certChunks.status).toBe('running')
  })

  it('scanChunkModel: last_mode yoksa (eski sunucu / kapalı) null; açık tur ilerlemesi ve pod payı', () => {
    expect(scanChunkModel({ last_run: 'x' })).toBeNull()
    expect(scanChunkModel(null)).toBeNull()
    const m = scanChunkModel({ last_mode: 'classic', chunked: { chunks_done: 4, chunks_total: 20, domains_measured: 80, domains_total: 400,
      per_pod: [{ pod: 'pod-a', domains: 60 }, { pod: 'pod-b', domains: 20 }] } })
    expect(m.mode).toBe('classic')
    expect(m.chunked.pods).toEqual([{ pod: 'pod-a', domains: 60 }, { pod: 'pod-b', domains: 20 }])
    expect(scanChunkModel({ last_mode: 'x', chunked: null, chunked_error: 'E' })).toEqual({ mode: 'unknown', error: true, chunked: null })
  })

  it('sabitler sunucuyla İKİ YÖNDE eşit: parça / koşu durumları, sonuçlar (CertSweepStore), kipler (CertSweepView)', () => {
    expect(javaConsts('CertSweepStore.java', 'CHUNK_').map((s) => s.toLowerCase())).toEqual(CERT_CHUNK_STATES)
    expect(javaConsts('CertSweepStore.java', 'RUN_')).toEqual(CERT_RUN_STATES)
    expect(javaConsts('CertSweepStore.java', 'OUTCOME_')).toEqual(CERT_RUN_OUTCOMES)
    expect(javaConsts('CertSweepView.java', 'MODE_')).toEqual(CERT_SWEEP_MODES)
  })

  it('her sonuç ve koşu durumu TR + EN çevrilir (ham kod basılmaz)', () => {
    for (const o of CERT_RUN_OUTCOMES) {
      expect(TR[`hmp.cc.outcome.${o}`], o).toBeTruthy()
      expect(EN[`hmp.cc.outcome.${o}`], o).toBeTruthy()
    }
    for (const s of ['DONE', 'FAILED', 'ABANDONED', 'other']) {
      expect(TR[`hmp.cc.state.${s}`], s).toBeTruthy()
      expect(EN[`hmp.cc.state.${s}`], s).toBeTruthy()
    }
    for (const m of CERT_SWEEP_MODES) expect(EN[`hmp.cc.mode.${m}`], m).toBeTruthy()
    // OB-L3: FAILED hangi adımda koptuğunu iddia etmez; klasiğe dönen kendi metniyle
    expect(TR['hmp.cc.outcome.FAILED']).not.toMatch(/karar/)
    expect(EN['hmp.cc.outcome.FAILED']).not.toMatch(/decid/)
    expect(TR['hmp.cc.warn.lastRunBad']).not.toMatch(/karar aşaması/)
    expect(EN['hmp.cc.warn.lastRunBad']).not.toMatch(/decision step/)
    expect(TR['hmp.cc.outcome.fellBack']).toMatch(/klasik/)
    expect(EN['hmp.cc.outcome.fellBack']).toMatch(/classic/)
  })
})
