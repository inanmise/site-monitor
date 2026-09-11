import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { TR, EN } from '../i18n/index.jsx'

/**
 * AYAR YARDIMI KAPISI — `settings-labels-sync.test.jsx`'in kardeşi.
 *
 * Ayar ekranlarındaki her yapılandırma değerinin yanında, tıklanınca açılan bir açıklama
 * (HelpTip) durur. Metin `t(helpKey)` ile DİNAMİK çözülür; `useT` eksik anahtarda anahtarın
 * KENDİSİNİ döndürdüğü için eksik bir çeviri hiçbir testi kırmaz — HelpTip o durumda hiç
 * çizilmez, yani ayar SESSİZCE açıklamasız kalır. Tek gerçek koruma budur:
 *
 *   (a) backend kataloğundaki HER ayarın iki dilde `help.set.<anahtar>` metni var mı,
 *   (b) JSX'te yazılı HER literal `helpKey` iki sözlükte de var mı,
 *   (c) her yardım metni tam ÜÇ satır mı (ne işe yarar / faydası / önerilen değer).
 *
 * Dinamik anahtarlar (`'help.set.' + it.key`) statik çözülemez; onları (a) kapsar.
 */
const HERE = path.dirname(fileURLToPath(import.meta.url))
const FRONT = path.resolve(HERE, '..')
const CATALOG = path.resolve(
  HERE, '../../../backend/src/main/java/com/sitemonitor/service/AppSettingsCatalog.java')

const read = (p) => fs.readFileSync(p, 'utf8')

/** new Setting("key", … ) → ["key", …] */
function catalogKeys() {
  return [...read(CATALOG).matchAll(/new Setting\(\s*"([^"]+)"/g)].map((m) => m[1])
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) walk(full, out)
    else if (e.name.endsWith('.jsx')) out.push(full)
  }
  return out
}

/** helpKey="x" ve helpKey={'x'} / helpKey={`x`} — yalnız TAM literaller (birleştirme değil). */
const LITERAL_HELP_KEY = /helpKey=(?:"([^"{}$`]+)"|\{\s*'([^'{}$`+]+)'\s*\}|\{\s*`([^`{}$]+)`\s*\})/g

function literalHelpKeys() {
  const found = new Map()
  for (const f of walk(path.join(FRONT, 'components', 'admin'))) {
    const src = read(f)
    for (const m of src.matchAll(LITERAL_HELP_KEY)) {
      const key = m[1] || m[2] || m[3]
      if (!found.has(key)) found.set(key, path.relative(FRONT, f).replace(/\\/g, '/'))
    }
  }
  return found
}

describe('Ayar yardımı (HelpTip) kapsamı', () => {
  const keys = catalogKeys()

  it('backend kataloğu okunabiliyor (boş liste = yol veya sözdizimi değişmiş)', () => {
    expect(keys.length).toBeGreaterThan(200)
  })

  it('katalogdaki HER ayarın TR yardım metni var', () => {
    expect(keys.filter((k) => !TR[`help.set.${k}`])).toEqual([])
  })

  it('katalogdaki HER ayarın EN yardım metni var', () => {
    expect(keys.filter((k) => !EN[`help.set.${k}`])).toEqual([])
  })

  it('JSX’te yazılı her literal helpKey iki sözlükte de var', () => {
    const found = literalHelpKeys()
    expect(found.size, 'hiç helpKey bulunamadı — regex bozulmuş olabilir').toBeGreaterThan(40)
    const missing = []
    for (const [key, file] of found) {
      if (!(key in TR) || !(key in EN)) missing.push(`${file} → ${key}`)
    }
    expect(missing).toEqual([])
  })

  /**
   * Yardım metinlerinin TAM kümesi: `help.set.*` (katalog) + JSX'te yazılı literal helpKey'ler.
   * `help.` öneki tek başına YETMEZ — Yardım SAYFASININ (`help.title`, `help.toc`, …) anahtarları
   * da o öneki taşır ve üç satır kuralına tabi değildir.
   */
  const helpTextKeys = () => new Set([
    ...Object.keys(TR).filter((k) => k.startsWith('help.set.')),
    ...Object.keys(EN).filter((k) => k.startsWith('help.set.')),
    ...literalHelpKeys().keys(),
  ])

  it('her yardım metni TAM ÜÇ satır (ne işe yarar / faydası / önerilen değer)', () => {
    const bad = []
    for (const key of helpTextKeys()) {
      for (const [dictName, dict] of [['TR', TR], ['EN', EN]]) {
        const lines = String(dict[key] ?? '').split('\n')
        if (lines.length !== 3) bad.push(`${dictName} ${key}: ${lines.length} satır`)
      }
    }
    expect(bad).toEqual([])
  })

  it('metinler beklenen üç başlıkla başlar (kopyala-yapıştır kaçağı)', () => {
    const trBad = []
    const enBad = []
    for (const key of helpTextKeys()) {
      const tr = String(TR[key] ?? '')
      const en = String(EN[key] ?? '')
      if (!/^Ne işe yarar: /.test(tr) || !/\nFaydası: /.test(tr)
          || !/\nÖnerilen değer: /.test(tr)) trBad.push(key)
      if (!/^What it does: /.test(en) || !/\nBenefit: /.test(en)
          || !/\nRecommended: /.test(en)) enBad.push(key)
    }
    expect({ trBad, enBad }).toEqual({ trBad: [], enBad: [] })
  })

  it('yardım metni ham anahtarın kendisi DEĞİL', () => {
    const bad = keys.filter((k) => TR[`help.set.${k}`] === `help.set.${k}`
                                || EN[`help.set.${k}`] === `help.set.${k}`)
    expect(bad).toEqual([])
  })
})
