import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * HAYALET SINIF KAPISI — `cssTokens.test.js`'in kardeşi.
 *
 * Tanımsız bir `var(--x)` özelliği sessizce düşürür; tanımsız bir SINIF ADI ise daha da sessizdir:
 * tarayıcı bilmediği sınıfı hiç sormadan yok sayar, eleman tarayıcı varsayılanı olarak çizilir ve
 * ne konsolda ne testte hiçbir iz kalır. Bildirim Grupları ekranı tam olarak böyle yayına
 * hazırlanmıştı: `ng-panel`, `data-table`, `text-danger`, `form-hint` gibi 13 ad hiçbir CSS
 * dosyasında yoktu, ekran biçimsiz çizildi ve bunu ancak kullanıcı ekran görüntüsüyle gösterdi.
 *
 * Kural: `className="..."` içinde geçen DÜZ sınıf adları ya bir CSS dosyasında tanımlı olmalı ya
 * da aşağıdaki muafiyet listesinde GEREKÇESİYLE yer almalı.
 *
 * Kapsam bilinçli olarak dar: yalnız tırnaklı, interpolasyonsuz `className="a b"` biçimi taranır.
 * Şablon literalleri (`` `x-${v}` ``) statik olarak çözülemez; onları da kapsamaya çalışmak
 * "kesilmiş önek" yanlış pozitifleri üretirdi (denendi: 108 aday, 55'i sahte).
 *
 * MUAFİYET LİSTESİ YALNIZ KÜÇÜLÜR — kapsam tabanı cırcırıyla aynı kural. Yeni bir ad eklemek
 * gerekiyorsa doğru cevap neredeyse her zaman CSS'i yazmak ya da mevcut bir sınıfı yeniden
 * kullanmaktır.
 */

/** Stil beklemeyen, yalnız JS/test kancası ya da anlamsal işaret olarak duran adlar. */
const EXEMPT = new Map([
  // — Kapsayıcı işaretçileri: görünümü çocukları/ana kural taşıyor —
  ['chg-tab', 'ChangeHistoryTab kök işaretçisi'],
  ['chg-detail', 'ChangeHistoryTab detay kabı'],
  ['chg-snapshot', 'ChangeHistoryTab snapshot kabı'],
  ['chg-restore-btn', 'geri-al düğmesi — görünüm .btn ailesinden'],
  ['chg-console', 'MonitorChangesConsole kök işaretçisi'],
  ['sc-templates', 'ScriptedTemplatesTab kök işaretçisi'],
  ['sc-tpl-info-hdr', 'şablon bilgi başlığı'],
  ['sc-diff-text', 'diff metin kabı'],
  ['sc-source-select', 'script kaynağı seçici sarmalayıcısı'],
  ['ret-review', 'RetentionReviewModal kök işaretçisi'],
  ['usr-devices', 'UserEditModal cihaz bölümü'],
  ['issue-root', 'IssueReportModal kök işaretçisi'],
  ['inc-cmt-modal', 'IncidentsPage yorum modalı'],
  ['inc-sendmail', 'IncidentHistoryPage mail bölümü'],
  ['threshold-form', 'AlertThresholds form kabı'],
  ['dexp-panel', 'DomainDiagnostics panel kabı'],
  ['perm-group-label', 'PermissionMatrix grup etiketi'],
  ['grp-table', 'MonitorGroups tablo kabı'],
  ['grp-team', 'MonitorGroups takım hücresi'],
  ['mnote-notes', 'MonitorNotes liste kabı'],
  ['mnote-card-body', 'MonitorNotes kart gövdesi'],
  ['dns-diff-col', 'DnsDetailModal diff sütunu'],
  ['dns-prop-hint', 'DnsMonitorPage yayılım ipucu'],
  ['ahc-chip-team', 'AlertHistory takım çipi'],
  ['ahc-chip-expiry', 'AlertHistory süre çipi'],
  ['chk-progress', 'CheckRunShell ilerleme kabı'],
  ['chk-sum-time', 'CheckRunShell süre özeti'],
  ['dev-row--expandable', 'DeviceHistoryPanel açılabilir satır işareti'],
  ['dev-list--failed', 'DeviceHistoryPanel başarısız giriş listesi'],
  ['ts-grid-row', 'TeamStats/StatsView satırı'],
  ['wr-brief-left', 'WeeklyReportsPage sol sütun'],
  ['wr-sum-col', 'WeeklySummaryBrief sütunu'],
  ['pg-info-page', 'PaginationBar sayfa bilgisi'],
  ['pg-info-range', 'PaginationBar aralık bilgisi'],
  ['kebab-trigger', 'KebabMenu tetiği — görünüm .btn-sm + satır-içi stilden'],
  ['trp-col-quick', 'TimeRangePicker hızlı sütunu'],
  ['sqlpg-menu-history', 'SqlPlayground geçmiş menüsü'],
  ['sqlpg-menu-samples', 'SqlPlayground örnek menüsü'],
  ['lp-blocked-icon--permanent', 'Login kalıcı-blok varyantı'],
  ['lp-blocked-title--permanent', 'Login kalıcı-blok varyantı'],
])

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'node_modules') continue
      walk(full, out)
    } else out.push(full)
  }
  return out
}

const files = walk(SRC)

const definedClasses = new Set()
for (const f of files.filter(x => x.endsWith('.css'))) {
  const css = fs.readFileSync(f, 'utf8')
  for (const m of css.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)) definedClasses.add(m[1])
}

/** className="a b c" — interpolasyonsuz, düz string biçimi. */
const CLASS_ATTR = /className="([^"{}$`]*)"/g

describe('CSS sınıf kapısı (JSX’te yazılan her düz sınıf adı tanımlı mı)', () => {
  it('CSS sözlüğü boş değil (tarayıcı okunamazsa test vakum olurdu)', () => {
    expect(definedClasses.size).toBeGreaterThan(500)
  })

  it('className="..." içindeki her ad CSS’te tanımlı ya da gerekçeli muaf', () => {
    const offenders = []
    for (const f of files.filter(x => x.endsWith('.jsx'))) {
      const src = fs.readFileSync(f, 'utf8')
      const rel = path.relative(SRC, f).replace(/\\/g, '/')
      for (const m of src.matchAll(CLASS_ATTR)) {
        for (const cls of m[1].split(/\s+/).filter(Boolean)) {
          if (!/^-?[_a-zA-Z][\w-]*$/.test(cls)) continue
          if (definedClasses.has(cls) || EXEMPT.has(cls)) continue
          offenders.push(`${rel} → .${cls}`)
        }
      }
    }
    expect([...new Set(offenders)].sort()).toEqual([])
  })

  it('muafiyet listesi ÖLÜ kayıt taşımaz — cırcır yalnız küçülsün', () => {
    // Bir ad sonradan CSS'e yazıldıysa ya da hiç kullanılmıyorsa muafiyetten DÜŞMELİ;
    // aksi halde liste zamanla anlamsız bir birikime döner ve kapı gevşer.
    const usedSomewhere = new Set()
    for (const f of files.filter(x => x.endsWith('.jsx'))) {
      const src = fs.readFileSync(f, 'utf8')
      for (const m of src.matchAll(CLASS_ATTR)) {
        for (const cls of m[1].split(/\s+/).filter(Boolean)) usedSomewhere.add(cls)
      }
    }
    const dead = [...EXEMPT.keys()].filter(c => definedClasses.has(c) || !usedSomewhere.has(c))
    expect(dead).toEqual([])
  })
})
