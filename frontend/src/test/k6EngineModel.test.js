import { describe, it, expect } from 'vitest'
import { readJavaMain } from './helpers/javaTable.js'
import {
  DIFFS, ERROR_CLASSES, GODEBUG_KEYS, buildGodebug, changedFields, csvFileName, csvTable, diffCounts, durationParts, engineState,
  filterResults, fmtUtc, godebugOn, idsDirty, isDirty, normalizeIds, pickableMonitors, resultClasses, resultTeams, scanProgress,
  scanTargetCount, sortResults, switchReadiness, toForm, utcMs, validateChanges,
} from '../components/admin/k6engine/k6EngineModel.js'

// Ayarlar → k6 motoru modeli (2026-10-29): sunucuyla AYNI listeler (Java kaynağından okunur), GODEBUG ayrıştır / kur, yalnız
// değişen alanlar, istemci kuralları, geçiş hazırlığı, süzgeçler, CSV, UTC zaman.

describe('k6EngineModel — sunucuyla aynı listeler', () => {
  it('GODEBUG anahtarları K6EngineSettings.GODEBUG_KEYS ile aynı sırada', () => {
    const src = readJavaMain('service/K6EngineSettings.java')
    const start = src.indexOf('GODEBUG_KEYS = orderedMap(')
    expect(start, 'GODEBUG_KEYS okunamadı').toBeGreaterThan(0)
    const body = src.slice(start, src.indexOf(');', start))
    const strings = [...body.matchAll(/"([^"]*)"/g)].map((m) => m[1])
    const keys = strings.filter((_, i) => i % 2 === 0)
    expect(keys).toEqual(GODEBUG_KEYS.map((g) => g.key))
    // Eski davranışı geri getiren değer: açıklamadaki "(1 = aç)" / "(0 = kapat|kabul et)" ile tutarlı
    const descs = strings.filter((_, i) => i % 2 === 1)
    GODEBUG_KEYS.forEach((g, i) => expect(descs[i], g.key).toContain(`(${g.restore} =`))
  })

  it('fark türleri K6CompatScanService.DIFFS ile aynı sırada', () => {
    const src = readJavaMain('service/k6engine/K6CompatScanService.java')
    const m = /List<String> DIFFS = List\.of\(([^)]*)\)/.exec(src)
    expect(m, 'DIFFS okunamadı').toBeTruthy()
    expect([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])).toEqual(DIFFS)
  })

  it('hata sınıfları K6CompatScanService.classify dönüşleriyle aynı küme', () => {
    const src = readJavaMain('service/k6engine/K6CompatScanService.java')
    const start = src.indexOf('static String classify(')
    const body = src.slice(start, src.indexOf('static String clip(', start))
    const returned = new Set([...body.matchAll(/return "([A-Z_]+)"/g)].map((x) => x[1]))
    expect([...returned].sort()).toEqual([...ERROR_CLASSES].sort())
  })
})

describe('GODEBUG', () => {
  it('ayrıştırma: yalnız eski davranışı geri getiren değer AÇIK; tersi değer ve bilinmeyen anahtar kapalı', () => {
    const on = godebugOn('tlsrsakex=1, TLSMLKEM=0,tls3des=0,foo=1,rsa1024min')
    expect(on.tlsrsakex).toBe(true)
    expect(on.tlsmlkem).toBe(true)
    expect(on.tls3des).toBe(false)   // 0 = yeni davranış (kapalı)
    expect(on.rsa1024min).toBe(false)
    expect(Object.keys(on)).toEqual(GODEBUG_KEYS.map((g) => g.key))
  })

  it('kurma: sabit sıra, yalnız açıklar, hiçbiri yoksa boş', () => {
    expect(buildGodebug({ tlsmlkem: true, tlsrsakex: true })).toBe('tlsrsakex=1,tlsmlkem=0')
    expect(buildGodebug({})).toBe('')
    expect(buildGodebug(godebugOn('tlssecpmlkem=0,x509negativeserial=1'))).toBe('x509negativeserial=1,tlssecpmlkem=0')
  })
})

