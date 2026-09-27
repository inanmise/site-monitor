import { describe, it, expect } from 'vitest'
import {
  idFromRef, isRefQuery, matchesFilters, sortRows, buildSteps, buildThread, filtersFromUrl, filtersToUrl, activeFilters,
  FILTER_DEFAULTS, serverParams, fmtRelative, dayLabel, istanbulDay, detailToRow,
} from '../components/issues/issuesModel.js'
import { reportHref, reportIdOf, loginHelpReason, loginHelpDetail, imagesFromClipboard } from '../components/issues/report/reportModel.js'

const t = (k, ...a) => `${k}${a.length ? ':' + a.join('|') : ''}`

describe('issuesModel — referans kodu', () => {
  it('idFromRef: LIR kodu, #n ve düz sayı; geçersiz → null', () => {
    expect(idFromRef('LIR-2026-000041')).toBe(41)
    expect(idFromRef('lir-2026-000041')).toBe(41)
    expect(idFromRef('#41')).toBe(41)
    expect(idFromRef(' 41 ')).toBe(41)
    expect(idFromRef('LIR-2026-000000')).toBeNull()
    expect(idFromRef('rapor')).toBeNull()
    expect(idFromRef('')).toBeNull()
  })
  it('isRefQuery: yalnız kod biçimleri', () => {
    expect(isRefQuery('LIR-2026-000041')).toBe(true)
    expect(isRefQuery('41')).toBe(true)
    expect(isRefQuery('pdf 41')).toBe(false)
    expect(isRefQuery('')).toBe(false)
  })
  it('reportHref / reportIdOf: başarı ekranı derin bağlantısı', () => {
    expect(reportHref('LIR-2026-000099')).toBe('/?tab=login-issues&ir_id=99')
    expect(reportIdOf('LIR-2026-000099')).toBe(99)
    expect(reportHref('')).toBeNull()
  })
})

describe('issuesModel — süzgeç / sıralama', () => {
  const rows = [
    { id: 1, refCode: 'LIR-2026-000001', status: 'OPEN', source: 'USER_REPORT', category: 'BLOCKER', reportedAt: '2026-09-20T10:00:00', lastActivityAt: '2026-09-25T10:00:00', messageSummary: 'PDF dışa aktarımı bozuk', unread: true },
    { id: 2, refCode: 'LIR-2026-000002', status: 'RESOLVED', source: 'CLIENT_ERROR', category: null, reportedAt: '2026-09-22T10:00:00', lastActivityAt: '2026-09-23T10:00:00', messageSummary: 'TypeError: map', unread: false },
    { id: 3, refCode: 'LIR-2026-000003', status: 'IN_PROGRESS', source: 'LOGIN', category: null, reportedAt: '2026-09-24T21:30:00', lastActivityAt: '2026-09-24T21:30:00', messageSummary: 'Giriş İŞLEMİ başarısız', unread: false },
  ]
  const f = (p) => ({ ...FILTER_DEFAULTS, ...p })
  const ids = (p) => rows.filter((r) => matchesFilters(r, f(p), t)).map((r) => r.id)

  it('durum, kaynak, önem, okunmamış', () => {
    expect(ids({ status: 'OPEN' })).toEqual([1])
    expect(ids({ source: 'CLIENT_ERROR' })).toEqual([2])
    expect(ids({ category: 'BLOCKER' })).toEqual([1])
    expect(ids({ awaiting: true })).toEqual([1])
  })
  it('arama: referans kodu (tam kayıt), metin (Türkçe İ/ı güvenli)', () => {
    expect(ids({ q: 'LIR-2026-000002' })).toEqual([2])
    expect(ids({ q: '3' })).toEqual([3])
    expect(ids({ q: 'pdf' })).toEqual([1])
    expect(ids({ q: 'işlemi' })).toEqual([3])
  })
  it('tarih aralığı Istanbul gününe göre (21:30 UTC → ertesi gün 00:30 Istanbul)', () => {
    expect(ids({ since: '2026-09-25' })).toEqual([3])
    expect(ids({ until: '2026-09-21' })).toEqual([1])
  })
  it('sıralama: son etkinlik / bildirim tarihi (yeni → eski)', () => {
    expect(sortRows(rows, 'activity').map((r) => r.id)).toEqual([1, 3, 2])
    expect(sortRows(rows, 'reported').map((r) => r.id)).toEqual([3, 2, 1])
  })
  it('URL gidiş-dönüş + çipler + sunucu parametreleri (kod araması metin olarak gönderilmez)', () => {
    const fl = f({ status: 'OPEN', source: 'LOGIN', q: 'abc', since: '2026-09-01', sort: 'reported', awaiting: true })
    const url = filtersToUrl(fl)
    expect(url).toMatchObject({ ir_status: 'OPEN', ir_src: 'LOGIN', ir_q: 'abc', ir_from: '2026-09-01', ir_sort: 'reported', ir_new: '1', ir_cat: null })
    expect(filtersFromUrl((k) => url[k] || '')).toEqual(fl)
    expect(filtersFromUrl(() => 'bozuk')).toMatchObject({ status: '', source: '', category: '', since: '', sort: 'activity', awaiting: false })
    expect(activeFilters(fl).map((x) => x.key)).toEqual(['status', 'awaiting', 'source', 'q', 'since'])
    expect(serverParams(f({ q: 'LIR-2026-000007' })).q).toBeUndefined()
    expect(serverParams(f({ q: ' locked ' })).q).toBe('locked')
  })
})

