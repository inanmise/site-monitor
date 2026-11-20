import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import {
  EMPTY_FORM, EDIT_KEYS, MAX_LENGTHS, MAX_WHOLE, MAX_ERROR_BUDGET, validateIncident, editPayload, changedKeys, formChanges,
  consistencyNudges, applyNudge, formFromRow, resolvedNowIso, olderRecord,
} from '../components/incidenthistory/incidentHistoryModel.js'

// "Sağlam Kayıt" paket 1 (2026-11-20) — saf model: yalnız değişen alanlar + sürüm, taze kayıt farkı, taslak farkı,
// durum ↔ zaman önerileri, sayı / uzunluk kuralları (sunucuyla AYNI sınırlar — Java kaynağı okunur).

/** Kayıtlı bir olay (sunucu DTO biçimi). */
const REC = Object.freeze({
  ...EMPTY_FORM, id: 7, version: 3, title: 'Ödeme kesintisi', occurred_at: '2026-11-16T10:00:00', detected_at: '2026-11-16T10:05:00',
  resolved_at: '', team_id: 1, team_name: 'Takım A', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION',
  affected_customers: 120, duration_minutes: null, tags: 'db', updated_by: 'kisi.a', updated_at: '2026-11-16T11:00:00',
})
const formOf = (over = {}) => ({ ...formFromRow(REC), ...over })

describe('editPayload — yalnız değişen alanlar + sürüm', () => {
  it('değişiklik yok → gövdede yalnız kimlik + sürüm, changed boş (istek gerekmez)', () => {
    const { body, changed } = editPayload(formOf(), REC)
    expect(changed).toEqual([])
    expect(body).toEqual({ id: 7, version: 3 })
  })

  it('tek alan değişti → yalnız o alan + kimlik + sürüm; team_name istemciden gönderilmez', () => {
    const { body, changed } = editPayload(formOf({ title: 'Ödeme kesintisi (güncel)' }), REC)
    expect(changed).toEqual(['title'])
    expect(body).toEqual({ id: 7, version: 3, title: 'Ödeme kesintisi (güncel)' })
    expect(body).not.toHaveProperty('team_name')
  })

  it('temizlenen alan açık null gider ("anahtar yok" = dokunma)', () => {
    const { body } = editPayload(formOf({ tags: '', detected_at: '' }), REC)
    expect(body).toMatchObject({ tags: null, detected_at: null })
    expect(Object.prototype.hasOwnProperty.call(body, 'tags')).toBe(true)
  })

  it('takım değişti → team_id (sayı) + team_name (kademeli dağıtımdaki eski pod için)', () => {
    const { body, changed } = editPayload(formOf({ team_id: '2', team_name: 'Takım B' }), REC)
    expect(changed).toEqual(['team_id'])
    expect(body).toMatchObject({ team_id: 2, team_name: 'Takım B' })
  })

  it('sayı metni sayıya çevrilir; aynı sayının başka yazımı değişiklik değil', () => {
    expect(editPayload(formOf({ affected_customers: '0120' }), REC).changed).toEqual([])
    const { body } = editPayload(formOf({ affected_customers: '130' }), REC)
    expect(body.affected_customers).toBe(130)
    // sayı olmayan metin OLDUĞU GİBİ gider (sunucu alan hatası döner, sessizce null olmaz)
    expect(editPayload(formOf({ affected_customers: '12 adet' }), REC).body.affected_customers).toBe('12 adet')
  })

  it('iki zaman da varken süre gönderilmez (sunucu hesaplar); çözülme silinince formda kalan otomatik süre de gitmez', () => {
    const resolved = { ...REC, resolved_at: '2026-11-16T12:00:00', duration_minutes: 120, status: 'RESOLVED' }
    const f = { ...formFromRow(resolved), duration_minutes: 120 }
    expect(editPayload({ ...f, resolved_at: '2026-11-16T12:30:00', duration_minutes: 150 }, resolved).body)
      .not.toHaveProperty('duration_minutes')
    // yeniden açıldı: çözülme zamanı silindi, süre alanında eski otomatik değer duruyor → gönderilmez
    const reopened = editPayload({ ...f, resolved_at: '', status: 'OPEN', duration_minutes: 120 }, resolved)
    expect(reopened.body).toMatchObject({ resolved_at: null, status: 'OPEN' })
    expect(reopened.body).not.toHaveProperty('duration_minutes')
    // elle temizlenen süre (öneri: "çözülme zamanını ve süreyi temizle") açık null
    expect(editPayload({ ...f, resolved_at: '', status: 'OPEN', duration_minutes: '' }, resolved).body.duration_minutes).toBeNull()
  })

  it('yeniden açma: 2026-11-20 öncesi YUVARLANMIŞ kayıtlı süre de (aşağı yuvarlanan form değeri de) otomatik sayılır, gitmez', () => {
    const legacy = { ...REC, occurred_at: '2026-11-16T10:00:00', resolved_at: '2026-11-16T10:02:40', duration_minutes: 3, status: 'RESOLVED' }
    for (const shown of [2, 3]) {                                       // form açılınca etki 2'ye (floor) çekebilir
      const body = editPayload({ ...formFromRow(legacy), resolved_at: '', status: 'OPEN', duration_minutes: shown }, legacy).body
      expect(body).toMatchObject({ resolved_at: null, status: 'OPEN' })
      expect(body).not.toHaveProperty('duration_minutes')
    }
    // elle girilmiş (otomatikten farklı) süre değişirse gider
    expect(editPayload({ ...formFromRow(legacy), resolved_at: '', status: 'OPEN', duration_minutes: 40 }, legacy).body.duration_minutes).toBe(40)
  })

  it('sürüm: kayıt taşımıyorsa (eski sunucu) gövdeye yazılmaz; 0 yazılır', () => {
    const { version, ...noVersion } = REC
    expect(version).toBe(3)
    expect(editPayload({ ...formFromRow(noVersion), title: 'x' }, noVersion).body).not.toHaveProperty('version')
    expect(editPayload({ ...formFromRow({ ...REC, version: 0 }), title: 'x' }, { ...REC, version: 0 }).body.version).toBe(0)
  })

  it('"Mail gönder" seçiliyse gövdede; seçilmemişse yok', () => {
    expect(editPayload(formOf({ send_notification: true }), REC).body.send_notification).toBe(true)
    expect(editPayload(formOf(), REC).body).not.toHaveProperty('send_notification')
  })

  it('gövde anahtarları yalnız EDIT_KEYS (+ id, version, team_name, send_notification)', () => {
    const all = Object.fromEntries(EDIT_KEYS.map((k) => [k, k.endsWith('_at') ? '2026-11-17T10:00:00' : 'x']))
    const { body } = editPayload({ ...formOf(), ...all, outage_related: true, sla_breached: true, send_notification: true }, REC)
    const allowed = new Set([...EDIT_KEYS, 'id', 'version', 'team_name', 'send_notification'])
    expect(Object.keys(body).filter((k) => !allowed.has(k))).toEqual([])
    expect(EDIT_KEYS).not.toContain('team_name')
  })
})

