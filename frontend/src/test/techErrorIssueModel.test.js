import { describe, it, expect } from 'vitest'
import {
  canonicalTemplate, canonicalValues, conformIssue, defaultIssueOptions, issueDayLabel, issueHidden, issueImageAlive, issueLegacy,
  issueOptions, issueProblems, issueRangeLabel, issueSlotId, issueTime, issueValidation, isRealDate, newSection, parseIssueSlot,
  ratioOfWarning, ratioSectionsUsing, reportErrorSection, reportMonthBounds, revertInvalidValues, templateDiff,
} from '../components/techerror/techErrorModel.js'

// Anomali konularının istemci modeli (2026-10-18): soruların okunması, kanonik değer (gizli cevap gönderilmez), istemci
// denetimi (sunucu kuralının aynası), otomatik kayıtta bağlı cevapların birlikte geri dönmesi, görsel yuvaları, şablon farkı.

const SEC = { id: 'issues', kind: 'issues', title: 'Anomaliler', item_title: '{konu}: Hata Artışı', description_label: 'Ne oldu?',
  customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Hata adedi', ...defaultIssueOptions('tr') }
const LEGACY = { id: 'old', kind: 'issues', title: 'Eski', item_title: '{konu}', description_label: 'A', customers_label: 'B', errors_label: 'C' }
const TPL = { lang: 'tr', title: 'T', sections: [
  { id: 'm', kind: 'metrics', title: 'V', fields: [{ id: 'error-count', label: 'Hata', type: 'number' }] }, SEC, LEGACY] }
const SEPT = { year: 2026, month: 9 }

describe('anomali soruları', () => {
  it('varsayılanlar açık; anahtar yoksa eski düzen; boş etiket şablon dilinin varsayılanı; gizleme yalnız etki sorulunca', () => {
    const o = issueOptions(SEC, 'tr')
    expect(o).toMatchObject({ askTime: true, askOutage: true, askImpact: true, allowImages: true, ratioOf: 'error-count',
      noImpactDisplay: 'hide_count', hideTopic: false, outageIdLabel: 'Kesinti ID' })
    expect(issueLegacy(o)).toBe(false)
    expect(issueLegacy(issueOptions(LEGACY))).toBe(true)
    expect(issueOptions({ ...SEC, outage_label: '  ' }, 'en').outageLabel).toBe('Caused by an outage?')
    expect(issueOptions({ ...SEC, ask_impact: false, no_impact_display: 'hide_topic' }).hideTopic).toBe(false)
    expect(issueHidden({ ...SEC, no_impact_display: 'hide_topic' }, { impact: false })).toBe(true)
    expect(issueHidden({ ...SEC, no_impact_display: 'hide_topic' }, { impact: null })).toBe(false)
    expect(issueHidden(SEC, { impact: false })).toBe(false)
  })

  it('yeni anomali bölümü varsayılan sorularla, etiketler şablon dilinde', () => {
    const labels = { title: 'A', itemTitle: 'B', descriptionLabel: 'C', customersLabel: 'D', errorsLabel: 'E' }
    const tr = newSection('issues', { lang: 'tr', sections: [] }, labels)
    expect(tr).toMatchObject({ ask_time: true, ask_outage: true, ask_impact: true, allow_images: true, ratio_of: 'error-count',
      outage_label: 'Kesinti kaynaklı mı?', no_impact_display: 'hide_count' })
    expect(newSection('issues', { lang: 'en', sections: [] }, labels).impact_label).toBe('Was there customer impact?')
  })
})

describe('tarih / saat yardımcıları', () => {
  it('gerçek gün, saat biçimi, ayın sınırları, gün ve aralık metni', () => {
    expect(isRealDate('2026-09-30')).toBe(true)
    expect(isRealDate('2026-02-29')).toBe(false)
    expect(isRealDate('2028-02-29')).toBe(true)
    expect(isRealDate('12.09.2026')).toBe(false)
    expect(issueTime('14:00:00')).toBe('14:00')
    expect(issueTime('24:00')).toBeNull()
    expect(reportMonthBounds(2026, 2)).toEqual({ first: '2026-02-01', last: '2026-02-28' })
    expect(issueDayLabel('2026-09-05', 'tr')).toBe('5 Eylül 2026')
    expect(issueDayLabel('2026-09-05', 'en')).toBe('5 September 2026')
    expect(issueRangeLabel({ start: '23:30', end: '01:15', end_next_day: true }, 'tr')).toBe('23:30 – ertesi gün 01:15')
    expect(issueRangeLabel({ start: '14:00', end: '15:30' }, 'en')).toBe('14:00–15:30')
    expect(issueRangeLabel({ start: '14:00' }, 'tr')).toBeNull()
  })
})

describe('istemci denetimi (sunucu kuralının aynası)', () => {
  const p = (issue, period = SEPT) => issueProblems(SEC, { id: 'k', ...issue }, period)
  it('tarih: geçersiz, ay dışı; saat aralığı: yarım (zorunlu), bitiş önce, ertesi gün > 24 sa, tarihsiz aralık (zorunlu)', () => {
    expect(p({ date: '2026-02-31' }).date.key).toBe('te.iss.err.dateInvalid')
    expect(p({ date: '2026-10-01' }).date).toMatchObject({ key: 'te.iss.err.dateOutsideMonth', required: false, params: ['Eylül', 2026] })
    expect(p({ date: '2026-09-12', start: '14:00' }).end).toMatchObject({ key: 'te.iss.err.endRequired', required: true })
    expect(p({ date: '2026-09-12', end: '14:00' }).start).toMatchObject({ key: 'te.iss.err.startRequired', required: true })
    expect(p({ date: '2026-09-12', start: '15:00', end: '14:00' }).end.key).toBe('te.iss.err.endBeforeStart')
    expect(p({ date: '2026-09-12', start: '14:00', end: '14:00' }).end.key).toBe('te.iss.err.endBeforeStart')
    expect(p({ date: '2026-09-12', start: '10:00', end: '11:00', end_next_day: true }).end.key).toBe('te.iss.err.rangeTooLong')
    expect(p({ date: '2026-09-12', start: '23:00', end: '01:00', end_next_day: true })).toEqual({})
    expect(p({ date: '2026-09-12', start: '10:00', end: '10:00', end_next_day: true })).toEqual({})     // tam 24 saat
    expect(p({ start: '14:00', end: '15:00' }).date).toMatchObject({ key: 'te.iss.err.dateRequired', required: true })
    expect(p({ date: '2026-10-01' }, null)).toEqual({})                                                 // dönem bilinmiyor
  })

  it('kesinti Evet ise ID zorunlu (boşluk yetmez), en çok 60; Hayır / cevapsız ise sorun yok; soru kapalıysa denetlenmez', () => {
    expect(p({ outage: true }).outage_id).toMatchObject({ key: 'te.iss.err.outageIdRequired', required: true })
    expect(p({ outage: true, outage_id: '   ' }).outage_id.key).toBe('te.iss.err.outageIdRequired')
    expect(p({ outage: true, outage_id: 'X'.repeat(61) }).outage_id).toMatchObject({ key: 'te.iss.err.outageIdTooLong', params: [60] })
    expect(p({ outage: false })).toEqual({})
    expect(issueProblems(LEGACY, { id: 'k', outage: true, date: '2030-01-01' }, SEPT)).toEqual({})
  })

  it('inceleme 2026-10-18: ay adı ARAYÜZ dilinde (Türkçe şablon, İngilizce arayüz → "September"); verilmezse şablon dili', () => {
    expect(issueProblems(SEC, { id: 'k', date: '2026-10-01' }, SEPT, 'tr', 'en').date.params).toEqual(['September', 2026])
    expect(issueProblems(SEC, { id: 'k', date: '2026-10-01' }, SEPT, 'tr').date.params).toEqual(['Eylül', 2026])
    const v = issueValidation(TPL, { issues: [{ id: 'a', date: '2026-08-31' }] }, SEPT, 'en')
    expect(v['issue:issues:a:date'].params).toEqual(['September', 2026])
  })

  it('şablon geneli denetim istemci anahtarıyla; hata anahtarlarının bölümü bulunur', () => {
    const v = issueValidation(TPL, { issues: [{ id: 'a', outage: true }, { id: 'b' }], old: [{ id: 'c', outage: true }] }, SEPT)
    expect(Object.keys(v)).toEqual(['issue:issues:a:outage_id'])
    expect(reportErrorSection('issue:issues:a:outage_id', TPL)).toBe('issues')
    expect(reportErrorSection('issues.issues.a.date', TPL)).toBe('issues')
  })
})

describe('kanonik değer ve otomatik kayıt', () => {
  it('sorulmayan / gizli cevap düşer, pay gönderilmez; eski düzen bölüm müşteri sayısını tutar', () => {
    const issue = { id: 'k', topic: 'T', share: 3.4, outage: false, outage_id: 'X', impact: false, customers: 5, start: '14:00',
      end_next_day: true, captions: { 1: 'a', 2: ' ' } }
    expect(conformIssue(SEC, issue)).toEqual({ id: 'k', topic: 'T', outage: false, impact: false, start: '14:00', captions: { 1: 'a' } })
    expect(conformIssue(SEC, { id: 'k', impact: true, customers: 5, outage: true, outage_id: 'X' }))
      .toEqual({ id: 'k', impact: true, customers: 5, outage: true, outage_id: 'X' })
    expect(conformIssue(LEGACY, issue)).toEqual({ id: 'k', topic: 'T', customers: 5 })
    // canonicalValues şablonla: bölümün sorularına göre; şablonsuz: yalnız pay düşer
    const v = { fields: {}, issues: { issues: [issue], old: [{ ...issue }] }, texts: {} }
    const c = canonicalValues(v, TPL)
    expect(c.issues.issues[0]).not.toHaveProperty('customers')
    expect(c.issues.issues[0]).not.toHaveProperty('share')
    expect(c.issues.old[0]).toEqual({ id: 'k', topic: 'T', customers: 5 })
    expect(canonicalValues(v).issues.issues[0]).not.toHaveProperty('share')
    expect(canonicalValues(v).issues.issues[0]).toHaveProperty('outage_id', 'X')
  })

  it('geçersiz anomali alanı otomatik kayıtta bağlı cevaplarla birlikte kayıtlı değerine döner (yeni konuda boşa)', () => {
    const base = { fields: {}, issues: { issues: [{ id: 'k', date: '2026-09-12', start: '10:00', end: '11:00', outage: false }] }, texts: {} }
    const draft = { fields: {}, issues: { issues: [
      { id: 'k', date: '2026-09-12', start: '10:00', end: '09:00', end_next_day: false, outage: true, outage_id: null, topic: 'Yeni ad' },
      { id: 'n', outage: true, date: '2026-09-02', start: '08:00' },
    ] }, texts: {} }
    const out = revertInvalidValues(draft, base, ['issue:issues:k:end', 'issue:issues:k:outage_id', 'issue:issues:n:end'])
    expect(out.issues.issues[0]).toEqual({ id: 'k', date: '2026-09-12', start: '10:00', end: '11:00', outage: false, outage_id: null,
      topic: 'Yeni ad' })
    expect(out.issues.issues[1]).toEqual({ id: 'n', outage: true })              // yeni konu: zaman cevapları boşa
    expect(draft.issues.issues[0].end).toBe('09:00')                              // taslak değişmez
  })
})

describe('pay alanı başvurusu (inceleme 2026-10-18)', () => {
  it('ratioSectionsUsing alanı pay alanı olarak kullanan anomali bölümlerini bulur; ratioOfWarning yok / sayı değil / tamam', () => {
    expect(ratioSectionsUsing(TPL, 'error-count').map((x) => x.id)).toEqual(['issues'])
    expect(ratioSectionsUsing(TPL, 'baska')).toEqual([])
    expect(ratioOfWarning(TPL, SEC)).toBeNull()
    expect(ratioOfWarning(TPL, { ...SEC, ratio_of: 'silinmis' })).toBe('te.tpl.anomaly.ratioMissing')
    const pct = { ...TPL, sections: [{ id: 'm', kind: 'metrics', title: 'V', fields: [{ id: 'error-count', label: 'Hata', type: 'percent' }] }, SEC] }
    expect(ratioOfWarning(pct, SEC)).toBe('te.tpl.anomaly.ratioNotNumber')
    expect(ratioOfWarning(TPL, { ...SEC, ratio_of: '' })).toBeNull()
    expect(ratioOfWarning(TPL, LEGACY)).toBeNull()
  })
})

describe('konu görselleri ve şablon farkı', () => {
  it('yuva kimliği ve çözümü; görsel yalnız konusu ve izni sürdükçe yaşar', () => {
    expect(issueSlotId('issues', 'k1', 2)).toBe('iss:issues:k1:2')
    expect(parseIssueSlot('iss:issues:k1:2')).toEqual({ sectionId: 'issues', issueId: 'k1', n: 2 })
    expect(parseIssueSlot('iss:issues:k1:3')).toBeNull()
    expect(parseIssueSlot('monthly-tx')).toBeNull()
    const values = { issues: { issues: [{ id: 'k1' }] } }
    expect(issueImageAlive('iss:issues:k1:1', TPL, values)).toBe(true)
    expect(issueImageAlive('iss:issues:k9:1', TPL, values)).toBe(false)
    expect(issueImageAlive('iss:old:k1:1', TPL, { issues: { old: [{ id: 'k1' }] } })).toBe(false)   // görsel izni yok
    expect(issueImageAlive('monthly-tx', TPL, values)).toBe(true)
  })

  it('kapalı sorunun anahtarı yoklukla aynı (sahte değişiklik yok); soru değişince sürüm farkında tek satır', () => {
    const off = { ...SEC, ask_time: false, time_label: 'Ne zaman?', ask_outage: false, allow_images: false, ratio_of: '' }
    const none = { ...SEC }
    for (const k of ['ask_time', 'time_label', 'ask_outage', 'outage_label', 'outage_id_label', 'allow_images', 'ratio_of']) delete none[k]
    expect(canonicalTemplate({ lang: 'tr', title: 'T', sections: [off] })).toEqual(canonicalTemplate({ lang: 'tr', title: 'T', sections: [none] }))
    const a = { lang: 'tr', title: 'T', sections: [SEC] }
    const b = { lang: 'tr', title: 'T', sections: [{ ...SEC, no_impact_display: 'hide_topic' }] }
    expect(templateDiff(a, b)).toEqual([{ group: 'section', op: 'anomaly', label: 'Anomaliler' }])
    expect(templateDiff(a, { lang: 'tr', title: 'T', sections: [{ ...SEC }] })).toEqual([])
    expect(templateDiff({ lang: 'tr', title: 'T', sections: [LEGACY] },
      { lang: 'tr', title: 'T', sections: [{ ...LEGACY, ask_impact: false }] })).toEqual([])
  })
})
