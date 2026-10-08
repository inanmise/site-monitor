import { describe, it, expect, beforeEach } from 'vitest'
import {
  ERROR_TEXTS, ApiError, statusMessage, networkMessage, nonJsonMessage, parseRetryAfter, isTechnicalMessage,
  isVagueMessage, isCodeLike, friendlyServerMessage, normalizeErrorBody, errorInfoOf, lookupErrorInfo,
  rememberErrorInfo, clearErrorRegistry, errorLang,
} from '../utils/errorMessages.js'

/**
 * Kullanıcıya görünen API hata metinleri (2026-10-08): her durumun açıklayıcı metni (ne · neden · ne yapmalı), iyi
 * sunucu metninin korunması, teknik metnin değiştirilmesi ve teknik künyenin (durum · kod · istek kimliği) taşınması.
 */
const STATUSES = [400, 401, 403, 404, 405, 408, 409, 413, 415, 422, 429, 500, 502, 503, 504]

beforeEach(() => clearErrorRegistry())

describe('statusMessage — her HTTP durumu için açıklayıcı TR/EN metin', () => {
  it.each(STATUSES)('%i: iki dilde dolu, ayrık ve "sonraki adım" içeren metin', (s) => {
    const tr = statusMessage(s, { lang: 'tr' })
    const en = statusMessage(s, { lang: 'en' })
    expect(tr.length).toBeGreaterThanOrEqual(60)
    expect(en.length).toBeGreaterThanOrEqual(60)
    expect(tr).not.toBe(en)
    // sonraki adım: tekrar dene / iste / kontrol et / yenile / bekle / küçült
    expect(tr).toMatch(/tekrar deneyin|isteyin|kontrol edin|yenileyin|bekleyip|küçültüp|yeniden giriş|seçin/)
    expect(en).toMatch(/try again|ask |check |reload|wait|make the file|sign in|choose/i)
  })

  it('403 yetkiyi ve kimden isteneceğini söyler; 404 olası nedeni söyler', () => {
    expect(statusMessage(403, { lang: 'tr' })).toMatch(/yetkiniz yok.*takım yöneticinizden/)
    expect(statusMessage(403, { lang: 'en' })).toMatch(/permission.*team manager/)
    expect(statusMessage(404, { lang: 'tr' })).toMatch(/silinmiş, taşınmış/)
  })

  it('413 sınır biliniyorsa MB olarak yazar', () => {
    expect(statusMessage(413, { lang: 'tr', limitMb: 10 })).toContain('en fazla 10 MB')
    expect(statusMessage(413, { lang: 'en', limitMb: 2.5 })).toContain('at most 2.5 MB')
    expect(statusMessage(413, { lang: 'en' })).not.toMatch(/MB/)
  })

  it('429 Retry-After varsa bekleme süresini yazar (saniye ya da HTTP tarihi)', () => {
    expect(statusMessage(429, { lang: 'tr', retryAfter: '30' })).toContain('30 saniye bekleyip')
    expect(statusMessage(429, { lang: 'en', retryAfter: 12 })).toContain('Wait 12 seconds')
    expect(statusMessage(429, { lang: 'en' })).toContain('about a minute')
    const inTen = new Date(Date.now() + 10_000).toUTCString()
    expect(parseRetryAfter(inTen)).toBeGreaterThanOrEqual(9)
    expect(parseRetryAfter('abc')).toBeNull()
    expect(parseRetryAfter('-5')).toBeNull()
    expect(parseRetryAfter('99999')).toBeNull()        // saatlerce bekleme metne yazılmaz
  })

  it('502/503/504: geçici kesinti, bir dakika sonra dene, sürerse yöneticilere bildir', () => {
    for (const s of [502, 503, 504]) {
      expect(statusMessage(s, { lang: 'tr' })).toMatch(/Bir dakika sonra.*sistem yöneticilerine bildirin/)
      expect(statusMessage(s, { lang: 'en' })).toMatch(/in a minute.*tell the system administrators/)
    }
  })

  it('bilinmeyen 4xx/5xx durum kodunu metne yazar', () => {
    expect(statusMessage(418, { lang: 'en' })).toContain('HTTP 418')
    expect(statusMessage(507, { lang: 'tr' })).toContain('HTTP 507')
  })

  it('dil: tr* → tr, diğer her şey en (arayüz varsayılanı)', () => {
    expect(errorLang('tr-TR')).toBe('tr')
    expect(errorLang('de')).toBe('en')
    expect(errorLang(undefined)).toBe('en')
  })

  it('iki dil sözlüğü aynı anahtarlara ve aynı yer tutuculara sahip', () => {
    expect(Object.keys(ERROR_TEXTS.tr).sort()).toEqual(Object.keys(ERROR_TEXTS.en).sort())
    for (const k of Object.keys(ERROR_TEXTS.tr)) {
      const ph = (s) => (s.match(/\{\d+\}/g) || []).sort()
      expect(ph(ERROR_TEXTS.tr[k]), k).toEqual(ph(ERROR_TEXTS.en[k]))
    }
  })
})

