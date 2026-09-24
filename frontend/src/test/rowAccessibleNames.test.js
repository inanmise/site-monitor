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

/** Kendiliğinden odaklanabilir OLMAYAN öğeler: tıklanabilir yapılırsa klavye kancası şart. */
const NON_INTERACTIVE = new Set(['div', 'span', 'li', 'tr', 'th', 'td', 'section', 'nav', 'p', 'ul', 'label'])

/**
 * Gerekçeli muafiyet (tıklanabilirlik): `dosya:<etiket> başlangıç…` öneki → NEDEN klavye
 * kancası gereksiz. Cırcır YALNIZ küçülür.
 */
const EXEMPT_CLICK = new Map([
  ['components/ui/Toast.jsx:<div>',
    'Sonner bildirim listesinin olay-yetkisi sarmalayıcısı (display: contents, kendisi kutu değil): ' +
    'gövdeye tıklamak yalnız erken kapatma kısayolu, bildirim kendiliğinden kayboluyor. Klavyeyle ' +
    'kapatma her kutudaki Sonner X düğmesinde (adı i18n\'den); liste aria-live ile zaten okunur.'],
  ['components/admin/AuditLogViewer.jsx:<tr>',
    'DOLAŞAN TABINDEX ızgarası: tabIndex satırda, ok/Home/End/Escape tuşları tbody üzerinde ' +
    '(daha gelişmiş kalıp). Kapı ikisini aynı etikette aradığı için burada yanlış ısırıyor.'],
  ['components/ScriptedMonitorPage.jsx:<span>',
    'Satırın açma kontrolü BİLİNÇLİ olarak tek hücrede (zaman): dört hücrenin dördü de ' +
    'odaklanabilir olsaydı satır başına dört durak olurdu. Diğer hücreler yalnız fare kolaylığı.'],
  ['components/shadcn/input-group.jsx:<div>',
    'shadcn InputGroupAddon: eke (ikon/metin) tıklamak yalnız içteki alana odak kısayolu; alanın ' +
    'kendisi klavyeyle tam erişilebilir, ekin içinde düğme varsa (InputGroupButton) o zaten odaklanır.'],
  ['components/ui/CodeEditor.jsx:<span>',
    'Satır numarası oluğu: düzenleyicinin kendisi (textarea) klavyeyle tam erişilebilir ve satır ' +
    'seçimi orada yapılır; oluk yalnız fare kısayolu.'],
])

/**
 * Tıklama gerçek bir EYLEM mi, yoksa pasif bir sarmalayıcı mı?
 *
 * <p>İki yaygın ve DOĞRU kalıp klavye kancası istemez: (1) yalnız olay yayılımını durduran
 * sarmalayıcılar (`onClick={e => e.stopPropagation()}`, `onClick={stop}`) — kendileri hiçbir şey
 * yapmaz; (2) modal örtüsü/maske — kapatma ayrıca gerçek bir düğmeyle ve Escape ile sağlanır,
 * örtüye odak vermek ekran okuyucu kullanıcısını çıkmaza sokar.
 */
function isPassiveClick(tagText) {
  const m = tagText.match(/onClick=\{([^]*?)\}\s*(?:[a-zA-Z-]+=|\/?>|$)/)
  const handler = (m ? m[1] : '').trim()
  // Yalnız yayılımı durduran / varsayılanı engelleyen sarmalayıcı: kendisi hiçbir şey yapmaz.
  const body = handler.replace(/^\(?\w*\)?\s*=>\s*\{?/, '').replace(/\}?$/, '')
  if (body && /^(\s*e?\.?(preventDefault|stopPropagation)\(\)\s*;?\s*)+$/.test(body)) return true
  if (/^stop$/.test(handler)) return true
  // Modal örtüsü: kapatma ayrıca gerçek düğme + Escape ile sağlanır; örtüye odak vermek
  // ekran okuyucu kullanıcısını çıkmaza sokar.
  if (/classList\.contains\(/.test(handler)) return true
  // shadcn örtüsü legacy sınıf adıyla değil `data-slot` ile tanınır (ui/ModalShell scrim'i).
  if (/data-slot="(alert-)?dialog-overlay"/.test(tagText)) return true
  return /className="[^"]*(overlay|backdrop|mask)/.test(tagText)
}

