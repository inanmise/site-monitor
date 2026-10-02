import { describe, it, expect, vi, afterEach } from 'vitest'
import { agoText, zonedMs, relTimeOrRaw, roundedAgoParts } from '../utils/relativeTime.js'
import { relativeTime as auditRelativeTime } from '../components/admin/audit/auditFormat.js'
import { relTime as changeRelTime } from '../components/admin/monitorchanges/changeParts.jsx'
import { relTime as activityRelTime } from '../components/myactivity/activityModel.js'
import { relTime as certRelParts } from '../components/certtable/certTableModel.js'
import { relTime as uactRelParts } from '../components/admin/useractivity/uactModel.js'
import { toUtc } from '../utils/localDay.js'

/**
 * KARAKTERİZASYON (2026-10-02, öneri 29 — "ekrandaki metinler aynı kalır"): göreli zamanın yerel kopyaları ortak
 * yardımcıya (utils/relativeTime.js) taşınmadan ÖNCE bugünkü çıktıları pinlenir. Her uygulamanın taşıma öncesi gövdesi
 * aşağıda KELİMESİ KELİMESİNE "kâhin" olarak durur; gerçek dışa aktarılan işlev, geniş bir girdi matrisi (bölgesiz / Z /
 * ofset / çıplak tarih / boşluklu / boş / bozuk / Date / gelecek) ve tüm eşiklerde kâhinle AYNI sonucu vermeli.
 * Sahte `t` anahtar + argümanları yazar → anahtar, sayı ve yuvarlama birlikte karşılaştırılır.
 */
const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
const NOW = Date.parse('2026-10-02T12:00:00Z')

// ── Kâhinler: taşıma öncesi gövdeler (değiştirme!) ────────────────────────────────────────────────────────────
// R1 components/admin/audit/auditFormat.js eventDate + relativeTime
function oEventDate(iso) {
  if (!iso) return null
  const s = String(iso).trim()
  const d = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(s) ? s : s + 'Z')
  return Number.isNaN(d.getTime()) ? null : d
}
function oAudit(iso, t, now = Date.now()) {
  const d = oEventDate(iso)
  if (!d) return null
  const sec = Math.floor(Math.max(0, now - d.getTime()) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60)
  if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60)
  if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R2 components/ActivityLog.jsx `rel` (useCallback; `now` yok)
