import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  REM_DEFAULT_LIMITS, REM_INT_FIELDS, changedFields, isFormDirty, isRepairKey, isUnsupportedResponse, normalizeOutOfRange, normalizeReminders,
  parseWhole, reminderState, repairKeys, toForm, validateForm,
} from '../components/admin/techErrorReminderModel.js'

// Ayarlar → Teknik hata raporu → hatırlatmalar ve posta sınırları (12. sürüm, 2026-10-17): saf model — sunucu aralıklarıyla
// aynı sınırlar (Java kaynağından okunur), yalnız değişen alanlar, yarım yazılmış sayı "değişiklik" ama gövdeye girmez.
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/techerror/TechErrorReportSettings.java')

const SERVER = {
  team_ids: [3, 1], reminder_day: 5, reminder_repeat_days: 3, approval_reminder_days: 3, max_mail_mb: 15, mail_inline_mb: 6,
  reminder_push: true,
}

describe('techErrorReminderModel — aralık dışı değer notu (inceleme 2026-10-18)', () => {
  it('normalizeOutOfRange: yalnız bilinen alan + kaynak (env / stored) + tam sayı varsayılan; eski sunucu (alan yok) → boş; uzun değer 40 karakterde kesilir', () => {
    expect(normalizeOutOfRange(undefined)).toEqual({})
    expect(normalizeOutOfRange([])).toEqual({})
    expect(normalizeOutOfRange({
      max_mail_mb: { source: 'env', value: 60, default: 15 },
      mail_inline_mb: { source: 'stored', value: ' abc ', default: 6 },
      reminder_day: { source: 'weird', value: '1', default: 5 },
      approval_reminder_days: { source: 'env', value: '99', default: 'x' },
      bogus: { source: 'env', value: '1', default: 1 },
    })).toEqual({
      max_mail_mb: { source: 'env', value: '60', default: 15 },
      mail_inline_mb: { source: 'stored', value: 'abc', default: 6 },
    })
    expect(normalizeOutOfRange({ max_mail_mb: { source: 'stored', value: 'x'.repeat(60), default: 15 } }).max_mail_mb.value)
      .toBe(`${'x'.repeat(40)}…`)
    // normalizeReminders taşır
    const saved = normalizeReminders({ ...SERVER, out_of_range: { max_mail_mb: { source: 'env', value: '60', default: 15 } } })
    expect(saved.out_of_range.max_mail_mb.value).toBe('60')
    expect(normalizeReminders(SERVER).out_of_range).toEqual({})
  })

  it('inceleme 2026-10-18 (2. tur): aralık dışı alan ONARIM bekler — formdaki geçerli değer (varsayılan, kayıtlı görüntüyle aynı sayı) değişiklik sayılır ve TEK kayıtta yazılır; kullanıcı değişikliği sayımı ({ repairs: false }) onu saymaz', () => {
    const saved = normalizeReminders({ ...SERVER, out_of_range: {
      max_mail_mb: { source: 'env', value: '60', default: 15 }, approval_reminder_days: { source: 'stored', value: 'abc', default: 3 },
    } })
    const form = toForm(saved)
    expect(repairKeys(saved)).toEqual(['approval_reminder_days', 'max_mail_mb'])
    expect(isRepairKey(saved, 'max_mail_mb')).toBe(true)
    expect(isRepairKey(saved, 'reminder_day')).toBe(false)
    expect(isRepairKey(null, 'max_mail_mb')).toBe(false)
    expect(changedFields(saved, form)).toEqual({ approval_reminder_days: 3, max_mail_mb: 15 })
    expect(isFormDirty(saved, form)).toBe(true)
    expect(changedFields(saved, form, { repairs: false })).toEqual({})
    expect(isFormDirty(saved, form, { repairs: false })).toBe(false)
    // Kullanıcı onarım alanına başka bir geçerli değer yazdı: o gider; diğer onarım da aynı kayda girer
    expect(changedFields(saved, { ...form, max_mail_mb: '25', reminder_day: '7' }))
      .toEqual({ reminder_day: 7, approval_reminder_days: 3, max_mail_mb: 25 })
    expect(isFormDirty(saved, { ...form, reminder_day: '7' }, { repairs: false })).toBe(true)
    // Onarım alanı "değişmemiş" sayılıp doğrulamadan kaçmaz: aralık ve alanlar-arası kural ona da uygulanır
    expect(validateForm(form, REM_DEFAULT_LIMITS, saved)).toEqual({})
    expect(validateForm({ ...form, max_mail_mb: '4' }, REM_DEFAULT_LIMITS, saved).max_mail_mb).toEqual({ key: 'te.rem.v.whole', params: [5, 50] })
    const inlineHigh = { ...saved, mail_inline_mb: 20 }
    expect(validateForm(toForm(inlineHigh), REM_DEFAULT_LIMITS, inlineHigh).mail_inline_mb).toEqual({ key: 'te.rem.v.inline', params: [20, 15] })
    // Not yoksa eski davranış: kayıtlıyla aynı değer değişiklik değildir
    const plain = normalizeReminders(SERVER)
    expect(changedFields(plain, toForm(plain))).toEqual({})
    expect(isFormDirty(plain, toForm(plain))).toBe(false)
  })
})

