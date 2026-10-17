import { describe, it, expect } from 'vitest'
import {
  bucketMinutes, bucketStarts, clusterRows, fmtRate, httpRows, isNotWriting, podShare, latestValue, logCacheRows, normalizeRange, overviewKpis, perPodRows, podSeries, podState,
  seriesPods, sortPods, toneOf, uptimeText, utcMs,
} from '../components/admin/metrics/metricsModel.js'

/**
 * Sistem Sağlığı → Metrikler modeli (2026-10-13): UTC zaman okuma, kova ekseni (örneksiz kova null — boşluk), pod
 * serileri güvenli anahtarla, küme toplamları (sıfır uydurulmaz), canlı pod / bilinmiyor tonları (bilinmeyen asla yeşil değil).
 */
const t = (k, ...a) => `${k}(${a.join(',')})`
const series = {
  range: '1h', bucket: '1m', from: '2031-04-02T12:00:30', to: '2031-04-02T12:03:30', instance: null,
  pods: [{ id: 'b.host:1', name: 'pod-b', live: true }, { id: 'a.host:1', name: 'pod-a', live: false }],
  points: [
    { pod: 'a.host:1', t: '2031-04-02T12:01:00', samples: 1, delta_minutes: 1, http_minutes: 1, cpu_process_avg: 10, http_requests: 60, http_errors: 3, http_sum_ms: 600, http_rpm: 60, http_p95_ms: 40, log_warn: 2, log_error: 0, cache_hits: 9, cache_misses: 1, caller_runs: 0 },
    { pod: 'b.host:1', t: '2031-04-02T12:01:00', samples: 1, delta_minutes: 1, http_minutes: 1, cpu_process_avg: 30, http_requests: 40, http_errors: 0, http_sum_ms: 1400, http_rpm: 40, http_p95_ms: 90, log_warn: 1, log_error: 1, cache_hits: null, cache_misses: null },
    { pod: 'b.host:1', t: '2031-04-02T12:03:00', samples: 1, cpu_process_avg: 50 },
  ],
}

