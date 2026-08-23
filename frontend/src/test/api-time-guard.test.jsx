import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ: sunucuya gidecek bir zaman damgası YEREL saat bileşenlerinden elle kurulamaz.
 *
 * <p><b>Neden var (2026-08-23).</b> İzleme Değişiklikleri konsolunda aralık süzgeci
 * {@code `${d.getFullYear()}-…T${d.getHours()}:…`} kalıbıyla üretiliyordu. Backend zaman
 * damgalarını UTC yazıyor ve sorgular metin karşılaştırması; Türkiye UTC+3 olduğu için "Bugün"
 * penceresi üç saat geç başlıyor ve gecenin ilk üç saatindeki değişiklikler listeden SESSİZCE
 * düşüyordu. Ne bir hata çıkıyor ne ekran bozuluyor — yalnız eksik veri gösteriliyor.
 *
 * <p><b>Kural.</b> Yerel bileşenlerden ISO benzeri bir TARİH-SAAT (içinde {@code T} ve saat
 * geçen) dize üreten kod yasak; sunucuya gidecek sınırlar {@code utils/apiTime.js} üzerinden
 * geçmeli. Yalnızca TARİH (Y-A-G) üreten yerler kuralın dışında: dosya adı, gruplama anahtarı
 * ve takvim etiketleri yereldir ve öyle kalmalıdır.
 *
 * <p><b>Sınırı (dürüstçe):</b> bu kural kalıbı görür, niyeti değil. {@code apiTime}'ı atlayıp
 * {@code toISOString()} yazan bir ekran teknik olarak doğru sonucu üretir ve kurala takılmaz;
 * yanlış olan tek şey yerel bileşenlerle dize kurmaktır ve kapı tam onu kovalar.
 */
const SRC = path.resolve(__dirname, '..')

/**
 * Bilinçli istisnalar. Buraya bir satır eklemek "bu dize SUNUCUYA GİTMİYOR" taahhüdüdür.
 * Gerekçesiz satır, yukarıdaki hatanın kapısını yeniden açar.
 */
const ALLOW = new Map([
  [path.join('components', 'ui', 'TimeRangePicker.jsx'),
    '<input type="datetime-local"> DEĞERİ üretir; o alan tanımı gereği yerel saattir ve ' +
    'sunucuya gönderilmeden önce Date\'e çevrilir.'],
  [path.join('components', 'ui', 'DateTimeField.jsx'),
    'Aynı sebep: yerel tarih/saat girdi alanının kendi değeri.'],
])

/** Yerel bileşenlerden kurulmuş ISO TARİH-SAAT: aynı satırda getHours/getMinutes ve "T" ayracı. */
const LOCAL_DATETIME = /get(FullYear|Month|Date)\(\)[\s\S]{0,200}?T\$\{[\s\S]{0,80}?get(Hours|Minutes|Seconds)\(\)/

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { if (e.name !== 'test') walk(p, acc) }
    else if (/\.jsx?$/.test(e.name)) acc.push(p)
  }
  return acc
}

describe('sunucuya giden zaman sınırları', () => {
  const files = walk(SRC)

  it('kaynak ağacı taranabiliyor (boş tarama sessiz yeşil demektir)', () => {
    expect(files.length).toBeGreaterThan(100)
  })

  it('yerel saatle elle ISO tarih-saat kuran kod yok', () => {
    const hits = []
    for (const f of files) {
      const rel = path.relative(SRC, f)
      if (ALLOW.has(rel)) continue
      const text = fs.readFileSync(f, 'utf8')
      for (const line of text.split('\n')) {
        if (LOCAL_DATETIME.test(line)) hits.push(`${rel} → ${line.trim().slice(0, 90)}`)
      }
    }
    expect(hits, 'Sunucuya gidecek sınırlar için utils/apiTime.js kullanın (toApiTime).').toEqual([])
  })

  it('kuralın kendisi çalışıyor: hatalı kalıp YAKALANIR, doğru kalıp geçer', () => {
    const bad = 'const iso = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:00:00`'
    const okDateOnly = 'const ymd = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`'
    const okUtc = 'const iso = d.toISOString().slice(0, 19)'

    expect(LOCAL_DATETIME.test(bad)).toBe(true)
    expect(LOCAL_DATETIME.test(okDateOnly)).toBe(false)
    expect(LOCAL_DATETIME.test(okUtc)).toBe(false)
  })

  it('apiTime yardımcısı yerinde ve konsol onu kullanıyor', () => {
    expect(fs.existsSync(path.join(SRC, 'utils', 'apiTime.js'))).toBe(true)
    const console_ = fs.readFileSync(
      path.join(SRC, 'components', 'admin', 'MonitorChangesConsole.jsx'), 'utf8')
    expect(console_).toMatch(/toApiTime\s*\(/)
  })
})