describe('techErrorReminderModel', () => {
  it('varsayılan sınırlar Java TechErrorReportSettings ile AYNI (min, max, varsayılan)', () => {
    const src = fs.readFileSync(JAVA, 'utf8')
    const KEY = { reminder_day: 'KEY_REMINDER_DAY', reminder_repeat_days: 'KEY_REMINDER_REPEAT_DAYS',
      approval_reminder_days: 'KEY_APPROVAL_REMINDER_DAYS', max_mail_mb: 'KEY_MAX_MAIL_MB', mail_inline_mb: 'KEY_MAIL_INLINE_MB' }
    for (const k of REM_INT_FIELDS) {
      const m = new RegExp(`new IntRange\\(${KEY[k]},\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)`).exec(src)
      expect(m, k).not.toBeNull()
      expect(REM_DEFAULT_LIMITS[k], k).toEqual({ min: Number(m[1]), max: Number(m[2]), default: Number(m[3]) })
    }
  })

  it('normalizeReminders: blok yoksa null (eski sunucu → kart yok); eksik sınır / değer varsayılandan; push yalnız açık false ise kapalı', () => {
    expect(normalizeReminders(undefined)).toBeNull()
    expect(normalizeReminders([])).toBeNull()
    const n = normalizeReminders({ team_ids: ['2', 2, 'x'], reminder_day: 9 })
    expect(n.team_ids).toEqual([2])
    expect(n.reminder_day).toBe(9)
    expect(n.max_mail_mb).toBe(15)
    expect(n.reminder_push).toBe(true)
    expect(n.limits.reminder_day).toEqual({ min: 1, max: 28, default: 5 })
    expect(normalizeReminders({ reminder_push: false }).reminder_push).toBe(false)
  })

  it('doğrulama: aralık dışı / kesirli / boş → alan iletisi; gövde ≥ sınır → gövde alanında; geçerli → boş', () => {
    const form = toForm(normalizeReminders(SERVER))
    expect(validateForm(form)).toEqual({})
    expect(validateForm({ ...form, reminder_day: '29' }).reminder_day).toEqual({ key: 'te.rem.v.whole', params: [1, 28] })
    expect(validateForm({ ...form, reminder_repeat_days: '1.5' }).reminder_repeat_days).toBeTruthy()
    expect(validateForm({ ...form, approval_reminder_days: '' }).approval_reminder_days).toBeTruthy()
    expect(validateForm({ ...form, reminder_repeat_days: '0' })).toEqual({})
    expect(validateForm({ ...form, mail_inline_mb: '15' }).mail_inline_mb).toEqual({ key: 'te.rem.v.inline', params: [15, 15] })
    expect(validateForm({ ...form, max_mail_mb: '6' }).mail_inline_mb).toEqual({ key: 'te.rem.v.inline', params: [6, 6] })
    // Sınırın kendisi geçersizse alanlar-arası ileti eklenmez (tek ileti, doğru alanda)
    const bad = validateForm({ ...form, max_mail_mb: '3', mail_inline_mb: '4' })
    expect(bad.max_mail_mb).toBeTruthy()
    expect(bad.mail_inline_mb).toBeUndefined()
    expect(parseWhole(' 12 ')).toBe(12)
    expect(parseWhole('-1')).toBeNull()
  })

  it('inceleme 2026-10-18 (kural 9): kayıtlı değer verilirse YALNIZ değişen alanlar doğrulanır — değişmeyen (aynı değerin başka yazımı da) yeniden denetlenmez; alanlar-arası kural yalnız sınırlardan biri değiştiyse', () => {
    // Kayıtlı görüntüde aralık dışı / çelişen değerler (eski sunucu, elle yazılmış ayar): kullanıcı yalnız günü değiştirdi
    const saved = { ...normalizeReminders(SERVER), approval_reminder_days: 40, max_mail_mb: 10, mail_inline_mb: 12 }
    const form = { ...toForm(saved), reminder_day: '7' }
    expect(validateForm(form, REM_DEFAULT_LIMITS, saved)).toEqual({})
    expect(validateForm({ ...form, approval_reminder_days: '040' }, REM_DEFAULT_LIMITS, saved)).toEqual({})   // aynı değer
    // Değişen alan yine denetlenir; sınır değişince alanlar-arası kural geri gelir
    expect(validateForm({ ...form, reminder_day: '29' }, REM_DEFAULT_LIMITS, saved).reminder_day).toEqual({ key: 'te.rem.v.whole', params: [1, 28] })
    expect(validateForm({ ...form, max_mail_mb: '11' }, REM_DEFAULT_LIMITS, saved).mail_inline_mb).toEqual({ key: 'te.rem.v.inline', params: [12, 11] })
    expect(validateForm({ ...form, approval_reminder_days: '' }, REM_DEFAULT_LIMITS, saved).approval_reminder_days).toBeTruthy()
    // Kayıtlı değer verilmezse eski davranış (her alan)
    expect(validateForm(form).approval_reminder_days).toBeTruthy()
  })

  it('changedFields: yalnız değişen alanlar (takım sırası değişiklik değil); boş = istek yok; yarım sayı kirli ama gövdeye girmez', () => {
    const saved = normalizeReminders(SERVER)
    const form = toForm(saved)
    expect(changedFields(saved, form)).toEqual({})
    expect(isFormDirty(saved, form)).toBe(false)
    expect(changedFields(saved, { ...form, team_ids: [1, 3] })).toEqual({})
    expect(changedFields(saved, { ...form, team_ids: [1], reminder_day: '7' }))
      .toEqual({ reminder_team_ids: [1], reminder_day: 7 })
    // 2026-10-18: dönem hatırlatmasının push anahtarı "Push bildirimleri" kartına taşındı — bu kartın gövdesine ASLA girmez
    // (kaydedilmemiş taslaklı yenileme diğer kartın kaydını eski değerle ezmesin)
    expect(form).not.toHaveProperty('reminder_push')
    expect(changedFields(saved, { ...form, reminder_push: false })).toEqual({})
    expect(isFormDirty(saved, { ...form, reminder_push: false })).toBe(false)
    const half = { ...form, max_mail_mb: '' }
    expect(changedFields(saved, half)).toEqual({})
    expect(isFormDirty(saved, half)).toBe(true)
    expect(isFormDirty(saved, { ...form, mail_inline_mb: '06' })).toBe(false)   // aynı değer, farklı yazım
    expect(isFormDirty(saved, { ...form, reminder_day: '1.5' })).toBe(true)
  })

  it('inceleme 2026-10-18 — reminderState: eski pod (GET 404 / PUT 405) "desteklenmiyor"; başarısız okuma önceki değerleri korur, değer uydurmaz; beklenmeyen biçim → kart yok', () => {
    expect(isUnsupportedResponse({ success: false, status: 404, code: 'NOT_FOUND' })).toBe(true)
    expect(isUnsupportedResponse({ success: false, status: 405, code: 'METHOD_NOT_ALLOWED' })).toBe(true)
    expect(isUnsupportedResponse({ success: false, status: 403 })).toBe(false)
    expect(isUnsupportedResponse({ success: true, status: 404 })).toBe(false)
    expect(isUnsupportedResponse(null)).toBe(false)

    const ok = reminderState({ success: true, data: { ...SERVER, read_only: true } })
    expect(ok.status).toBe('ok')
    expect(ok.data.read_only).toBe(true)
    expect(ok.data.team_ids).toEqual([3, 1])
    expect(reminderState({ success: true, data: SERVER }).data.read_only).toBe(false)

    expect(reminderState({ success: false, status: 404 })).toEqual({ status: 'unsupported', data: null, error: null })
    expect(reminderState({ success: false, status: 405 }, ok)).toEqual({ status: 'unsupported', data: ok.data, error: null })
    expect(reminderState({ success: false, status: 503, error: 'Veritabanı yok' }))
      .toEqual({ status: 'failed', data: null, error: 'Veritabanı yok' })
    expect(reminderState({ success: false, status: 503, error: 'x' }, ok).data).toBe(ok.data)
    expect(reminderState(null, ok)).toEqual({ status: 'failed', data: ok.data, error: null })
    // Başarılı ama blok yok / dizi: önceki yoksa kart çizilmez, varsa korunur (hata olarak)
    expect(reminderState({ success: true, data: [] })).toBeNull()
    expect(reminderState({ success: true, data: [] }, ok)).toEqual({ status: 'failed', data: ok.data, error: null })
  })
})
