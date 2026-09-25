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
 * <p>Kurallar:
 *   (1) `aria-label` sabit bir İngilizce dizeye bağlanamaz — ad i18n'den gelmeli. Sabit dize
 *       hem TR arayüzde İngilizce okunur hem de tanımı gereği her satırda AYNIdır.
 *   (2) Tıklanabilir izleme kartı (`upt-card` + `onClick`) `role="button"` + `tabIndex` taşımalı.
 *   (3) Tıklanabilir her öğe klavyeyle de kullanılabilir (tabIndex + onKeyDown).
 *   (4) Seçim kümesine bağlı kutu (`checked={x.has(…)}`) satır argümanlı i18n adı taşır (R5, 09-25).
 *   (5) Seçici (`SearchableSelect`/`MultiTeamSelect`, tetik role="combobox") adlandırılır:
 *       ariaLabel ya da bağlı etiket (R17, 09-25).
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
function openingTags(src, nameRe = /^<([a-z][a-z0-9]*)[\s/>]/) {
  const tags = []
  for (let i = 0; i < src.length; i++) {
    if (src[i] !== '<') continue
    const m = nameRe.exec(src.slice(i, i + 40))
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
      tags.push({ name: m[1], text: src.slice(i, j + 1), start: i })
      i = j
    }
  }
  return tags
}

/** Satır numarası (kapı çıktısında dosya:satır göstermek için). */
const lineOf = (src, pos) => src.slice(0, pos).split('\n').length

/**
 * Konum bir `<label>` öğesinin İÇİNDE mi? Etiketler iç içe geçemediği için açılış/kapanış
 * sayısı yeter. Sarmalayan etiket, içindeki düğmeye (ve role="combobox" tetiğine) adını verir.
 */
function insideLabel(src, pos) {
  const before = src.slice(0, pos)
  const opens = (before.match(/<label[\s>]/g) || []).length
  const closes = (before.match(/<[/]label>/g) || []).length
  return opens > closes
}

/** Seçim kümesine bağlı kutu: `checked={selected.has(…)}` (başında olumsuzlama YOK — o, alıcı listesi gibi etiketli satırlar). */
const SELECTION_CHECKED = /\bchecked=\{\s*[A-Za-z_$][\w$.]*[.]has\(/
/** Satır argümanlı i18n adı: aria-label={t('anahtar', <satır>…)}. */
const ROW_ARG_NAME = /aria-label=\{t\(\s*'[^']+'\s*,\s*[^)\s]/

/** Seçici bileşenleri — tetikleri role="combobox" ve adını İÇERİKTEN almaz (PickerPopover.jsx). */
const PICKER_TAG = /^<(SearchableSelect|MultiTeamSelect)[\s/>]/

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

  /**
   * 2026-09-25 (R5): toplu seçim kutusu SATIRI ayırmalı. Toplu onayla/çöz/aktar/"onayla ve gönder"
   * onay diyalogları yalnız ADET söylüyor — kutunun adı yoksa ya da her satırda aynıysa ("Bu alarmı
   * seç") ekran okuyucu kullanıcısı yanlış alarmı çözer, yanlış haftanın raporunu gönderir. F2
   * düzeltmesi dokuz ızgarayı kapatmıştı; üç kardeşi (AlertHistory, IncidentHistory, WeeklyReports)
   * süpürmeden kaçtı. Bu kural sınıfı kapatır.
   */
  it('seçim kümesine bağlı kutu satır argümanlı i18n adı taşır (checked={x.has(…)} → t(key, satır))', () => {
    const offenders = []
    let seen = 0
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const tag of openingTags(src, /^<(input|Checkbox)[\s/>]/)) {
        if (tag.name === 'input' && !/type="checkbox"/.test(tag.text)) continue
        if (!SELECTION_CHECKED.test(tag.text)) continue
        seen++
        if (ROW_ARG_NAME.test(tag.text) || /aria-labelledby=/.test(tag.text)) continue
        offenders.push(`${rel(f)}:${lineOf(src, tag.start)} — ${tag.text.slice(0, 90).replace(/\s+/g, ' ')}…`)
      }
    }
    // Vakum koruması: tarama hiçbir kutu bulmazsa kural sessizce "yeşil" kalırdı.
    expect(seen, 'seçim kutusu taraması vakum — desen kod tabanıyla uyuşmuyor').toBeGreaterThan(10)
    expect(offenders, [
      'Toplu seçim kutusunun adı satırı ayırmıyor (ya da hiç yok): toplu işlem onayı yalnız ADET',
      'söylediği için ekran okuyucu kullanıcısı yanlış kaydı seçip işleme sokabilir. Kalıp:',
      "aria-label={t('bulk.selectOneFor', satır.ad)} (CertificatesTable, HttpMonitorPage).",
    ].join(' ')).toEqual([])
  })

  /**
   * 2026-09-25 (R17): seçici tetiği `role="combobox"` ve adını İÇERİKTEN ALMAZ — shadcn geçişinde
   * düz düğmeden combobox'a dönünce 70'ten fazla seçici sessizce adsız kaldı (ekran okuyucu yalnız
   * "combobox" der; süzgeç satırındaki 15 seçici birbirinden ayırt edilemez). Ad yolları:
   * `ariaLabel`, `ariaLabelledBy`, `id` + bağlı etiket (`<label htmlFor>` ya da Field render-prop'u),
   * ya da seçiciyi saran bir `<label>`.
   */
  it('seçici (role="combobox") erişilebilir ad taşır — ariaLabel ya da bağlı etiket', () => {
    const offenders = []
    let seen = 0
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const tag of openingTags(src, PICKER_TAG)) {
        seen++
        if (/\bariaLabel=/.test(tag.text) || /\bariaLabelledBy=/.test(tag.text)) continue
        const id = /\bid=("[^"]*"|\{[^}]*\})/.exec(tag.text)
        // id tek başına ad değildir: aynı değerle bir htmlFor (ya da Field'ın FieldLabel'ı) şart.
        if (id && (src.includes(`htmlFor=${id[1]}`) || /<Field[\s>]/.test(src))) continue
        if (insideLabel(src, tag.start)) continue
        offenders.push(`${rel(f)}:${lineOf(src, tag.start)} — ${tag.text.slice(0, 90).replace(/\s+/g, ' ')}…`)
      }
    }
    expect(seen, 'seçici taraması vakum — desen kod tabanıyla uyuşmuyor').toBeGreaterThan(100)
    expect(offenders, [
      'Adsız seçici: tetik role="combobox" ve adını içerikten almaz, ekran okuyucu yalnız "combobox"',
      "der. ariaLabel={t('flt.team')} verin, ya da görünür etiketi bağlayın: <label htmlFor=\"x\"> +",
      '<SearchableSelect id="x" …> (Field render-prop\'u id\'yi zaten verir).',
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
