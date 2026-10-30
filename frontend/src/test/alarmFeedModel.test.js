import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  OUTCOMES, OUTCOME_TONE, activeBlockCount, blockRows, crossFieldErrors, remainingParts,
  DEFAULT_LIMITS, FEED_COLUMNS, REQUEST_SIZE_OPTIONS, SILENT_AFTER_MS, bodyFileName, bodyOf, changedSettings, curlSample, feedUrl,
  fmtUtc, forwardedChain, isDirty, isValidIpEntry, isValidIpv4, isValidIpv6, limitsOf, normalizeIds, normalizeIps, numText,
  prettyBody, pyQuote, pythonSample, requestParams, shellQuote, statusModel, toForm, typeLabel, utcMs, validateChanges,
} from '../components/admin/alarmfeed/alarmFeedModel.js'

// Ayarlar → Alarm akışı (2026-10-30) — saf model: IPv4 / IPv6 / CIDR doğrulaması, yalnız değişen alanlar, sınır kuralları,
// durum tonu (bilinmeyen asla yeşil değil), örnek çağrılar, gövde gösterimi, istek parametreleri.

const SETTINGS = {
  enabled: false, min_level: 'WARNING', team_ids: [7, 3, 3], types: ['http', 'cert'], resolved_minutes: 15, resolved_mode: 'status',
  clear_text: 'Clear', severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target_type',
  source: 'SiteMonitor', rcitype: '', mail_separator: 'semicolon', allowed_ips: ['10.0.0.0/8', 'FE80::1'], rate_per_minute: 30,
  max_rows: 2000, store_body: true,
}

describe('alarmFeedModel — IP / CIDR', () => {
  it.each([
    ['10.0.0.1', true], ['255.255.255.255', true], ['0.0.0.0', true], ['256.1.1.1', false], ['10.0.0', false], ['10.0.0.1.2', false],
    ['010.0.0.1', false], ['1.2.3.a', false], ['', false], [' 10.0.0.1', false],
  ])('IPv4 %s → %s', (ip, ok) => { expect(isValidIpv4(ip)).toBe(ok) })

  it.each([
    ['::1', true], ['::', true], ['2001:db8::', true], ['2001:db8:0:0:0:0:0:1', true], ['fe80::1', true], ['FE80::ABCD', true],
    ['::ffff:192.0.2.1', true], ['64:ff9b::1.2.3.4', true], ['1:2:3:4:5:6:7:8', true],
    ['1:2:3:4:5:6:7', false], ['1:2:3:4:5:6:7:8:9', false], ['1::2::3', false], [':::1', false], ['12345::1', false], ['g::1', false],
    ['fe80::1%eth0', false], ['::ffff:300.0.0.1', false], ['1:2:3:4:5:6:7::8', false], ['2001:db8', false],
  ])('IPv6 %s → %s', (ip, ok) => { expect(isValidIpv6(ip)).toBe(ok) })

  it.each([
    ['10.0.0.0/8', true], ['192.168.1.20', true], ['10.0.0.0/32', true], ['10.0.0.0/0', true], ['10.0.0.0/33', false], ['10.0.0.0/', false],
    ['10.0.0.0/08', false], ['10.0.0.0/8/8', false], ['2001:db8::/32', true], ['2001:db8::/128', true], ['2001:db8::/129', false],
    ['  10.1.2.3  ', true], ['ÖRNEK', false], ['example.com', false], ['x'.repeat(200), false], [null, false],
  ])('giriş %s → %s', (ip, ok) => { expect(isValidIpEntry(ip)).toBe(ok) })

  it('normalizeIps: kırpar, küçük harfe çevirir, tekrarı atar (sıra korunur)', () => {
    expect(normalizeIps([' FE80::1 ', '10.0.0.1', 'fe80::1', ''])).toEqual(['fe80::1', '10.0.0.1'])
  })
})

