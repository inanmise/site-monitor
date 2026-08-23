import { describe, it, expect } from 'vitest'
import {
  MASK, humanSeconds, humanMillis, formatValue, fieldLabel,
  parseChanges, parseSnapshot, shortUserAgent,
} from '../components/history/changeFields.js'

/**
 * Değişiklik geçmişinin DEĞER BİÇİMLENDİRMESİ.
 *
 * <p>Bu modül şimdiye dek yalnız bileşenler üzerinden dolaylı test ediliyordu; sınır durumları
 * (bozuk JSON, maskeli değer, sıfır/negatif süre, tanınmayan user-agent) doğrudan pinlenmemişti.
 * Buradaki hatalar sessizdir: ekran çizilir, sadece yanlış ya da okunmaz bilgi gösterir.
 */
const tr = (k) => ({ 'chg.unitSec': 'sn', 'chg.unitMin': 'dk', 'chg.unitHour': 'sa',
  'chg.valueOn': 'Açık', 'chg.valueOff': 'Kapalı', 'chg.field.name': 'Ad' }[k] ?? k)

describe('humanSeconds / humanMillis', () => {
  it('en büyük tam birime yuvarlar', () => {
    expect(humanSeconds(300, tr)).toBe('5 dk')
    expect(humanSeconds(3600, tr)).toBe('1 sa')
    expect(humanSeconds(7200, tr)).toBe('2 sa')
    expect(humanSeconds(45, tr)).toBe('45 sn')
    expect(humanSeconds(90, tr)).toBe('90 sn')      // tam dakika değil → saniye kalır
  })

  it('milisaniye tam saniyeyse saniyeye çevrilir', () => {
    expect(humanMillis(5000, tr)).toBe('5 sn')
    expect(humanMillis(4500, tr)).toBe('4500 ms')
  })

  it('sıfır ve negatif değer OLDUĞU GİBİ bırakılır (uydurma birim yazılmaz)', () => {
    expect(humanSeconds(0, tr)).toBe('0')
    expect(humanSeconds(-1, tr)).toBe('-1')
    expect(humanMillis(0, tr)).toBe('0')
  })

  it('t verilmezse Türkçe kısaltmaya düşer (saf birim testleri için)', () => {
    expect(humanSeconds(300)).toBe('5 dk')
  })
})

describe('formatValue', () => {
  const ctx = { t: tr, teamNames: { 5: 'Kanal' } }

  it('boş değerler tire ile gösterilir — kullanıcı "veri yok"u görsün', () => {
    expect(formatValue('name', null, ctx)).toBe('—')
    expect(formatValue('name', undefined, ctx)).toBe('—')
    expect(formatValue('name', '', ctx)).toBe('—')
  })

  it('maskeli değer OLDUĞU GİBİ kalır — asla insancıllaştırılmaz', () => {
    expect(formatValue('password', MASK, ctx)).toBe(MASK)
  })

  it('boolean açık/kapalı rozetine çevrilir (metin "true" dâhil)', () => {
    expect(formatValue('active', true, ctx)).toBe('Açık')
    expect(formatValue('active', false, ctx)).toBe('Kapalı')
    expect(formatValue('active', 'true', ctx)).toBe('Açık')
  })

  it('takım kimliği ADA çevrilir; ad yoksa ham değer kalır', () => {
    expect(formatValue('teamId', 5, ctx)).toBe('Kanal')
    expect(formatValue('teamId', 99, ctx)).toBe('99')
  })

  it('süre alanları ad KALIBINDAN tanınır', () => {
    expect(formatValue('intervalSeconds', 300, ctx)).toBe('5 dk')
    expect(formatValue('timeoutMs', 5000, ctx)).toBe('5 sn')
  })

  it('çok uzun metin kırpılır — satır taşmasın (script gövdesi, env JSON)', () => {
    const long = 'x'.repeat(200)
    const out = formatValue('script', long, ctx)
    expect(out).toHaveLength(121)          // 120 + tek karakterlik üç nokta
    expect(out.endsWith('…')).toBe(true)
  })

  it('tam sınırdaki metin kırpılmaz', () => {
    expect(formatValue('script', 'y'.repeat(120), ctx)).toHaveLength(120)
  })
})

describe('fieldLabel', () => {
  it('sözlükte varsa etiketi, yoksa HAM ANAHTARI döner (sessiz boşluk olmaz)', () => {
    expect(fieldLabel(tr, 'name')).toBe('Ad')
    expect(fieldLabel(tr, 'bilinmeyenAlan')).toBe('bilinmeyenAlan')
  })
})

describe('parseChanges / parseSnapshot', () => {
  it('diff JSON satır listesine çevrilir', () => {
    const rows = parseChanges('{"port":{"from":80,"to":443}}')
    expect(rows).toEqual([{ key: 'port', from: 80, to: 443 }])
  })

  it('BOZUK JSON boş liste döner — tek satır ekranı çökertmez', () => {
    expect(parseChanges('{bozuk')).toEqual([])
    expect(parseSnapshot('[]bozuk')).toEqual([])
  })

  it('boş/eksik girdiler boş liste döner', () => {
    for (const bad of [null, undefined, '', '"metin"', '42']) {
      expect(parseChanges(bad)).toEqual([])
      expect(parseSnapshot(bad)).toEqual([])
    }
  })

  it('hazır nesne de kabul edilir (uç ham JSON metni taşıyor ama sözleşme esnek)', () => {
    expect(parseChanges({ a: { from: 1, to: 2 } })).toEqual([{ key: 'a', from: 1, to: 2 }])
    expect(parseSnapshot({ a: 1 })).toEqual([{ key: 'a', value: 1 }])
  })

  it('null değerli alan çökertmez', () => {
    expect(parseChanges('{"a":null}')).toEqual([{ key: 'a', from: undefined, to: undefined }])
  })
})

describe('shortUserAgent', () => {
  it('tarayıcı + işletim sistemine indirger', () => {
    expect(shortUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36'))
      .toBe('Chrome 126 · Windows')
    expect(shortUserAgent('Mozilla/5.0 (Windows NT 10.0) Edg/125.0.0.0')).toBe('Edge 125 · Windows')
  })

  it('tanınmayan aracı kırpılarak gösterilir (bilgi tamamen kaybolmasın)', () => {
    const out = shortUserAgent('x'.repeat(60))
    expect(out).toHaveLength(41)
    expect(out.endsWith('…')).toBe(true)
    expect(shortUserAgent('curl/8.5.0')).toBe('curl/8.5.0')
  })

  it('boş aracı null döner — arayüz alanı hiç çizmez', () => {
    expect(shortUserAgent(null)).toBeNull()
    expect(shortUserAgent('')).toBeNull()
  })
})