describe('form', () => {
  const settings = { rollback_bin: '/usr/local/bin/k6-0.49', candidate_bin: '', godebug: 'tlsmlkem=0,tlsrsakex=1', user_agent: '' }

  it('toForm GODEBUG\'u normalize eder (sıra / tersi değer sahte "değişti" üretmez)', () => {
    const f = toForm(settings)
    expect(f.godebug).toBe('tlsrsakex=1,tlsmlkem=0')
    expect(isDirty(toForm(settings), toForm({ ...settings, godebug: 'tlsrsakex=1,tlsmlkem=0,tls3des=0' }))).toBe(false)
  })

  it('changedFields yalnız değişenleri (kırpılmış) verir; değişiklik yoksa boş', () => {
    const saved = toForm(settings)
    expect(changedFields(saved, saved)).toEqual({})
    expect(changedFields(saved, { ...saved, candidate_bin: '  /opt/k6-next/k6 ', godebug: '' }))
      .toEqual({ candidate_bin: '/opt/k6-next/k6', godebug: '' })
    expect(changedFields(saved, { ...saved, rollback_bin: '/usr/local/bin/k6-0.49  ' })).toEqual({})
  })

  it('istemci kuralları sunucunun kuralları; yalnız değişen alanlar denetlenir', () => {
    expect(validateChanges({ candidate_bin: '/opt/k6 next' }).candidate_bin.key).toBe('k6e.v.binPath')
    expect(validateChanges({ rollback_bin: '/opt/k6;rm' }).rollback_bin.key).toBe('k6e.v.binPath')
    expect(validateChanges({ rollback_bin: '' })).toEqual({})          // boş = tanımlı değil
    expect(validateChanges({ candidate_bin: '/usr/local/bin/k6-2.3' })).toEqual({})
    expect(validateChanges({ user_agent: 'x'.repeat(201) }).user_agent).toEqual({ key: 'k6e.v.uaLong', params: [200] })
    expect(validateChanges({ user_agent: 'Site Monitör' }).user_agent.key).toBe('k6e.v.uaAscii')
    expect(validateChanges({ user_agent: 'SiteMonitor/1.0 (+https://example.org)' })).toEqual({})
  })
})

describe('tarama dışı izlemeler', () => {
  const monitors = [
    { id: 1, name: 'İnternet Şubesi Girişi', team_name: 'Dijital Kanallar', active: true },
    { id: 2, name: 'Ödeme API', team_name: 'Ödeme Sistemleri', active: true },
    { id: 3, name: 'Eski senaryo', team_name: null, active: false },
  ]

  it('kimlik listesi normalize; kirli = küme farkı', () => {
    expect(normalizeIds(['3', 1, 1, 'x', -2, null])).toEqual([1, 3])
    expect(idsDirty([1, 3], [3, 1])).toBe(false)
    expect(idsDirty([1], [1, 2])).toBe(true)
  })

  it('taranacak sayı = etkin ∧ tarama dışı değil', () => {
    expect(scanTargetCount(monitors, [])).toBe(2)
    expect(scanTargetCount(monitors, [2, 3])).toBe(1)
    expect(scanTargetCount(null, [])).toBe(0)
  })

  it('seçici yalnız etkin izlemeleri, Türkçe katlamalı aramayla listeler', () => {
    expect(pickableMonitors(monitors, '').map((m) => m.id)).toEqual([1, 2])
    expect(pickableMonitors(monitors, 'internet sube').map((m) => m.id)).toEqual([1])
    expect(pickableMonitors(monitors, 'odeme').map((m) => m.id)).toEqual([2])
  })
})

