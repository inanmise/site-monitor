import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  MAX_PHONE_INPUT, PHONE_DIGITS, PROFILE_CODES, SNOOZE_LONG_MS, askWho, makeContext, normalizePhone, phoneReasons, phoneSeverity,
  phoneState, profileGaps,
} from '../components/gaps/gapsProfile.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const BACKEND = path.resolve(__dirname, '../../../backend/src')
const MATCHER = fs.readFileSync(path.join(BACKEND, 'main/java/com/sitemonitor/service/otp/OtpContactMatcher.java'), 'utf8')
const MATCHER_TEST = fs.readFileSync(path.join(BACKEND, 'test/java/com/sitemonitor/service/otp/OtpContactMatcherTest.java'), 'utf8')

/** Sunucu testinin telefon tablosu (`normalizePhone_table` @CsvSource satırları): [girdi, beklenen | null]. */
function javaPhoneTable() {
  // İlk @CsvSource bloğu telefon tablosudur (normalizePhone_table)
  const block = /@CsvSource\([^{]*value = \{([\s\S]*?)\}\)/.exec(MATCHER_TEST)?.[1] || ''
  return [...block.matchAll(/"([^"]*)"/g)].map((x) => {
    const [raw, exp] = x[1].split('|').map((p) => p.trim())
    return [raw === "''" ? '' : raw, exp === 'NULL' ? null : exp]
  })
}

