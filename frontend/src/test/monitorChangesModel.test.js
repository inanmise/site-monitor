import { describe, it, expect, beforeEach } from 'vitest'
import {
  activeToggle, changesCsv, dailySeries, dayLabel, diffModel, groupByDay, linkFor, listDelta, readUrlState,
  urlMapping, valueKind, windowFor, URL_KEYS, KINDS, TAB_BY_KIND,
} from '../components/admin/monitorchanges/changeModel.js'

/**
 * İzleme Değişiklikleri saf modeli (2026-09-28). Tarihler ŞİMDİDEN türetilir (sabit tarih + kayan pencere = zaman
 * bombası); gece yarısı sınırı YEREL saatle kurulur, sunucu damgası UTC'ye çevrilerek verilir.
 */

const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
const pad = (n) => String(n).padStart(2, '0')
const key = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
/** Basit t: anahtarı işaretli döndürür (sözlükten bağımsız; "çeviri yok" yedeğine düşmesin diye anahtardan FARKLI). */
const t = (k, ...a) => `⟨${k}${a.length ? '|' + a.join('|') : ''}⟩`

describe('groupByDay / dayLabel — yerel gün', () => {
  it('gece yarısının iki yanındaki kayıtlar YEREL güne göre ayrılır (UTC dilimi değil)', () => {
    const today = new Date(); today.setHours(0, 30, 0, 0)            // bugün 00:30 yerel
    const yday = new Date(today); yday.setDate(yday.getDate() - 1); yday.setHours(23, 30, 0, 0)   // dün 23:30
    const groups = groupByDay([{ at: iso(today.getTime()), n: 1 }, { at: iso(yday.getTime()), n: 2 }])
    expect(groups.map(g => g.key)).toEqual([key(today), key(yday)])
    expect(groups[0].rows.map(r => r.n)).toEqual([1])
  })

  it('başlık: Bugün / Dün (+ tam tarih), diğer günler hafta günü + tarih; geçen yılsa yıl da', () => {
    const now = Date.now()
    const today = new Date(now)
    const yday = new Date(now); yday.setDate(yday.getDate() - 1)
    const old = new Date(now); old.setDate(old.getDate() - 5)
    expect(dayLabel(key(today), now, 'en', t).label).toBe('⟨chg.dayToday⟩')
    expect(dayLabel(key(today), now, 'en', t).sub).not.toBe('')
    expect(dayLabel(key(yday), now, 'en', t).label).toBe('⟨chg.dayYesterday⟩')
    const fmt = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long',
      ...(old.getFullYear() === today.getFullYear() ? {} : { year: 'numeric' }) }).format(old)
    expect(dayLabel(key(old), now, 'en', t)).toEqual({ label: fmt, sub: '' })
    const lastYear = new Date(now); lastYear.setFullYear(lastYear.getFullYear() - 1)
    expect(dayLabel(key(lastYear), now, 'tr', t).label).toContain(String(lastYear.getFullYear()))
  })
})

describe('valueKind / diffModel / listDelta / activeToggle', () => {
  it('değer türleri', () => {
    expect(valueKind('x', null)).toBe('empty')
    expect(valueKind('x', '')).toBe('empty')
    expect(valueKind('password', '***')).toBe('masked')
    expect(valueKind('active', false)).toBe('boolean')
    expect(valueKind('active', 'true')).toBe('boolean')
    expect(valueKind('teamId', 3)).toBe('team')
    expect(valueKind('intervalSeconds', 300)).toBe('duration')
    expect(valueKind('timeoutMs', 5000)).toBe('duration')
    expect(valueKind('intervalSeconds', 0)).toBe('number')     // sıfır süre biçimlenmez
    expect(valueKind('tags', ['a'])).toBe('list')
    expect(valueKind('headers', { a: 1 })).toBe('object')
    expect(valueKind('logoData', { bytes: 1200 })).toBe('binary')
    expect(valueKind('port', 443)).toBe('number')
    expect(valueKind('script', 'a\nb')).toBe('multiline')
    expect(valueKind('description', 'x'.repeat(161))).toBe('long')
    expect(valueKind('name', 'kısa')).toBe('text')
  })

  it('satır kipi: çok satırlı → lines, iki taraf liste (ya da boş) → list, diğerleri pair', () => {
    const m = diffModel(JSON.stringify({
      script: { from: 'a', to: 'a\nb' }, tags: { from: null, to: ['x'] }, name: { from: 'a', to: 'b' },
    }), t)
    expect(Object.fromEntries(m.map(e => [e.key, e.mode]))).toEqual({ script: 'lines', tags: 'list', name: 'pair' })
    expect(diffModel('{bozuk', t)).toEqual([])
  })

  it('liste farkı: çıkan / eklenen / kalan', () => {
    expect(listDelta(['a', 'b'], ['b', 'c'])).toEqual({ removed: ['a'], added: ['c'], kept: ['b'] })
    expect(listDelta(null, ['c'])).toEqual({ removed: [], added: ['c'], kept: [] })
  })

  it('duraklatma / sürdürme yalnız active alanının GEÇİŞİNDEN türer', () => {
    expect(activeToggle(JSON.stringify({ active: { from: true, to: false } }))).toBe('PAUSE')
    expect(activeToggle(JSON.stringify({ active: { from: 'false', to: 'true' } }))).toBe('RESUME')
    expect(activeToggle(JSON.stringify({ isActive: { from: true, to: false } }))).toBeNull()
    expect(activeToggle(JSON.stringify({ active: { from: null, to: true } }))).toBeNull()
    expect(activeToggle(null)).toBeNull()
  })
})