describe('metricsModel', () => {
  it('aralık doğrulama ve UTC okuma', () => {
    expect(normalizeRange('7d')).toBe('7d')
    expect(normalizeRange('2d')).toBe('6h')
    expect(normalizeRange(undefined)).toBe('6h')
    expect(utcMs('2031-04-02T12:01:00')).toBe(Date.UTC(2031, 3, 2, 12, 1, 0))
    expect(utcMs('2031-04-02T12:01:00Z')).toBe(Date.UTC(2031, 3, 2, 12, 1, 0))
    expect(utcMs('bozuk')).toBeNull()
    expect(utcMs(null)).toBeNull()
  })

  it('kova ekseni: aralığın her dakikası bir satır; tavanlı; bozuk girdi boş', () => {
    const b = bucketStarts(series)
    expect(b).toEqual([0, 1, 2, 3].map((m) => Date.UTC(2031, 3, 2, 12, m, 0)))
    expect(bucketStarts({ ...series, bucket: 'x' })).toEqual([])
    expect(bucketStarts({ ...series, from: '2031-04-02T13:00:00' })).toEqual([])
    expect(bucketStarts({ bucket: '1m', from: '2000-01-01T00:00:00', to: '2031-01-01T00:00:00' })).toHaveLength(2000)
  })

  it('podlar: SUNUCU sırası korunur (ada göre yeniden dizilmez), güvenli anahtar (nokta / iki nokta CSS adına girmez), tek pod süzgeci', () => {
    const pods = seriesPods(series)
    expect(pods.map((p) => p.name), 'sunucu canlı pod-b\'yi önde verdi').toEqual(['pod-b', 'pod-a'])
    const ps = podSeries(pods)
    expect(ps.map((s) => s.key)).toEqual(['p0', 'p1'])
    expect(ps.map((s) => s.podId), 'renk / anahtar listenin sırasını izler: p0 ilk pod').toEqual(['b.host:1', 'a.host:1'])
    expect(seriesPods({ ...series, instance: 'b.host:1' }).map((p) => p.id)).toEqual(['b.host:1'])
    expect(seriesPods({ ...series, instance: 'yok' })).toEqual([])
    expect(seriesPods({ pods: [], points: [{ pod: 'eski', t: '2031-04-02T12:01:00' }] })[0]).toMatchObject({ id: 'eski', live: false })
    // Yalnız noktada görünen podlar listenin SONUNA, ada göre; bir kez
    const extra = seriesPods({ pods: [{ id: 'z', name: 'z-pod', live: true }],
      points: [{ pod: 'y' }, { pod: 'x' }, { pod: 'y' }, { pod: 'z' }] })
    expect(extra.map((p) => p.id)).toEqual(['z', 'x', 'y'])
    expect(seriesPods({ ...series, pods: [], instance: 'b.host:1' }).map((p) => p.id), 'tek pod süzgeci yalnız noktadaki podu da bulur').toEqual(['b.host:1'])
  })

  it('sortPods: canlı → sessiz → durdu; aynı durumda en yeni süreç (first_seen) önce, bilinmeyen sonda; eski sunucuda canlı önde', () => {
    const list = [
      { id: 'stop', name: 'pod-stop', state: 'stopped', first_seen: '2031-04-02T11:58:00', last_seen: '2031-04-02T11:59:00' },
      { id: 'old', name: 'pod-old', state: 'live', first_seen: '2031-03-30T08:00:00', last_seen: '2031-04-02T11:59:00' },
      { id: 's1', name: 'pod-silent', state: 'silent', first_seen: '2031-04-02T11:30:00', last_seen: '2031-04-02T11:40:00' },
      { id: 'nofirst', name: 'pod-a', state: 'live', last_seen: '2031-04-02T11:59:00' },
      { id: 'new', name: 'pod-new', state: 'live', first_seen: '2031-04-02T11:20:00', last_seen: '2031-04-02T11:59:00' },
      { id: 's2', name: 'pod-silent-old', state: 'silent', first_seen: '2031-04-01T12:30:00', last_seen: '2031-04-02T11:50:00' },
    ]
    const before = list.map((p) => p.id)
    expect(sortPods(list).map((p) => p.id)).toEqual(['new', 'old', 'nofirst', 's1', 's2', 'stop'])
    expect(list.map((p) => p.id), 'girdi değişmez').toEqual(before)
    // Eski sunucu: first_seen yok, `state` yok (yalnız `live`) → canlılar önde, sonra son örnek, sonra ad
    const legacy = [
      { id: 'b', name: 'pod-b', live: false, last_seen: '2031-04-02T10:00:00' },
      { id: 'c', name: 'pod-c', live: true, last_seen: '2031-04-02T11:59:00' },
      { id: 'a', name: 'pod-a', live: true, last_seen: '2031-04-02T11:59:00' },
    ]
    expect(sortPods(legacy).map((p) => p.id)).toEqual(['a', 'c', 'b'])
    // Eşit ilk örnek: son örneği yeni önce; o da eşitse ad, sonra kimlik
    expect(sortPods([
      { id: 'y', name: 'a', state: 'live', first_seen: '2031-04-02T11:00:00', last_seen: '2031-04-02T11:58:00' },
      { id: 'x', name: 'b', state: 'live', first_seen: '2031-04-02T11:00:00', last_seen: '2031-04-02T11:59:00' },
      { id: 'z', name: 'a', state: 'live', first_seen: '2031-04-02T11:00:00', last_seen: '2031-04-02T11:59:00' },
    ]).map((p) => p.id)).toEqual(['z', 'x', 'y'])
    expect(sortPods(null)).toEqual([])
    expect(sortPods(undefined)).toEqual([])
  })

  it('pod satırları: örneksiz kova null (boşluk), değer güvenli anahtarda', () => {
    const keys = new Map([['a.host:1', 'p0'], ['b.host:1', 'p1']])
    const rows = perPodRows(series, (p) => p.cpu_process_avg, (id) => keys.get(id))
    expect(rows).toHaveLength(4)
    expect(rows[0]).toEqual({ t: Date.UTC(2031, 3, 2, 12, 0, 0) })
    expect(rows[1]).toMatchObject({ p0: 10, p1: 30 })
    expect(rows[2].p0).toBeUndefined()
    expect(rows[3]).toMatchObject({ p1: 50 })
    expect(latestValue(rows, ['p0', 'p1'])).toBe(50)
    expect(latestValue([{ t: 1 }], ['p0'])).toBeNull()
  })

  it('HTTP küme satırları: istek/dk = podların kovayı kapladıkları paya göre ağırlıklı oranları; hata %, ortalama, en kötü p95', () => {
    const rows = httpRows(series)
    const r = rows[1]
    expect(r).toMatchObject({ req: 100, err: 3, rpm: 100, errPct: 3, avg: 20, p95: 90 })
    expect(rows[0].rpm).toBeNull()
    expect(rows[0].errPct).toBeNull()
    expect(rows[3].rpm).toBeNull()
    // 10 dakikalık kovanın yalnız 3 dakikası geçti: 30 istek → oran 10/dk (kova toplamını 10'a bölmek 3 derdi)
    const tenMin = httpRows({ ...series, bucket: '10m', from: '2031-04-02T12:00:00', to: '2031-04-02T12:03:00',
      points: [{ pod: 'x', t: '2031-04-02T12:00:00', samples: 3, http_minutes: 3, http_requests: 30, http_errors: 0, http_rpm: 10 }] })
    expect(tenMin[0]).toMatchObject({ rpm: 10, errPct: 0 })
    expect(tenMin[0].avg).toBeNull()

    // Dağıtım: biten 10 dakikalık kovada eski ve yeni pod yarımşar yaşadı → küme 1000 istek / 10 dk = 100 (podların
    // kendi oranlarını toplamak 200 derdi — ikinci inceleme 2026-10-13)
    const rollout = httpRows({ bucket: '10m', from: '2031-04-02T12:00:00', to: '2031-04-02T12:30:00', points: [
      { pod: 'old', t: '2031-04-02T12:00:00', samples: 5, http_minutes: 5, http_requests: 500, http_errors: 0, http_rpm: 100 },
      { pod: 'new', t: '2031-04-02T12:00:00', samples: 5, http_minutes: 5, http_requests: 500, http_errors: 0, http_rpm: 100 },
    ] })
    expect(rollout[0].rpm).toBe(100)

    // Dönüş kayması: 1 dk kovada A 1 dakika / 100 istek, B 2 dakika / 200 istek → ikisi de 100/dk → küme 200
    // (en meşgul podun dakikasına bölmek 300 / 2 = 150 derdi — inceleme turu 3)
    const drift = httpRows({ bucket: '1m', from: '2031-04-02T12:00:00', to: '2031-04-02T12:10:00', points: [
      { pod: 'a', t: '2031-04-02T12:05:00', http_minutes: 1, http_requests: 100, http_rpm: 100 },
      { pod: 'b', t: '2031-04-02T12:05:00', http_minutes: 2, http_requests: 200, http_rpm: 100 },
    ] })
    expect(drift.find((r) => r.t === Date.UTC(2031, 3, 2, 12, 5)).rpm).toBe(200)

    // Tek pod seçiliyken podun kendi oranı: yeni podun ilk kovası (10 dakikanın 3'ü) düşük görünmez
    const single = httpRows({ bucket: '10m', instance: 'new', from: '2031-04-02T12:00:00', to: '2031-04-02T12:30:00', points: [
      { pod: 'new', t: '2031-04-02T12:00:00', http_minutes: 3, http_requests: 300, http_rpm: 100 },
    ] })
    expect(single[0].rpm).toBe(100)
  })

  it('kovanın kapsadığı dakika ve podun payı: biten kova tam, son kova geçen dakika; geciken tick sıçratmaz', () => {
    const t0 = Date.UTC(2031, 3, 2, 12, 0, 0)
    expect(bucketMinutes(t0, 600_000, t0 + 30 * 60_000)).toBe(10)
    expect(bucketMinutes(t0, 600_000, t0 + 3.5 * 60_000)).toBe(3)
    expect(bucketMinutes(t0, 600_000, t0 + 10_000)).toBe(1)
    expect(bucketMinutes(t0, 60_000, null)).toBe(1)
    expect(podShare(500, 5, 10, false), 'yarım kova yaşayan pod (100/dk) yarım sayılır').toBe(50)
    expect(podShare(1200, 12, 10, false), 'kovadan uzun artış en çok tam pay').toBe(100)
    expect(podShare(300, 3, 10, true), 'tek pod: kendi oranı').toBe(100)
    expect(podShare(null, 3, 10, false)).toBeNull()
    expect(podShare(100, 0, 10, false), 'dakikası bilinmeyen toplam katılmaz').toBeNull()
    // Sahte sıfır yok: saatlik kovada 2 ERROR = 0,033/dk (bir ondalık 0 gösterirdi — inceleme turu 4)
    const hourly = logCacheRows({ bucket: '1h', from: '2031-04-02T10:00:00', to: '2031-04-02T12:30:00',
      points: [{ pod: 'a', t: '2031-04-02T10:00:00', delta_minutes: 60, log_error: 2 }] })
    expect(hourly[0].error).toBeCloseTo(2 / 60, 6)
    expect(fmtRate(hourly[0].error)).toBe('0.033')
    expect(fmtRate(0)).toBe('0')
    expect(fmtRate(null)).toBe('—')
    expect(fmtRate(2.345)).toBe('2.35')
    expect(fmtRate(123.45)).toBe('123.5')
    // Geciken tick: 1 dakikalık kovada tek satır 3 dakikanın artışını taşıyor — sunucu oranı 3/dk, küme de 3 (9 değil)
    const late = logCacheRows({ bucket: '1m', from: '2031-04-02T12:00:00', to: '2031-04-02T12:10:00',
      points: [{ pod: 'a', t: '2031-04-02T12:05:00', samples: 1, delta_minutes: 3, log_warn: 9 }] })
    expect(late.find((r) => r.t === Date.UTC(2031, 3, 2, 12, 5, 0)).warn).toBe(3)
  })

  it('günlük / önbellek: dakika başına oranlar toplanır; isabet % yalnız bilinen sayılardan', () => {
    const rows = logCacheRows(series)
    expect(rows[1]).toMatchObject({ warn: 3, error: 1, hits: 9, misses: 1, hitPct: 90 })
    expect(rows[3].hitPct).toBeNull()
    expect(clusterRows(series, { x: (p) => p.nope })[1].x).toBeUndefined()
  })

  it('özet kutucukları: canlı pod son örneklerinden; pod yoksa bilinmiyor; canlı yoksa kırmızı', () => {
    const ov = {
      pods: [
        { id: 'a', live: true, latest: { cpu_process: 82, heap_pct: 40, db_usage_pct: 50, db_pending: 0, exec_queue: 10, exec_queue_cap: 100 } },
        { id: 'b', live: true, latest: { cpu_process: 12, heap_pct: 90, db_usage_pct: 85, db_pending: 0, exec_queue: 50, exec_queue_cap: 100 } },
        { id: 'c', live: false, latest: null },
      ],
      last_hour: { http_requests: 1000, http_error_pct: 0.4, http_p95_ms: 300, log_error: 2, log_warn: 9, caller_runs: 0 },
    }
    const k = overviewKpis(ov)
    expect(k.pods).toEqual({ live: 2, total: 3, stopped: 0, notWriting: false, tone: 'warn' })
    expect(k.cpu).toEqual({ value: 82, tone: 'warn' })
    expect(k.heap).toEqual({ value: 90, tone: 'crit' })
    expect(k.db).toMatchObject({ value: 85, pending: 0, tone: 'warn' })
    expect(k.queue, 'en dolu pod %50 — toplam %30 onu gizlerdi').toMatchObject({ value: 50, queued: 60, tone: 'warn' })
    expect(k.http).toMatchObject({ requests: 1000, errPct: 0.4, tone: 'ok' })
    expect(k.logs).toMatchObject({ errors: 2, tone: 'warn' })

    const pending = overviewKpis({ pods: [{ id: 'a', live: true, latest: { db_usage_pct: 10, db_pending: 3 } }] })
    expect(pending.db.tone).toBe('crit')

    const none = overviewKpis(null)
    expect(none.pods.tone).toBe('neutral')
    for (const key of ['cpu', 'heap', 'db', 'queue', 'http', 'logs']) expect(none[key].tone, key).toBe('neutral')
    expect(none.http.requests).toBeNull()

    const rollout = overviewKpis({ pods: [
      { id: 'new', state: 'live', latest: { cpu_process: 5 } },
      { id: 'old', state: 'stopped', latest: null },
    ] })
    expect(rollout.pods, 'dağıtımla yerine yenisi gelen pod uyarı değildir')
      .toEqual({ live: 1, total: 1, stopped: 1, notWriting: false, tone: 'ok' })
    const justAfter = overviewKpis({ generated_at: '2031-04-02T12:02:00',
      pods: [{ id: 'old', state: 'stopped', last_seen: '2031-04-02T12:00:00', latest: null }] })
    expect(justAfter.pods, 'yeni pod ilk örneğini yazmadan (2 dk): kırmızı değil')
      .toEqual({ live: 0, total: 0, stopped: 1, notWriting: false, tone: 'neutral' })
    const stuck = overviewKpis({ generated_at: '2031-04-02T12:10:00', served_uptime_s: 900,
      pods: [{ id: 'old', state: 'stopped', last_seen: '2031-04-02T12:00:00', latest: null }] })
    expect(stuck.pods, 'süre geçti, 15 dk açık pod yazamıyor: kırmızı').toMatchObject({ notWriting: true, tone: 'crit' })
    // Tam yeniden başlatma: eski işaret 10 dk önce ama sunan pod 40 sn önce açıldı, ilk örneğini henüz yazmadı → kırmızı değil
    const fresh = overviewKpis({ generated_at: '2031-04-02T12:10:00', served_uptime_s: 40,
      pods: [{ id: 'old', state: 'stopped', last_seen: '2031-04-02T12:00:00', latest: null }] })
    expect(fresh.pods, 'yeni açılan pod: kırmızı değil (inceleme turu 5)').toMatchObject({ notWriting: false, tone: 'neutral' })
    expect(isNotWriting({ enabled: true, generated_at: '2031-04-02T12:10:00' },
      [{ state: 'stopped', last_seen: '2031-04-02T12:00:00' }]), 'çalışma süresi bilinmiyor → iddia yok').toBe(false)
    expect(isNotWriting({ enabled: true, served_uptime_s: 30 }, []), 'yeni açılan pod: toplanıyor').toBe(false)
    expect(isNotWriting({ enabled: true, served_uptime_s: 200 }, []), 'açılış + ilk tick payı içinde').toBe(false)
    expect(isNotWriting({ enabled: true, served_uptime_s: 900 }, []), 'açık ama hiç örnek yok').toBe(true)
    expect(isNotWriting({ enabled: false, served_uptime_s: 900 }, []), 'örnekleme kapalı ayrı durum').toBe(false)
    expect(isNotWriting({ enabled: true }, []), 'süre bilinmiyor → iddia yok').toBe(false)
    expect(podState({ live: true })).toBe('live')
    expect(podState({ live: false })).toBe('silent')
    expect(podState({ state: 'stopped', live: false })).toBe('stopped')

    const dead = overviewKpis({ pods: [{ id: 'a', live: false, latest: { cpu_process: 5 } }], last_hour: {} })
    expect(dead.pods.tone).toBe('crit')
    expect(dead.cpu).toEqual({ value: null, tone: 'neutral' })
    expect(dead.logs.tone).toBe('neutral')
  })

  it('ton ve çalışma süresi metni', () => {
    expect(toneOf(null, { warn: 1, crit: 2 })).toBe('neutral')
    expect(toneOf(0, { warn: 1, crit: 2 })).toBe('ok')
    expect(toneOf(1, { warn: 1, crit: 2 })).toBe('warn')
    expect(toneOf(5, { warn: 1, crit: 2 })).toBe('crit')
    expect(uptimeText(null, t)).toBe('—')
    expect(uptimeText(90061, t)).toBe('mx.uptime.dh(1,1)')
    expect(uptimeText(3720, t)).toBe('mx.uptime.hm(1,2)')
    expect(uptimeText(30, t)).toBe('mx.uptime.m(0)')
  })
})
