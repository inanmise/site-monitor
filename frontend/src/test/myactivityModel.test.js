import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  presetRange, toUtcIso, eventKind, deviceText, locationText, reasonText, parseFlags, isUnusualSignIn, sentence,
  targetText, recordLink, diffOf, detailOf, groupByDay, dayLabel, matchesQuery, deviceStats, typeBreakdown, heatmapOf,
} from '../components/myactivity/activityModel.js'

/** Sözlükten çevirmen (useT ile aynı yer tutucu kuralı — split/join). */
const tOf = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const t = tOf(EN)
const tr = tOf(TR)

const HOUR = 3_600_000
const DAY = 24 * HOUR

describe('activityModel — aralık (UTC ISO sözleşmesi)', () => {
  it('hazır aralık: since = şimdi − N, ek yok, saniye var; until açık', () => {
    const now = Date.UTC(2026, 8, 26, 9, 30, 15)
    expect(presetRange('24h', now)).toEqual({ preset: '24h', since: '2026-09-25T09:30:15', until: '' })
    expect(presetRange('7d', now).since).toBe('2026-09-19T09:30:15')
    expect(presetRange('30d', now).since).toBe('2026-08-27T09:30:15')
    expect(presetRange('custom', now)).toEqual({ preset: 'custom', since: '', until: '' })
    expect(toUtcIso(now)).not.toMatch(/Z$/)
  })
})

