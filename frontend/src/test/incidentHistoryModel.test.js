import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import {
  FILTER_DEFAULTS, EMPTY_FORM, filtersFromUrl, filtersToUrl, initialDetailId, serverParams, activeFilters, patchFilters,
  isCardActive, applyCardFilter, toggleDayFilter, dailySeries, validateIncident, errorCountBySection, firstErrorSection,
  formSnapshot, payloadFromForm, previewKind, uniqueCaption, buildTimeline, durationOf, csvList, detailLink,
  FIELD_SECTION, MAX_TECH_ERROR_COUNT, MAX_OUTAGE_ID, techErrorCountProblem, outageIdProblem, outageAnswerOf,
  techErrorCountOf, outageIdOf, formFromRow, OUTAGE_PAYLOAD_KEYS,
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

  it('kart: uygular, tekrar tıklayınca kaldırır; tarih, takım ve arama korunur (2026-11-26: kartlar yalnız kendi boyutunu sıfırlar)', () => {
    const f = { ...FILTER_DEFAULTS, since: '2026-09-01', team_id: '2', q: 'x' }
    const a = applyCardFilter(f, 'sla')
    expect(a).toMatchObject({ slaBreached: true, since: '2026-09-01', team_id: '2', q: 'x' })
    expect(isCardActive('sla', a)).toBe(true)
    const b = applyCardFilter(a, 'sla')
    expect(b.slaBreached).toBeUndefined()
    expect(isCardActive('total', b)).toBe(true)
  })

  it('önayar kartı tarihi yazar (son 7 gün = bugün dahil 7 takvim günü); tekrar tıklayınca tarihi de temizler', () => {
    const now = new Date(2026, 8, 28, 12)
    const a = applyCardFilter(FILTER_DEFAULTS, 'last7d', now)
    expect(a).toMatchObject({ since: '2026-09-22', until: '2026-09-28', _preset: 'last7d' })
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

// ── Teknik hata adedi · kesinti kaynaklı mı · kesinti ID (2026-11-20) ──────────────────────────────────────────────
// Üçü de isteğe bağlıdır. Kurallar sunucudaki IncidentService.strictTechErrorCount / strictOutageRelated / strictOutageId
// ile AYNIDIR (sınırlar Java kaynağından okunur). Bilinmeyen (boş / seçilmemiş) asla "Hayır" ya da 0 sayılmaz.
describe('incidentHistoryModel — teknik hata adedi ve kesinti alanları', () => {
  const valid = { ...EMPTY_FORM, title: 't', occurred_at: '2026-09-01T10:00:00', team_id: '1' }
  const ch = (code) => String.fromCharCode(code)

  it('sınırlar sunucudaki sabitlerle aynı (IncidentService.java)', () => {
    const src = readJavaMain('service/IncidentService.java')
    const max = /MAX_TECH_ERROR_COUNT\s*=\s*([\d_]+)L/.exec(src)
    const idMax = /MAX_OUTAGE_ID\s*=\s*(\d+)\s*;/.exec(src)
    expect(max, 'MAX_TECH_ERROR_COUNT Java kaynağında bulunamadı').not.toBeNull()
    expect(idMax, 'MAX_OUTAGE_ID Java kaynağında bulunamadı').not.toBeNull()
    expect(Number(max[1].replaceAll('_', ''))).toBe(MAX_TECH_ERROR_COUNT)
    expect(Number(idMax[1])).toBe(MAX_OUTAGE_ID)
    expect(Number.isSafeInteger(MAX_TECH_ERROR_COUNT)).toBe(true)      // gövdeye SAYI olarak kayıpsız yazılabilir
    // Tel adları sunucununkiyle aynı
    for (const key of ['tech_error_count', 'outage_related', 'outage_id']) {
      expect(src, key).toContain(`"${key}"`)
      expect(key in EMPTY_FORM, key).toBe(true)
    }
  })

  it('boş form: adet boş metin, cevap null (bilinmiyor — false DEĞİL), kimlik boş; üçü de Etki bölümünde', () => {
    expect(EMPTY_FORM.tech_error_count).toBe('')
    expect(EMPTY_FORM.outage_related).toBeNull()
    expect(EMPTY_FORM.outage_id).toBe('')
    expect(FIELD_SECTION.tech_error_count).toBe('impact')
    expect(FIELD_SECTION.outage_related).toBe('impact')
    expect(FIELD_SECTION.outage_id).toBe('impact')
    expect(errorCountBySection({ tech_error_count: 'x', outage_id: 'y', outage_related: 'z' })).toEqual({ impact: 3 })
    expect(firstErrorSection({ outage_id: 'y' })).toBe('impact')
    expect(validateIncident(valid)).toEqual({})                         // üçü de boşken geçerli
  })

  it('teknik hata adedi doğrulama tablosu (sunucuyla aynı kural)', () => {
    const ok = ['', '   ', null, undefined, '0', '7', '1250', ' 1250 ', '0007', '-0', 0, 1250,
      '1000000000000000', MAX_TECH_ERROR_COUNT, '00001000000000000000']
    for (const v of ok) expect(techErrorCountProblem(v), `geçerli: ${JSON.stringify(v)}`).toBeNull()

    const whole = ['abc', '12.7', '12,7', '1.250', '1,250', '1e3', '+5', '1 250', '0x10', '-', '--5', '5-', '१२', 12.7, 0.5,
      Number.NaN, Number.POSITIVE_INFINITY, 1e21, true, '12abc']
    for (const v of whole) expect(techErrorCountProblem(v), `tam sayı değil: ${String(v)}`).toBe('inc.errTechCountWhole')

    for (const v of ['-1', '-3', -1, '-0001', `-${'9'.repeat(60)}`]) {
      expect(techErrorCountProblem(v), `negatif: ${String(v)}`).toBe('inc.errTechCountNegative')
    }
    for (const v of ['1000000000000001', 1e16, '9'.repeat(17), '9'.repeat(400)]) {
      expect(techErrorCountProblem(v), `çok büyük: ${String(v).slice(0, 20)}`).toBe('inc.errTechCountMax')
    }

    expect(validateIncident({ ...valid, tech_error_count: '12.7' })).toEqual({ tech_error_count: 'inc.errTechCountWhole' })
    expect(validateIncident({ ...valid, tech_error_count: '-2' })).toEqual({ tech_error_count: 'inc.errTechCountNegative' })
    expect(validateIncident({ ...valid, tech_error_count: '1000000000000001' })).toEqual({ tech_error_count: 'inc.errTechCountMax' })
    expect(validateIncident({ ...valid, tech_error_count: '1000000000000000' })).toEqual({})
    expect(validateIncident({ ...valid, tech_error_count: '0' })).toEqual({})
  })

  it('doğrulama doğrusal: çok uzun girdi takılmadan sonuçlanır', () => {
    const t0 = performance.now()
    expect(techErrorCountProblem('9'.repeat(200_000))).toBe('inc.errTechCountMax')
    expect(techErrorCountProblem(`${'9'.repeat(200_000)}x`)).toBe('inc.errTechCountWhole')
    expect(outageIdProblem('x'.repeat(200_000))).toBe('inc.errOutageIdLong')
    expect(performance.now() - t0).toBeLessThan(1500)
  })

  it('kesinti ID doğrulama: kırpılır, boş geçerli, en çok 60 karakter, içinde satır sonu olamaz', () => {
    expect(outageIdProblem('')).toBeNull()
    expect(outageIdProblem('   ')).toBeNull()
    expect(outageIdProblem(null)).toBeNull()
    expect(outageIdProblem('INC-2026-0915')).toBeNull()
    expect(outageIdProblem('x'.repeat(MAX_OUTAGE_ID))).toBeNull()
    expect(outageIdProblem(`  ${'x'.repeat(MAX_OUTAGE_ID)}  `)).toBeNull()           // kenar boşluğu sayılmaz
    expect(outageIdProblem('x'.repeat(MAX_OUTAGE_ID + 1))).toBe('inc.errOutageIdLong')
    for (const code of [0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029]) {
      expect(outageIdProblem(`INC${ch(code)}42`), `satır sonu 0x${code.toString(16)}`).toBe('inc.errOutageIdLine')
    }
    expect(outageIdProblem(`INC-42${ch(0x0A)}`)).toBeNull()                           // sondaki satır sonu kırpılır
  })

  it('kesinti ID yalnız cevap Evet iken denetlenir (Hayır / bilinmiyor iken alan gizli, gövdeye null yazılır)', () => {
    const long = 'x'.repeat(MAX_OUTAGE_ID + 5)
    expect(validateIncident({ ...valid, outage_related: true, outage_id: long })).toEqual({ outage_id: 'inc.errOutageIdLong' })
    expect(validateIncident({ ...valid, outage_related: false, outage_id: long })).toEqual({})
    expect(validateIncident({ ...valid, outage_related: null, outage_id: long })).toEqual({})
    expect(validateIncident({ ...valid, outage_related: true, outage_id: '' })).toEqual({})    // Evet'te de zorunlu değil
    expect(validateIncident({ ...valid, outage_related: true, outage_id: `a${ch(0x0A)}b` })).toEqual({ outage_id: 'inc.errOutageIdLine' })
  })

  it('gövde (yeni kayıt / önizleme — karşılaştırma kaydı yok): üç anahtar HER ZAMAN var — boş adet null, geçerli adet SAYI, cevap true / false / null', () => {
    const empty = payloadFromForm(valid)
    expect(empty).toHaveProperty('tech_error_count', null)
    expect(empty).toHaveProperty('outage_related', null)
    expect(empty).toHaveProperty('outage_id', null)

    expect(payloadFromForm({ ...valid, tech_error_count: '1250' }).tech_error_count).toBe(1250)
    expect(payloadFromForm({ ...valid, tech_error_count: ' 0007 ' }).tech_error_count).toBe(7)
    expect(payloadFromForm({ ...valid, tech_error_count: '0' }).tech_error_count).toBe(0)       // 0 ≠ bilinmiyor
    expect(Object.is(payloadFromForm({ ...valid, tech_error_count: '-0' }).tech_error_count, 0)).toBe(true)
    expect(payloadFromForm({ ...valid, tech_error_count: '   ' }).tech_error_count).toBeNull()
    expect(payloadFromForm({ ...valid, tech_error_count: 42 }).tech_error_count).toBe(42)
    expect(payloadFromForm({ ...valid, tech_error_count: '1000000000000000' }).tech_error_count).toBe(1e15)
    // Geçersiz metin sessizce null'a (= "temizle") ÇEVRİLMEZ: olduğu gibi gider, sunucu alan hatası döner
    expect(payloadFromForm({ ...valid, tech_error_count: '12.7' }).tech_error_count).toBe('12.7')
    expect(payloadFromForm({ ...valid, tech_error_count: 'abc' }).tech_error_count).toBe('abc')

    expect(payloadFromForm({ ...valid, outage_related: true }).outage_related).toBe(true)
    expect(payloadFromForm({ ...valid, outage_related: false }).outage_related).toBe(false)
    for (const odd of [undefined, '', 'true', 'false', 0, 1]) {
      expect(payloadFromForm({ ...valid, outage_related: odd }).outage_related, `cevap ${JSON.stringify(odd)}`).toBeNull()
    }
    // JSON'da anahtarlar gerçekten var (undefined olsaydı JSON.stringify düşürürdü → sunucu "dokunma" sayardı)
    const wire = JSON.parse(JSON.stringify(payloadFromForm(valid)))
    for (const key of ['tech_error_count', 'outage_related', 'outage_id']) expect(key in wire, key).toBe(true)
  })

  it('gövde: kesinti ID yalnız cevap Evet ve değer doluyken (kırpılmış); aksi halde null', () => {
    expect(payloadFromForm({ ...valid, outage_related: true, outage_id: '  INC-2026-0915  ' }).outage_id).toBe('INC-2026-0915')
    expect(payloadFromForm({ ...valid, outage_related: true, outage_id: '   ' }).outage_id).toBeNull()
    expect(payloadFromForm({ ...valid, outage_related: true, outage_id: '' }).outage_id).toBeNull()
    expect(payloadFromForm({ ...valid, outage_related: false, outage_id: 'INC-1' }).outage_id).toBeNull()
    expect(payloadFromForm({ ...valid, outage_related: null, outage_id: 'INC-1' }).outage_id).toBeNull()
    expect(payloadFromForm({ ...valid, outage_related: 'true', outage_id: 'INC-1' }).outage_id).toBeNull()
    // Mevcut gövde kuralları değişmedi
    expect(payloadFromForm({ ...valid, duration_minutes: '', error_budget_burn_pct: '' })).not.toHaveProperty('error_budget_burn_pct')
    expect(payloadFromForm({ ...valid, title: 'x' }).title).toBe('x')
  })

  it('gövde (düzenleme — formun açıldığı kayıt verilir): üç anahtardan yalnız DEĞİŞEN gönderilir', () => {
    const sent = (form, row) => OUTAGE_PAYLOAD_KEYS.filter((k) => Object.prototype.hasOwnProperty.call(payloadFromForm(form, row), k))
    expect(OUTAGE_PAYLOAD_KEYS).toEqual(['tech_error_count', 'outage_related', 'outage_id'])

    // Eski kayıt (anahtarlar hiç yok / null) + dokunulmamış form → hiçbiri (sunucuda "anahtar yok" = dokunma)
    const oldRow = { ...valid, id: 7 }
    expect(sent(formFromRow(oldRow), oldRow)).toEqual([])
    const nullRow = { ...valid, id: 7, tech_error_count: null, outage_related: null, outage_id: null }
    expect(sent(formFromRow(nullRow), nullRow)).toEqual([])
    // Sayfanın düzenleme açılışı: { ...EMPTY_FORM, ...kayıt } — boş metinler de "yok" sayılır
    const pageInitial = { ...EMPTY_FORM, ...oldRow }
    expect(sent(formFromRow(pageInitial), pageInitial)).toEqual([])
    // Öteki alanlar kuraldan ETKİLENMEZ (form eskisi gibi tamamını yazar)
    expect(payloadFromForm({ ...formFromRow(oldRow), title: 'yeni başlık' }, oldRow)).toMatchObject({ id: 7, title: 'yeni başlık', team_id: '1' })

    // Tek tek değişiklikler
    expect(sent({ ...formFromRow(oldRow), tech_error_count: '1250' }, oldRow)).toEqual(['tech_error_count'])
    expect(sent({ ...formFromRow(oldRow), outage_related: false }, oldRow)).toEqual(['outage_related'])
    expect(sent({ ...formFromRow(oldRow), outage_related: true }, oldRow)).toEqual(['outage_related'])
    expect(sent({ ...formFromRow(oldRow), outage_related: true, outage_id: 'INC-1' }, oldRow)).toEqual(['outage_related', 'outage_id'])
    // Cevap Evet değilken alanda kalan kimlik değişiklik DEĞİLDİR
    expect(sent({ ...formFromRow(oldRow), outage_id: 'INC-1' }, oldRow)).toEqual([])

    // Kayıtlı değerleri olan olay
    const row = { ...valid, id: 7, tech_error_count: 1250, outage_related: true, outage_id: 'INC-7' }
    const f = formFromRow(row)
    expect(sent(f, row)).toEqual([])
    expect(sent({ ...f, tech_error_count: ' 01250 ' }, row)).toEqual([])                 // aynı sayı
    expect(sent({ ...f, outage_id: '  INC-7 ' }, row)).toEqual([])                      // kırpılınca aynı
    expect(sent({ ...f, tech_error_count: '0' }, row)).toEqual(['tech_error_count'])
    expect(payloadFromForm({ ...f, tech_error_count: '' }, row).tech_error_count).toBeNull()   // boşaltıldı → açık null (temizle)
    expect(sent({ ...f, tech_error_count: '' }, row)).toEqual(['tech_error_count'])
    // Evet → Hayır / bilinmiyor: cevap VE kimlik (null) gönderilir
    for (const answer of [false, null]) {
      const p = payloadFromForm({ ...f, outage_related: answer }, row)
      expect(OUTAGE_PAYLOAD_KEYS.filter((k) => k in p), `cevap ${answer}`).toEqual(['outage_related', 'outage_id'])
      expect(p.outage_related).toBe(answer)
      expect(p.outage_id).toBeNull()
    }
    expect(payloadFromForm({ ...f, outage_id: '' }, row)).toHaveProperty('outage_id', null)
    expect(sent({ ...f, outage_id: '' }, row)).toEqual(['outage_id'])
    expect(payloadFromForm({ ...f, outage_id: 'INC-8' }, row).outage_id).toBe('INC-8')
    // Geçersiz adet metni kayıttakinden farklıdır → olduğu gibi gider (sunucu alan hatası döner)
    expect(payloadFromForm({ ...f, tech_error_count: '1.250' }, row).tech_error_count).toBe('1.250')

    // 0 kayıtlı + Hayır kayıtlı: dokunulmamış form yine hiçbirini göndermez (0 ≠ null, false ≠ null)
    const zeroRow = { ...valid, id: 7, tech_error_count: 0, outage_related: false, outage_id: null }
    expect(sent(formFromRow(zeroRow), zeroRow)).toEqual([])
    expect(sent({ ...formFromRow(zeroRow), tech_error_count: '' }, zeroRow)).toEqual(['tech_error_count'])
    expect(sent({ ...formFromRow(zeroRow), outage_related: null }, zeroRow)).toEqual(['outage_related'])

    // Karşılaştırma kaydı yok / nesne değil → kural uygulanmaz (yeni kayıt)
    for (const none of [undefined, null, 0, '', 'x']) {
      expect(sent(formFromRow(oldRow), none), `baseline ${JSON.stringify(none)}`).toEqual(OUTAGE_PAYLOAD_KEYS)
    }
  })

  it('kayıt → form: adet metne, cevap kesin true / false / null, kimlik metne; anahtarsız (eski) kayıt "bilinmiyor" açılır', () => {
    const f = formFromRow({ id: 7, title: 'Olay', tech_error_count: 1250, outage_related: true, outage_id: 'INC-1', sla_breached: true })
    expect(f).toMatchObject({ id: 7, title: 'Olay', tech_error_count: '1250', outage_related: true, outage_id: 'INC-1', sla_breached: true })
    expect(f.severity).toBe(EMPTY_FORM.severity)                         // eksik alanlar boş formdan
    expect(formFromRow({ tech_error_count: 0, outage_related: false, outage_id: null }))
      .toMatchObject({ tech_error_count: '0', outage_related: false, outage_id: '' })
    expect(formFromRow({ tech_error_count: null, outage_related: null, outage_id: null }))
      .toMatchObject({ tech_error_count: '', outage_related: null, outage_id: '' })
    expect(formFromRow({ id: 1, title: 'eski pod' })).toMatchObject({ tech_error_count: '', outage_related: null, outage_id: '' })
    for (const odd of ['true', 'false', 0, 1, '', undefined]) {
      expect(formFromRow({ outage_related: odd }).outage_related, `cevap ${JSON.stringify(odd)}`).toBeNull()
    }
    expect(formFromRow(null)).toEqual({ ...EMPTY_FORM })
    expect(formFromRow(undefined)).toEqual({ ...EMPTY_FORM })
    // Gidiş-dönüş: kayıt → form → gövde aynı değerleri taşır
    const back = payloadFromForm(formFromRow({ ...valid, tech_error_count: 1250, outage_related: true, outage_id: 'INC-1' }))
    expect(back).toMatchObject({ tech_error_count: 1250, outage_related: true, outage_id: 'INC-1' })
    const unknown = payloadFromForm(formFromRow({ ...valid, tech_error_count: null, outage_related: null, outage_id: null }))
    expect(unknown).toMatchObject({ tech_error_count: null, outage_related: null, outage_id: null })
  })

  it('kirli-form: açılan kayıt kirli değil; cevap / adet değişince kirli; Evet değilken alanda kalan kimlik sayılmaz', () => {
    const row = { ...valid, tech_error_count: 1250, outage_related: false, outage_id: null }
    const opened = formFromRow(row)
    expect(formSnapshot(opened)).toBe(formSnapshot(formFromRow(row)))
    expect(formSnapshot(formFromRow({ ...valid }))).toBe(formSnapshot({ ...valid }))             // anahtarsız kayıt = boş form
    expect(formSnapshot({ ...opened, tech_error_count: '1251' })).not.toBe(formSnapshot(opened))
    expect(formSnapshot({ ...opened, outage_related: null })).not.toBe(formSnapshot(opened))     // Hayır → bilinmiyor bir DEĞİŞİKLİKTİR
    expect(formSnapshot({ ...opened, outage_related: true })).not.toBe(formSnapshot(opened))
    // Evet → kimlik yaz → yeniden Hayır: gövde açılıştakiyle aynı → kirli değil
    expect(formSnapshot({ ...opened, outage_related: false, outage_id: 'INC-YARIM' })).toBe(formSnapshot(opened))
    const yes = { ...opened, outage_related: true, outage_id: 'INC-1' }
    expect(formSnapshot({ ...yes, outage_id: 'INC-2' })).not.toBe(formSnapshot(yes))
    expect(formSnapshot({ ...yes, outage_id: '  INC-1 ' })).toBe(formSnapshot(yes))               // gövdeye kırpılmış yazılır
  })

  it('ayrıntı yardımcıları: girilmemiş değer null / boş döner — bilinmeyen cevap "Hayır" DEĞİLDİR', () => {
    expect(outageAnswerOf({ outage_related: true })).toBe(true)
    expect(outageAnswerOf({ outage_related: false })).toBe(false)
    for (const odd of [null, undefined, '', 'false', 0]) expect(outageAnswerOf({ outage_related: odd })).toBeNull()
    expect(outageAnswerOf(null)).toBeNull()

    expect(techErrorCountOf({ tech_error_count: 1250 })).toBe(1250)
    expect(techErrorCountOf({ tech_error_count: '1250' })).toBe(1250)
    expect(techErrorCountOf({ tech_error_count: 0 })).toBe(0)                                     // 0 girilmiş bir değerdir
    for (const none of [null, undefined, '', '  ', 'abc', -1, 12.5, true, [], {}]) {
      expect(techErrorCountOf({ tech_error_count: none }), `adet ${JSON.stringify(none)}`).toBeNull()
    }
    expect(techErrorCountOf({})).toBeNull()
    expect(techErrorCountOf(null)).toBeNull()

    expect(outageIdOf({ outage_related: true, outage_id: ' INC-1 ' })).toBe('INC-1')
    expect(outageIdOf({ outage_related: true, outage_id: '' })).toBe('')
    expect(outageIdOf({ outage_related: true, outage_id: null })).toBe('')
    expect(outageIdOf({ outage_related: false, outage_id: 'INC-1' })).toBe('')
    expect(outageIdOf({ outage_related: null, outage_id: 'INC-1' })).toBe('')
  })

  it('i18n: yeni anahtarlar iki dilde de var; doğrulama iletileri açıklayıcı; etiket anahtarı "hata" kapısına takılmaz', () => {
    const keys = ['inc.fTechFaultCount', 'inc.techFaultCountHint', 'inc.fOutageRelated', 'inc.outageRelatedHint', 'inc.outageYes',
      'inc.outageNo', 'inc.fOutageId', 'inc.outageIdHint', 'inc.outageIdPlaceholder', 'inc.dTechFaults', 'inc.dOutage',
      'inc.dOutageYesId', 'inc.errTechCountWhole', 'inc.errTechCountNegative', 'inc.errTechCountMax', 'inc.errOutageIdLong',
      'inc.errOutageIdLine']
    for (const k of keys) {
      expect(typeof TR[k], `TR ${k}`).toBe('string')
      expect(typeof EN[k], `EN ${k}`).toBe('string')
      expect(TR[k].trim(), `TR ${k}`).not.toBe('')
      expect(EN[k].trim(), `EN ${k}`).not.toBe('')
      // Hata iletisi OLMAYAN anahtar adları kalite kapısının desenine (error / failed / err.) girmemeli
      if (!k.startsWith('inc.err')) expect(k).not.toMatch(/error|Error|failed|Failed|err\./)
    }
    for (const k of keys.filter((x) => x.startsWith('inc.err'))) {
      expect(TR[k].length, `TR ${k}`).toBeGreaterThanOrEqual(25)
      expect(EN[k].length, `EN ${k}`).toBeGreaterThanOrEqual(25)
    }
    // Modelin döndürdüğü her hata anahtarı sözlükte var (t() ham anahtar göstermesin)
    for (const k of ['inc.errTechCountWhole', 'inc.errTechCountNegative', 'inc.errTechCountMax', 'inc.errOutageIdLong', 'inc.errOutageIdLine']) {
      expect(k in TR && k in EN, k).toBe(true)
    }
    expect(TR['inc.errOutageIdLong']).toContain(String(MAX_OUTAGE_ID))
    expect(EN['inc.errOutageIdLong']).toContain(String(MAX_OUTAGE_ID))
    expect(TR['inc.outageIdHint']).toContain(String(MAX_OUTAGE_ID))
    expect(EN['inc.outageIdHint']).toContain(String(MAX_OUTAGE_ID))
    expect(TR['inc.dOutageYesId']).toContain('{0}')
    expect(EN['inc.dOutageYesId']).toContain('{0}')
  })
})