describe('alarmFeedModel — form ve yalnız değişen alanlar', () => {
  it('toForm: kimlikler benzersiz + sıralı, türler sıralı, sayılar metin, IP küçük harf', () => {
    const f = toForm(SETTINGS)
    expect(f.team_ids).toEqual([3, 7])
    expect(f.types).toEqual(['cert', 'http'])
    expect(f.resolved_minutes).toBe('15')
    expect(f.allowed_ips).toEqual(['10.0.0.0/8', 'fe80::1'])
    expect(f.enabled).toBe(false)
  })

  it('değişiklik yok → boş gövde (istek gönderilmez); sıra / tekrar farkı değişiklik değildir', () => {
    const saved = toForm(SETTINGS)
    const form = { ...saved, team_ids: [7, 3], types: ['http', 'cert'] }
    expect(changedSettings(saved, form)).toEqual({})
    expect(isDirty(saved, form)).toBe(false)
  })

  it('yalnız değişen alanlar, tipli (sayı tamsayı, metin kırpılmış)', () => {
    const saved = toForm(SETTINGS)
    const form = { ...saved, enabled: true, resolved_minutes: ' 20 ', source: ' SM ', allowed_ips: [...saved.allowed_ips, '192.168.1.20'] }
    expect(changedSettings(saved, form)).toEqual({
      enabled: true, resolved_minutes: 20, source: 'SM', allowed_ips: ['10.0.0.0/8', 'fe80::1', '192.168.1.20'],
    })
  })

  it('bodyOf: tamsayı olmayan sayı metin olarak kalır (doğrulama yakalar)', () => {
    const b = bodyOf({ ...toForm(SETTINGS), max_rows: '12a' })
    expect(b.max_rows).toBe('12a')
  })
})

describe('alarmFeedModel — doğrulama (yalnız değişenler)', () => {
  const lim = DEFAULT_LIMITS
  it('tamsayı sınırları', () => {
    expect(validateChanges({ resolved_minutes: 0 }, lim)).toEqual({ resolved_minutes: { key: 'af.v.int', params: [1, 1440] } })
    expect(validateChanges({ resolved_minutes: 1441 }, lim).resolved_minutes.key).toBe('af.v.int')
    expect(validateChanges({ resolved_minutes: 1440 }, lim)).toEqual({})
    expect(validateChanges({ rate_per_minute: 601 }, lim).rate_per_minute.params).toEqual([1, 600])
    expect(validateChanges({ max_rows: 99 }, lim).max_rows.params).toEqual([100, 10000])
    expect(validateChanges({ max_rows: '12a' }, lim).max_rows.key).toBe('af.v.int')
  })

  it('metinler: zorunlu olanlar 1..60, RCITYPE boş olabilir', () => {
    expect(validateChanges({ source: '' }, lim).source).toEqual({ key: 'af.v.required', params: [60] })
    expect(validateChanges({ severity_high: 'x'.repeat(61) }, lim).severity_high.key).toBe('af.v.tooLong')
    expect(validateChanges({ severity_high: 'x'.repeat(60) }, lim)).toEqual({})
    expect(validateChanges({ rcitype: '' }, lim)).toEqual({})
    expect(validateChanges({ rcitype: 'y'.repeat(61) }, lim).rcitype.key).toBe('af.v.tooLong')
    expect(validateChanges({ clear_text: '' }, lim).clear_text.key).toBe('af.v.required')
  })

  it('seçimler ve IP listesi', () => {
    expect(validateChanges({ min_level: 'LOW' }, lim).min_level.key).toBe('af.v.choice')
    expect(validateChanges({ rci_mode: 'target' }, lim)).toEqual({})
    expect(validateChanges({ allowed_ips: ['10.0.0.1', 'bozuk'] }, lim).allowed_ips).toEqual({ key: 'af.v.ip', params: ['bozuk'] })
    const many = Array.from({ length: 101 }, (_, i) => `10.0.${Math.floor(i / 250)}.${i % 250}`)
    expect(validateChanges({ allowed_ips: many }, lim).allowed_ips).toEqual({ key: 'af.v.ipMax', params: [100] })
    expect(validateChanges({ allowed_ips: [] }, lim)).toEqual({})
  })

  it('sunucu sınırları kullanılır; eksikse varsayılan', () => {
    const l = limitsOf({ limits: { resolved_minutes: [5, 60], text_max: 20 } })
    expect(l.resolved_minutes).toEqual([5, 60])
    expect(l.rate_per_minute).toEqual([1, 600])
    expect(l.text_max).toBe(20)
    expect(validateChanges({ resolved_minutes: 4 }, l).resolved_minutes.params).toEqual([5, 60])
    expect(limitsOf(null)).toEqual({ ...DEFAULT_LIMITS })
  })

  it('sayfa boyutları sunucu tavanı olan 100 ile sınırlı', () => {
    expect(REQUEST_SIZE_OPTIONS).toEqual([25, 50, 100])
  })
})

