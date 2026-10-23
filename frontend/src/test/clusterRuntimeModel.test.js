import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { evalStateModel, sideJobsModel, EVAL_KINDS, EVAL_VERIFY_TONE, EVAL_UNKNOWN_REASONS } from '../components/admin/health/clusterRuntimeModel.js'
import { TR } from '../i18n/index.jsx'
import { EN } from '../i18n/en.js'
import { clusterModel } from '../components/admin/health/healthModel.js'

// Çok pod Sürüm 3-1 (2026-10-23, paket B): Küme kartının kalıcı değerlendirme durumu + yan iş modeli. Bilinmeyen ASLA yeşil değil.
const NOW = '2026-10-23T09:00:00'
const REC = { mode: 'verify', active: true, writer: 'sm-a-0000000a', writer_pod: 'sm-a', generation: 4, pending: 3, dropped: 0,
  flushes: 120, write_failures: 0, last_flush_at: '2026-10-23T08:59:58', last_failure_at: null, last_failure: null,
  table_missing: false, failing: false, inherited: null, last_verify: null }
const VERIFY = { observed_by: 'sm-b-0000000b', observed_by_pod: 'sm-b', observed_at: '2026-10-23T08:58:00', age_seconds: 120,
  stale_after_seconds: 900, stale: false, at: '2026-10-23T08:58:00', pod: 'sm-b-0000000b', generation: 4, status: 'ok', reason: null,
  kinds: {
    recovery: { compared: 40, mismatches: 0, skipped: 1, max_delta: null, measured: false },
    sibling: { compared: 12, mismatches: 0, skipped: 0, max_delta: null, measured: false },
    window_seed: { compared: 9, mismatches: 1, skipped: 0, max_delta: 0.12, measured: true },
  },
  pass_fail_mismatches: 0, first_mismatches: [] }
const H = (eval_state, extra = {}) => ({ timestamp: NOW, eval_state, ...extra })