describe('networkMessage / nonJsonMessage', () => {
  it('ağ hatası bağlantı/VPN kontrolünü önerir; çevrimdışı ayrı metin', () => {
    expect(networkMessage({ lang: 'tr', offline: false })).toMatch(/VPN.*tekrar deneyin/)
    expect(networkMessage({ lang: 'en', offline: false })).toMatch(/VPN.*try again/)
    expect(networkMessage({ lang: 'en', offline: true })).toMatch(/offline/)
  })

  it('zaman aşımı ve iptal ayrı metinler', () => {
    expect(networkMessage({ lang: 'tr', kind: 'timeout' })).toMatch(/zamanında yanıt vermedi/)
    expect(networkMessage({ lang: 'en', kind: 'aborted' })).toMatch(/cancelled/)
  })

  it('JSON olmayan yanıt: 4xx/5xx durum metnine, 2xx "beklenmeyen yanıt"a düşer', () => {
    expect(nonJsonMessage(502, { lang: 'en' })).toBe(statusMessage(502, { lang: 'en' }))
    expect(nonJsonMessage(200, { lang: 'tr' })).toMatch(/web sayfası/)
  })
})

describe('sınıflandırma — teknik / jenerik / kod', () => {
  it.each([
    'java.lang.NullPointerException: Cannot invoke "Object.toString()"',
    'org.springframework.dao.InvalidDataAccessApiUsageException: x',
    'at com.sitemonitor.service.X.run(X.java:42)',
    'IllegalStateException: boom',
    'Unexpected token < in JSON at position 0',
    '<!DOCTYPE html><html><body><h1>502 Bad Gateway</h1></body></html>',
    'Request failed with status code 500',
    'TypeError: Failed to fetch',
    'NetworkError when attempting to fetch resource.',
    'could not execute statement; SQL [n/a]; constraint [uk_x]',
    'Whitelabel Error Page',
  ])('teknik: %s', (m) => expect(isTechnicalMessage(m)).toBe(true))

  it.each([
    'Bu alan adı zaten kayıtlı',
    'net.ornek.com.tr envanterde zaten var',
    'Domain must not be empty',
    'Alarm not found: #999',
    'Eşik 1 ile 100 arasında olmalı',
  ])('teknik DEĞİL (alan adları dahil): %s', (m) => expect(isTechnicalMessage(m)).toBe(false))

  it('jenerik metinler tam eşleşmeyle yakalanır; açıklamalı metin kalır', () => {
    for (const m of ['Hata', 'Error.', 'Sunucu hatası', 'Internal Server Error', 'İşlem başarısız', 'Forbidden', 'No value present', '']) {
      expect(isVagueMessage(m), m).toBe(true)
    }
    expect(isVagueMessage('Hata: port 1-65535 arasında olmalı')).toBe(false)
  })

  it('BÜYÜK_HARF_KOD korunur (ekranlar metinle dallanıyor)', () => {
    expect(isCodeLike('VERSION_CONFLICT')).toBe(true)
    expect(friendlyServerMessage('DUPLICATE_WEEK', 409, { lang: 'tr' })).toBe('DUPLICATE_WEEK')
    expect(isCodeLike('Kayıt yok')).toBe(false)
  })
})

describe('friendlyServerMessage', () => {
  it('iyi sunucu metnini AYNEN korur', () => {
    expect(friendlyServerMessage('Bu alan adı zaten kayıtlı', 409, { lang: 'en' })).toBe('Bu alan adı zaten kayıtlı')
  })

  it('teknik ya da jenerik metni duruma göre açıklayıcı metinle değiştirir', () => {
    expect(friendlyServerMessage('java.lang.IllegalStateException: x', 500, { lang: 'tr' })).toBe(statusMessage(500, { lang: 'tr' }))
    expect(friendlyServerMessage('Forbidden', 403, { lang: 'en' })).toBe(statusMessage(403, { lang: 'en' }))
    expect(friendlyServerMessage(null, 404, { lang: 'en' })).toBe(statusMessage(404, { lang: 'en' }))
  })
})

