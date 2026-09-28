import { describe, it, expect } from 'vitest'
import {
  FILTER_DEFAULTS, EMPTY_FORM, filtersFromUrl, filtersToUrl, initialDetailId, serverParams, activeFilters, patchFilters,
  isCardActive, applyCardFilter, toggleDayFilter, dailySeries, validateIncident, errorCountBySection, firstErrorSection,
  formSnapshot, payloadFromForm, previewKind, uniqueCaption, buildTimeline, durationOf, csvList, detailLink,
} from '../components/incidenthistory/incidentHistoryModel.js'

const reader = (qs) => (k, fb = null) => new URLSearchParams(qs).get(k) ?? fb

describe('incidentHistoryModel — URL', () => {
  it('gidiş-dönüş: süzgeç → ih_* → süzgeç aynı', () => {
    const f = { ...FILTER_DEFAULTS, q: 'dns', severity: 'HIGH', status: 'OPEN', category: 'NETWORK', channel: 'Mobil Şube',
      team_id: '3', since: '2026-09-01', until: '2026-09-10', slaBreached: false, open: true, _preset: 'last7d' }
    const qs = new URLSearchParams(Object.entries(filtersToUrl(f)).filter(([, v]) => v != null)).toString()
    expect(qs).not.toMatch(/(^|&)(q|status|severity|tab|page)=/)   // yalnız ih_ önekli anahtarlar
    expect(filtersFromUrl(reader(qs))).toEqual(f)
  })

  it('bozuk / bilinmeyen değerler yok sayılır (adres çubuğu sayfayı bozamaz)', () => {
    const f = filtersFromUrl(reader('ih_sev=URGENT&ih_st=x&ih_team=1;drop&ih_from=2026-13&ih_sla=maybe&ih_preset=ever'))
    expect(f).toEqual({ ...FILTER_DEFAULTS })
  })

  it('varsayılan süzgeç adrese hiçbir şey yazmaz', () => {
    expect(Object.values(filtersToUrl(FILTER_DEFAULTS)).every((v) => v == null)).toBe(true)
  })

  it('ayrıntı kimliği: ih_id, yoksa e-postanın incident\'ı; sayısal olmayan yok sayılır', () => {
    expect(initialDetailId(reader('ih_id=5&incident=9'))).toBe('5')
    expect(initialDetailId(reader('incident=9'))).toBe('9')
    expect(initialDetailId(reader('incident=abc'))).toBeNull()
    expect(detailLink(7)).toMatch(/\?tab=incident-history&incident=7$/)
  })

  it('serverParams: boşları atar, tarihleri UTC sınırına çevirir, _preset göndermez', () => {
    const p = serverParams({ ...FILTER_DEFAULTS, q: '  x ', since: '2026-09-01', until: '2026-09-01', _preset: 'today', open: true })
    expect(p).toMatchObject({ q: 'x', open: true })
    expect(p.since).toMatch(/^2026-0[89]-\d\dT\d\d:\d\d:\d\d$/)
    expect(p.until).toMatch(/^2026-09-0[12]T\d\d:59:59$|^2026-09-01T\d\d:\d\d:\d\d$/)
    expect(p).not.toHaveProperty('_preset')
    expect(p).not.toHaveProperty('severity')
  })
})

