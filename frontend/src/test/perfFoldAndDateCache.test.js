import { describe, it, expect, vi, afterEach } from 'vitest'
import { foldSearchText, matchesSearchText, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { formatDate, formatDateSec, formatTime, formatDateOnly, toUtc } from '../api/client.js'
import { setDateLocale } from '../i18n/dateLocale.js'

// Performans (2026-11-06, PF4 D3 / D5): arama katlaması ve tarih biçimleyicisi önbellekli — çıktı BİREBİR aynı.

afterEach(() => {
  vi.restoreAllMocks()
  setDateLocale('en')
})

describe('foldSearchText önbelleği (D3)', () => {
  // Önbelleksiz eski gövde — kâhin (oracle)
  const oldFold = (v) => String(v ?? '').toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/[̀-ͯ]/g, '')

  it('çıktı eski gövdeyle aynı (Türkçe harfler, aksanlar, boş / sayı / null)', () => {
    const inputs = ['İnternet Şubesi', 'KATILIM', 'Ödeme', 'ığüşöç', 'IĞÜŞÖÇ', '', null, undefined, 42, 'api.example.com',
      'Dijital Kanallar (Çağrı Merkezi / Mobil Bankacılık)']
    for (const v of inputs) {
      expect(foldSearchText(v)).toBe(oldFold(v))
      expect(foldSearchText(v)).toBe(oldFold(v))   // ikinci çağrı önbellekten — aynı sonuç
    }
  })

  it('aynı dize ikinci kez normalize EDİLMEZ; pano araması tuş başına kart sayısı kadar katlama yapmaz', () => {
    const spy = vi.spyOn(String.prototype, 'normalize')
    const cards = Array.from({ length: 300 }, (_, i) => ({
      domain: `uniq-perf-${i}.example.com`, issuer: `CA ${i % 3}`, subject: `CN=uniq-perf-${i}`, group_name: `Grup ${i % 5}`, tags: 'prod,kritik',
    }))
    const filter = (q) => cards.filter((c) => matchesGroupOrTagText(c, q) || matchesSearchText(q, [c.domain, c.issuer, c.subject]))
    expect(filter('hicbiri-eslesmez')).toEqual([])   // ilk tuş: bütün kart değerleri bir kez katlanır
    const firstPass = spy.mock.calls.length
    expect(firstPass).toBe(300 + 300 + 3 + 5 + 2 + 1)   // yalnız FARKLI değerler (alan adı, konu, yayıncı, grup, etiket, arama)
    spy.mockClear()
    // İkinci tuş: aynı kart değerleri önbellekten; yalnız yeni arama metni katlanır.
    const found = filter('uniq-perf-12')
    expect(spy.mock.calls.length).toBe(1)
    expect(found.map((c) => c.domain)).toEqual(['uniq-perf-12.example.com', 'uniq-perf-120.example.com',
      'uniq-perf-121.example.com', 'uniq-perf-122.example.com', 'uniq-perf-123.example.com', 'uniq-perf-124.example.com',
      'uniq-perf-125.example.com', 'uniq-perf-126.example.com', 'uniq-perf-127.example.com', 'uniq-perf-128.example.com',
      'uniq-perf-129.example.com'])
  })

  it('çok uzun metin önbelleğe girmez ama doğru katlanır', () => {
    const long = 'Ş'.repeat(600)
    expect(foldSearchText(long)).toBe(oldFold(long))
    const spy = vi.spyOn(String.prototype, 'normalize')
    foldSearchText(long)
    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('tarih biçimleyici önbelleği (D5)', () => {
  // Eski gövdeler — kâhin
  const old = {
    formatDate: (iso) => { if (!iso) return 'N/A'; try { return new Date(toUtc(iso)).toLocaleString(locale(), { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) } catch { return iso } },
    formatDateSec: (iso) => { if (!iso) return 'N/A'; try { return new Date(toUtc(iso)).toLocaleString(locale(), { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) } catch { return iso } },
    formatTime: (iso) => { if (!iso) return '—'; try { return new Date(toUtc(iso)).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }) } catch { return iso.substring(11, 19) } },
    formatDateOnly: (iso) => { if (!iso) return '—'; try { return new Date(toUtc(iso)).toLocaleDateString(locale(), { year: 'numeric', month: '2-digit', day: '2-digit' }) } catch { return iso.substring(0, 10) } },
  }
  let current = 'en'
  const locale = () => (current === 'tr' ? 'tr-TR' : 'en-GB')
  const fns = { formatDate, formatDateSec, formatTime, formatDateOnly }
  const inputs = ['2026-09-07T10:00:00', '2026-09-07T10:00:00Z', '2026-09-07T10:00:00-03:00', '2026-09-07', '2026-01-31T23:59:59.123',
    'bozuk-tarih', '', null, 1767225600000]

  for (const lang of ['en', 'tr']) {
    it(`çıktı eski toLocale* gövdesiyle aynı (${lang}; geçersiz tarih dahil)`, () => {
      current = lang
      setDateLocale(lang)
      for (const [name, fn] of Object.entries(fns)) {
        for (const v of inputs) expect(fn(v), `${name}(${v})`).toBe(old[name](v))
      }
    })
  }

  it('yüzlerce çağrı yeni Intl.DateTimeFormat KURMAZ (dil değişse de her yerel için tek biçimleyici)', () => {
    for (const lang of ['en', 'tr']) { setDateLocale(lang); for (const fn of Object.values(fns)) fn('2026-09-07T10:00:00') }   // ısıt
    const spy = vi.spyOn(Intl, 'DateTimeFormat')
    const proto = vi.spyOn(Date.prototype, 'toLocaleString')
    for (let i = 0; i < 500; i++) {
      setDateLocale(i % 2 ? 'tr' : 'en')
      formatDate(`2026-09-07T10:${String(i % 60).padStart(2, '0')}:00`)
      formatDateOnly('2026-09-07')
    }
    expect(spy).not.toHaveBeenCalled()
    expect(proto).not.toHaveBeenCalled()     // eski yol (her çağrıda yeni biçimleyici) yalnız geçersiz tarihte
    current = 'tr'
    setDateLocale('tr')
    expect(formatDate('2026-09-07T10:00:00')).toBe(old.formatDate('2026-09-07T10:00:00'))
  })
})
