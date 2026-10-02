import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  LOCAL_PREF_KEYS, LOCAL_PREF_PREFIXES, MONITOR_TYPES, MAX_FAVORITES, MAX_VIEWS_PER_LIST, MAX_VIEW_NAME, MAX_LOCAL_VALUE,
} from '../hooks/userPrefsModel.js'

/**
 * DİLLER-ARASI BEKÇİ (öneri 23): aynalanan localStorage anahtarları ve sınırlar frontend ↔ backend BİREBİR aynı olmalı.
 *
 * Neden: sunucu beyaz liste dışı `local` anahtarını 400 ile reddeder ve istemci 4xx'i "düzelmez" sayıp o yazımı atar.
 * İki liste ayrışırsa yeni bir tercih sessizce HİÇ sunucuya gitmez (ya da aynı PUT'taki diğer anahtarlarla birlikte
 * reddedilir). İki dil arasında çalışma-zamanı bağı yok — tek doğrulama noktası kaynak metni.
 */
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/UserPreferencesService.java')

function javaList(src, name) {
  const m = new RegExp(`${name}\\s*=\\s*List\\.of\\(([\\s\\S]*?)\\);`).exec(src)
  if (!m) return null
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
}
function javaSet(src, name) {
  const m = new RegExp(`${name}\\s*=\\s*\\n?\\s*Set\\.of\\(([\\s\\S]*?)\\);`).exec(src)
  if (!m) return null
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
}
function javaInt(src, name) {
  const m = new RegExp(`int\\s+${name}\\s*=\\s*([^;]+);`).exec(src)
  return m ? Function(`return (${m[1]})`)() : null
}

describe('kişisel tercihler — frontend ↔ backend beyaz liste senkronu', () => {
  const src = fs.existsSync(JAVA) ? fs.readFileSync(JAVA, 'utf8') : ''

  it('backend kaynağı okunabiliyor', () => {
    expect(src.length, `bulunamadı: ${JAVA}`).toBeGreaterThan(100)
  })

  it('aynalanan anahtarlar ve önekler birebir aynı (sıra dahil)', () => {
    expect(javaList(src, 'LOCAL_KEYS')).toEqual([...LOCAL_PREF_KEYS])
    expect(javaList(src, 'LOCAL_PREFIXES')).toEqual([...LOCAL_PREF_PREFIXES])
  })

  it('favori türleri ve sınırlar aynı', () => {
    expect(new Set(javaSet(src, 'MONITOR_TYPES'))).toEqual(new Set(MONITOR_TYPES))
    expect(javaInt(src, 'MAX_FAVORITES')).toBe(MAX_FAVORITES)
    expect(javaInt(src, 'MAX_VIEWS_PER_LIST')).toBe(MAX_VIEWS_PER_LIST)
    expect(javaInt(src, 'MAX_VIEW_NAME')).toBe(MAX_VIEW_NAME)
    expect(javaInt(src, 'MAX_LOCAL_VALUE')).toBe(MAX_LOCAL_VALUE)
  })
})