describe('incidentHistoryModel — süzgeç eylemleri', () => {
  it('çipler: her etkin süzgeç bir çip; çipin yaması o süzgeci kaldırır', () => {
    const f = { ...FILTER_DEFAULTS, severity: 'LOW', since: '2026-09-01', slaBreached: true }
    const chips = activeFilters(f)
    expect(chips.map((c) => c.key)).toEqual(['severity', 'since', 'slaBreached'])
    let g = f
    for (const c of chips) g = patchFilters(g, c.patch)
    expect(activeFilters(g)).toEqual([])
  })

  it('tarih elle değişince önayar işareti düşer; aynı değer yaması durumu değiştirmez', () => {
    const f = { ...FILTER_DEFAULTS, since: '2026-09-01', until: '2026-09-28', _preset: 'last30d' }
    expect(patchFilters(f, { since: '2026-09-02' })._preset).toBeUndefined()
    expect(patchFilters(f, { severity: '' })).toBe(f)
  })

  it('kart: uygular, tekrar tıklayınca kaldırır; tarih ve takım korunur', () => {
    const f = { ...FILTER_DEFAULTS, since: '2026-09-01', team_id: '2', q: 'x' }
    const a = applyCardFilter(f, 'sla')
    expect(a).toMatchObject({ slaBreached: true, since: '2026-09-01', team_id: '2', q: '' })
    expect(isCardActive('sla', a)).toBe(true)
    const b = applyCardFilter(a, 'sla')
    expect(b.slaBreached).toBeUndefined()
    expect(isCardActive('total', b)).toBe(true)
  })

  it('önayar kartı tarihi yazar; tekrar tıklayınca tarihi de temizler', () => {
    const now = new Date(2026, 8, 28, 12)
    const a = applyCardFilter(FILTER_DEFAULTS, 'last7d', now)
    expect(a).toMatchObject({ since: '2026-09-21', until: '2026-09-28', _preset: 'last7d' })
    expect(applyCardFilter(a, 'last7d', now)).toMatchObject({ since: '', until: '', _preset: undefined })
  })

  it('trend günü: seçer, aynı güne tekrar → temizler', () => {
    const a = toggleDayFilter(FILTER_DEFAULTS, '2026-09-10')
    expect(a).toMatchObject({ since: '2026-09-10', until: '2026-09-10' })
    expect(toggleDayFilter(a, '2026-09-10')).toMatchObject({ since: '', until: '' })
  })

  it('günlük seri sürekli (olaysız gün 0) ve bugünle biter', () => {
    const now = new Date(2026, 8, 28, 12)
    const s = dailySeries([{ day: '2026-09-27', count: 3, critical: 1 }], 7, now)
    expect(s).toHaveLength(7)
    expect(s.at(-1).day).toBe('2026-09-28')
    expect(s.at(-2)).toMatchObject({ day: '2026-09-27', count: 3, critical: 1, high: 0 })
    expect(s[0].count).toBe(0)
  })
})

