import { describe, it, expect } from 'vitest'
import {
  defaultPlanForm, defaultStartNowForm, fieldOf, formFromWindow, hasRecipients, istanbulToMs, phaseMeta, planPayload,
  startNowPayload, toIstanbulParts, validatePlan,
} from '../components/admin/sysmaint/sysmaintModel.js'

/** Sistem Bakımı formu — saf model (2026-10-02): İstanbul yerel saat, doğrulama (alan anahtarları), gönderim gövdesi. */
const NOW = Date.parse('2026-10-02T07:10:00Z')   // İstanbul 10:10

describe('İstanbul yerel saat', () => {
  it('ms ↔ İstanbul tarih/saat (sabit UTC+3; tarayıcı dilimi kullanılmaz)', () => {
    expect(toIstanbulParts(Date.parse('2026-10-02T21:30:00Z'))).toEqual({ date: '2026-10-03', time: '00:30' })
    expect(istanbulToMs('2026-10-03', '00:30')).toBe(Date.parse('2026-10-02T21:30:00Z'))
    expect(istanbulToMs('', '10:00')).toBeNull()
    expect(istanbulToMs('2026-10-02', '1:0')).toBeNull()
  })
  it('varsayılan plan: sunucu saatinden 1 sa sonra, yarım saate yuvarlı başlangıç, 1 sa süre', () => {
    const f = defaultPlanForm(NOW)
    expect(f).toMatchObject({ startDate: '2026-10-02', startTime: '11:30', endDate: '2026-10-02', endTime: '12:30', mute: false,
      warnMinutes: 10, announceHours: 24, emailCorrections: true })
  })
})

describe('validatePlan — alan yanında gösterilecek anahtarlar', () => {
  const base = { startDate: '2026-10-02', startTime: '12:00', endDate: '2026-10-02', endTime: '13:00', messageTr: '', messageEn: '', contact: '' }
  it('geçerli plan hatasız', () => {
    expect(validatePlan(base, NOW)).toEqual({})
  })
  it('geçmiş başlangıç, eksik alanlar, bitiş önce, çok kısa, çok uzun', () => {
    expect(validatePlan({ ...base, startTime: '10:00' }, NOW).start_local).toBe('sysmaint.err.startPast')
    expect(validatePlan({ ...base, startDate: '' }, NOW).start_local).toBe('sysmaint.err.startRequired')
    expect(validatePlan({ ...base, endTime: '' }, NOW).end_local).toBe('sysmaint.err.endRequired')
    expect(validatePlan({ ...base, endTime: '11:00' }, NOW).end_local).toBe('sysmaint.err.endBeforeStart')
    expect(validatePlan({ ...base, endTime: '12:03' }, NOW).end_local).toBe('sysmaint.err.tooShort')
    expect(validatePlan({ ...base, endDate: '2026-10-06' }, NOW).end_local).toBe('sysmaint.err.tooLong')
    expect(validatePlan({ ...base, contact: 'x'.repeat(301) }, NOW).contact).toBe('sysmaint.err.tooLongText')
  })
  it('çakışan pencere reddedilir; düzenlenen pencerenin kendisi sayılmaz', () => {
    const other = { id: 3, start_at: '2026-10-02T09:30:00Z', end_at: '2026-10-02T10:30:00Z' }   // 12:30–13:30
    expect(validatePlan(base, NOW, [other]).start_local).toBe('sysmaint.err.overlap')
    expect(validatePlan(base, NOW, [other], 3)).toEqual({})
  })
})

describe('gönderim gövdeleri', () => {
  it('plan: İstanbul yerel saat + ayarlar + e-posta', () => {
    expect(planPayload({ ...defaultPlanForm(NOW), mute: true, emailAllUsers: true, emailTeamIds: ['4'] })).toMatchObject({
      start_local: '2026-10-02T11:30', end_local: '2026-10-02T12:30', warn_minutes: 10, announce_hours: 24,
      mute_notifications: true, email_all_users: true, email_team_ids: [4], email_corrections: true,
    })
    expect(startNowPayload({ countdownMinutes: '0', durationMinutes: '30', mute: false, messageTr: 'x' }))
      .toMatchObject({ countdown_minutes: 0, duration_minutes: 30, mute_notifications: false, message_tr: 'x' })
  })
  it('"bakım bitince de e-posta" (2026-10-02): varsayılan açık, gövdede email_on_end; alıcı kontrolü', () => {
    expect(defaultPlanForm(NOW).emailOnEnd).toBe(true)
    expect(defaultStartNowForm().emailOnEnd).toBe(true)
    expect(planPayload({ ...defaultPlanForm(NOW), emailOnEnd: false }).email_on_end).toBe(false)
    expect(planPayload(defaultPlanForm(NOW)).email_on_end).toBe(true)
    expect(startNowPayload({ ...defaultStartNowForm(), emailTeamIds: ['2'] }))
      .toMatchObject({ email_all_users: false, email_team_ids: [2], email_on_end: true })
    expect(hasRecipients(defaultPlanForm(NOW))).toBe(false)
    expect(hasRecipients({ emailAllUsers: true })).toBe(true)
    expect(hasRecipients({ emailTeamIds: [3] })).toBe(true)
    expect(hasRecipients(null)).toBe(false)
  })
  it('düzenleme formu pencereden; sunucu alan adları forma eşlenir; durum tonları', () => {
    const f = formFromWindow({ start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z', warn_minutes: 15,
      announce_hours: 6, mute_notifications: true, email_team_ids: [2], email_corrections: false })
    expect(f).toMatchObject({ startDate: '2026-10-02', startTime: '22:00', endTime: '23:00', warnMinutes: 15, announceHours: 6,
      mute: true, emailTeamIds: [2], emailCorrections: false })
    expect(f.emailOnEnd).toBe(true)   // email_on_end yoksa (eski kayıt) açık
    expect(formFromWindow({ email_on_end: false }).emailOnEnd).toBe(false)
    expect(fieldOf('end')).toBe('end_local')
    expect(fieldOf('start_at')).toBe('start_local')
    expect(fieldOf('warn_minutes')).toBe('warn_minutes')
    expect(phaseMeta('active').tone).toBe('destructive')
    expect(phaseMeta('bogus').key).toBe('sysmaint.phase.none')
  })
})