describe('changedKeys / formChanges', () => {
  it('changedKeys: iki kaydın farklı alanları (biçim farkı sayılmaz)', () => {
    expect(changedKeys(REC, { ...REC, affected_customers: '120' })).toEqual([])
    expect(changedKeys(REC, { ...REC, title: 'Başka', tags: 'db, ağ' })).toEqual(['title', 'tags'])
    expect(changedKeys(REC, { ...REC, version: 9, updated_at: 'x' })).toEqual([])   // yalnız damga
  })

  it('formChanges: taslak yalnız değişen ham form değerlerini taşır; takım değişince adı da', () => {
    expect(formChanges(formOf(), REC)).toEqual({})
    expect(formChanges(formOf({ title: 'Yeni', tech_error_count: '12' }), REC)).toEqual({ title: 'Yeni', tech_error_count: '12' })
    expect(formChanges(formOf({ team_id: '2', team_name: 'Takım B' }), REC)).toEqual({ team_id: '2', team_name: 'Takım B' })
  })
})

describe('consistencyNudges / applyNudge', () => {
  it('Çözüldü + çözülme zamanı yok → setResolvedNow; uygulanınca zaman yazılır', () => {
    const f = formOf({ status: 'RESOLVED', resolved_at: '' })
    expect(consistencyNudges(f, REC)).toEqual(['setResolvedNow'])
    expect(applyNudge(f, 'setResolvedNow', '2026-11-20T09:00:00').resolved_at).toBe('2026-11-20T09:00:00')
  })

  it('çözülme zamanı var + durum Çözüldü değil → markResolved; açılıştaki kayıt Çözüldü idiyse reopenClear', () => {
    const f = formOf({ status: 'INVESTIGATING', resolved_at: '2026-11-16T12:00:00' })
    expect(consistencyNudges(f, REC)).toEqual(['markResolved'])
    expect(applyNudge(f, 'markResolved').status).toBe('RESOLVED')
    const wasResolved = { ...REC, status: 'RESOLVED', resolved_at: '2026-11-16T12:00:00' }
    expect(consistencyNudges(f, wasResolved)).toEqual(['reopenClear'])
    expect(applyNudge({ ...f, duration_minutes: 120 }, 'reopenClear')).toMatchObject({ resolved_at: '', duration_minutes: '' })
  })

  it('"şimdi" önerisi: şimdi / oluş / tespitin EN GEÇİ, dakikaya YUKARI — saniyeli alarm zamanının önüne düşmez', () => {
    const now = new Date('2026-11-20T09:00:20Z')
    expect(resolvedNowIso(formOf({ occurred_at: '2026-11-20T08:00:00', detected_at: '' }), now)).toBe('2026-11-20T09:01:00')
    expect(resolvedNowIso(formOf({ occurred_at: '2026-11-20T08:00:00', detected_at: '' }), new Date('2026-11-20T09:00:00Z')))
      .toBe('2026-11-20T09:00:00')                                       // tam dakika olduğu gibi
    // alarmdan gelen oluş / tespit bu dakikanın içinde (saniyeli): aşağı yuvarlanmış şimdi ondan ÖNCE kalırdı
    const f = formOf({ status: 'RESOLVED', resolved_at: '', occurred_at: '2026-11-20T09:00:41', detected_at: '2026-11-20T09:00:45' })
    const iso = resolvedNowIso(f, new Date('2026-11-20T09:00:50Z'))
    expect(iso).toBe('2026-11-20T09:01:00')
    expect(validateIncident(applyNudge(f, 'setResolvedNow', iso), null).resolved_at).toBeUndefined()   // sıra kuralına takılmaz
    // saat farkı: tespit gelecekte (cihaz saati geri) → yine ondan sonra
    expect(resolvedNowIso(formOf({ occurred_at: '2026-11-20T08:00:00', detected_at: '2026-11-20T09:30:10' }), now)).toBe('2026-11-20T09:31:00')
  })

  it('olderRecord: yalnız iki tarafta da tam sayı sürüm varken DAHA ESKİ gelen true', () => {
    expect(olderRecord({ version: 3 }, { version: 2 })).toBe(true)
    expect(olderRecord({ version: 3 }, { version: 3 })).toBe(false)
    expect(olderRecord({ version: 3 }, { version: 4 })).toBe(false)
    expect(olderRecord({}, { version: 1 })).toBe(false)               // eski sunucu: sürüm yok → bugünkü davranış
    expect(olderRecord({ version: 2 }, {})).toBe(false)
    expect(olderRecord({ version: '' }, { version: 0 })).toBe(false)
  })

  it('tutarlı form → öneri yok; bilinmeyen öneri formu değiştirmez', () => {
    expect(consistencyNudges(formOf(), REC)).toEqual([])
    expect(consistencyNudges(formOf({ status: 'RESOLVED', resolved_at: '2026-11-16T12:00:00' }), REC)).toEqual([])
    const f = formOf()
    expect(applyNudge(f, 'nope')).toBe(f)
  })
})