describe('alarmFeedModel — durum tonu (bilinmeyen asla yeşil değil)', () => {
  const now = Date.parse('2026-10-30T12:00:00Z')
  it('kapalı → off; uyarı yok', () => {
    expect(statusModel({ last_call_at: '2026-10-30T11:00:00' }, false, now)).toMatchObject({ tone: 'off', warnings: [] })
  })
  it('açık + durum yok → unknown', () => {
    expect(statusModel(null, true, now).tone).toBe('unknown')
  })
  it('açık + son çağrı 5 dk içinde → info (yeşil DEĞİL)', () => {
    const m = statusModel({ last_call_at: '2026-10-30T11:58:00', calls_24h: 900, log_write_failures: 0 }, true, now)
    expect(m).toMatchObject({ tone: 'info', warnings: [] })
  })
  it('açık + son çağrı 5 dk\'dan eski → silent uyarısı', () => {
    const m = statusModel({ last_call_at: new Date(now - SILENT_AFTER_MS - 1000).toISOString().slice(0, 19) }, true, now)
    expect(m.warnings).toContain('silent')
    expect(m.tone).toBe('warning')
  })
  it('açık + hiç çağrı yok (24 sa sayısı 0) → noCalls; sayı bilinmiyorsa uyarı yok', () => {
    expect(statusModel({ last_call_at: null, calls_24h: 0 }, true, now).warnings).toEqual(['noCalls'])
    expect(statusModel({ last_call_at: null, calls_24h: null }, true, now).warnings).toEqual([])
  })
  it('sunucu istatistikleri okuyamadı (status.error) → statsUnreadable; "sessiz" / "çağrı yok" uydurulmaz', () => {
    const m = statusModel({ error: 'DataAccessResourceFailureException', last_call_at: null, calls_24h: null, log_write_failures: 0 }, true, now)
    expect(m.warnings).toEqual(['statsUnreadable'])
    expect(m.tone).toBe('warning')
    expect(m.known).toBe(false)
  })
  it('günlük yazma hatası kapalıyken de uyarı; son çağrı ERROR → lastError', () => {
    expect(statusModel({ log_write_failures: 2 }, false, now)).toMatchObject({ tone: 'warning', warnings: ['logFailures'], failures: 2 })
    expect(statusModel({ last_call_at: '2026-10-30T11:59:00', last_outcome: 'ERROR' }, true, now).warnings).toEqual(['lastError'])
  })
})

describe('alarmFeedModel — adres ve örnekler', () => {
  it('feedUrl: taban adresin sondaki eğik çizgileri atılır; taban yoksa yalnız yol', () => {
    expect(feedUrl('https://sm.example.com//', '/api/public/alarm-feed')).toEqual({ url: 'https://sm.example.com/api/public/alarm-feed', full: true })
    expect(feedUrl(null, '/api/public/alarm-feed')).toEqual({ url: '/api/public/alarm-feed', full: false })
    expect(feedUrl('   ', undefined)).toEqual({ url: '/api/public/alarm-feed', full: false })
  })
  it('curl: tek tırnaklı, içteki tırnak kaçışlı', () => {
    expect(curlSample('https://a.example/x')).toBe("curl -s 'https://a.example/x'")
    expect(shellQuote("a'b")).toBe("'a'\\''b'")
  })
  it('python: üç satır, kolonlar sırasıyla', () => {
    const lines = pythonSample('https://a.example/x', FEED_COLUMNS).split('\n')
    expect(lines).toHaveLength(3)
    expect(lines[0]).toBe('import pandas as pd, requests')
    expect(lines[1]).toBe("url = 'https://a.example/x'")
    expect(lines[2]).toContain("pd.DataFrame(requests.get(url, timeout=30).json(), columns=['CREATEDTIME', 'SEVERITY', 'RCI'")
    expect(lines[2]).toContain("'LINK'])")
    expect(pyQuote("o'k\\")).toBe("'o\\'k\\\\'")
  })
})

