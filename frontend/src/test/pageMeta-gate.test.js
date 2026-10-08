import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { VALID_TABS } from '../utils/appRoutes.js'
import {
  META_PAGES, META_TITLE_MAX, META_DESCRIPTION_MIN, META_DESCRIPTION_MAX, formatTitle, pageMetaKey, tabMetaKey,
} from '../utils/pageMeta.js'

/**
 * SAYFA META KAPISI (2026-10-08, kullanıcı isteği: "her sayfada meta başlığı ve açıklaması olsun").
 * App.jsx'in bildiği HER sekme (VALID_TABS) ve sekme dışı her ekran (META_PAGES) iki dilde de:
 *  - başlık: boş değil, "<Sayfa> · SiteMonitor" ≤ 60 karakter,
 *  - açıklama: 50–160 karakter, gerçek bir cümle (başlığın tekrarı değil), dilde benzersiz.
 * Yeni sekme eklenince bu test kırmızıya döner — `meta.tab.<sekme>.title|description` TR + EN eklenmeli.
 */
const DICTS = { tr: TR, en: EN }
const KEYS = [
  ...[...VALID_TABS].map((tab) => ({ id: `tab:${tab}`, key: tabMetaKey(tab) })),
  ...META_PAGES.map((page) => ({ id: `page:${page}`, key: pageMetaKey(page) })),
]

describe('sayfa meta kapısı', () => {
  it('kapsam: 40 sekme + 8 sekme dışı ekran (VALID_TABS tek kaynaktan)', () => {
    expect(VALID_TABS.size).toBeGreaterThanOrEqual(40)
    expect(KEYS.length).toBe(VALID_TABS.size + META_PAGES.length)
  })

  for (const [lang, dict] of Object.entries(DICTS)) {
    describe(lang, () => {
      it.each(KEYS)('$id: başlık + açıklama var ve sınırlar içinde', ({ id, key }) => {
        const title = dict[`${key}.title`]
        const desc = dict[`${key}.description`]
        expect(typeof title, `${lang} ${id} başlık eksik`).toBe('string')
        expect(typeof desc, `${lang} ${id} açıklama eksik`).toBe('string')
        expect(title.trim(), `${lang} ${id} başlık boş`).not.toBe('')
        const full = formatTitle(title, 'SiteMonitor')
        expect(full.length, `${lang} ${id} tam başlık "${full}" (${full.length})`).toBeLessThanOrEqual(META_TITLE_MAX)
        expect(desc.length, `${lang} ${id} açıklama ${desc.length} karakter: ${desc}`).toBeGreaterThanOrEqual(META_DESCRIPTION_MIN)
        expect(desc.length, `${lang} ${id} açıklama ${desc.length} karakter: ${desc}`).toBeLessThanOrEqual(META_DESCRIPTION_MAX)
        expect(desc.toLowerCase()).not.toBe(title.toLowerCase())
        expect(desc, `${lang} ${id} yer tutucu`).not.toMatch(/\{\d+\}/)
        expect(desc.trim()).toBe(desc)
      })

      it('açıklamalar dil içinde benzersiz (kopyala-yapıştır dolgu yok)', () => {
        const seen = new Map()
        for (const { id, key } of KEYS) {
          const d = dict[`${key}.description`]
          expect(seen.has(d), `${lang}: "${id}" açıklaması "${seen.get(d)}" ile aynı`).toBe(false)
          seen.set(d, id)
        }
      })
    })
  }

  it('TR ve EN metinleri birbirinin kopyası değil (çeviri yapılmış)', () => {
    const same = KEYS.filter(({ key }) => TR[`${key}.description`] === EN[`${key}.description`]).map((k) => k.id)
    expect(same).toEqual([])
  })

  it('VALID_TABS tek kaynak: App.jsx kendi listesini tutmaz; mobil kapı (responsive.spec) aynı sekmeleri gezer', () => {
    const src = path.resolve(__dirname, '..')
    const app = fs.readFileSync(path.join(src, 'App.jsx'), 'utf8')
    expect(app).not.toMatch(/const VALID_TABS\s*=/)
    expect(app).toMatch(/import \{[^}]*\bVALID_TABS\b[^}]*\} from '\.\/utils\/appRoutes\.js'/)
    const spec = fs.readFileSync(path.resolve(src, '..', 'e2e', 'responsive.spec.js'), 'utf8')
    const block = spec.match(/const TABS = \[([\s\S]*?)\]/)
    expect(block, 'responsive.spec.js TABS listesi').not.toBeNull()
    const tabs = [...block[1].matchAll(/'([^']+)'/g)].map((m) => m[1])
    expect(new Set(tabs)).toEqual(VALID_TABS)
  })
})