describe('normalizeErrorBody', () => {
  it('iyi metni korur, status ekler, künyeyi sayılamaz özellik olarak iliştirir', () => {
    const body = { success: false, error: 'Bu takımın izlemesini düzenleyemezsiniz', code: 'FORBIDDEN', request_id: 'abc123' }
    const out = normalizeErrorBody(body, { status: 403, lang: 'tr' })
    expect(out).toBe(body)
    expect(out.error).toBe('Bu takımın izlemesini düzenleyemezsiniz')
    expect(out.status).toBe(403)
    expect(out.errorInfo).toEqual({ status: 403, code: 'FORBIDDEN', requestId: 'abc123' })
    expect(Object.keys(out)).not.toContain('errorInfo')          // toEqual / JSON / spread değişmez
    expect(JSON.stringify(out)).not.toContain('errorInfo')
  })

  it('teknik metni değiştirir; istek kimliği başlıktan gelir', () => {
    const out = normalizeErrorBody({ success: false, error: 'org.hibernate.LazyInitializationException: no session' },
      { status: 500, requestId: 'rid-9', lang: 'en' })
    expect(out.error).toBe(statusMessage(500, { lang: 'en' }))
    expect(out.errorInfo).toEqual({ status: 500, requestId: 'rid-9' })
  })

  it('error yoksa iyi `message` kullanılır', () => {
    expect(normalizeErrorBody({ message: 'Şablon adı zaten kullanılıyor' }, { status: 409, lang: 'tr' }).error)
      .toBe('Şablon adı zaten kullanılıyor')
  })

  it('düz nesne olmayan gövde (null/dizi) açıklayıcı yükle değiştirilir', () => {
    const out = normalizeErrorBody(null, { status: 503, lang: 'tr' })
    expect(out).toEqual({ success: false, status: 503, error: statusMessage(503, { lang: 'tr' }) })
  })

  it('429 gövdesindeki retry_after_seconds ve 413 max_mb metne yansır', () => {
    expect(normalizeErrorBody({ success: false }, { status: 429, lang: 'en' }).error).toContain('about a minute')
    expect(normalizeErrorBody({ success: false, retry_after_seconds: 20 }, { status: 429, lang: 'en' }).error).toContain('Wait 20 seconds')
    expect(normalizeErrorBody({ success: false, max_mb: 12 }, { status: 413, lang: 'tr' }).error).toContain('en fazla 12 MB')
    expect(normalizeErrorBody({ success: false }, { status: 429, retryAfter: '7', lang: 'tr' }).error).toContain('7 saniye')
  })

  it('metin→künye kaydı: aynı metin ortak bildirimde künyeyi bulur; süre dolunca düşer', () => {
    normalizeErrorBody({ error: 'Kayıt çakıştı, sayfayı yenileyin' }, { status: 409, requestId: 'r1', lang: 'tr' })
    expect(lookupErrorInfo('Kayıt çakıştı, sayfayı yenileyin')).toEqual({ status: 409, requestId: 'r1' })
    expect(lookupErrorInfo('başka metin')).toBeNull()
    rememberErrorInfo('eski', { status: 500 }, Date.now() - 200_000)
    expect(lookupErrorInfo('eski')).toBeNull()
  })
})

describe('ApiError / errorInfoOf', () => {
  it('ApiError kullanıcı metnini taşır; String(e) önek eklemez', () => {
    const e = new ApiError('Sunucuya ulaşılamadı', { status: 0, code: 'NETWORK_ERROR' })
    expect(e).toBeInstanceOf(Error)
    expect(String(e)).toBe('Sunucuya ulaşılamadı')
    expect(errorInfoOf(e)).toEqual({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('error_code ve kod biçimli error künyeye kod olarak girer; boş künye null', () => {
    expect(errorInfoOf({ error_code: 'ACTIVE_SESSION_EXISTS', status: 409 })).toEqual({ status: 409, code: 'ACTIVE_SESSION_EXISTS' })
    expect(errorInfoOf({ error: 'VERSION_CONFLICT' })).toEqual({ code: 'VERSION_CONFLICT' })
    expect(errorInfoOf({ error: 'düz metin' })).toBeNull()
    expect(errorInfoOf(null)).toBeNull()
  })
})