describe('alarmFeedModel — zaman, sayı, gövde, istek', () => {
  it('UTC damga İstanbul saatiyle; bozuk → —', () => {
    expect(utcMs('2026-10-30T09:15:00')).toBe(Date.parse('2026-10-30T09:15:00Z'))
    expect(fmtUtc('2026-10-30T09:15:07', 'tr', true)).toBe('30.10.2026 12:15:07')
    expect(fmtUtc(null, 'tr')).toBe('—')
    expect(fmtUtc('bozuk', 'en')).toBe('—')
  })
  it('numText: null / geçersiz → — (asla 0)', () => {
    expect(numText(null, 'tr')).toBe('—')
    expect(numText(undefined, 'tr')).toBe('—')
    expect(numText('x', 'tr')).toBe('—')
    expect(numText(0, 'tr')).toBe('0')
    expect(numText(12500, 'tr')).toBe('12.500')
  })
  it('prettyBody: JSON girintili, değilse olduğu gibi, yoksa null', () => {
    expect(prettyBody('[{"A":1}]', 'application/json')).toEqual({ text: '[\n  {\n    "A": 1\n  }\n]', pretty: true })
    expect(prettyBody('A,B\n1,2', 'text/csv')).toEqual({ text: 'A,B\n1,2', pretty: false })
    expect(prettyBody('{bozuk', null)).toEqual({ text: '{bozuk', pretty: false })
    expect(prettyBody(null, 'application/json')).toBeNull()
  })
  it('bodyFileName: içerik türüne göre uzantı', () => {
    expect(bodyFileName({ id: 5, body_content_type: 'application/json' })).toBe('alarm-akisi-yanit-5.json')
    expect(bodyFileName({ id: 6, body_content_type: 'text/csv;charset=utf-8' })).toBe('alarm-akisi-yanit-6.csv')
    expect(bodyFileName({ id: 7 })).toBe('alarm-akisi-yanit-7.txt')
  })
  it('requestParams: boşlar atılır, bilinmeyen sonuç gönderilmez', () => {
    expect(requestParams({ outcome: '', ip: ' ', from: '', to: '' }, 0, 25)).toEqual({ page: 0, size: 25 })
    expect(requestParams({ outcome: 'IP_DENIED', ip: ' 10.1.1.1 ', from: '2026-10-01', to: '2026-10-30' }, 2, 50))
      .toEqual({ page: 2, size: 50, outcome: 'IP_DENIED', ip: '10.1.1.1', from: '2026-10-01', to: '2026-10-30' })
    expect(requestParams({ outcome: 'BOGUS' }, 0, 25)).toEqual({ page: 0, size: 25 })
  })
  it('X-Forwarded-For zinciri', () => {
    expect(forwardedChain(' 1.1.1.1 , 10.0.0.2,')).toEqual(['1.1.1.1', '10.0.0.2'])
    expect(forwardedChain(null)).toEqual([])
  })
  it('normalizeIds / typeLabel', () => {
    expect(normalizeIds(['3', 1, 'x', -2, 1])).toEqual([1, 3])
    const t = (k) => ({ 'userpush.type.cert': 'Sertifika' }[k] ?? k)
    expect(typeLabel('cert', 'Cert', t)).toBe('Sertifika')
    expect(typeLabel('yeni', 'Yeni tür', t)).toBe('Yeni tür')
    expect(typeLabel('yeni', null, t)).toBe('yeni')
  })
})

// ── Otomatik IP engeli (2026-10-31) ─────────────────────────────────────────────────────────────

