import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * Localization regression guard.
 *
 * Catches the common mistake of adding a translation key to one language
 * dictionary and forgetting the other -- previously this only surfaced when
 * a Turkish operator stumbled across an English string in production.
 */
describe('i18n parity (TR ↔ EN)', () => {
  /**
   * YİNELENEN ANAHTAR (2026-09-22 QA): aynı anahtar bir sözlükte iki kez tanımlanırsa SONRAKİ
   * kazanır — nesne yine geçerli olduğundan yukarıdaki eşleşme testleri bunu GÖREMEZ. Gerçek
   * vaka: 'inv.copyFailed' hem "bağlantı kopyalanamadı" hem "açıklama kopyalanamadı" için
   * tanımlandı; kullanıcı açıklamayı kopyalayamayınca "adres çubuğundan kopyalayın" uyarısı aldı.
   * Vite yalnız derleme UYARISI basıyor (kimse görmüyor), bu yüzden kaynak metin taranır.
   */
  it('aynı anahtar bir sözlükte iki kez tanımlanmaz (sonraki sessizce kazanır)', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../i18n/index.jsx'), 'utf8')
    const start = (marker) => src.indexOf(marker)
    const blocks = {
      TR: src.slice(start('export const TR = {'), start('export const EN = {')),
      EN: src.slice(start('export const EN = {')),
    }
    const dups = {}
    for (const [lang, body] of Object.entries(blocks)) {
      const seen = new Set(); const twice = new Set()
      for (const m of body.matchAll(/(?:^|[{,\s])'([a-zA-Z0-9_.$-]+)'\s*:/g)) {
        if (seen.has(m[1])) twice.add(m[1]); else seen.add(m[1])
      }
      if (twice.size) dups[lang] = [...twice].sort()
    }
    expect(dups, `Yinelenen i18n anahtarı: ${JSON.stringify(dups)}`).toEqual({})
  })

  it('every TR key has an EN counterpart', () => {
    const missing = Object.keys(TR).filter((k) => !(k in EN))
    expect(missing, `EN translations missing for keys: ${missing.join(', ')}`).toEqual([])
  })

  it('every EN key has a TR counterpart', () => {
    const missing = Object.keys(EN).filter((k) => !(k in TR))
    expect(missing, `TR translations missing for keys: ${missing.join(', ')}`).toEqual([])
  })

  it('no empty translation values', () => {
    const trEmpty = Object.entries(TR)
      .filter(([, v]) => typeof v !== 'string' || !v.trim())
      .map(([k]) => k)
    const enEmpty = Object.entries(EN)
      .filter(([, v]) => typeof v !== 'string' || !v.trim())
      .map(([k]) => k)
    expect({ trEmpty, enEmpty }).toEqual({ trEmpty: [], enEmpty: [] })
  })

  it('placeholder counts match between TR and EN', () => {
    // If a TR key uses {0} and {1}, the EN counterpart must too (otherwise
    // formatting is silently lossy and user-visible text loses substitutions).
    //
    // Çokluk kümesi (multiset) karşılaştırılır, benzersiz küme DEĞİL: t() artık split/join
    // ile TÜM tekrarları doldurduğu için TR'de iki kez, EN'de bir kez geçen bir {0} gerçek
    // bir çeviri farkıdır (bir dilde bilgi eksik kalır). Set kullanan eski sürüm bunu
    // yakalamıyordu.
    function placeholders(str) {
      if (typeof str !== 'string') return []
      return (str.match(/\{\d+\}/g) || []).sort()
    }
    const mismatches = []
    for (const key of Object.keys(TR)) {
      if (!(key in EN)) continue
      const trArr = placeholders(TR[key])
      const enArr = placeholders(EN[key])
      if (trArr.join(',') !== enArr.join(',')) {
        mismatches.push(`${key}: TR=${trArr.join(',') || '(none)'} EN=${enArr.join(',') || '(none)'}`)
      }
    }
    expect(mismatches, `Placeholder mismatch:\n  ${mismatches.join('\n  ')}`).toEqual([])
  })

  it('placeholder indices are contiguous from {0}', () => {
    // t(key, a, b) argümanları 0'dan sırayla eşlenir; metin {0} ve {2} kullanıp {1}'i
    // atlarsa ikinci argüman sessizce kaybolur ve üçüncüsü hiç yazılmaz.
    const bad = []
    for (const [dictName, dict] of [['TR', TR], ['EN', EN]]) {
      for (const [key, value] of Object.entries(dict)) {
        if (typeof value !== 'string') continue
        const idx = [...new Set((value.match(/\{\d+\}/g) || []).map((p) => Number(p.slice(1, -1))))]
          .sort((a, b) => a - b)
        if (idx.length && idx.some((n, i) => n !== i)) {
          bad.push(`${dictName} ${key}: {${idx.join('},{')}}`)
        }
      }
    }
    expect(bad, `Non-contiguous placeholder indices:\n  ${bad.join('\n  ')}`).toEqual([])
  })
})
