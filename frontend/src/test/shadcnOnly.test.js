import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { RULES, ALLOWLIST, countRule, measure, stripComments } from '../../scripts/shadcn-only-rules.mjs'

/**
 * "YALNIZ SHADCN" KAPISI (2026-09-25, kullanıcı kararı).
 *
 * shadcn/ui projenin varsayılan ve ana UI kütüphanesi: ekranlarda https://ui.shadcn.com/docs/components
 * bileşeni kullanmayan öğe kalmayacak. Bu kapı çıplak HTML kontrollerini (`<button>`, `<input>`,
 * `<select>`, `<textarea>`, `<table>` …), elle kurulmuş bileşenleri (role="switch"/"tab", legacy
 * `.modal-overlay` pencereleri) ve shadcn sarmalayıcısı olmayan kütüphane kullanımlarını
 * (react-datepicker, doğrudan recharts) dosya başına sayar.
 *
 * CIRCIR: `shadcn-only-baseline.json` geçişin başlangıç envanteridir ve YALNIZ KÜÇÜLÜR. Bir dosyada bir
 * kuralın sayısı tabanın ÜSTÜNE çıkarsa (yeni ham öğe) test kırmızıdır. İş ilerledikçe
 * `npm run shadcn:baseline` tabanı düşürür (artışı reddeder). Hedef: boş taban.
 * Karşılıklar ve tuzaklar: frontend/docs/SHADCN.md.
 *
 * GEREKÇELİ İZİN LİSTESİ (`ALLOWLIST`, kurallar dosyasında): string-kurulu bağımsız belgeler ve bilinçli
 * `role="button"` span'ler ölçümden düşülür. Her giriş gerekçe taşır ve CANLI olmalıdır — gerçek sayı
 * izinden küçükse (öğe kalktıysa) test kırmızıdır; bayat izin başka bir ham öğeyi sessizce örtemez.
 */
const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const BASE = JSON.parse(fs.readFileSync(path.join(SRC, 'test', 'shadcn-only-baseline.json'), 'utf8'))

describe('yalnız shadcn kapısı', () => {
  it('hiçbir dosya tabandaki sayının ÜSTÜNE ham kontrol / elle bileşen eklemez', () => {
    const now = measure(SRC)
    const over = []
    for (const [file, counts] of Object.entries(now)) {
      for (const [rule, n] of Object.entries(counts)) {
        const allowed = BASE.files[file]?.[rule] ?? 0
        if (n > allowed) over.push(`${file} · ${rule}: ${n} (izin ${allowed}) → ${RULES[rule].fix}`)
      }
    }
    expect(over, 'Yeni ham UI öğesi — shadcn bileşeniyle yazın (frontend/docs/SHADCN.md)').toEqual([])
  })

  it('kurallar gerçekten yakalıyor (kendini sınama)', () => {
    const sample = stripComments([
      '<button onClick={x}>a</button>',
      '<input value={v} />',
      '<input type="hidden" name="h" />',
      '// <select> yorumdaki öğe sayılmaz',
      '<div role="switch" aria-checked="true" />',
      '<div className="modal-overlay"><div className="modal-box" /></div>',
      "import DatePicker from 'react-datepicker'",
      'const url = "https://example.com/x" // satır sonu yorumu',
      '<Card role="button" tabIndex={0} onClick={go}>',
      "<span role={open ? 'button' : undefined} />",
      '<ToggleGroupItem value="a"\n  role="button" aria-pressed={on}>a</ToggleGroupItem>',
      '<Badge data-role="button" />',
      '{/* <span role="button"> yorumdaki öğe sayılmaz */}',
    ].join('\n'))
    const hits = Object.fromEntries(Object.keys(RULES).map((k) => [k, countRule(k, sample)]))
    expect(hits['raw-button']).toBe(1)
    expect(hits['raw-input']).toBe(1)          // hidden input muaf
    expect(hits['raw-select']).toBe(0)         // yorum ayıklanır
    expect(hits['hand-switch']).toBe(1)
    expect(hits['hand-modal']).toBe(2)
    expect(hits['datepicker']).toBe(1)
    expect(hits['hand-button']).toBe(2)        // Card + koşullu span; ToggleGroupItem, data-role ve yorum muaf
    expect(sample).toContain('https://example.com/x')   // URL'deki // yorum sanılmaz
  })

  it('izin listesi girişleri CANLI: ham sayı izne eşit (bayat giriş başka öğeyi örtemez)', () => {
    const raw = measure(SRC, { raw: true })
    const stale = []
    for (const [file, entry] of Object.entries(ALLOWLIST)) {
      for (const [rule, allowed] of Object.entries(entry)) {
        if (rule === 'reason') continue
        const actual = raw[file]?.[rule] ?? 0
        if (actual < allowed) stale.push(`${file} · ${rule}: gerçek ${actual} < izin ${allowed} → girişi küçültün/silin`)
      }
    }
    expect(stale).toEqual([])
  })

  it('izin listesi girişleri gerekçeli ve bilinen kurallara ait', () => {
    for (const [file, entry] of Object.entries(ALLOWLIST)) {
      expect(typeof entry.reason === 'string' && entry.reason.trim().length > 20, `${file}: gerekçe yok`).toBe(true)
      for (const [rule, n] of Object.entries(entry)) {
        if (rule === 'reason') continue
        expect(RULES[rule], `${file}: bilinmeyen kural ${rule}`).toBeDefined()
        expect(Number.isInteger(n) && n > 0, `${file} · ${rule}: sayı pozitif tamsayı olmalı`).toBe(true)
      }
    }
  })

  it('taban toplamı kayıtlı dosyaların toplamıyla tutarlı', () => {
    const sum = Object.values(BASE.files).reduce((a, c) => a + Object.values(c).reduce((x, y) => x + y, 0), 0)
    expect(BASE.total).toBe(sum)
  })
})