describe('eksikler — profil kuralları', () => {
  it('telefon normalleştirmesi sunucunun OtpContactMatcher kuralıyla aynı (sunucu testinin tablosu satır satır)', () => {
    expect(Number(/PHONE_DIGITS = (\d+)/.exec(MATCHER)[1])).toBe(PHONE_DIGITS)
    expect(Number(/MAX_PHONE_INPUT = (\d+)/.exec(MATCHER)[1])).toBe(MAX_PHONE_INPUT)
    const rows = javaPhoneTable()
    expect(rows.length).toBeGreaterThanOrEqual(15)
    for (const [raw, exp] of rows) expect(normalizePhone(raw), raw).toBe(exp)
    expect(normalizePhone('0500 000 00 00' + ' '.repeat(60))).toBeNull()     // aşırı uzun girdi eşleşmez (sunucuyla aynı)
    expect(normalizePhone(null)).toBeNull()
  })

  it('ASCII dışı rakam (tam genişlik, Arap-Hint) bilinmiyor sayılır — yanlış "geçersiz telefon" uyarısı yok', () => {
    expect(normalizePhone('０５００ ０００ ００ ００')).toBeUndefined()
    expect(phoneState('٠٥٠٠٠٠٠٠٠٠١')).toBe('unknown')
    expect(phoneState('')).toBe('missing')
    expect(phoneState('  ')).toBe('missing')
    expect(phoneState(null)).toBe('missing')
    expect(phoneState('0532 123')).toBe('invalid')
    expect(phoneState('+90 (532) 123-45-67')).toBe('ok')
  })

  it('telefon eksiğinin önemi bağlama göre: biri doğruysa YÜKSEK, hepsi bilinen yanlışsa DÜŞÜK, bilinmiyorsa ORTA', () => {
    expect(phoneSeverity({ onCall: true })).toBe('HIGH')
    expect(phoneSeverity({ noc: true, onCall: false, otpPhone: false })).toBe('HIGH')
    expect(phoneSeverity({ otpPhone: true })).toBe('HIGH')
    expect(phoneSeverity({ onCall: false, noc: false, otpPhone: false })).toBe('LOW')
    expect(phoneSeverity({ onCall: false, noc: null, otpPhone: false })).toBe('MEDIUM')
    expect(phoneSeverity({})).toBe('MEDIUM')
    expect(phoneReasons({ onCall: true, noc: false, otpPhone: true })).toEqual(['ONCALL', 'OTP_PHONE'])
    const gaps = profileGaps({ email: 'a@b.c', phone: '' }, { onCall: true, otpPhone: true })
    expect(gaps).toEqual([expect.objectContaining({ code: 'PROFILE_NO_PHONE', severity: 'HIGH', reasons: ['ONCALL', 'OTP_PHONE'], scope: 'p' })])
    expect(profileGaps({ email: null, phone: '05551112233' }, { otpEmail: true })[0].reasons).toEqual(['OTP_EMAIL'])
  })

  it('kişisel push tercihleri: kapalı, uzun erteleme, nöbetçiyken yalnız kritik — okunmadıysa (null) eksik yok', () => {
    const P = { email: 'a@b.c', phone: '05551112233' }
    const now = Date.parse('2026-11-15T09:00:00Z')
    expect(profileGaps(P, {}, null, now)).toEqual([])
    expect(profileGaps(P, {}, { opt_out: true }, now).map((g) => [g.code, g.severity, g.scope])).toEqual([['PROFILE_PUSH_OFF', 'LOW', 'pp']])
    expect(profileGaps(P, { onCall: true }, { opt_out: true }, now)[0].severity).toBe('MEDIUM')
    // kapalıysa erteleme / seviye ayrıca söylenmez
    expect(profileGaps(P, { onCall: true }, { opt_out: true, snooze_active: true, snooze_until: '2026-12-30T00:00:00Z', min_level: 'CRITICAL' }, now)
      .map((g) => g.code)).toEqual(['PROFILE_PUSH_OFF'])
    const long = new Date(now + SNOOZE_LONG_MS + 60_000).toISOString()
    const short = new Date(now + SNOOZE_LONG_MS - 60_000).toISOString()
    expect(profileGaps(P, {}, { snooze_active: true, snooze_until: long, server_now: new Date(now).toISOString() }, now).map((g) => g.code))
      .toEqual(['PROFILE_PUSH_SNOOZED'])
    expect(profileGaps(P, {}, { snooze_active: true, snooze_until: short }, now)).toEqual([])
    expect(profileGaps(P, {}, { snooze_active: false, snooze_until: long }, now)).toEqual([])
    expect(profileGaps(P, { onCall: true }, { min_level: 'CRITICAL' }, now).map((g) => g.code)).toEqual(['PROFILE_PUSH_CRITICAL_ONLY'])
    expect(profileGaps(P, { onCall: false }, { min_level: 'CRITICAL' }, now)).toEqual([])
    expect(profileGaps(P, { onCall: null }, { min_level: 'CRITICAL' }, now)).toEqual([])    // bilinmeyen nöbet → söylenmez
  })

  it('"kime sormalı": birincil takımın lideri; lider kişinin kendisiyse ya da yoksa null', () => {
    const dir = [{ id: 4, name: 'Dijital Kanallar', leader_id: 'u-9', leader_display_name: 'Ayşe Yılmaz (Dijital / Lider)' },
      { id: 5, name: 'X', leader_id: null, leader_display_name: null }]
    expect(askWho(dir, 4, 'u-1')).toEqual({ leaderName: 'Ayşe Yılmaz (Dijital / Lider)', teamName: 'Dijital Kanallar' })
    expect(askWho(dir, 4, 'u-9')).toBeNull()
    expect(askWho(dir, 5, 'u-1')).toBeNull()
    expect(askWho(dir, 99, 'u-1')).toBeNull()
    expect(askWho(null, 4, 'u-1')).toBeNull()
    expect(makeContext({ onCall: 'evet' })).toEqual({ onCall: null, noc: null, otpPhone: null, otpEmail: null })
  })

  it('her profil kodunun başlığı ve nedeni iki dilde', () => {
    for (const c of PROFILE_CODES) {
      for (const part of ['title', 'why']) {
        const k = `gaps.profile.${c}.${part}`
        expect(tr[k], k).toBeTruthy()
        expect(en[k], k).toBeTruthy()
      }
    }
    for (const r of ['ONCALL', 'NOC', 'OTP_PHONE', 'OTP_EMAIL']) { expect(tr[`gaps.reason.${r}`]).toBeTruthy(); expect(en[`gaps.reason.${r}`]).toBeTruthy() }
  })
})