describe('motor durumu ve geçiş hazırlığı', () => {
  it('engineState: çalışıyor yalnız available=true; tanımlı ama yok → missing; null → unset', () => {
    expect(engineState({ available: true })).toBe('running')
    expect(engineState({ available: false })).toBe('missing')
    expect(engineState({})).toBe('missing')
    expect(engineState(null)).toBe('unset')
    // 2026-10-30: bulundu ama sürümü desteklenmiyor (en az v2.0.0) — yeşil değil, "bulunamadı" da değil
    expect(engineState({ available: false, unsupported: true, version: 'v0.49.0' })).toBe('unsupported')
    expect(engineState({ available: true, unsupported: true })).toBe('running')
  })

  const done = (extra = {}) => ({ id: 5, state: 'DONE', scope: 'ALL', total: 40, regressions: 0, candidate_version: 'v2.3.0', ...extra })

  it('tarama yok → uyarı (geçiş engellenmez)', () => {
    const r = switchReadiness({ scans: [], latest: null, candidateVersion: 'v2.3.0' })
    expect(r).toMatchObject({ level: 'warning', blocked: false })
    expect(r.items.map((i) => i.key)).toEqual(['noScan'])
  })

  it('tarama sürüyor → engelli', () => {
    const r = switchReadiness({ scans: [{ ...done(), state: 'RUNNING' }, done()], candidateVersion: 'v2.3.0' })
    expect(r).toMatchObject({ level: 'running', blocked: true })
  })

  it('gerileme → tehlike; başka aday sürümü → uyarı; temiz → başarı', () => {
    expect(switchReadiness({ scans: [done({ regressions: 3 })], candidateVersion: 'v2.3.0' }).items)
      .toEqual([{ key: 'regressions', tone: 'danger', params: [3] }])
    const other = switchReadiness({ scans: [done({ candidate_version: 'v2.2.0' })], candidateVersion: 'v2.3.0' })
    expect(other.level).toBe('warning')
    expect(other.items[0]).toMatchObject({ key: 'otherVersion', params: ['v2.2.0', 'v2.3.0'] })
    const clean = switchReadiness({ scans: [done()], candidateVersion: 'v2.3.0' })
    expect(clean.level).toBe('success')
    expect(clean.items[0]).toMatchObject({ key: 'clean', params: [40] })
  })

  it('bilinmeyen sayım / bilinmeyen sürüm / tamamlanmamış tarama asla "temiz" değil', () => {
    expect(switchReadiness({ scans: [done({ regressions: null })], candidateVersion: 'v2.3.0' }).level).toBe('warning')
    expect(switchReadiness({ scans: [done()], candidateVersion: null }).level).toBe('warning')
    const inc = switchReadiness({ scans: [done({ state: 'INTERRUPTED' })], candidateVersion: 'v2.3.0' })
    expect(inc.level).toBe('warning')
    expect(inc.items[0]).toMatchObject({ key: 'incomplete', params: ['INTERRUPTED'] })
  })

  it('en yeni tarama yalnız seçili izlemelerse tam tarama esas alınır + bilgi satırı', () => {
    const r = switchReadiness({ scans: [{ id: 9, state: 'DONE', scope: 'SELECTED', regressions: 0, candidate_version: 'v2.3.0' }, done({ regressions: 2 })], candidateVersion: 'v2.3.0' })
    expect(r.items.map((i) => i.key)).toEqual(['regressions', 'rescanAfter'])
    expect(r.scan.id).toBe(5)
  })

  it('koşulamayan / yeni hata / değişen sonuç varsa "temiz" denmez (inceleme L2)', () => {
    const r = switchReadiness({ scans: [done({ not_run: 2, new_fail: 1, changed: 1 })], candidateVersion: 'v2.3.0' })
    expect(r.level).toBe('warning')
    expect(r.items.map((i) => i.key)).toEqual(['notRun', 'newFail', 'changed'])
    expect(r.items[0].params).toEqual([2])
  })

  it('tarama geçmişi okunmadıysa "tarama yok" değil, "okunamadı" (inceleme L3)', () => {
    const latestSelected = { id: 9, state: 'DONE', scope: 'SELECTED', regressions: 0, candidate_version: 'v2.3.0' }
    const r = switchReadiness({ scans: null, scansKnown: false, latest: latestSelected, candidateVersion: 'v2.3.0' })
    expect(r.items.map((i) => i.key)).toEqual(['scansUnknown'])
  })

  it('başka sürüm koşan pod / okunamayan pod sürümleri → tehlike + engelli; alan yoksa (eski sunucu) etkisiz (inceleme M2)', () => {
    const mixed = switchReadiness({ scans: [done()], candidateVersion: 'v2.3.0', otherPods: [{ pod: 'old-1', version: '20.171.0' }] })
    expect(mixed).toMatchObject({ level: 'danger', blocked: true })
    expect(mixed.items[0]).toMatchObject({ key: 'mixedVersions', params: ['old-1 (20.171.0)'] })
    const unknown = switchReadiness({ scans: [done()], candidateVersion: 'v2.3.0', otherPods: null })
    expect(unknown).toMatchObject({ level: 'danger', blocked: true })
    expect(unknown.items[0].key).toBe('podsUnknown')
    expect(switchReadiness({ scans: [done()], candidateVersion: 'v2.3.0', otherPods: [] }).level).toBe('success')
    expect(switchReadiness({ scans: [done()], candidateVersion: 'v2.3.0' }).level).toBe('success')
  })

  it('ilerleme', () => {
    expect(scanProgress({ total: 40, done: 10 })).toEqual({ done: 10, total: 40, pct: 25 })
    expect(scanProgress({ total: 0, done: 0 }).pct).toBeNull()
  })
})