/**
 * JSX açılış etiketlerini ayrıştırır.
 *
 * <p>Regex ile `<div…>` aramak bu kod tabanında GÜVENİLMEZ: etiketin içindeki ok fonksiyonları
 * (`onClick={() => …}`) `>` taşıyor, yani "ilk `>`'a kadar" kabulü etiketi ortasından kesiyor ve
 * kapı sessizce yanlış cevap veriyor. Bu yüzden süslü parantez derinliği ve tırnak durumu
 * izlenerek etiket sonu gerçekten bulunur.
 */
function openingTags(src) {
  const tags = []
  for (let i = 0; i < src.length; i++) {
    if (src[i] !== '<') continue
    const m = /^<([a-z][a-z0-9]*)[\s/>]/.exec(src.slice(i, i + 24))
    if (!m) continue
    let depth = 0
    let quote = null
    let j = i + 1 + m[1].length
    for (; j < src.length; j++) {
      const c = src[j]
      if (quote) {
        if (c === quote && src[j - 1] !== '\\') quote = null
        continue
      }
      if (c === '"' || c === "'" || c === '`') { quote = c; continue }
      if (c === '{') depth++
      else if (c === '}') depth--
      else if (c === '>' && depth === 0) break
    }
    if (j < src.length) {
      tags.push({ name: m[1], text: src.slice(i, j + 1) })
      i = j
    }
  }
  return tags
}

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

  it('tıklanabilir olan her öğe klavyeyle de kullanılabilir (tabIndex + onKeyDown)', () => {
    const offenders = []
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const tag of openingTags(src)) {
        if (!NON_INTERACTIVE.has(tag.name)) continue
        if (!/\bonClick=/.test(tag.text)) continue
        if (isPassiveClick(tag.text)) continue
        if (/\btabIndex=/.test(tag.text) && /\bonKeyDown=/.test(tag.text)) continue
        const key = `${rel(f)}:<${tag.name}> ${tag.text.slice(0, 60).replace(/\s+/g, ' ')}…`
        if ([...EXEMPT_CLICK.keys()].some(k => key.startsWith(k))) continue
        offenders.push(key)
      }
    }
    expect(offenders, [
      'Tıklanabilir ama klavyeye kapalı öğe: Tab ona hiç uğramaz, yani yalnız klavye kullanan',
      '(ya da ekran okuyucu kullanan) biri o eylemi HİÇ yapamaz — satır detayı açılmaz, filtre',
      'uygulanmaz, kart genişlemez. Kardeş kalıplar: satır için CertificatesTable (tabIndex={0} +',
      'Enter/Space, e.target === e.currentTarget korumalı), kart/başlık için ScriptedMonitorPage ve',
      'ui/TeamBadge (role="button" tabIndex={0} + Enter/Space). Gerçekten pasif bir sarmalayıcıysa',
      '(yalnız stopPropagation / modal örtüsü) kural onu zaten atlar; başka bir zorunluluk varsa',
      'EXEMPT_CLICK listesine GEREKÇESİYLE ekleyin.',
    ].join(' ')).toEqual([])
  })

  it('muafiyet listesi ÖLÜ kayıt taşımaz — cırcır yalnız küçülür', () => {
    const live = new Set()
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const tag of openingTags(src)) {
        if (!NON_INTERACTIVE.has(tag.name)) continue
        if (!/\bonClick=/.test(tag.text)) continue
        if (isPassiveClick(tag.text)) continue
        if (/\btabIndex=/.test(tag.text) && /\bonKeyDown=/.test(tag.text)) continue
        live.add(`${rel(f)}:<${tag.name}>`)
      }
    }
    const dead = [...EXEMPT_CLICK.keys()].filter(k => !live.has(k))
    expect(dead, 'Bu yollarda artık klavyeye kapalı tıklama yok — muafiyet DÜŞMELİ').toEqual([])
    expect([...EXEMPT_CLICK.values()].filter(v => !v || !v.trim()), 'Her muafiyet GEREKÇELİ olmalı')
      .toEqual([])
  })
})