describe('linkFor — ölü bağlantı yok', () => {
  it('izleme türü → derin bağlantı; silme olayı / sonradan silinmiş / izleme olmayan tür → null', () => {
    const r = { kind: 'PAGESPEED', resource_id: 4, event_type: 'UPDATE' }
    expect(linkFor(r)).toEqual({ tab: 'pagespeed', params: { monitor: 4, mtab: 'changes' }, href: '?tab=pagespeed&monitor=4&mtab=changes' })
    expect(linkFor({ ...r, event_type: 'DELETE' })).toBeNull()
    expect(linkFor({ ...r, resource_deleted: true })).toBeNull()
    expect(linkFor({ ...r, kind: 'INVENTORY' })).toBeNull()
  })

  it('izleme olmayan üç tür DIŞINDAKİ her tür sekme eşlemesinde (change-kinds-sync ile aynı muafiyet)', () => {
    expect(KINDS.filter(k => !TAB_BY_KIND[k])).toEqual(['inventory', 'group', 'maintenance'])
  })
})

describe('URL durumu', () => {
  beforeEach(() => window.history.replaceState(null, '', '/?tab=monitorchanges'))

  it('boş URL varsayılanları verir; varsayılan durum URL\'e HİÇBİR ŞEY yazmaz', () => {
    const s = readUrlState()
    expect(s).toMatchObject({ q: '', kind: '', eventType: '', actor: '', teamId: '', res: '', rangeKey: 'all', from: '', to: '', openId: '' })
    expect(Object.values(urlMapping(s)).every(v => v === null)).toBe(true)
  })

  it('gidiş-dönüş: özel aralık sabit uçlarla, hazır pencere yalnız anahtarla', () => {
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_range=custom&ch_from=2026-09-01T00:00:00&ch_to=2026-09-02T00:00:00&ch_res=port:7&ch_id=port:7:3')
    const s = readUrlState()
    expect(s).toMatchObject({ rangeKey: 'custom', from: '2026-09-01T00:00:00', to: '2026-09-02T00:00:00', res: 'port:7', openId: 'port:7:3' })
    const m = urlMapping(s)
    expect(m[URL_KEYS.from]).toBe('2026-09-01T00:00:00')
    expect(m[URL_KEYS.res]).toBe('port:7')

    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_range=7')
    const p = readUrlState()
    expect(p.from).toBe(windowFor('7').from)                 // açılışta ŞİMDİYE göre yeniden hesaplanır
    expect(urlMapping(p)[URL_KEYS.from]).toBeNull()          // göreli pencere sabit tarih yazmaz
    expect(urlMapping(p)[URL_KEYS.range]).toBe('7')
  })

  it('özel aralıkta başlangıç yoksa ya da biçim bozuksa "tümü"ne düşer', () => {
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_range=custom&ch_from=dün')
    expect(readUrlState()).toMatchObject({ rangeKey: 'all', from: '' })
  })
})

describe('dailySeries — boş günler sıfır, en çok 90 nokta', () => {
  it('pencerenin her yerel günü bir nokta', () => {
    const now = Date.now()
    const d = (n) => { const x = new Date(now); x.setDate(x.getDate() - n); return x }
    const s = dailySeries({ daily: [{ day: key(d(2)), count: 4 }] }, { from: windowFor('7').from, now })
    expect(s).toHaveLength(7)
    expect(s.at(-1)).toEqual({ day: key(d(0)), count: 0 })
    expect(s.find(p => p.day === key(d(2))).count).toBe(4)
  })

  it('sunucu sınırı (daily_since) pencereden yeniyse ondan başlar; 90 günü aşmaz', () => {
    const now = Date.now()
    const since = new Date(now); since.setDate(since.getDate() - 89)
    const s = dailySeries({ daily: [], daily_since: key(since) }, { from: '', now })
    expect(s).toHaveLength(90)
    expect(s[0].day).toBe(key(since))
  })
})

describe('changesCsv', () => {
  it('başlık + satır düzeyinde tam fark, sistem aktörü, silinmiş işareti; formül nötrlenir', () => {
    const csv = changesCsv([
      { kind: 'PORT', resource_id: 7, resource_name: '=HYPERLINK("x")', seq: 2, event_type: 'UPDATE', at: '2026-09-01T10:00:00',
        actor: null, resource_deleted: true, note: '@bad', changes: JSON.stringify({ active: { from: true, to: false }, tags: { from: ['a'], to: ['a', 'b'] } }) },
    ], t, (s) => `local:${s}`)
    const [head, row] = csv.replace(/^\uFEFF/, '').split('\r\n')
    expect(head.split(',')).toHaveLength(16)
    expect(row).toContain('local:2026-09-01T10:00:00')
    expect(row).toContain('2026-09-01T10:00:00Z')
    expect(row).toContain('⟨chg.eventPAUSE⟩')                  // durum değişikliği sütunu
    expect(row).toContain('⟨audit.systemActor⟩')
    expect(row).toContain('⟨chg.csvYes⟩')
    expect(row).toContain('"\'=HYPERLINK(""x"")"')           // formül nötr + tırnak kaçışı
    expect(row).toContain("'@bad")
    expect(row).toContain('⟨chg.field.tags⟩: a → a, b')
  })
})