describe('evalStateModel — kalıcı değerlendirme durumu (R3-1)', () => {
  it('eski sunucu alanı göndermez → null (satırlar gizlenir, çökme yok)', () => {
    expect(evalStateModel({})).toBeNull()
    expect(evalStateModel(null)).toBeNull()
    expect(evalStateModel({ eval_state: 'x' })).toBeNull()
    expect(sideJobsModel({})).toBeNull()
  })

  it('taze "ok" turu: doğrulama ok (yeşil yalnız burada), ölçüm türü uyuşmazlığı durumu bozmaz, türler sabit sırada', () => {
    const m = evalStateModel(H({ this_pod: REC, verify: VERIFY }))
    expect(m.verify.level).toBe('ok')
    expect(EVAL_VERIFY_TONE[m.verify.level]).toBe('success')
    expect(m.verify.mismatches).toBe(0)
    expect(m.verify.kinds.map((k) => k.kind)).toEqual(['recovery', 'sibling', 'window_seed'])
    expect(m.verify.kinds.find((k) => k.kind === 'window_seed')).toMatchObject({ measured: true, mismatches: 1, maxDelta: 0.12 })
    expect(m.verify.observedBy).toBe('sm-b')
    expect(m.recorderLevel).toBe('ok')
    expect(m.level).toBe('ok')
  })

  it('uyuşmazlık → uyarı; aynı tur eskiyse "eski" (gri, uyarı değil); bayatlık sunucu alanı yoksa sunucu saatine göre hesaplanır', () => {
    const mm = { ...VERIFY, status: 'mismatch', pass_fail_mismatches: 2, kinds: { ...VERIFY.kinds, sibling: { compared: 12, mismatches: 2, skipped: 0 } },
      first_mismatches: ['sibling:0123456789ab'] }
    const m = evalStateModel(H({ this_pod: REC, verify: mm }))
    expect(m.verify.level).toBe('mismatch')
    expect(m.level).toBe('warn')
    expect(m.verify.firstMismatches).toEqual(['sibling:0123456789ab'])
    expect(evalStateModel(H({ this_pod: REC, verify: { ...mm, stale: true } })).verify.level).toBe('stale')
    expect(evalStateModel(H({ this_pod: REC, verify: { ...mm, stale: true } })).level).toBe('unknown')
    const { stale, ...noFlag } = VERIFY
    expect(stale).toBe(false)
    expect(evalStateModel(H({ this_pod: REC, verify: { ...noFlag, observed_at: '2026-10-23T08:40:00' } })).verify.level).toBe('stale')
    expect(evalStateModel(H({ this_pod: REC, verify: { ...noFlag, observed_at: '2026-10-23T08:50:00' } })).verify.level).toBe('ok')
  })

  it('bilinmeyen tur / okunamadı / bozuk / hiç gözlem yok → asla yeşil, sayı yok (0 değil)', () => {
    const unknown = evalStateModel(H({ this_pod: REC, verify: { ...VERIFY, status: 'unknown', reason: 'flush_lag', pass_fail_mismatches: null } }))
    expect(unknown.verify).toMatchObject({ level: 'unknown', reason: 'flush_lag', mismatches: null, kinds: [] })
    expect(unknown.level).toBe('unknown')
    expect(evalStateModel(H({ this_pod: REC, verify: null, verify_error: 'BadSqlGrammarException' })).verify).toMatchObject({ level: 'unknown', reason: 'unreadable' })
    expect(evalStateModel(H({ error: 'IllegalStateException' })).verify.level).toBe('unknown')
    expect(evalStateModel(H({ this_pod: REC, verify: { ...VERIFY, status: 'bogus' } })).verify.level).toBe('unknown')
    const none = evalStateModel(H({ this_pod: REC, verify: null }))
    expect(none.verify.level).toBe('none')
    expect(EVAL_VERIFY_TONE.none).toBe('muted')
    for (const lvl of ['unknown', 'stale', 'none', 'off']) expect(EVAL_VERIFY_TONE[lvl]).not.toBe('success')
  })

  it('kip kapalı / yalnız yazma: doğrulama beklenmez → "kapalı" (gri), eski gözlem uyarı vermez', () => {
    expect(evalStateModel(H({ this_pod: { ...REC, mode: 'off' }, verify: null })).verify.level).toBe('off')
    expect(evalStateModel(H({ this_pod: { ...REC, mode: 'write' }, verify: { ...VERIFY, stale: true, status: 'mismatch' } })).verify.level).toBe('off')
    const off = evalStateModel(H({ this_pod: { ...REC, mode: 'off' }, verify: null }))
    expect(off.recorderLevel).toBe('off')
    expect(off.level).toBe('off')
  })

  it('kaydedici: şu an yazamıyor → uyarı; tablolar yok → bilinmiyor; durum yok (NOOP / okunamadı) → bilinmiyor', () => {
    expect(evalStateModel(H({ this_pod: { ...REC, failing: true }, verify: VERIFY })).recorderLevel).toBe('warn')
    expect(evalStateModel(H({ this_pod: { ...REC, failing: true }, verify: VERIFY })).level).toBe('warn')
    // failing alanı yoksa zamanlardan
    const { failing, ...noFailing } = REC
    expect(failing).toBe(false)
    expect(evalStateModel(H({ this_pod: { ...noFailing, last_failure_at: '2026-10-23T08:59:59' }, verify: VERIFY })).recorderLevel).toBe('warn')
    expect(evalStateModel(H({ this_pod: { ...noFailing, last_failure_at: '2026-10-23T08:50:00' }, verify: VERIFY })).recorderLevel).toBe('ok')
    expect(evalStateModel(H({ this_pod: { ...REC, table_missing: true }, verify: null })).recorderLevel).toBe('unknown')
    const noRec = evalStateModel(H({ this_pod: null, this_pod_error: 'IllegalStateException', verify: VERIFY }))
    expect(noRec).toMatchObject({ recorderKnown: false, recorderLevel: 'unknown', mode: null })
    expect(noRec.verify.level).toBe('ok')
  })

  it('bilinmeyen (yeni sürümün) tür sonda, ham adıyla; bilinen türler sırası sunucuyla aynı', () => {
    expect(EVAL_KINDS).toEqual(['recovery', 'sibling', 'suppression', 'round_seen', 'confirmation', 'descriptor', 'window_seed'])
    const m = evalStateModel(H({ this_pod: REC, verify: { ...VERIFY, kinds: { brand_new: { compared: 1, mismatches: 0 }, ...VERIFY.kinds } } }))
    expect(m.verify.kinds.map((k) => k.kind)).toEqual(['recovery', 'sibling', 'window_seed', 'brand_new'])
    expect(m.verify.kinds.at(-1)).toMatchObject({ known: false, measured: false })
  })
})

