import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * SATIR KONTROLLERİ KAPISI — erişilebilir ad SATIRI ayırt eder, kontrol klavyeye açıktır.
 *
 * <p>Neden kapı. 2026-09-23 QA turu tek bir örneği kapattı (HTTP geçmişinde "Detay" düğmelerinin
 * adı satırları ayırmıyordu); aynı gün yapılan süpürme aynı kusurun ON BİR yüzeyde daha durduğunu
 * gösterdi: 50 kartlık ızgarada 50 özdeş "Seç", 200 satırlık tabloda 200 özdeş "İşlemler", ve
 * daha kötüsü, tıklanabilir ama ODAKLANAMAYAN kartlar — klavye kullanıcısı hiçbir monitörün
 * detayını açamıyordu. Düzeltme örneği kapatır, SINIFI kapatmaz; bu kapı sınıfı kapatır.
 *
 * <p>İki kural taranır:
 *   (1) `aria-label` sabit bir İngilizce dizeye bağlanamaz — ad i18n'den gelmeli. Sabit dize
 *       hem TR arayüzde İngilizce okunur hem de tanımı gereği her satırda AYNIdır.
 *   (2) Tıklanabilir izleme kartı (`upt-card` + `onClick`) `role="button"` + `tabIndex` taşımalı.
 *
 * <p>Kapsam bilinçli olarak dar ve statiktir: yalnız düz `aria-label="..."` biçimi yakalanır.
 * "Bu ad satırı gerçekten ayırt ediyor mu" sorusu statik olarak çözülemez (ad çalışma anında
 * `t('...', row.x)` ile kurulur); kapı, ADIN SABİT OLMADIĞINI garanti eder — kalanı kod
 * incelemesinin işi.
 */

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'node_modules') continue
      walk(full, out)
    } else if (/[.]jsx?$/.test(entry.name)) out.push(full)
  }
  return out
}

const files = walk(SRC)
const rel = (f) => path.relative(SRC, f).replace(/[\\]/g, '/')

/** Sabit `aria-label="..."` — değeri i18n'den GELMEYEN ad. */
const LITERAL_ARIA = /aria-label="([^"]*)"/g

/**
 * Gerekçeli muafiyet: dosya:ad → NEDEN sabit dize doğru. Cırcır YALNIZ küçülür.
 * (Boş tutuluyor: bugün üretimde tek bir sabit aria-label kalmadı.)
 */
const EXEMPT = new Map()

describe('satır kontrolleri — erişilebilir ad ve klavye erişimi', () => {
  it('tarama vakum değil — kayda değer sayıda kaynak dosya okunuyor', () => {
    expect(files.length).toBeGreaterThan(100)
  })

  it('aria-label sabit dizeye bağlanmaz (ad i18n üzerinden, satır kimliğiyle kurulur)', () => {
    const offenders = []
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const m of src.matchAll(LITERAL_ARIA)) {
        const key = `${rel(f)}:${m[1]}`
        if (EXEMPT.has(key)) continue
        offenders.push(key)
      }
    }
    expect(offenders, [
      'Sabit aria-label: TR arayüzde İngilizce okunur ve tanımı gereği her satırda AYNIdır —',
      '8 etiketli bir kayıtta 8 özdeş "remove" düğmesi, hangisinin kaldırılacağı duyulmaz.',
      "Ad'ı i18n'e taşıyın ve satır kimliğini parametre olarak geçin:",
      "aria-label={t('tag.removeTag', tag)}. Dekoratif bir öğeyse aria-hidden=\"true\" kullanın.",
    ].join(' ')).toEqual([])
  })

  it('tıklanabilir izleme kartı klavyeyle de açılır (role + tabIndex + Enter/Space)', () => {
    const offenders = []
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      // Kart açılışı: className içinde `upt-card` geçen ve onClick taşıyan div blokları.
      for (const m of src.matchAll(/<div[^>]*?className=\{`upt-card[\s\S]*?>/g)) {
        const tag = m[0]
        if (!/onClick=/.test(tag)) continue
        if (/role="button"/.test(tag) && /tabIndex=/.test(tag)) continue
        offenders.push(`${rel(f)} — ${tag.slice(0, 80).replace(/\s+/g, ' ')}…`)
      }
    }
    expect(offenders, [
      'Tıklanabilir ama odaklanamayan kart: Tab kartlara hiç uğramaz, klavye kullanıcısı hiçbir',
      'monitörün detayını açamaz; ekran okuyucu kartı düğme olarak duyurmadığı için satırlar',
      'ayırt edilemez. ScriptedMonitorPage kalıbını uygulayın: role="button" tabIndex={0}',
      "aria-label={t('mon.openDetailFor', …)} + Enter/Space onKeyDown (yalnız e.target === e.currentTarget).",
    ].join(' ')).toEqual([])
  })
})
