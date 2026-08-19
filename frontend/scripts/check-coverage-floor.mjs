// Büyük bileşenler için DOSYA-BAZLI kapsam tabanı.
//
// NEDEN VAR: 2026-08-19'da Sistem Sağlığı ekranı üretimde çöktü. `SystemHealth.jsx`
// 2324 satırlık bir dosyaydı ve satır kapsamı %11.94 idi — ama global %65 eşiği
// ~90 başka test dosyasının taşıdığı ortalamayla rahatça geçiyordu. Global eşik,
// büyük ve kapsamsız dosyaları ödüllendirir: küçük bir dosyayı tam test etmek ortalamayı
// ucuza yükseltir, dev bir dosyayı test etmemek ise ortalamayı yavaş düşürür.
//
// KURAL:
//   - LARGE_LINES satırdan büyük her kaynak dosya en az FLOOR satır kapsamı taşımalı.
//   - GRANDFATHERED'daki dosyalar bugünkü ölçülen değerleriyle kayıtlıdır; tabana
//     ulaşmaları beklenmez ama KENDİ değerlerinin altına DÜŞEMEZLER (cırcır/ratchet).
//     Kapsam tabanı geçince satırı listeden silin — liste küçülmelidir, büyümemeli.
//
// Çalıştır: npm run coverage:floor   (önce `npm run test:coverage` koşmalı)
import { readFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND = join(dirname(fileURLToPath(import.meta.url)), '..')
const SUMMARY = join(FRONTEND, 'coverage', 'coverage-summary.json')

const LARGE_LINES = 500
const FLOOR = 40          // satır kapsamı %

/** Ölçüye hiç girmeyenler — gerekçesiyle. */
const EXCLUDED = new Set([
  'src/i18n/index.jsx',   // saf çeviri sözlüğü; mantık yok, kapsam anlamsız
])

/**
 * Cırcırlı muafiyet: 2026-08-19 ölçümü. Değerler yalnız YUKARI güncellenir.
 * Bir dosya FLOOR'u geçtiğinde buradan SİLİNİR.
 */
const GRANDFATHERED = {
  'src/components/admin/DiagnosticsModal.jsx': 0,      // 661 satır — hiç birim testi yok
  'src/components/IncidentHistoryPage.jsx':    24,     // 908 satır
  'src/pages/ExpiryForecastPage.jsx':          25,     // 578 satır
  'src/components/CertificateModal.jsx':       27,     // 713 satır
}

function fail(msg) {
  console.error(msg)
  process.exitCode = 1
}

if (!existsSync(SUMMARY)) {
  console.error(`Kapsam özeti yok: ${SUMMARY}\nÖnce çalıştırın:  npm run test:coverage`)
  process.exit(1)
}

const summary = JSON.parse(readFileSync(SUMMARY, 'utf8'))
const measure = process.argv.includes('--measure')
const rows = []

for (const [abs, entry] of Object.entries(summary)) {
  if (abs === 'total') continue
  const norm = abs.replace(/\\/g, '/')
  const idx = norm.lastIndexOf('/frontend/')
  const rel = idx === -1 ? norm : norm.slice(idx + '/frontend/'.length)
  if (!rel.startsWith('src/') || !/\.jsx?$/.test(rel)) continue
  if (EXCLUDED.has(rel)) continue
  if (!existsSync(abs)) continue

  const lines = readFileSync(abs, 'utf8').split('\n').length
  if (lines < LARGE_LINES) continue
  rows.push({ rel, lines, pct: entry.lines.pct })
}

rows.sort((a, b) => a.pct - b.pct)

if (measure) {
  console.log(`${LARGE_LINES}+ satırlık dosyalar (satır kapsamına göre artan):\n`)
  for (const r of rows) {
    console.log(`  '${r.rel}': ${r.pct},`.padEnd(64) + `// ${r.lines} satır`)
  }
  process.exit(0)
}

const violations = []
for (const r of rows) {
  const recorded = GRANDFATHERED[r.rel]
  if (recorded === undefined) {
    if (r.pct < FLOOR) {
      violations.push(
        `${r.rel} — ${r.lines} satır, kapsam %${r.pct} (taban %${FLOOR}).\n` +
        `    Büyük bir bileşen için test yazın; geçici olarak listelemek gerekiyorsa ` +
        `GRANDFATHERED'a gerekçesiyle ekleyin.`
      )
    }
  } else if (r.pct < recorded) {
    violations.push(
      `${r.rel} — kapsam GERİLEDİ: %${recorded} → %${r.pct}. ` +
      `Muaf dosyalar kendi kayıtlı değerlerinin altına düşemez.`
    )
  } else if (r.pct >= FLOOR) {
    console.log(`✓ ${r.rel} artık tabanı geçiyor (%${r.pct}) — GRANDFATHERED listesinden silin.`)
  }
}

if (violations.length) {
  fail(`\nKapsam tabanı ihlali (${violations.length}):\n\n` + violations.map(v => `  • ${v}`).join('\n\n') + '\n')
} else {
  console.log(`Kapsam tabanı tamam — ${rows.length} büyük dosya denetlendi (taban %${FLOOR}, ${LARGE_LINES}+ satır).`)
}