describe('issuesModel — durum adımları ve konuşma', () => {
  const base = { reportedAt: '2026-09-20T10:00:00', username: 'kullanici.x' }
  it('doğrudan çözülen kayıtta "İşlemde" atlanır; eski kayıtta çözüm zamanı resolvedAt\'tan', () => {
    const { steps } = buildSteps({ ...base, status: 'RESOLVED', resolvedAt: '2026-09-21T10:00:00', resolvedBy: 'someadmin', timeline: [] })
    expect(steps.map((s) => s.state)).toEqual(['done', 'skipped', 'done'])
    expect(steps[2]).toMatchObject({ at: '2026-09-21T10:00:00', by: 'someadmin' })
  })
  it('bildirenin yeniden açması: son RESOLVED\'dan sonraki geçiş', () => {
    const d = { ...base, status: 'IN_PROGRESS', timeline: [
      { status: 'OPEN', at: '2026-09-20T10:00:00', by: 'kullanici.x', byReporter: true },
      { status: 'RESOLVED', at: '2026-09-21T10:00:00', by: 'someadmin', byReporter: false },
      { status: 'IN_PROGRESS', at: '2026-09-22T10:00:00', by: 'kullanici.x', byReporter: true },
    ] }
    const { steps, reopened } = buildSteps(d)
    expect(steps.map((s) => s.state)).toEqual(['done', 'current', 'todo'])
    expect(reopened).toMatchObject({ at: '2026-09-22T10:00:00', byReporter: true })
  })
  it('açık kayıt: ilk adım güncel; yeniden açma yok', () => {
    const { steps, reopened } = buildSteps({ ...base, status: 'OPEN', timeline: [{ status: 'OPEN', at: base.reportedAt, by: 'kullanici.x', byReporter: true }] })
    expect(steps.map((s) => s.state)).toEqual(['current', 'todo', 'todo'])
    expect(reopened).toBeNull()
  })
  it('konuşma: yorumlar + durum geçişleri tek sırada, Istanbul gününe göre gruplu; ilk girdi "açıldı"', () => {
    const groups = buildThread({ ...base, timeline: [
      { status: 'OPEN', at: '2026-09-20T10:00:00', by: 'kullanici.x', byReporter: true },
      { status: 'IN_PROGRESS', at: '2026-09-21T09:00:00', by: 'someadmin' },
    ], comments: [
      { id: 7, author: 'someadmin', body: 'b', createdAt: '2026-09-21T08:00:00' },
      { id: 8, author: 'kullanici.x', byReporter: true, body: 'c', createdAt: '2026-09-21T22:30:00' },
    ] })
    expect(groups.map((g) => g.day)).toEqual(['2026-09-20', '2026-09-21', '2026-09-22'])
    expect(groups[0].items[0].kind).toBe('opened')
    expect(groups[1].items.map((i) => i.kind)).toEqual(['comment', 'status'])
    expect(groups[2].items[0].key).toBe('c8')
  })
  it('detailToRow: ayrıntıdan liste satırı (referans araması)', () => {
    expect(detailToRow({ id: 5, refCode: 'LIR-2026-000005', message: 'a   b', status: 'OPEN', images: ['x'] }))
      .toMatchObject({ id: 5, messageSummary: 'a b', imageCount: 1 })
  })
})

describe('issuesModel — zaman ve sebep metinleri', () => {
  it('göreli zaman eşikleri', () => {
    const now = Date.parse('2026-09-26T12:00:00Z')
    expect(fmtRelative('2026-09-26T11:59:40', t, now)).toBe('myIssues.relNow')
    expect(fmtRelative('2026-09-26T11:30:00', t, now)).toBe('myIssues.relMin:30')
    expect(fmtRelative('2026-09-26T09:00:00', t, now)).toBe('myIssues.relHour:3')
    expect(fmtRelative('2026-09-23T12:00:00', t, now)).toBe('myIssues.relDay:3')
    expect(fmtRelative(null, t, now)).toBe('—')
  })
  it('gün etiketi: bugün / dün / tarih', () => {
    const now = Date.parse('2026-09-26T12:00:00Z')
    expect(dayLabel(istanbulDay(now), t, 'en-GB', now)).toBe('issues.today')
    expect(dayLabel(istanbulDay(now - 86400000), t, 'en-GB', now)).toBe('issues.yesterday')
    expect(dayLabel('2026-09-01', t, 'en-GB', now)).toBe('1 September 2026')
  })
  it('giriş sayfası sebep eşlemesi + teknik ayrıntı', () => {
    expect(loginHelpReason({ networkError: true }, t)).toBe('login.helpErrNetwork')
    expect(loginHelpReason({ status: 429 }, t)).toBe('login.helpErrRate')
    expect(loginHelpReason({ status: 404 }, t)).toBe('login.helpErrDisabled')
    expect(loginHelpReason({ status: 503 }, t)).toBe('login.helpErrServer')
    expect(loginHelpReason({ status: 400, error: 'Alan uzun' }, t)).toBe('Alan uzun')
    expect(loginHelpDetail({ status: 429, error: 'x' })).toBe('HTTP 429 · x')
  })
  it('pano: yalnız görsel dosyalar', () => {
    const img = new File(['x'], 'a.png', { type: 'image/png' })
    const txt = new File(['x'], 'a.txt', { type: 'text/plain' })
    expect(imagesFromClipboard({ clipboardData: { files: [img, txt], items: [] } })).toEqual([img])
    expect(imagesFromClipboard({ clipboardData: { files: [], items: [{ kind: 'file', type: 'image/png', getAsFile: () => img }] } })).toEqual([img])
    expect(imagesFromClipboard({})).toEqual([])
  })
})