describe('alarmFeedModel — otomatik IP engeli', () => {
  const S = { ...SETTINGS, block_enabled: true, block_threshold: 120, block_minutes: 15, block_escalate: true }

  it('BLOCKED bir sonuç ve tehlike tonunda', () => {
    expect(OUTCOMES).toContain('BLOCKED')
    expect(OUTCOME_TONE.BLOCKED).toBe('danger')
  })

  it('sonuç süzgeci sunucunun kabul ettiği listeyle AYNI (Java OUTCOMES ↔ JS OUTCOMES)', () => {
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
    const log = fs.readFileSync(path.join(root, 'backend/src/main/java/com/sitemonitor/service/alarmfeed/AlarmFeedRequestLog.java'), 'utf8')
    const ctrl = fs.readFileSync(path.join(root, 'backend/src/main/java/com/sitemonitor/controller/AlarmFeedAdminController.java'), 'utf8')
    const consts = Object.fromEntries([...log.matchAll(/static final String (OUTCOME_[A-Z_]+) = "([A-Z_]+)"/g)].map((m) => [m[1], m[2]]))
    const list = ctrl.slice(ctrl.indexOf('static final List<String> OUTCOMES'))
    const body = list.slice(0, list.indexOf(';'))
    const java = [...body.matchAll(/AlarmFeedRequestLog\.(OUTCOME_[A-Z_]+)/g)].map((m) => consts[m[1]])
    expect(java.length).toBeGreaterThan(4)
    expect([...java].sort()).toEqual([...OUTCOMES].sort())
    expect(Object.keys(OUTCOME_TONE).sort()).toEqual([...OUTCOMES].sort())
  })

  it('form ↔ gövde: engel alanları tipli; eski sunucu alan göndermezse değişiklik sayılmaz', () => {
    expect(validateChanges({ block_exempt_ips: ['10.0.0.1', 'bozuk'] })).toEqual({ block_exempt_ips: { key: 'af.v.ip', params: ['bozuk'] } })
    expect(changedSettings(toForm({ ...S, block_exempt_ips: [] }), { ...toForm(S), block_exempt_ips: ['10.1.1.0/24'] }))
      .toEqual({ block_exempt_ips: ['10.1.1.0/24'] })
    const f = toForm(S)
    expect(f.block_threshold).toBe('120')
    expect(bodyOf(f)).toMatchObject({ block_enabled: true, block_threshold: 120, block_minutes: 15, block_escalate: true })
    expect(changedSettings(f, { ...f, block_minutes: '30', block_escalate: false })).toEqual({ block_minutes: 30, block_escalate: false })
    const legacy = toForm(SETTINGS)
    expect(isDirty(legacy, legacy)).toBe(false)
    expect(limitsOf({}).block_threshold).toEqual(DEFAULT_LIMITS.block_threshold)
    expect(limitsOf({ limits: { block_minutes: [2, 600] } }).block_minutes).toEqual([2, 600])
    expect(validateChanges({ block_threshold: 5 })).toEqual({ block_threshold: { key: 'af.v.int', params: [10, 6000] } })
  })

  it('eşik < sınır: değişen alan suçlanır (ikisi de değiştiyse eşik); eşitlik serbest; geçersiz sayıda karar yok', () => {
    const f = toForm(S)
    expect(crossFieldErrors({}, f)).toEqual({})
    expect(crossFieldErrors({ block_threshold: 20 }, { ...f, block_threshold: '20' }))
      .toEqual({ block_threshold: { key: 'af.v.blockBelowRate', params: [20, 30] } })
    expect(crossFieldErrors({ rate_per_minute: 200 }, { ...f, rate_per_minute: '200' }))
      .toEqual({ rate_per_minute: { key: 'af.v.blockBelowRate', params: [120, 200] } })
    expect(crossFieldErrors({ rate_per_minute: 200, block_threshold: 150 }, { ...f, rate_per_minute: '200', block_threshold: '150' }))
      .toHaveProperty('block_threshold')
    expect(crossFieldErrors({ rate_per_minute: 120 }, { ...f, rate_per_minute: '120' })).toEqual({})
    expect(crossFieldErrors({ block_threshold: 'x' }, { ...f, block_threshold: 'x' })).toEqual({})
  })

  it('engel listesi: etkin önce (sunucu sırası korunur), süresi geçmiş "etkin" satır expired; okunamayan liste null', () => {
    const now = Date.parse('2026-10-31T10:00:00Z')
    const rows = blockRows([
      { ip: 'a', state: 'expired', blocked_until: '2026-10-31T09:00:00' },
      { ip: 'b', state: 'active', blocked_until: '2026-10-31T10:13:00' },
      { ip: 'c', state: 'active', blocked_until: '2026-10-31T09:59:00' },
      { ip: 'd', state: 'unblocked', blocked_until: '2026-10-31T09:30:00' },
      { ip: 'e', state: 'weird', blocked_until: null },
      null,
    ], now)
    expect(rows.map((r) => `${r.ip}:${r.state}`)).toEqual(['b:active', 'a:expired', 'c:expired', 'd:unblocked', 'e:expired'])
    expect(rows[0].remainingMs).toBe(13 * 60_000)
    expect(blockRows(null)).toBeNull()
    expect(activeBlockCount(null)).toBeNull()
    expect(activeBlockCount([{ ip: 'b', state: 'active', blocked_until: '2026-10-31T10:13:00' }], now)).toBe(1)
  })

  it('kalan süre: en az 1 dk, 60 dk ve üstü saat + dk; geçmiş / bilinmeyen null', () => {
    expect(remainingParts(30_000)).toEqual({ key: 'af.block.remainingMinutes', params: [1] })
    expect(remainingParts(13 * 60_000)).toEqual({ key: 'af.block.remainingMinutes', params: [13] })
    expect(remainingParts(65 * 60_000)).toEqual({ key: 'af.block.remainingHours', params: [1, 5] })
    expect(remainingParts(0)).toBeNull()
    expect(remainingParts(null)).toBeNull()
  })
})