describe('incidentHistoryModel — form', () => {
  const valid = { ...EMPTY_FORM, title: 't', occurred_at: '2026-09-01T10:00:00', team_id: '1' }

  it('zorunlular + zaman sırası + negatif sayı', () => {
    expect(validateIncident(valid)).toEqual({})
    expect(Object.keys(validateIncident(EMPTY_FORM)).sort()).toEqual(['occurred_at', 'team_id', 'title'])
    expect(validateIncident({ ...valid, detected_at: '2026-09-01T12:00:00', resolved_at: '2026-09-01T11:00:00' }).resolved_at).toBe('inc.resolvedBeforeDetected')
    expect(validateIncident({ ...valid, resolved_at: '2026-09-01T09:00:00' }).resolved_at).toBe('inc.errResolvedBeforeOccurred')
    expect(validateIncident({ ...valid, detected_at: '2026-09-01T09:00:00' }).detected_at).toBe('inc.errDetectedBeforeOccurred')
    expect(validateIncident({ ...valid, affected_customers: '-3' }).affected_customers).toBe('inc.errNonNegative')
    expect(validateIncident({ ...valid, error_budget_burn_pct: '150' })).toEqual({})   // bütçe %100'ü aşabilir
  })

  it('hata → bölüm sayıları ve ilk hatalı bölüm (sekme sırası)', () => {
    const e = { affected_customers: 'x', tags: 'y', title: 'z' }
    expect(errorCountBySection(e)).toEqual({ impact: 1, codes: 1, summary: 1 })
    expect(firstErrorSection(e)).toBe('summary')
    expect(firstErrorSection({ tags: 'y' })).toBe('codes')
    expect(firstErrorSection({})).toBeNull()
  })

  it('kirli-form: oluş + çözülme varken türetilmiş süre karşılaştırmaya girmez', () => {
    const a = { ...valid, resolved_at: '2026-09-01T12:00:00', duration_minutes: '' }
    expect(formSnapshot({ ...a, duration_minutes: 120 })).toBe(formSnapshot(a))
    expect(formSnapshot({ ...a, title: 'u' })).not.toBe(formSnapshot(a))
    expect(formSnapshot({ ...valid, duration_minutes: 5 })).not.toBe(formSnapshot(valid))   // elle girilen süre sayılır
  })

  it('gövde: boş sayısal alanlar gönderilmez; önizleme türü sunucu kararıyla aynı', () => {
    expect(payloadFromForm({ ...valid, duration_minutes: '', error_budget_burn_pct: '' })).not.toHaveProperty('duration_minutes')
    expect(payloadFromForm({ ...valid, duration_minutes: 0 }).duration_minutes).toBe(0)
    expect(previewKind('create', 'RESOLVED')).toBe('NEW')
    expect(previewKind('edit', 'RESOLVED')).toBe('RESOLVED')
    expect(previewKind('edit', 'OPEN')).toBe('UPDATED')
  })

  it('görsel adı tekilleştirme: markdown alanlarındaki ve ayrılmış adlar çakışmaz', () => {
    const form = { rca_summary: '![a.png](/api/incidents/images/1)', description: '', resolution_steps: '', business_impact: '' }
    expect(uniqueCaption('a.png', form, new Set())).toBe('a (2).png')
    expect(uniqueCaption('a.png', form, new Set(['a (2).png']))).toBe('a (3).png')
    expect(uniqueCaption('b.png', form, new Set())).toBe('b.png')
  })
})

describe('incidentHistoryModel — ayrıntı', () => {
  it('zaman çizelgesi: çözülmüş olayda üç adım, geçen süreler dakika', () => {
    const tl = buildTimeline({ status: 'RESOLVED', occurred_at: '2026-09-01T10:00:00', detected_at: '2026-09-01T10:15:00', resolved_at: '2026-09-01T12:00:00' })
    expect(tl.map((s) => s.kind)).toEqual(['occurred', 'detected', 'resolved'])
    expect(tl[1].after).toBe(15)
    expect(tl[2].after).toBe(120)
  })

  it('zaman çizelgesi: açık olayda güncel durum adımı, eksik zamanlar pending', () => {
    const tl = buildTimeline({ status: 'MITIGATED', occurred_at: '2026-09-01T10:00:00' })
    expect(tl.map((s) => [s.kind, !!s.pending])).toEqual([['occurred', false], ['detected', true], ['status', false], ['resolved', true]])
    expect(tl[2].status).toBe('MITIGATED')
  })

  it('süre: kayıtlı süre > oluş↔çözülme farkı > (açıksa) şimdiye kadar', () => {
    const now = Date.parse('2026-09-01T11:00:00Z')
    expect(durationOf({ status: 'RESOLVED', duration_minutes: 42 }, now)).toEqual({ minutes: 42, ongoing: false })
    expect(durationOf({ status: 'RESOLVED', occurred_at: '2026-09-01T10:00:00', resolved_at: '2026-09-01T10:30:00' }, now).minutes).toBe(30)
    expect(durationOf({ status: 'OPEN', occurred_at: '2026-09-01T10:00:00' }, now)).toEqual({ minutes: 60, ongoing: true })
    expect(durationOf({ status: 'OPEN' }, now).minutes).toBeNull()
  })

  it('csvList: kırpar, boşları ve (harf duyarsız) tekrarları atar', () => {
    expect(csvList(' a, b ,,A, c ')).toEqual(['a', 'b', 'c'])
    expect(csvList(null)).toEqual([])
  })
})
