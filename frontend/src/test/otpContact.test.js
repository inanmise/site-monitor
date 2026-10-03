import { describe, it, expect } from 'vitest'
import {
  contactKindOf, formatPhoneInput, isPlausibleEmail, isPlausiblePhone, normalizePhone, phoneDigits,
} from '../utils/otpContact.js'
import { validateOtpRequest } from '../components/login/OtpLoginFlow.jsx'

/**
 * Kodla giriş kişi bilgisi yardımcıları (2026-10-03). Telefon normalleştirmesi sunucudaki OtpContactMatcher ile AYNI
 * tablodan sınanır (OtpContactMatcherTest) — iki taraf ayrışırsa arayüz geçerli numarayı reddeder ya da tersi.
 * Örnek numaralar yer tutucudur (0500 000 00 00 ailesi).
 */
describe('otpContact — telefon', () => {
  it.each([
    ['0500 000 00 00', '5000000000'],
    ['05000000000', '5000000000'],
    ['5000000000', '5000000000'],
    ['+90 (500) 000-00-00', '5000000000'],
    ['90 500 000 00 00', '5000000000'],
    ['0090 500 000 00 00', '5000000000'],
    ['+90 0500 000 00 00', '5000000000'],
    ['0500.000.00.00', '5000000000'],
    ['+44 7700 900000', '7700900000'],
    ['0500 000 00', ''],
    ['0500000000', ''],
    ['abc', ''],
    ['', ''],
  ])('normalizePhone(%j) → %j (sunucuyla aynı tablo)', (raw, expected) => {
    expect(normalizePhone(raw)).toBe(expected)
  })

  it('Unicode rakamları (tam genişlik, Arap-Hint) ASCII sayılır', () => {
    expect(phoneDigits('０５００ ０００ ００ ００')).toBe('05000000000')
    expect(normalizePhone('٠٥٠٠٠٠٠٠٠٠١')).toBe('5000000001')
  })

  it('makul telefon: ≥ 10 ulusal hane ve ≤ 15 toplam hane', () => {
    expect(isPlausiblePhone('0500 000 00 00')).toBe(true)
    expect(isPlausiblePhone('+90 (500) 000-00-00')).toBe(true)
    expect(isPlausiblePhone('0500 000')).toBe(false)
    expect(isPlausiblePhone('1234567890123456')).toBe(false)
    expect(isPlausiblePhone('')).toBe(false)
  })

  it('yazarken hafif biçim: düz rakam gruplanır; işaretli biçim (+ ( ) -) AYNEN korunur', () => {
    expect(formatPhoneInput('05000000000')).toBe('0500 000 00 00')
    expect(formatPhoneInput('5000000000')).toBe('500 000 00 00')
    expect(formatPhoneInput('905000000000')).toBe('90 500 000 00 00')
    expect(formatPhoneInput('00905000000000')).toBe('0090 500 000 00 00')
    expect(formatPhoneInput('05001')).toBe('0500 1')
    expect(formatPhoneInput('0500 ')).toBe('0500')
    expect(formatPhoneInput('+90 (500) 000-00-00')).toBe('+90 (500) 000-00-00')
    expect(formatPhoneInput('0500-000')).toBe('0500-000')
    expect(formatPhoneInput('')).toBe('')
  })
})

describe('otpContact — e-posta ve kanal', () => {
  it('makul e-posta: tek @, alan adında nokta, boşluksuz', () => {
    expect(isPlausibleEmail('alice@example.com')).toBe(true)
    expect(isPlausibleEmail('  alice@example.com ')).toBe(true)
    expect(isPlausibleEmail('alice@')).toBe(false)
    expect(isPlausibleEmail('alice@example')).toBe(false)
    expect(isPlausibleEmail('a b@example.com')).toBe(false)
    expect(isPlausibleEmail('a@@example.com')).toBe(false)
  })

  it('contactKindOf: kanal + public bayrak; bayrak yoksa (eski sunucu) alan yok', () => {
    const m = { push_requires_phone: true, email_requires_email: true }
    expect(contactKindOf(m, 'push')).toBe('phone')
    expect(contactKindOf(m, 'email')).toBe('email')
    expect(contactKindOf({ push_requires_phone: false, email_requires_email: true }, 'push')).toBeNull()
    expect(contactKindOf({}, 'push')).toBeNull()
    expect(contactKindOf(null, 'email')).toBeNull()
  })

  it('validateOtpRequest: zorunlu + makullük; istenmeyen alan doğrulanmaz', () => {
    const t = (k) => k
    expect(validateOtpRequest({ username: '', kind: 'phone', phone: '' }, t))
      .toEqual({ username: 'otp.err.usernameRequired', phone: 'otp.err.phoneRequired', email: false })
    expect(validateOtpRequest({ username: 'alice', kind: 'phone', phone: '0500 000' }, t).phone).toBe('otp.err.phoneInvalid')
    expect(validateOtpRequest({ username: 'alice', kind: 'email', email: 'x@' }, t).email).toBe('otp.err.emailInvalid')
    expect(validateOtpRequest({ username: 'alice', kind: 'email', email: '' }, t).email).toBe('otp.err.emailRequired')
    const ok = validateOtpRequest({ username: 'alice', kind: null, phone: '', email: '' }, t)
    expect(Object.values(ok).some(Boolean)).toBe(false)
  })
})