describe('sideJobsModel — bu pod’un yan işleri (R3-1)', () => {
  const SJ = { threads: 3, accepting: true, leader_yield_enabled: true, leader_hint: false, other_live_pod: null,
    running: 1, queued: 1, failed: 1, deferred_total: 5, yielded_total: 2, skipped_total: 0, jobs: [
      { name: 'nightly-cleanup', state: 'queued', runs: 0 },
      { name: 'cert-hourly', state: 'running', started_at: '2026-10-23T08:59:40', runs: 4, deferred_count: 5 },
      { name: 'tls-profile', state: 'idle', last_outcome: 'failed', last_finished_at: '2026-10-23T08:40:00', runs: 2, yielded_count: 2 },
    ] }

  it('özet, etkin işler (koşan önce), son koşusu hatalı işler; başka canlı pod bilinmiyorsa null', () => {
    const m = sideJobsModel({ side_jobs: SJ })
    expect(m).toMatchObject({ known: true, threads: 3, running: 1, queued: 1, deferred: 5, yielded: 2, otherLivePod: null, leaderYield: true })
    expect(m.active.map((j) => j.name)).toEqual(['cert-hourly', 'nightly-cleanup'])
    expect(m.failedJobs.map((j) => j.name)).toEqual(['tls-profile'])
  })

  it('özet sayaçları yoksa işlerden hesaplanır; okunamadı → known false', () => {
    const { running, queued, deferred_total, yielded_total, ...bare } = SJ
    expect([running, queued, deferred_total, yielded_total]).toEqual([1, 1, 5, 2])
    expect(sideJobsModel({ side_jobs: bare })).toMatchObject({ running: 1, queued: 1, deferred: 5, yielded: 2 })
    expect(sideJobsModel({ side_jobs: { error: 'IllegalStateException' } })).toEqual({ known: false })
  })
})

describe('clusterModel — R3-1 alanları', () => {
  const SL = { scheduler: { instance_id: 'sm-a-0000000a', sweep_leader: { owner: 'sm-b-0000000b', owner_pod: 'sm-b', until: NOW, expired: false, held_by_me: false } } }
  it('taze doğrulama uyuşmazlığı kartı uyarıya çeker; bilinmeyen doğrulama çekmez; tek pod dalında da modeller taşınır', () => {
    expect(clusterModel({ ...SL, ...H({ this_pod: REC, verify: VERIFY }) }).level).toBe('ok')
    expect(clusterModel({ ...SL, ...H({ this_pod: REC, verify: { ...VERIFY, status: 'mismatch', pass_fail_mismatches: 1 } }) }).level).toBe('warn')
    expect(clusterModel({ ...SL, ...H({ this_pod: REC, verify: null, verify_error: 'x' }) }).level).toBe('ok')
    const single = clusterModel(H({ this_pod: REC, verify: VERIFY }, { side_jobs: { threads: 0, jobs: [] } }))
    expect(single.available).toBe(false)
    expect(single.evalState.verify.level).toBe('ok')
    expect(single.sideJobs.known).toBe(true)
  })
})

// Entegrasyon (2026-10-23): sunucunun gönderebileceği her "bilinmiyor" nedeni çevrilir — ham kod ("neden: resync") gösterilmez.
describe('doğrulama nedenleri sunucu sabitleriyle eşleşir', () => {
  const evalDir = resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/evalstate')
  const read = (f) => readFileSync(resolve(evalDir, f), 'utf8')
  const constants = (src) => [...src.matchAll(/public static final String REASON_[A-Z_]+ = "([a-z_]+)"/g)].map((m) => m[1])
  const server = new Set([
    ...constants(read('EvalVerifyReport.java')),
    ...constants(read('EvalStateVerifier.java')),
    ...constants(read('EvalStateRecorder.java')),
    ...[...read('EvalStateVerifier.java').matchAll(/"(payload_too_large)"/g)].map((m) => m[1]),
  ])
  it('her sunucu nedeni modelde ve TR + EN çevirisi var', () => {
    expect(server.size).toBeGreaterThanOrEqual(7)
    for (const r of server) {
      expect(EVAL_UNKNOWN_REASONS, r).toContain(r)
      expect(TR[`hmp.esReason.${r}`], r).toBeTruthy()
      expect(EN[`hmp.esReason.${r}`], r).toBeTruthy()
    }
  })
})