describe('activityModel — cümle, cihaz, konum, sebep', () => {
  it('cihaz metni: "Windows · Chrome" → EN "Chrome on Windows", TR "Windows üzerinde Chrome"; tek parça olduğu gibi', () => {
    expect(deviceText('Windows · Chrome', t)).toBe('Chrome on Windows')
    expect(deviceText('Windows · Chrome', tr)).toBe('Windows üzerinde Chrome')
    expect(deviceText('Linux', t)).toBe('Linux')
    expect(deviceText(null, t)).toBeNull()
  })

  it('giriş cümleleri cihazı içerir; cihaz bilinmiyorsa yalın; hız sınırı kendi cümlesi', () => {
    expect(sentence({ event_type: 'LOGIN', ua_summary: 'iOS · Safari' }, t)).toEqual({ text: 'Signed in from Safari on iOS', target: null })
    expect(sentence({ event_type: 'LOGIN' }, t).text).toBe('Signed in')
    expect(sentence({ event_type: 'LOGIN_FAILED', outcome: 'FAILURE', ua_summary: null }, t).text).toBe('Failed sign-in attempt')
    expect(sentence({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED' }, t).text).toBe('Sign-in attempt blocked — too many tries')
    expect(sentence({ event_type: 'SELF_PASSWORD_CHANGE' }, t).text).toBe('You changed your password')
    expect(sentence({ event_type: 'LOGOUT' }, t).text).toBe('Signed out')
  })

  it('işlem cümlesi: etiket + hedef (izleme adı `detail`ten, alan adı `resource_id`den; USER kaynağı hedef değil)', () => {
    expect(sentence({ event_type: 'MONITOR_UPDATE', resource_type: 'HTTP_MONITOR', resource_id: '12', detail: 'www.example.com' }, t))
      .toEqual({ text: 'Monitor updated', target: 'www.example.com' })
    expect(targetText({ event_type: 'DOMAIN_ADD', resource_type: 'CERTIFICATE', resource_id: 'shop.example.com', detail: '{"port":443}' })).toBe('shop.example.com')
    expect(targetText({ event_type: 'USER_PUSH_OPT_OUT', resource_type: 'USER', resource_id: '42', detail: 'x' })).toBeNull()
    expect(targetText({ event_type: 'MONITOR_TEST', resource_type: 'PING_MONITOR', resource_id: '3', detail: '{"json":true}' })).toBe('3')
  })

  it('konum: özel ağ → "Corporate network"; şehir, ülke; hiçbiri yoksa null', () => {
    expect(locationText({ ip_country: 'Private', ip_city: 'LAN' }, t)).toBe('Corporate network')
    expect(locationText({ ip_country: 'Türkiye', ip_city: 'İstanbul' }, t)).toBe('İstanbul, Türkiye')
    expect(locationText({ ip_country: 'Netherlands' }, t)).toBe('Netherlands')
    expect(locationText({}, t)).toBeNull()
  })

  it('sebep: kod öneki sözlükten, hız sınırı BLOCKED girişte, bilinmeyen ham', () => {
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'FAILURE', failure_reason: "BAD_PASSWORD: attempt #1/5 for 'demo'" }, t)).toBe('Wrong password')
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'FAILURE', failure_reason: 'TEMP_PASSWORD_EXPIRED: x' }, t)).toBe('Temporary password expired')
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED', failure_reason: 'Rate limited: …' }, t)).toBe('Too many attempts from this address')
    // Pasif hesap (2026-10-02): sonuç BLOCKED ama oran sınırı DEĞİL — kendi metni.
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED', failure_reason: 'ACCOUNT_INACTIVE: pasif hesap (LDAP)' }, t))
      .toBe('Rejected because the account is inactive')
    expect(reasonText({ event_type: 'ACCESS_DENIED', outcome: 'BLOCKED', failure_reason: 'Insufficient role' }, t)).toBe('Insufficient role')
    expect(reasonText({ event_type: 'LOGIN' }, t)).toBeNull()
  })

  it('ikon türü ve işaretler: yalnız bilinen bayraklar; yeni ağdan BAŞARILI giriş "alışılmadık"', () => {
    expect(eventKind({ event_type: 'LOGIN' })).toBe('signin')
    expect(eventKind({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED' })).toBe('blocked')
    expect(eventKind({ event_type: 'ACCESS_DENIED', outcome: 'BLOCKED' })).toBe('denied')
    expect(eventKind({ event_type: 'MONITOR_DELETE' })).toBe('delete')
    expect(eventKind({ event_type: 'DOMAIN_ADD' })).toBe('create')
    expect(eventKind({ event_type: 'USER_PUSH_OPT_OUT' })).toBe('update')
    expect(eventKind({ event_type: 'MONITOR_TRIGGER' })).toBe('test')
    expect(eventKind({ event_type: 'SOMETHING_ODD' })).toBe('other')
    expect(parseFlags('UNUSUAL_IP,OFF_HOURS,MADE_UP')).toEqual(['UNUSUAL_IP', 'OFF_HOURS'])
    expect(isUnusualSignIn({ event_type: 'LOGIN', anomaly_flags: 'OFF_HOURS,UNUSUAL_IP' })).toBe(true)
    expect(isUnusualSignIn({ event_type: 'LOGIN', anomaly_flags: 'OFF_HOURS' })).toBe(false)
    expect(isUnusualSignIn({ event_type: 'LOGIN_FAILED', anomaly_flags: 'UNUSUAL_IP' })).toBe(false)
  })

  it('kayıt bağlantısı: izleme → sekme + monitor paramı; sertifika → pano + domain; silinen kayda yok', () => {
    expect(recordLink({ event_type: 'MONITOR_UPDATE', resource_type: 'PORT_MONITOR', resource_id: '31' })).toEqual({ tab: 'port', params: { monitor: '31' } })
    expect(recordLink({ event_type: 'MONITOR_CREATE', resource_type: 'SCRIPTED_MONITOR', resource_id: '9' })).toEqual({ tab: 'scripted', params: undefined })
    expect(recordLink({ event_type: 'DOMAIN_EDIT', resource_type: 'CERTIFICATE', resource_id: 'a.example.com' })).toEqual({ tab: 'dashboard', params: { domain: 'a.example.com' } })
    expect(recordLink({ event_type: 'MONITOR_DELETE', resource_type: 'DNS_MONITOR', resource_id: '8' })).toBeNull()
    expect(recordLink({ event_type: 'ACCESS_DENIED', resource_type: 'API', resource_id: '/api/admin' })).toBeNull()
  })

  it('fark: `changes` yapısal; envanter düzenlemesi farkı `detail`te → yine fark; düz ayrıntı anahtar/değer ya da metin', () => {
    expect(diffOf({ changes: JSON.stringify({ port: { from: 443, to: 8443 } }) })).toEqual([['port', 443, 8443]])
    expect(diffOf({ detail: JSON.stringify({ notes: { from: '', to: 'x' } }) })).toEqual([['notes', '', 'x']])
    expect(diffOf({ detail: '{"port":443,"teamId":1}' })).toBeNull()
    expect(detailOf({ detail: '{"port":443,"teamId":1}' })).toEqual({ obj: { port: 443, teamId: 1 } })
    expect(detailOf({ detail: 'Kişi webhook push bildirimini AÇTI' })).toEqual({ text: 'Kişi webhook push bildirimini AÇTI' })
    expect(detailOf({ detail: JSON.stringify({ notes: { from: '', to: 'x' } }) })).toBeNull()
    expect(detailOf({ detail: '   ' })).toBeNull()
  })
})

describe('activityModel — gün gruplama, arama, özet', () => {
  it('güne göre gruplar (yerel gün) ve sırayı korur; başlık Bugün/Dün/uzun tarih', () => {
    const now = new Date()
    const at = (msAgo) => new Date(now.getTime() - msAgo).toISOString().slice(0, 19)
    const rows = [{ id: 1, event_time: at(1000) }, { id: 2, event_time: at(2000) }, { id: 3, event_time: at(3 * DAY) }]
    const days = groupByDay(rows)
    expect(days.length).toBeGreaterThanOrEqual(2)
    expect(days[0].rows.map((r) => r.id)).toEqual([1, 2])
    expect(dayLabel(days[0].key, t, 'en-GB', now)).toBe('Today')
    const y = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
    const pad = (n) => String(n).padStart(2, '0')
    expect(dayLabel(`${y.getFullYear()}-${pad(y.getMonth() + 1)}-${pad(y.getDate())}`, t, 'en-GB', now)).toBe('Yesterday')
    // ICU sürümleri virgül/sıra konusunda ayrışır (en-GB yıl varken "Tuesday, 24 …") → parçalar sınanır
    expect(dayLabel('2024-09-24', t, 'en-GB', new Date(2026, 8, 26))).toMatch(/^Tuesday,? 24 September 2024$/)
    expect(dayLabel('2026-09-24', t, 'en-GB', new Date(2026, 8, 26))).toMatch(/^Thursday,? 24 September$/)
    const trLabel = dayLabel('2026-09-24', tr, 'tr-TR', new Date(2026, 8, 26))
    expect(trLabel).toMatch(/24 Eylül/)
    expect(trLabel).toMatch(/Perşembe/)
    expect(trLabel).not.toMatch(/2026/)
  })

  it('sayfa içi arama: IP, cihaz, konum, cümle ve ham tür üzerinde; boş sorgu her şeyi geçirir', () => {
    const row = { event_type: 'LOGIN', ua_summary: 'Windows · Chrome', ip_address: '203.0.113.24', ip_city: 'İstanbul', ip_country: 'Türkiye' }
    expect(matchesQuery(row, '203.0.113', t)).toBe(true)
    expect(matchesQuery(row, 'chrome on', t)).toBe(true)
    expect(matchesQuery(row, 'istanbul', t)).toBe(true)   // aksan/nokta katlanır: "İstanbul" ~ "istanbul"
    expect(matchesQuery(row, 'İstanbul', t)).toBe(true)
    expect(matchesQuery(row, 'turkiye', t)).toBe(true)
    expect(matchesQuery(row, 'IP', t)).toBe(false)         // "IP" metinde yok ("ıp" tuzağı yok)
    expect(matchesQuery(row, 'login', t)).toBe(true)
    expect(matchesQuery(row, 'yok', t)).toBe(false)
    expect(matchesQuery(row, '  ', t)).toBe(true)
  })

  it('cihaz dökümü: ua_summary başına giriş sayısı, son giriş, farklı IP; sıra son kullanılan önce', () => {
    const rows = [
      { event_time: '2026-09-20T10:00:00', ua_summary: 'Windows · Chrome', ip_address: '10.0.0.1' },
      { event_time: '2026-09-25T10:00:00', ua_summary: 'iOS · Safari', ip_address: '198.51.100.7' },
      { event_time: '2026-09-26T10:00:00', ua_summary: 'Windows · Chrome', ip_address: '203.0.113.24' },
    ]
    const { devices, ipCount } = deviceStats(rows)
    expect(ipCount).toBe(3)
    expect(devices.map((d) => d.label)).toEqual(['Windows · Chrome', 'iOS · Safari'])
    expect(devices[0].count).toBe(2)
    expect(devices[0].last.event_time).toBe('2026-09-26T10:00:00')
    expect([...devices[0].ips]).toEqual(['10.0.0.1', '203.0.113.24'])
    expect(devices[1].mobile).toBe(true)
    expect(typeBreakdown([{ event_type: 'A' }, { event_type: 'B' }, { event_type: 'B' }])).toEqual([['B', 2], ['A', 1]])
  })

  it('ısı haritası: yerel gün×saat, Pazartesi = 0; başarısızlar işaret', () => {
    const mon = new Date(2026, 8, 21, 9, 5) // yerel Pazartesi 09:05
    const iso = (d) => d.toISOString().slice(0, 19)   // sunucu biçimi: UTC, ek yok
    const h = heatmapOf([{ event_time: iso(mon) }, { event_time: iso(mon) }], [{ event_time: iso(new Date(2026, 8, 27, 23, 0)) }])
    expect(h.matrix[0][9]).toBe(2)
    expect(h.rowTotals[0]).toBe(2)
    expect(h.colTotals[9]).toBe(2)
    expect(h.max).toBe(2)
    expect(h.total).toBe(2)
    expect(h.marks[6][23]).toBe(1)
  })
})
