import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * CSS hijyen bekçisi — progress-guard.test.jsx ev standardında, DENY-LIST tabanlı.
 *
 * Kapsamlı bir "kullanılan her sınıf tanımlı mı" denetimi bilinçle yapılmıyor: react-datepicker
 * ve md-editor gibi dış kütüphanelerin sınıfları yanlış-pozitif üretirdi. Bunun yerine bu iş
 * sırasında düzeltilen SOMUT sorunların geri gelmesi engelleniyor.
 */
const SRC = path.resolve(__dirname, '..')
const CSS = fs.readFileSync(path.join(SRC, 'App.css'), 'utf8')
const SKIP_DIRS = new Set(['test', 'assets'])

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx?$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

/** Yorumları ayıklar — bir sınıfın adını "kaldırıldı" diye ANLATAN yorum kullanım sayılmamalı. */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

const FILES = walk(SRC).map((f) => [path.relative(SRC, f), stripComments(fs.readFileSync(f, 'utf8'))])

describe('CSS hijyeni', () => {
  // App.css'te HİÇ tanımlı olmayan, dolayısıyla görsel olarak ölü sınıflar. Hepsi bu iş
  // kapsamında kaldırıldı; adları geri gelirse yine sessizce stilsiz render edilirlerdi.
  it.each([
    'btn-ghost',        // IssueReportModal'ın X butonu — ModalShell'e taşındı
    'modal-close-x',    // CertInventoryReportSettings önizleme modalı — ModalShell'e taşındı
    'wa-toggle',        // aşağıdaki dördü WeeklyAvailability'den kopyalanmış, hiç tanımlanmamıştı
    'wa-schedule',
    'wa-test',
    'wa-history-title',
  ])('tanımsız sınıf "%s" kaynak ağacında kullanılmıyor', (cls) => {
    const users = FILES.filter(([, src]) => src.includes(cls)).map(([f]) => f)
    expect(users, `Tanımsız sınıf hâlâ kullanılıyor: ${users.join(', ')}`).toEqual([])
  })

  it('kullanılan yardımcı sınıflar App.css\'te tanımlı', () => {
    // Bu üçü 11 admin dosyasında kullanılıyordu ama tanımsızdı (alanlar bitişik, ipuçları
    // stilsiz, uyarılar YEŞİL "başarı" kutusu olarak çıkıyordu).
    // Seçici bir kural başlatıyor mu? Gruplu tanımlarda ".form-field," biçiminde de olabilir.
    const defined = (sel) => CSS.includes(`${sel} {`) || CSS.includes(`${sel}{`) || CSS.includes(`${sel},`)
    for (const sel of ['.hint', '.form-group', '.alert-msg--warn', '.form-field', '.field-error']) {
      expect(defined(sel), `${sel} tanımlı değil`).toBe(true)
    }
  })

  it('.pg-bar yalnız Progress ailesine ait — PaginationBar .pgn-bar kullanır', () => {
    // İki bileşen aynı seçiciyi paylaşıyordu; App.css'te sonra gelen pagination kuralı
    // Progress'inkini eziyor ve her <ProgressBar>'a flex + margin-top bindiriyordu.
    const paginationSrc = FILES.find(([f]) => f.endsWith(path.join('ui', 'PaginationBar.jsx')))[1]
    expect(paginationSrc).toContain('pgn-bar')
    expect(paginationSrc).not.toMatch(/className=\{`pg-bar/)
    // App.css'te tek bir top-level `.pg-bar {` kuralı kalmalı
    expect(CSS.match(/^\.pg-bar \{/gm) ?? []).toHaveLength(1)
  })

  it('.form-grid alan geometrisi tik/radyo kutusunu KAPSAMAZ', () => {
    // 2026-08-21'de `.form-grid label input` kuralına `width: 100%` eklendi ve kural
    // input[type="checkbox"]'ı da kapsadığı için tik kutusu satır genişliğine yayılıp yanındaki
    // etiketi kaydırdı (Sentetik İzleme formunda "E-posta bildirimi" ve "Aktif" alanları).
    // jsdom yerleşim hesaplamadığı için görünüm test edilemiyor; KURALIN KENDİSİ pinleniyor.
    // Satır sonu ve girinti farklarına takılmamak için boşluklar tekilleştirilir (dosya CRLF).
    const flat = CSS.replace(/\s+/g, ' ')
    const rule = flat.match(/\.form-grid label input[^{]*\.form-grid label textarea \{[^}]*width: 100%[^}]*\}/)
    expect(rule, '.form-grid alan kuralı bulunamadı').not.toBeNull()
    expect(rule[0], 'kural metin-kutusu geometrisini checkbox\'a da uyguluyor')
      .toContain(':not([type="checkbox"])')
    expect(rule[0]).toContain(':not([type="radio"])')

    // Kutunun kendi boyutu ayrıca sabitlenmiş olmalı: ileride eklenecek başka bir
    // `.form-grid label input` kuralı onu yeniden esnetemesin.
    expect(CSS).toMatch(/\.form-grid label input\[type="checkbox"\][\s\S]{0,200}width: auto/)
  })

  it("MDEditor ic textarea'si form alani geometrisinden MUAF", () => {
    // MDEditor, görünen <pre> katmanının ÜSTÜNDE metni ŞEFFAF, mutlak konumlu bir <textarea>
    // çizer. Ona OPAK bir arka plan verilirse <pre> tamamen örtülür ve kutu BOŞ görünür
    // (envanter "Değişiklik Açıklaması" vakası). Sızıntının iki kaynağı vardı:
    // `.form-grid label textarea` (0,2,1) ve GLOBAL `[data-theme="dark"] textarea` (0,1,1);
    // ikincisi yüzünden muafiyet global ve !important olmak zorunda.
    // jsdom renk/yığın hesaplamaz — KURALIN KENDİSİ pinleniyor.
    const flat = CSS.replace(/\s+/g, ' ')
    const rule = flat.match(/\.w-md-editor-text-input \{[^}]*\}/)
    expect(rule, 'MDEditor textarea muafiyet bloğu bulunamadı').not.toBeNull()
    for (const decl of ['background: none !important', 'border: 0 !important',
                        'min-height: 0 !important', 'resize: none !important']) {
      expect(rule[0], `muafiyette eksik: ${decl}`).toContain(decl)
    }

    // Yazma alanı yüksekliği: kütüphane satır içi min-height:100px veriyor, kutu yarıda kalıyordu.
    expect(flat).toMatch(/\.md-editor-box \.w-md-editor-text \{[^}]*min-height: 100% !important/)
  })

  it('9999 beraberliği çözüldü: katman token\'ları tanımlı ve kullanılıyor', () => {
    for (const tok of ['--z-modal', '--z-announce', '--z-dialog', '--z-toast', '--z-critical']) {
      expect(CSS, `${tok} tanımlı değil`).toContain(`${tok}:`)
    }
    for (const tok of ['--z-announce', '--z-dialog', '--z-toast', '--z-critical']) {
      expect(CSS, `${tok} hiç kullanılmıyor`).toContain(`var(${tok})`)
    }
  })

  it('animasyonlu her aile prefers-reduced-motion kapsamında', () => {
    const blocks = CSS.match(/@media \(prefers-reduced-motion: reduce\)[\s\S]*?\n\}/g) ?? []
    const all = blocks.join('\n')
    for (const sel of ['.pg-spinner', '.toast', '.dlg-box']) {
      expect(all, `${sel} reduced-motion bloğunda yok`).toContain(sel)
    }
  })

  it('yeni durum yüzeylerinin her tonu koyu tema karşılığına sahip', () => {
    for (const tone of ['info', 'success', 'warning', 'danger']) {
      expect(CSS, `.alert-banner--${tone} koyu tema karşılığı yok`)
        .toContain(`[data-theme="dark"] .alert-banner--${tone}`)
    }
  })

  it.todo('.badge iki kez tanımlı (2108 ve 3850) — hata/bildirim yüzeyleriyle ilgisiz, ayrı iş')
})
