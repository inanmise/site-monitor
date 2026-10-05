import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { THEMES, THEME_IDS } from '../theme/themes.js'
import { themeName, themeDescription } from '../components/theme/themeLabels.js'

/**
 * KAPI (2026-10-05): ön yüz tema listesi ↔ sunucu `ThemeCatalog.java` (kimlik + şema + sıra) ve her temanın iki dilde
 * adı/açıklaması. Sunucu tarafındaki kardeşi `ThemeCatalogSyncTest`. Ayrışırsa yönetici sunucunun tanımadığı temayı
 * kaydedemez (400) ya da seçicide adı ham anahtar olarak görünür.
 */
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/ThemeCatalog.java')

describe('tema kataloğu senkronu', () => {
  it('THEMES ile ThemeCatalog.ALL aynı kimlik, şema ve sırada', () => {
    const src = fs.readFileSync(JAVA, 'utf8')
    const back = [...src.matchAll(/new Theme\("([a-z]+)",\s*"(light|dark)"\)/g)].map((m) => `${m[1]}:${m[2]}`)
    expect(back.length, 'ThemeCatalog.java okunamadı').toBeGreaterThan(0)
    expect(THEMES.map((t) => `${t.id}:${t.scheme}`)).toEqual(back)
  })

  it('Açık ve Koyu temel; diğer altısı ek tema', () => {
    expect(THEMES.filter((t) => t.base).map((t) => t.id)).toEqual(['light', 'dark'])
    expect(THEMES.filter((t) => !t.base).map((t) => t.id))
      .toEqual(['blueprint', 'parchment', 'alloy', 'obsidian', 'slag', 'crucible'])
  })

  it('her temanın TR ve EN adı + açıklaması var ve etiket yardımcıları ham anahtar döndürmez', () => {
    const missing = []
    for (const id of THEME_IDS) {
      for (const k of [`theme.name.${id}`, `theme.desc.${id}`]) {
        if (!TR[k]) missing.push(`TR ${k}`)
        if (!EN[k]) missing.push(`EN ${k}`)
      }
      const t = (k) => TR[k] ?? k
      expect(themeName(t, id)).toBe(TR[`theme.name.${id}`])
      expect(themeDescription(t, id)).toBe(TR[`theme.desc.${id}`])
    }
    expect(missing).toEqual([])
  })
})