const oActivityLogRel = (iso, now) => {
  if (!iso) return '—'
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  const then = new Date(s).getTime()
  if (isNaN(then)) return iso
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60);  if (hr < 24)  return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R3 components/admin/AdminChangeHistory.jsx relTime (dışa aktarılmıyor)
function oAdminChangeRel(iso, t, now = Date.now()) {
  if (!iso) return '—'
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  const then = new Date(s).getTime()
  if (Number.isNaN(then)) return iso
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R4 components/admin/monitorchanges/changeParts.jsx toMs + relTime
function oChangeToMs(iso) {
  if (!iso) return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  return new Date(s).getTime()
}
function oChangeRel(iso, t, now = Date.now()) {
  const then = oChangeToMs(iso)
  if (Number.isNaN(then)) return iso || '—'
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R5 components/myactivity/activityModel.js relTime
function oActivityModelRel(iso, t, now = Date.now()) {
  if (!iso) return null
  const then = new Date(toUtc(iso)).getTime()
  if (Number.isNaN(then)) return null
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R6 components/LastLoginInfo.jsx relativeTime(t, iso) (dışa aktarılmıyor; `now` yok)
const oLastLogin = (iso, now) => {
  if (!iso) return null
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  const then = new Date(s).getTime()
  if (isNaN(then)) return null
  const sec = Math.floor(Math.max(0, now - then) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60);  if (hr < 24)  return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}
// R9 components/certtable/certTableModel.js relTime + toMs
function oCertToMs(iso) {
  if (!iso) return null
  const s = String(iso)
  const t = Date.parse(/[zZ]|[+-]\d\d:\d\d$/.test(s) ? s : s + 'Z')
  return Number.isNaN(t) ? null : t
}
function oCertParts(iso, now = Date.now()) {
  const t = oCertToMs(iso)
  if (t == null) return null
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 60) return { unit: 'sec', n: s }
  const m = Math.round(s / 60); if (m < 60) return { unit: 'min', n: m }
  const h = Math.round(m / 60); if (h < 24) return { unit: 'hour', n: h }
  const d = Math.round(h / 24); if (d < 30) return { unit: 'day', n: d }
  const mo = Math.round(d / 30); if (mo < 12) return { unit: 'month', n: mo }
  return { unit: 'year', n: Math.round(mo / 12) }
}
// R10 components/admin/useractivity/uactModel.js relTime
function oUactParts(iso, now = Date.now()) {
  if (!iso) return null
  const t = Date.parse(iso.endsWith('Z') ? iso : iso + 'Z')
  if (Number.isNaN(t)) return null
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 60) return { unit: 'sec', n: s }
  const m = Math.round(s / 60); if (m < 60) return { unit: 'min', n: m }
  const h = Math.round(m / 60); if (h < 24) return { unit: 'hour', n: h }
  const d = Math.round(h / 24); if (d < 30) return { unit: 'day', n: d }
  const mo = Math.round(d / 30); if (mo < 12) return { unit: 'month', n: mo }
  return { unit: 'year', n: Math.round(mo / 12) }
}

// ── Girdi matrisi ─────────────────────────────────────────────────────────────────────────────────────────
const S = 1000, M = 60 * S, H = 60 * M, D = 24 * H
// Eşiklerin iki yanı + yuvarlama sınırları (x.5) + gelecek damga
const AGES = [-5 * M, -1, 0, 1, 499, 500, 29_499, 29_500, 59_499, 59_500, 59 * S, 59_999, 60 * S, 89 * S, 90 * S, 119_999,
  30 * M - 1, 30 * M, 59 * M + 29 * S, 59 * M + 30 * S, 59 * M + 59 * S, H, 90 * M, H + 30 * M - 1, 23 * H + 29 * M,
  23 * H + 30 * M, 23 * H + 59 * M + 59 * S, D, D + 12 * H - 1, D + 12 * H, 47 * H, 2 * D, 29 * D + 11 * H, 29 * D + 12 * H,
  30 * D, 45 * D, 364 * D, 365 * D, 400 * D, 800 * D]
const isoNoZone = (ms) => new Date(ms).toISOString().replace('Z', '')
function inputs() {
  const out = [null, undefined, '', 'garbage', 'z', 'abc+03:00', '2026-13-45T99:99:99', 0, '2026-10-02', ' 2026-10-02T11:59:00 ',
    '2026-10-02T11:00:00z', '2026-10-02T14:00:00+0300', '2026-10-02T14:00:00+03:00', '2026-10-02T10:00:00-02:00']
  for (const age of AGES) {
    const ms = NOW - age
    out.push(isoNoZone(ms), isoNoZone(ms) + 'Z', isoNoZone(ms).slice(0, 19), new Date(ms).toISOString().slice(0, 19) + '+00:00')
  }
  return out
}
const STRINGS = inputs()

describe('göreli zaman — dışa aktarılan işlevler kâhinle birebir (taşıma öncesi davranış)', () => {
  it('auditFormat.relativeTime (19 çağrı yeri)', () => {
    for (const iso of [...STRINGS, new Date(NOW - 5 * M)]) expect(auditRelativeTime(iso, t, NOW), String(iso)).toEqual(oAudit(iso, t, NOW))
  })
  it('changeParts.relTime (TimeAgo/TimeStamp, DNS kartı)', () => {
    for (const iso of STRINGS) expect(changeRelTime(iso, t, NOW), String(iso)).toEqual(oChangeRel(iso, t, NOW))
  })
  it('activityModel.relTime (Denetim Kaydım, Etkinliklerim) — Date ve epoch da kabul', () => {
    for (const iso of [...STRINGS, new Date(NOW - 3 * H), NOW - 2 * D]) {
      expect(activityRelTime(iso, t, NOW), String(iso)).toEqual(oActivityModelRel(iso, t, NOW))
    }
  })
  it('certTableModel.relTime ve uactModel.relTime ({unit, n} parçaları)', () => {
    for (const iso of STRINGS) {
      expect(certRelParts(iso, NOW), String(iso)).toEqual(oCertParts(iso, NOW))
      if (typeof iso === 'string' || iso == null || iso === 0) {
        let want, got
        try { want = oUactParts(iso, NOW) } catch (e) { want = `throws:${e.constructor.name}` }
        try { got = uactRelParts(iso, NOW) } catch (e) { got = `throws:${e.constructor.name}` }
        expect(got, String(iso)).toEqual(want)
      }
    }
  })
})

describe('göreli zaman — sabit beklenen değerler (eşik ve dil sözleşmesi)', () => {
  const at = (age, fmt = isoNoZone) => fmt(NOW - age)
  it('act.rel ailesi: <60 sn "az önce", sonra dk / sa / gün, aşağı yuvarlama, gelecek = az önce', () => {
    const table = [
      [-5 * M, 'act.rel.now'], [59_999, 'act.rel.now'], [60 * S, 'act.rel.min(1)'], [59 * M + 59 * S, 'act.rel.min(59)'],
      [H, 'act.rel.hour(1)'], [23 * H + 59 * M, 'act.rel.hour(23)'], [D, 'act.rel.day(1)'], [47 * H, 'act.rel.day(1)'],
      [400 * D, 'act.rel.day(400)'],
    ]
    for (const [age, want] of table) {
      expect(auditRelativeTime(at(age), t, NOW)).toBe(want)
      expect(changeRelTime(at(age), t, NOW)).toBe(want)
      expect(activityRelTime(at(age), t, NOW)).toBe(want)
    }
  })
  it('boş / bozuk damga: auditFormat null, changeParts "—" ya da ham değer, activityModel null', () => {
    expect(auditRelativeTime('', t, NOW)).toBeNull()
    expect(auditRelativeTime('garbage', t, NOW)).toBeNull()
    expect(changeRelTime('', t, NOW)).toBe('—')
    expect(changeRelTime(null, t, NOW)).toBe('—')
    expect(changeRelTime('garbage', t, NOW)).toBe('garbage')
    expect(activityRelTime(undefined, t, NOW)).toBeNull()
    expect(activityRelTime('garbage', t, NOW)).toBeNull()
  })
  it('{unit,n}: zincirleme Math.round, "az önce" yok (0 sn), ay 30 gün', () => {
    expect(certRelParts(at(0), NOW)).toEqual({ unit: 'sec', n: 0 })
    expect(certRelParts(at(59_499), NOW)).toEqual({ unit: 'sec', n: 59 })
    expect(certRelParts(at(59_500), NOW)).toEqual({ unit: 'min', n: 1 })
    expect(certRelParts(at(89 * S), NOW)).toEqual({ unit: 'min', n: 1 })
    expect(certRelParts(at(90 * S), NOW)).toEqual({ unit: 'min', n: 2 })
    expect(certRelParts(at(59 * M + 30 * S), NOW)).toEqual({ unit: 'hour', n: 1 })
    expect(certRelParts(at(29 * D + 12 * H), NOW)).toEqual({ unit: 'month', n: 1 })
    expect(certRelParts(at(400 * D), NOW)).toEqual({ unit: 'year', n: 1 })
    expect(uactRelParts(at(0, (ms) => isoNoZone(ms) + 'Z'), NOW)).toEqual({ unit: 'sec', n: 0 })
    expect(uactRelParts('2026-10-02T14:00:00+03:00', NOW)).toBeNull()   // yalnız büyük 'Z' tanınır (bugünkü davranış)
    expect(certRelParts('2026-10-02T14:00:00+03:00', NOW)).toEqual({ unit: 'hour', n: 1 })
  })
})

describe('ortak yardımcı (utils/relativeTime) — yerel kopyaların yerine geçenler kâhinle birebir', () => {
  afterEach(() => { vi.useRealTimers() })

  it('relTimeOrRaw ≡ AdminChangeHistory.relTime ≡ changeParts.relTime (boş "—", bozuk ham)', () => {
    for (const iso of STRINGS) {
      expect(relTimeOrRaw(iso, t, NOW), String(iso)).toEqual(oAdminChangeRel(iso, t, NOW))
      expect(relTimeOrRaw(iso, t, NOW), String(iso)).toEqual(oChangeRel(iso, t, NOW))
    }
  })

  it('ActivityLog.rel ve LastLoginInfo.relativeTime `now` almaz: saat sabitken relTimeOrRaw / zonedMs+agoText aynı', () => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
    const lastLogin = (iso) => { const then = zonedMs(iso); return Number.isNaN(then) ? null : agoText(then, t) }
    for (const iso of STRINGS) {
      expect(relTimeOrRaw(iso, t), String(iso)).toEqual(oActivityLogRel(iso, NOW))
      expect(lastLogin(iso), String(iso)).toEqual(oLastLogin(iso, NOW))
    }
  })

  it('agoText ≡ act.rel çekirdeği; roundedAgoParts ≡ tbl/uact çekirdeği (her yaş)', () => {
    for (const age of AGES) {
      const then = NOW - age
      expect(agoText(then, t, NOW), String(age)).toEqual(oAudit(new Date(then).toISOString(), t, NOW))
      expect(roundedAgoParts(then, NOW), String(age)).toEqual(oCertParts(new Date(then).toISOString(), NOW))
    }
  })
})