describe('sonuçlar', () => {
  const rows = [
    { monitor_id: 1, monitor_name: 'Şube Girişi', team_id: 7, team_name: 'Dijital Kanallar', diff: 'SAME_PASS' },
    { monitor_id: 2, monitor_name: 'Ödeme API', team_id: 8, team_name: 'Ödeme Sistemleri', diff: 'REGRESSION', error_class: 'TLS', error: 'tls: handshake failure' },
    { monitor_id: 3, monitor_name: 'Kampanya', team_id: null, team_name: null, diff: null, error_class: 'SYNTAX' },
  ]

  it('süzgeç: fark, sınıf, takım, Türkçe katlamalı arama (hata metninde de)', () => {
    expect(filterResults(rows, { diff: 'REGRESSION' }).map((r) => r.monitor_id)).toEqual([2])
    expect(filterResults(rows, { diff: 'NOT_RUN' }).map((r) => r.monitor_id)).toEqual([3])
    expect(filterResults(rows, { cls: 'SYNTAX' }).map((r) => r.monitor_id)).toEqual([3])
    expect(filterResults(rows, { team: 'none' }).map((r) => r.monitor_id)).toEqual([3])
    expect(filterResults(rows, { q: 'sube' }).map((r) => r.monitor_id)).toEqual([1])
    expect(filterResults(rows, { q: 'HANDSHAKE' }).map((r) => r.monitor_id)).toEqual([2])
  })

  it('sıralama gerileme önce; takım ve sınıf listeleri', () => {
    expect(sortResults(rows).map((r) => r.monitor_id)).toEqual([2, 1, 3])
    expect(resultTeams(rows).map((t) => t.value)).toEqual(['7', '8', 'none'])
    expect(resultClasses(rows)).toEqual(['SYNTAX', 'TLS'])
  })

  it('sayımlar: sunucu counts varsa o, yoksa satırlardan', () => {
    expect(diffCounts({ counts: { REGRESSION: 4 } }).REGRESSION).toBe(4)
    const c = diffCounts({ results: rows })
    expect(c).toMatchObject({ SAME_PASS: 1, REGRESSION: 1, NOT_RUN: 1, FLAKY: 0 })
  })

  it('CSV: etiketlerle, boş değer boş hücre', () => {
    const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
    const { header, rows: body } = csvTable([rows[1]], t, 'tr')
    expect(header).toHaveLength(13)
    expect(body[0][0]).toBe('Ödeme API')
    expect(body[0][2]).toBe('k6e.diff.REGRESSION')
    expect(body[0][11]).toBe('k6e.cls.TLS')
    expect(body[0][12]).toBe('tls: handshake failure')
    expect(body[0][3]).toBe('')
    expect(csvFileName(12)).toBe('k6-uyumluluk-taramasi-12.csv')
  })
})

describe('zaman ve süre', () => {
  it('sunucu damgası UTC okunur (yerel saat sanılmaz), Türkiye saatiyle yazılır', () => {
    expect(utcMs('2026-10-29T20:15:03')).toBe(Date.UTC(2026, 9, 29, 20, 15, 3))
    expect(fmtUtc('2026-10-29T20:15:03', 'tr')).toBe('29.10.2026 23:15')
    expect(fmtUtc(null, 'tr')).toBe('—')
    expect(fmtUtc('bozuk', 'en')).toBe('—')
  })

  it('süre parçaları', () => {
    expect(durationParts(850, 'tr')).toEqual({ key: 'k6e.dur.ms', params: [850] })
    expect(durationParts(1250, 'tr')).toEqual({ key: 'k6e.dur.sec', params: ['1,3'] })
    expect(durationParts(1250, 'en')).toEqual({ key: 'k6e.dur.sec', params: ['1.3'] })
    expect(durationParts(65_000, 'en')).toEqual({ key: 'k6e.dur.min', params: [1, 5] })
    expect(durationParts(null, 'tr')).toBeNull()
  })
})