describe('validateIncident — sayı / uzunluk kuralları, değişmeyen değer yeniden denetlenmez', () => {
  const valid = formOf()

  it('tam sayı: rakam dışı / ondalık / üst sınır → errWholeNumber; negatif → errNonNegative', () => {
    expect(validateIncident({ ...valid, affected_customers: '12.5' }).affected_customers).toBe('inc.errWholeNumber')
    expect(validateIncident({ ...valid, affected_customers: 'abc' }).affected_customers).toBe('inc.errWholeNumber')
    expect(validateIncident({ ...valid, affected_customers: String(MAX_WHOLE + 1) }).affected_customers).toBe('inc.errWholeNumber')
    expect(validateIncident({ ...valid, affected_customers: String(MAX_WHOLE) })).toEqual({})
    expect(validateIncident({ ...valid, duration_minutes: '-5' }).duration_minutes).toBe('inc.errNonNegative')
    expect(validateIncident({ ...valid, affected_transactions: '-0' })).toEqual({})
  })

  it('hata bütçesi: ondalık nokta, 0 … 1.000.000', () => {
    expect(validateIncident({ ...valid, error_budget_burn_pct: '12.5' })).toEqual({})
    expect(validateIncident({ ...valid, error_budget_burn_pct: '12,5' }).error_budget_burn_pct).toBe('inc.errNumber')
    expect(validateIncident({ ...valid, error_budget_burn_pct: '1e3' }).error_budget_burn_pct).toBe('inc.errNumber')
    expect(validateIncident({ ...valid, error_budget_burn_pct: String(MAX_ERROR_BUDGET + 1) }).error_budget_burn_pct).toBe('inc.errNumber')
    expect(validateIncident({ ...valid, error_budget_burn_pct: '-1' }).error_budget_burn_pct).toBe('inc.errNonNegative')
  })

  it('uzunluk: sınırı aşan metin { key, params: [sınır, girilen] }', () => {
    const e = validateIncident({ ...valid, title: 'x'.repeat(256) })
    expect(e.title).toEqual({ key: 'inc.errTooLong', params: [255, 256] })
    expect(validateIncident({ ...valid, error_code: 'x'.repeat(101) }).error_code.params).toEqual([100, 101])
    expect(validateIncident({ ...valid, description: 'x'.repeat(20000) })).toEqual({})
  })

  it('düzenlemede DEĞİŞMEYEN eski değer (bugünkü kurala uymasa da) yeniden denetlenmez — sunucuyla aynı kural', () => {
    const legacy = { ...REC, title: 'x'.repeat(300), affected_customers: -4, team_id: null, detected_at: '2026-11-16T09:00:00' }
    const f = formFromRow(legacy)
    expect(validateIncident(f)).not.toEqual({})                         // yeni kayıt gibi bakılsaydı hatalıydı
    expect(validateIncident(f, legacy)).toEqual({})                      // değişmedikleri için düzenleme kilitlenmez
    // değişen değer denetlenir
    expect(validateIncident({ ...f, affected_customers: '-5' }, legacy).affected_customers).toBe('inc.errNonNegative')
    // sıra: çiftin bir ucu değişince denetlenir
    expect(validateIncident({ ...f, occurred_at: '2026-11-16T09:30:00' }, legacy).detected_at).toBe('inc.errDetectedBeforeOccurred')
  })

  it('sınırlar sunucudaki sabitlerle ve alan → sabit eşlemesiyle AYNI (IncidentService.java)', () => {
    const src = readJavaMain('service/IncidentService.java')
    const constant = (name) => {
      const m = new RegExp(`static final (?:int|double) ${name}\\s*=\\s*([\\d_]+)`).exec(src)
      expect(m, `${name} Java kaynağında bulunamadı`).not.toBeNull()
      return Number(m[1].replaceAll('_', ''))
    }
    const java = {}
    for (const m of src.matchAll(/text\(body, "([a-z_]+)", [^;]*?, (MAX_[A-Z_]+), w,/g)) java[m[1]] = constant(m[2])
    expect(Object.keys(java).length).toBeGreaterThan(15)
    expect(java).toEqual({ ...MAX_LENGTHS })
    expect(constant('MAX_ERROR_BUDGET')).toBe(MAX_ERROR_BUDGET)
  })
})

describe('i18n — Sağlam Kayıt anahtarları iki dilde, tekil (.one) biçimleriyle', () => {
  it('anahtarlar var ve yer tutucu sayıları eşleşir', () => {
    const keys = [
      'inc.errWholeNumber', 'inc.errNumber', 'inc.errTooLong', 'inc.fresh.updated', 'inc.fresh.updated.one',
      'inc.fresh.pendingTitle', 'inc.fresh.pendingBody', 'inc.fresh.pendingBody.one', 'inc.fresh.loadLatest', 'inc.fresh.failed',
      'inc.fresh.latestFailed', 'inc.fresh.gone', 'inc.fresh.detailUpdated', 'inc.fresh.detailFailed', 'inc.fresh.detailGone',
      'inc.conflict.title', 'inc.conflict.by', 'inc.conflict.anon', 'inc.draft.title', 'inc.draft.body', 'inc.draft.body.one',
      'inc.draft.olderBase', 'inc.draft.restore', 'inc.draft.delete', 'inc.draft.restored', 'inc.nothingToSave',
      ...['setResolvedNow', 'markResolved', 'reopenClear'].flatMap((k) => [`inc.nudge.${k}`, `inc.nudge.${k}Action`]),
      'inc.nudge.hint', 'inc.nudge.dismiss',
    ]
    const holes = (s) => (String(s).match(/\{\d\}/g) || []).sort().join()
    for (const k of keys) {
      expect(TR[k], `TR ${k}`).toBeTruthy()
      expect(EN[k], `EN ${k}`).toBeTruthy()
      expect(holes(TR[k]), k).toBe(holes(EN[k]))
    }
  })
})
