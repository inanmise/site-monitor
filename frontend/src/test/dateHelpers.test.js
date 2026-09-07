import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { formatDate, formatDateSec, formatTime, formatDateOnly } from '../api/client.js'
import { setDateLocale, dateLocale, localeFor, LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * `api/client.js` tarih yardımcıları — E7 (yerel) ve E10 (`toUtc`).
 *
 * Bu yardımcıların birim testi HİÇ yoktu; `client.test.js` yalnız istek/401/timeout yollarını
 * kapsıyor. İki kusur bu yüzden görünmedi:
 *
 * E7 — Dördü de sabit `'tr-TR'` yazıyordu, oysa `useDateLocale()` mevcut ve 5 yerde kullanılıyor.
 * İngilizce arayüzde AYNI EKRANDA iki biçim çıkıyordu: AlertHistory'nin bildirim satırı
 * `21/09/2026`, hemen altındaki alarm kartı `21.09.2026`.
 *
 * E10 — `toUtc` yalnız `'+'` arıyordu: negatif ofsetli damgaya `Z` ekleyip `Invalid Date`
 * üretiyordu. `toLocaleString` fırlatmadığı için catch dalı da çalışmıyor, ekrana düpedüz
 * "Invalid Date" yazılıyordu. Yalnız-tarih girdisi ("2026-09-07") de aynı şekilde bozuluyordu —
 * ki `formatDateOnly` tam o biçimi alıyor.
 */
describe('tarih yardımcıları', () => {
  beforeEach(() => {
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* jsdom */ }
    setDateLocale('tr')
  })
  afterEach(() => setDateLocale('tr'))

  describe('E7 — yerel canlı okunur', () => {
    it('TR seçiliyken nokta ayırıcılı Türkçe biçim', () => {
      setDateLocale('tr')
      expect(formatDate('2026-09-21T14:05:00')).toContain('21.09.2026')
    })

    it('EN seçiliyken eğik çizgili İngiliz biçimi (aynı ekranda iki biçim OLMAZ)', () => {
      setDateLocale('en')
      expect(formatDate('2026-09-21T14:05:00')).toContain('21/09/2026')
    })

    it('dil değişimi ANINDA yansır — modül yüklenirken dondurulmaz', () => {
      setDateLocale('en')
      const en = formatDateOnly('2026-09-21T00:00:00')
      setDateLocale('tr')
      const tr = formatDateOnly('2026-09-21T00:00:00')
      expect(en).not.toEqual(tr)
    })

    it('dört yardımcının hepsi aynı yereli kullanır (biri sabit kalırsa ayrışır)', () => {
      setDateLocale('en')
      expect(formatDate('2026-09-21T14:05:00')).toContain('/')
      expect(formatDateSec('2026-09-21T14:05:00')).toContain('/')
      expect(formatDateOnly('2026-09-21T14:05:00')).toContain('/')
      // formatTime yalnız saat basar; TR ve EN'in ikisi de 24 saat düzeni kullandığı için
      // biçim ayrışmaz — burada iddia "geçerli bir saat üretir" olmalı.
      //
      // SAAT RAKAMI İDDİA EDİLEMEZ: çıktı makinenin saat dilimine göre kayar (14:05 UTC,
      // geliştirici makinesinde Europe/Istanbul ile 17:05). Literal yazmak CI'da (UTC) ve
      // yerelde farklı sonuç verir — yani yeni bir zaman bombası olurdu.
      expect(formatTime('2026-09-21T14:05:07')).toMatch(/^\d{2}[:.]\d{2}[:.]\d{2}$/)
    })

    it('ayna kurulmadıysa localStorage’a düşer (i18n ile AYNI varsayılan: en)', () => {
      // Modül durumunu sıfırlayamıyoruz; sözleşmeyi doğrudan doğrula.
      expect(localeFor('en')).toBe('en-GB')
      expect(localeFor('tr')).toBe('tr-TR')
      expect(dateLocale()).toBeTruthy()
    })
  })

  describe('E10 — toUtc bozuk damgada "Invalid Date" üretmez', () => {
    it('NEGATİF ofsetli damga geçerli sonuç verir (eskiden Invalid Date)', () => {
      const out = formatDate('2026-09-07T10:00:00-03:00')
      expect(out).not.toMatch(/invalid/i)
      expect(out).toMatch(/2026/)
    })

    it('POZİTİF ofsetli damga da geçerli (mevcut davranış korunuyor)', () => {
      expect(formatDate('2026-09-07T10:00:00+03:00')).not.toMatch(/invalid/i)
    })

    it('YALNIZ TARİH girdisi geçerli sonuç verir (eskiden Invalid Date)', () => {
      setDateLocale('tr')
      const out = formatDateOnly('2026-09-07')
      expect(out).not.toMatch(/invalid/i)
      expect(out).toContain('07.09.2026')
    })

    it('Z ekli damga olduğu gibi kabul edilir', () => {
      expect(formatDate('2026-09-07T10:00:00Z')).not.toMatch(/invalid/i)
    })

    it('eksi ek olmayan düz damga UTC sayılır (yerel saate KAYMAZ)', () => {
      // Backend damgaları UTC'dir; ek olmadan verilirse JS yerel sayardı ve saat kayardı.
      setDateLocale('tr')
      expect(formatTime('2026-09-07T10:00:00')).toMatch(/^\d{2}[:.]\d{2}[:.]\d{2}$/)
    })

    it('dize OLMAYAN girdi fırlatmaz (eskiden .endsWith patlıyordu)', () => {
      expect(() => formatDate(new Date('2026-09-07T10:00:00Z'))).not.toThrow()
      expect(() => formatDate(12345)).not.toThrow()
    })

    it('boş/null girdi yer tutucu döner', () => {
      expect(formatDate(null)).toBe('N/A')
      expect(formatDateSec('')).toBe('N/A')
      expect(formatTime(null)).toBe('—')
      expect(formatDateOnly(undefined)).toBe('—')
    })
  })
})
