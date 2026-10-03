import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PUSH_TRANSLATE, canEncodeUnit, pushSafe, pushSafeReport, pushTruncate } from '../utils/pushSafeText.js'

/**
 * SENKRON KAPISI (2026-10-03): `utils/pushSafeText.js` backend `PushText.pushSafe`'in aynasıdır — Giriş Yöntemleri push
 * metni önizlemesi telefona giden metni TAM OLARAK göstermeli. Çeviri tablosu backend kaynağından OKUNUR ve birebir
 * karşılaştırılır (kardeş desen: `inventory-flags-sync.test.jsx` Java'yı okur). Repertuvar kuralını (ISO-8859-9) backend
 * `OtpPushTemplateTest#channelRepertoire_matchesFrontendMirror` pinler. Davranış örnekleri backend
 * `LoginOtpPushSafeTextTest` ile AYNI girdi/çıktı.
 */
const HERE = path.dirname(fileURLToPath(import.meta.url))
const PUSH_TEXT = path.resolve(HERE, '../../../backend/src/main/java/com/sitemonitor/service/PushText.java')

/** `Map.entry('X', "Y")` → [X, Y] (Java dize kaçışı \" ve \\ çözülür). */
function javaTranslate() {
  const src = fs.readFileSync(PUSH_TEXT, 'utf8')
  const block = /TRANSLATE\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/.exec(src)
  if (!block) return null
  return [...block[1].matchAll(/Map\.entry\('(.)',\s*"((?:[^"\\]|\\.)*)"\)/gu)]
    .map((m) => [m[1], m[2].replace(/\\(.)/g, '$1')])
}

describe('pushSafeText — backend PushText aynası', () => {
  it('çeviri tablosu backend PushText.TRANSLATE ile BİREBİR aynı (anahtar + karşılık)', () => {
    const java = javaTranslate()
    expect(java, 'PushText.java TRANSLATE bloğu okunamadı (yol ya da sözdizimi değişmiş)').not.toBeNull()
    expect(java.length).toBeGreaterThan(15)
    expect(Object.fromEntries(java)).toEqual({ ...PUSH_TRANSLATE })
  })

  it('backend LoginOtpPushSafeTextTest ile aynı girdi → aynı çıktı', () => {
    expect(pushSafe('Giriş — kodu ✓')).toBe('Giriş - kodu OK')
    expect(pushSafe('Kodunuz: 654321 — ğüşıöç İ “tırnak” … ✓ ⚡ son'))
      .toBe('Kodunuz: 654321 - ğüşıöç İ "tırnak" ... OK son')
  })

  it('repertuvar: Latin-5 — Türkçe harfler kalır; Ð Ý Þ ð ý þ, emoji ve vekil çiftler düşer', () => {
    expect(pushSafe('ĞğİıŞşÇçÖöÜü é ß')).toBe('ĞğİıŞşÇçÖöÜü é ß')
    expect(pushSafe('aÐbÝcÞdðeýfþg')).toBe('abcdefg')
    expect(pushSafe('kod 😀 hazır 🔐')).toBe('kod hazır')
    for (const cp of [0x41, 0xe9, 0x11e, 0x131]) expect(canEncodeUnit(cp)).toBe(true)
    for (const cp of [0xd0, 0xfe, 0x2014, 0xd83d, 0x20ac]) expect(canEncodeUnit(cp)).toBe(false)
  })

  it('boşluk: Java \\s{2,} → tek boşluk (tek satır sonu korunur, NBSP Java\'da boşluk SAYILMAZ); uçlar Java trim', () => {
    expect(pushSafe('  a —  b  ')).toBe('a - b')
    expect(pushSafe('a\nb')).toBe('a\nb')
    expect(pushSafe('a\n\nb')).toBe('a b')
    expect(pushSafe('a  b')).toBe('a  b')
    expect(pushSafe(' x ')).toBe(' x ')
    expect(pushSafe('')).toBe('')
    expect(pushSafe(null)).toBe(null)
  })

  it('truncate aynası: kelime sınırından kes + "..."', () => {
    expect(pushTruncate('kısa', 10)).toBe('kısa')
    expect(pushTruncate('bir iki üç dört beş altı', 15)).toBe('bir iki üç...')
    expect(pushTruncate('a'.repeat(30), 10)).toBe('aaaaaaa...')
  })

  it('rapor: çevrilen ve düşen karakterler sayılarıyla; emoji tek simge, birleştirici raporlanmaz', () => {
    const r = pushSafeReport('Kod — 1 — 2 … 👍🏽 ✓ 👨‍💻 ⚡️')
    expect(r.converted).toEqual([
      { from: '—', to: '-', count: 2 }, { from: '…', to: '...', count: 1 }, { from: '✓', to: 'OK', count: 1 },
    ])
    expect(r.dropped.map((d) => d.ch)).toEqual(['👍', '🏽', '👨', '💻', '⚡'])
    expect(r.text).toBe('Kod - 1 - 2 ... OK')
    expect(pushSafeReport('Temiz metin ğüş').converted).toEqual([])
    expect(pushSafeReport('Temiz metin ğüş').dropped).toEqual([])
  })
})
