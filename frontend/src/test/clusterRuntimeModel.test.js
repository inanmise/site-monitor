import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { evalStateModel, sideJobsModel, otherPodState, EVAL_KINDS, EVAL_VERIFY_TONE, EVAL_UNKNOWN_REASONS } from '../components/admin/health/clusterRuntimeModel.js'
import { evalLoadModel, handoverModel, loadLevel, EVAL_LOAD_REASONS, EVAL_LOAD_EXPECTED_REASONS, EVAL_LOAD_OPERATIONAL_REASONS,
  EVAL_LOAD_OUTCOMES, EVAL_LOAD_TONE, HANDOVER_RESULTS, HANDOVER_NOT_READY, EVAL_LOAD_OBSERVATIONS_REASONS,
  HANDOVER_NOT_READY_FROM_REASON } from '../components/admin/health/clusterRuntimeModel.js'
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

  // R3-1 inceleme M2 (FX-OBS): DB kesintisinde liderliği kaybeden pod artık yazmıyor ve yeniden denemiyor — eski hatası kartı
  // günlerce uyarıya çekmemeli (sunucu `failing`'i yalnız yazarken verir; model ikinci emniyet)
  // R3-1 entegrasyon (inceleme durable #1): lider ama yazma kuşağını başka pod almış (çitlenmiş) → uyarı; sunucu `fenced`'i yalnız
  // bu pod liderken true gönderir; alan yoksa (eski sunucu) eski davranış
  it('çitlenmiş lider → kaydedici uyarı; fenced false / yok → normal; kapalı kipte kapalı', () => {
    const fenced = { ...REC, active: false, generation: null, fenced: true }
    expect(evalStateModel(H({ this_pod: fenced, verify: VERIFY })).recorderLevel).toBe('warn')
    expect(evalStateModel(H({ this_pod: fenced, verify: VERIFY })).level).toBe('warn')
    expect(evalStateModel(H({ this_pod: { ...fenced, fenced: false }, verify: VERIFY })).recorderLevel).toBe('ok')
    const { fenced: _f, ...noField } = fenced
    expect(evalStateModel(H({ this_pod: noField, verify: VERIFY })).recorderLevel).toBe('ok')
    expect(evalStateModel(H({ this_pod: { ...fenced, mode: 'off' } })).recorderLevel).toBe('off')
  })

  it('eski lider (yazmıyor) + eski hata → uyarı değil; aynı geçmiş yazan liderde → uyarı', () => {
    const former = { ...REC, active: false, generation: null, write_failures: 3, last_failure_at: '2026-10-23T08:30:00',
      last_flush_at: '2026-10-23T08:20:00', last_failure: 'CannotGetJdbcConnectionException' }
    expect(evalStateModel(H({ this_pod: { ...former, failing: true }, verify: VERIFY })).recorderLevel).toBe('ok')
    expect(evalStateModel(H({ this_pod: { ...former, failing: true }, verify: VERIFY })).level).toBe('ok')
    const { failing, ...noFlag } = former   // eski sunucu: failing yok → zamanlardan (son hata son yazımdan yeni)
    expect(failing).toBe(false)
    expect(evalStateModel(H({ this_pod: noFlag, verify: VERIFY })).recorderLevel).toBe('ok')
    expect(evalStateModel(H({ this_pod: { ...former, active: true, failing: true }, verify: VERIFY })).recorderLevel).toBe('warn')
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

  // R3-1 inceleme L4 (FX-OBS): "başka canlı pod" satırı takipçide "yalnız lider bakar", lider önbelleği boşken son görülen
  it('başka canlı pod: takipçi → yalnız lider; lider + boş önbellek → son görülen (zamanıyla) ya da henüz bakılmadı; erteleme kapalı → gösterilmez', () => {
    const at = '2026-10-23T08:57:00'
    expect(otherPodState(sideJobsModel({ side_jobs: SJ }))).toBe('leaderOnly')
    expect(otherPodState(sideJobsModel({ side_jobs: { ...SJ, leader_hint: true } }))).toBe('notChecked')
    const seen = sideJobsModel({ side_jobs: { ...SJ, leader_hint: true, other_live_pod_seen: { value: true, at } } })
    expect(seen.otherLivePodSeen).toEqual({ value: true, at })
    expect(otherPodState(seen)).toBe('seenYes')
    expect(otherPodState(sideJobsModel({ side_jobs: { ...SJ, leader_hint: true, other_live_pod_seen: { value: false, at } } }))).toBe('seenNo')
    expect(otherPodState(sideJobsModel({ side_jobs: { ...SJ, leader_hint: true, other_live_pod: true } }))).toBe('yes')
    expect(otherPodState(sideJobsModel({ side_jobs: { ...SJ, leader_hint: true, other_live_pod: false } }))).toBe('no')
    expect(otherPodState(sideJobsModel({ side_jobs: { ...SJ, leader_yield_enabled: false } }))).toBe('off')
    // Eski sunucu (lider bilgisi yok) → eski "bilinmiyor"; bozuk alan → yok sayılır
    const { leader_hint, ...old } = SJ
    expect(leader_hint).toBe(false)
    expect(otherPodState(sideJobsModel({ side_jobs: old }))).toBe('unknown')
    expect(sideJobsModel({ side_jobs: { ...SJ, other_live_pod_seen: { value: 'x' } } }).otherLivePodSeen).toBeNull()
    expect(otherPodState({ known: false })).toBe('unknown')
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
  it('R3-1 inceleme sözleşmesi: leadership_changed ve nothing_compared modelde ve TR + EN çevirili (ham kod basılmaz)', () => {
    for (const r of ['leadership_changed', 'nothing_compared']) {
      expect(EVAL_UNKNOWN_REASONS, r).toContain(r)
      expect(TR[`hmp.esReason.${r}`], r).toBeTruthy()
      expect(EN[`hmp.esReason.${r}`], r).toBeTruthy()
    }
  })
  // R3-1 inceleme düzeltmeleri (2026-10-23): iki yeni neden (FX-STATE sunucuda, FX-OBS çeviride) — liste İKİ YÖNDE eşit (sunucuda
  // olmayan ölü neden yok). FX-OBS dalı tek başına KIRMIZI (sunucu sabitleri FX-STATE'te); birleşince yeşil.
  it('model listesi sunucu sabitleriyle iki yönde eşit; yeni nedenler (leadership_changed, nothing_compared) sunucuda tanımlı', () => {
    expect(server.has('leadership_changed')).toBe(true)
    expect(server.has('nothing_compared')).toBe(true)
    for (const r of EVAL_UNKNOWN_REASONS) {
      expect(server.has(r), r).toBe(true)
      expect(TR[`hmp.esReason.${r}`], r).toBeTruthy()
      expect(EN[`hmp.esReason.${r}`], r).toBeTruthy()
    }
  })
})

// ── Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §8.3): devirde yüklenen durum + devir hazırlığı ─────────────

const LOAD_FULL = { at_ms: 1, at: '2026-10-28T08:59:30', pod: 'sm-b-0000000b', pod_name: 'sm-b', outcome: 'full', reason: null,
  attempted_tier: 'full', from_generation: 11, from_pod: 'sm-a-0000000a', from_pod_name: 'sm-a', handover_age_ms: 9000,
  durable_age_ms: 9000, recovery_counters: 3, siblings: 2, suppressions: 1, round_seen: 4, window_types: 2, window_domains: 40,
  read_ms: 85, max_wait_ms: 140, waiters: 2, observed_by: 'sm-b-0000000b', observed_by_pod: 'sm-b', observed_at: '2026-10-28T08:59:31' }
const loadNone = (reason) => ({ ...LOAD_FULL, outcome: 'none', reason, attempted_tier: null, recovery_counters: 0, siblings: 0,
  suppressions: 0, round_seen: 0, window_types: 0, window_domains: 0 })
const ES = (extra = {}) => ({ timestamp: NOW, eval_state: { this_pod: { ...REC, handover_ready: false, last_load: null, last_handover: null },
  verify: VERIFY, read_enabled: true, load: null, ...extra } })

describe('devir yükleme nedenleri sunucu sabitleriyle eşleşir (3-2)', () => {
  const evalDir = resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/evalstate')
  const src = readFileSync(resolve(evalDir, 'EvalLoadReport.java'), 'utf8')
  const consts = Object.fromEntries([...src.matchAll(/public static final String (REASON_[A-Z_]+|OUTCOME_[A-Z_]+) = "([a-z_]+)"/g)]
    .map((m) => [m[1], m[2]]))
  const listOf = (name) => {
    const m = src.match(new RegExp(`public static final List<String> ${name} = List\\.of\\(([^;]+)\\);`))
    expect(m, name).toBeTruthy()
    return [...m[1].matchAll(/(?:REASON|OUTCOME)_[A-Z_]+/g)].map((x) => consts[x[0]])
  }
  const reasons = Object.entries(consts).filter(([k]) => k.startsWith('REASON_')).map(([, v]) => v)

  it('model listesi Java REASONS ile iki yönde, AYNI sırada eşit; her kodun TR + EN metni var', () => {
    expect(reasons.length).toBeGreaterThanOrEqual(22)
    expect(new Set(EVAL_LOAD_REASONS)).toEqual(new Set(reasons))
    expect(EVAL_LOAD_REASONS).toEqual(listOf('REASONS'))
    for (const r of EVAL_LOAD_REASONS) {
      expect(TR[`hmp.esLoad.reason.${r}`], r).toBeTruthy()
      expect(EN[`hmp.esLoad.reason.${r}`], r).toBeTruthy()
    }
  })

  it('beklenen / işletim nedenleri ve sonuçlar sunucu listeleriyle aynı', () => {
    expect(EVAL_LOAD_EXPECTED_REASONS).toEqual(listOf('EXPECTED_REASONS'))
    expect(EVAL_LOAD_OPERATIONAL_REASONS).toEqual(listOf('OPERATIONAL_REASONS'))
    expect(EVAL_LOAD_OBSERVATIONS_REASONS).toEqual(listOf('OBSERVATIONS_REASONS'))
    expect(EVAL_LOAD_OUTCOMES).toEqual(listOf('OUTCOMES'))
    // inceleme 2026-10-28: bekleyen elle sonuç → yalnız gözlemler (bilgi), metin çevrili
    // L-6: "nasıl açılır" ipucu ayar grubunun GERÇEK adını söyler (Ayarlar → Genel → <grup>)
    expect(TR['hmp.esLoad.offHint']).toContain(TR['general.grp.monitoring'])
    expect(EN['hmp.esLoad.offHint']).toContain(EN['general.grp.monitoring'])
    expect(EVAL_LOAD_OBSERVATIONS_REASONS).toContain('pending_manual_results')
    expect(TR['hmp.esLoad.reason.pending_manual_results']).toMatch(/elle kontrol/)
    expect(EN['hmp.esLoad.reason.pending_manual_results']).toMatch(/manual check/)
  })

  it('devir boşaltması sonuçları Java HandoverResult ile iki yönde eşit; her sonuç ve hazır-değil nedeni TR + EN', () => {
    const hr = readFileSync(resolve(evalDir, 'HandoverResult.java'), 'utf8')
    const start = hr.indexOf('public enum HandoverResult {')
    const body = hr.slice(start, hr.indexOf('public String key()', start))
    const names = [...body.matchAll(/^\s{4}([A-Z_]+)[,;]\s*$/gm)].map((m) => m[1].toLowerCase())
    expect(names.length).toBe(8)
    expect(HANDOVER_RESULTS).toEqual(names)
    for (const k of HANDOVER_RESULTS) {
      expect(TR[`hmp.esLoad.handover.${k}`], k).toBeTruthy()
      expect(EN[`hmp.esLoad.handover.${k}`], k).toBeTruthy()
    }
    for (const r of HANDOVER_NOT_READY) {
      const key = HANDOVER_NOT_READY_FROM_REASON.includes(r) ? `hmp.esLoad.reason.${r}` : `hmp.esLoad.ready.${r}`
      expect(TR[key], r).toBeTruthy()
      expect(EN[key], r).toBeTruthy()
    }
  })
})

describe('evalLoadModel — devirde yüklenen durum (3-2)', () => {
  it('eski sunucu (alan yok) → null; kayıt hatası → null (doğrulama satırı zaten bilinmiyor)', () => {
    expect(evalLoadModel(H({ this_pod: REC, verify: VERIFY }))).toBeNull()
    expect(evalLoadModel(H({ error: 'X' }))).toBeNull()
    expect(evalLoadModel({})).toBeNull()
    expect(evalStateModel(H({ this_pod: REC, verify: VERIFY })).load).toBeNull()
  })

  it('tonlar: yalnız TAM yeşil; gözlemler bilgi; beklenen neden gri; işletim nedeni uyarı; kapalı / bilinmiyor / henüz yok gri', () => {
    expect(evalLoadModel(ES({ load: LOAD_FULL }))).toMatchObject({ level: 'full', tone: 'success', scope: 'cluster' })
    expect(evalLoadModel(ES({ load: { ...LOAD_FULL, outcome: 'observations', reason: 'no_handover' } })))
      .toMatchObject({ level: 'observations', tone: 'info', reason: 'no_handover', reasonKnown: true })
    // clock_skew: gözlemlerde bilgi, yüklenmedi'de uyarı (önce sonuç)
    expect(evalLoadModel(ES({ load: { ...LOAD_FULL, outcome: 'observations', reason: 'clock_skew' } })).tone).toBe('info')
    expect(evalLoadModel(ES({ load: { ...LOAD_FULL, outcome: 'observations', reason: 'pending_manual_results' } })))
      .toMatchObject({ level: 'observations', tone: 'info', reasonKnown: true })
    for (const r of EVAL_LOAD_EXPECTED_REASONS.filter((x) => x !== 'setting_off')) {
      expect(evalLoadModel(ES({ load: loadNone(r) })), r).toMatchObject({ level: 'none', tone: 'muted' })
    }
    for (const r of EVAL_LOAD_OPERATIONAL_REASONS) {
      expect(evalLoadModel(ES({ load: loadNone(r) })), r).toMatchObject({ level: 'warn', tone: 'warning' })
    }
    for (const r of ['no_handover', 'handover_too_old', 'intermediate_leader']) {
      expect(evalLoadModel(ES({ load: loadNone(r) })).tone, r).toBe('muted')
    }
    expect(evalLoadModel(ES({ load: { ...LOAD_FULL, outcome: 'brand_new' } }))).toMatchObject({ level: 'unknown', tone: 'muted' })
    expect(evalLoadModel(ES({ load: { ...LOAD_FULL, outcome: 'none', reason: 'future_reason' } })))
      .toMatchObject({ level: 'none', tone: 'muted', reasonKnown: false })
    expect(evalLoadModel(ES({ read_enabled: false, load: LOAD_FULL }))).toMatchObject({ level: 'off', tone: 'muted', reason: 'setting_off' })
    expect(evalLoadModel(ES({ load: null, load_error: 'BadSqlGrammarException' })))
      .toMatchObject({ level: 'unknown', error: true, tone: 'muted' })
    expect(evalLoadModel(ES())).toMatchObject({ level: 'empty', tone: 'muted' })
    for (const lvl of ['observations', 'none', 'warn', 'off', 'unknown', 'empty']) expect(EVAL_LOAD_TONE[lvl], lvl).not.toBe('success')
  })

  it('küme gözlemi yoksa bu pod\'un son yüklemesi ("Bu pod"); gözlem okunamadıysa bilinmiyor (son yükleme kullanılmaz)', () => {
    const podLoad = ES({ this_pod: { ...REC, handover_ready: false, last_load: loadNone('no_previous_generation'), last_handover: null } })
    podLoad.served_by_pod = 'sm-a'
    expect(evalLoadModel(podLoad)).toMatchObject({ level: 'none', scope: 'pod', podName: 'sm-a', reason: 'no_previous_generation' })
    const both = ES({ load: LOAD_FULL, this_pod: { ...REC, handover_ready: false, last_load: loadNone('timeout') } })
    expect(evalLoadModel(both)).toMatchObject({ level: 'full', scope: 'cluster' })
    const err = ES({ load: null, load_error: 'X', this_pod: { ...REC, handover_ready: false, last_load: LOAD_FULL } })
    expect(evalLoadModel(err)).toMatchObject({ level: 'unknown', error: true })
  })

  it('ayar şimdi açık ama son rapor "ayar kapalıydı" → yüklenmedi (gri), "Kapalı" değil; ayar alanı bilinmiyorsa rapordan', () => {
    expect(loadLevel({ outcome: 'none', reason: 'setting_off' }, true)).toBe('none')
    expect(loadLevel({ outcome: 'none', reason: 'setting_off' }, null)).toBe('off')
    expect(loadLevel(null, true)).toBe('unknown')
  })

  it('işletim nedenli yüklenmedi kalıcı durum ve kart seviyesini uyarıya çeker; bilinmeyen / beklenen çekmez', () => {
    expect(evalStateModel(ES({ load: loadNone('read_failed') })).level).toBe('warn')
    expect(evalStateModel(ES({ load: loadNone('writer_is_self') })).level).toBe('ok')
    expect(evalStateModel(ES({ load: null, load_error: 'X' })).level).toBe('ok')
    expect(evalStateModel(ES({ load: LOAD_FULL })).level).toBe('ok')
    const SL = { scheduler: { instance_id: 'sm-a-0000000a', sweep_leader: { owner: 'sm-b-0000000b', owner_pod: 'sm-b', until: NOW,
      expired: false, held_by_me: false } } }
    expect(clusterModel({ ...SL, ...ES({ load: loadNone('timeout') }) }).level).toBe('warn')
    expect(clusterModel({ ...SL, ...ES({ load: loadNone('previous_unmarked') }) }).level).toBe('ok')
  })
})

describe('evalLoadModel — eski devrin raporu (inceleme 2026-10-28, L-1)', () => {
  const SCHED = (owner, held, extra = {}) => ({ scheduler: { instance_id: 'sm-a-0000000a', sweep_leader: { owner, owner_pod: owner,
    until: NOW, expired: false, held_by_me: held, ...extra } } })
  const B_FULL = { ...LOAD_FULL, at_ms: 1_000, pod: 'sm-b-0000000b', pod_name: 'sm-b' }

  it('küme gözlemi şimdiki liderin değil → gri "güncel değil", yeşil değil; kart uyarıya çıkmaz', () => {
    const h = { ...SCHED('sm-c-0000000c', false), served_by: 'sm-a-0000000a', ...ES({ load: B_FULL }) }
    expect(evalLoadModel(h)).toMatchObject({ level: 'stale', tone: 'muted', scope: 'cluster', stale: true, reason: null })
    expect(evalLoadModel(h).report.podName).toBe('sm-b')
    expect(EVAL_LOAD_TONE.stale).not.toBe('success')
    expect(evalStateModel(h).level).not.toBe('warn')
  })

  it('küme gözlemi şimdiki liderin → olduğu gibi (TAM yeşil); lider bilinmiyor / boş / süresi dolmuş → olduğu gibi', () => {
    expect(evalLoadModel({ ...SCHED('sm-b-0000000b', false), ...ES({ load: B_FULL }) }).level).toBe('full')
    expect(evalLoadModel({ ...ES({ load: B_FULL }) }).level).toBe('full')
    expect(evalLoadModel({ ...SCHED(null, false), ...ES({ load: B_FULL }) }).level).toBe('full')
    expect(evalLoadModel({ ...SCHED('sm-c-0000000c', false, { expired: true }), ...ES({ load: B_FULL }) }).level).toBe('full')
    expect(evalLoadModel({ scheduler: { sweep_leader: { error: 'DataAccess' } }, ...ES({ load: B_FULL }) }).level).toBe('full')
    // raporda pod yoksa karşılaştırılamaz → olduğu gibi
    expect(evalLoadModel({ ...SCHED('sm-c-0000000c', false), ...ES({ load: { ...B_FULL, pod: null } }) }).level).toBe('full')
  })

  it('yanıtlayan pod şimdiki lider ve kendi son yüklemesi gözlemden YENİ → "Bu pod" ile kendi yüklemesi', () => {
    const own = { ...LOAD_FULL, at_ms: 5_000, pod: 'sm-c-0000000c', pod_name: 'sm-c', outcome: 'none', reason: 'mode_off' }
    const h = { ...SCHED('sm-c-0000000c', true), served_by: 'sm-c-0000000c', served_by_pod: 'sm-c',
      ...ES({ load: B_FULL, this_pod: { ...REC, handover_ready: false, last_load: own, last_handover: null } }) }
    expect(evalLoadModel(h)).toMatchObject({ level: 'none', scope: 'pod', reason: 'mode_off', stale: false })
    // aynı lider yeniden aldı (gözlem onun eski TAM'ı) ama kendi yeni yüklemesi yayımlanmadı → kendi yüklemesi
    const again = { ...SCHED('sm-b-0000000b', true), served_by: 'sm-b-0000000b',
      ...ES({ load: B_FULL, this_pod: { ...REC, handover_ready: false, last_load: { ...own, pod: 'sm-b-0000000b' }, last_handover: null } }) }
    expect(evalLoadModel(again)).toMatchObject({ level: 'none', scope: 'pod', reason: 'mode_off' })
    // kendi yüklemesi daha ESKİ → küme gözlemi (şimdiki liderin değilse güncel değil)
    const older = { ...SCHED('sm-c-0000000c', true), served_by: 'sm-c-0000000c',
      ...ES({ load: { ...B_FULL, at_ms: 9_000 }, this_pod: { ...REC, handover_ready: false, last_load: own, last_handover: null } }) }
    expect(evalLoadModel(older)).toMatchObject({ level: 'stale', scope: 'cluster' })
  })
})

describe('handoverModel — bu pod\'un devir hazırlığı (3-2)', () => {
  const H2 = (pod, extra = {}, scheduler) => ({ timestamp: NOW, ...(scheduler ? { scheduler } : {}),
    eval_state: { this_pod: { ...REC, handover_ready: false, last_load: null, last_handover: null, ...pod }, verify: VERIFY,
      read_enabled: true, ...extra } })
  const leaderSched = { sweep_leader: { owner: 'sm-a', held_by_me: true } }

  it('eski sunucu / kaydedici yok → null; hazır → yeşil', () => {
    expect(handoverModel(H({ this_pod: REC, verify: VERIFY }))).toBeNull()
    expect(handoverModel(H({ this_pod: null, verify: VERIFY, read_enabled: true }))).toBeNull()
    expect(handoverModel(H2({ handover_ready: true }, {}, leaderSched))).toMatchObject({ ready: true, reason: null, tone: 'success' })
  })

  it('hazır değil nedenleri sırayla: ayar → kip → lider değil → tablo → çit → kuşak → eşitleniyor', () => {
    expect(handoverModel(H2({}, { read_enabled: false }, leaderSched)).reason).toBe('setting_off')
    expect(handoverModel(H2({ mode: 'off' }, {}, leaderSched)).reason).toBe('mode_off')
    // L-3: kapanan pod "lider değil" değil, "pod kapanıyor" der (kapanırken lider ipucu düşer)
    expect(handoverModel(H2({ closing: true }, {}, { sweep_leader: { held_by_me: false } })).reason).toBe('recorder_closing')
    expect(handoverModel(H2({}, {}, { sweep_leader: { held_by_me: false } })).reason).toBe('not_leader')
    expect(handoverModel(H2({ table_missing: true }, {}, leaderSched)).reason).toBe('tables_missing')
    // L-3: işaret sütunları yok (ilk açılış ya da şema yaması düştü) — "eşitleniyor" DEĞİL
    expect(handoverModel(H2({ marker_columns_missing: true, generation: 7 }, {}, leaderSched)).reason).toBe('columns_missing')
    // bilinmiyor (kaydedici değil / eski sunucu) → o adım atlanır
    expect(handoverModel(H2({ marker_columns_missing: null, closing: null, generation: 7 }, {}, leaderSched)).reason)
      .toBe('incomplete')
    expect(handoverModel(H2({ fenced: true }, {}, leaderSched)).reason).toBe('fenced')
    expect(handoverModel(H2({ generation: null }, {}, leaderSched)).reason).toBe('no_generation')
    expect(handoverModel(H2({ generation: 7 }, {}, leaderSched))).toMatchObject({ reason: 'incomplete', tone: 'muted' })
    // tek pod dalı (kira yok): yazan kaydedici = lider; yazmıyorsa lider bilinmiyor → "lider değil" denmez
    expect(handoverModel(H2({ active: true, generation: 3 })).reason).toBe('incomplete')
    expect(handoverModel(H2({ active: false, generation: null })).reason).toBe('no_generation')
  })

  it('son devir boşaltması: bilinen sonuç çevrilir, bilinmeyen işaretlenir', () => {
    expect(handoverModel(H2({ last_handover: 'written' }, {}, leaderSched))).toMatchObject({ lastHandover: 'written', lastHandoverKnown: true })
    expect(handoverModel(H2({ last_handover: 'future_code' }, {}, leaderSched))).toMatchObject({ lastHandoverKnown: false })
  })
})
