import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  REM_DEFAULT_LIMITS, REM_INT_FIELDS, changedFields, isFormDirty, isUnsupportedResponse, normalizeReminders, parseWhole, reminderState,
  toForm, validateForm,
} from '../components/admin/techErrorReminderModel.js'

// Ayarlar → Teknik hata raporu → hatırlatmalar ve posta sınırları (12. sürüm, 2026-10-17): saf model — sunucu aralıklarıyla
// aynı sınırlar (Java kaynağından okunur), yalnız değişen alanlar, yarım yazılmış sayı "değişiklik" ama gövdeye girmez.
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/techerror/TechErrorReportSettings.java')

const SERVER = {
  team_ids: [3, 1], reminder_day: 5, reminder_repeat_days: 3, approval_reminder_days: 3, max_mail_mb: 15, mail_inline_mb: 6,
  reminder_push: true,
}

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

  it('changedFields: yalnız değişen alanlar (takım sırası değişiklik değil); boş = istek yok; yarım sayı kirli ama gövdeye girmez', () => {
    const saved = normalizeReminders(SERVER)
    const form = toForm(saved)
    expect(changedFields(saved, form)).toEqual({})
    expect(isFormDirty(saved, form)).toBe(false)
    expect(changedFields(saved, { ...form, team_ids: [1, 3] })).toEqual({})
    expect(changedFields(saved, { ...form, team_ids: [1], reminder_day: '7', reminder_push: false }))
      .toEqual({ reminder_team_ids: [1], reminder_day: 7, reminder_push: false })
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
