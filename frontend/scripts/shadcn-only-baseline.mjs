// "Yalnız shadcn" kapısının tabanını (src/test/shadcn-only-baseline.json) ölçümden yeniden yazar.
//
// CIRCIR: taban YALNIZ KÜÇÜLÜR. Bir dosyada bir kuralın sayısı artmışsa betik reddeder (çıkış 1) —
// artışın doğru cevabı öğeyi shadcn bileşeniyle yazmaktır, tabanı büyütmek değil. Bilinçli bir istisna
// gerekiyorsa `--allow-raise` ile koşup commit mesajında GEREKÇE yazın.
//
// Çalıştır: npm run shadcn:baseline
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { measure } from './shadcn-only-rules.mjs'

const FRONTEND = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(FRONTEND, 'src')
const BASE = path.join(SRC, 'test', 'shadcn-only-baseline.json')
const allowRaise = process.argv.includes('--allow-raise')

const now = measure(SRC)
const old = fs.existsSync(BASE) ? JSON.parse(fs.readFileSync(BASE, 'utf8')).files ?? {} : null

const raises = []
if (old) {
  for (const [file, counts] of Object.entries(now)) {
    for (const [rule, n] of Object.entries(counts)) {
      const allowed = old[file]?.[rule] ?? 0
      if (n > allowed) raises.push(`${file} · ${rule}: ${allowed} → ${n}`)
    }
  }
}
if (raises.length && !allowRaise) {
  console.error('Taban BÜYÜYEMEZ. Artanlar (shadcn bileşeniyle yazın):\n  ' + raises.join('\n  '))
  process.exit(1)
}

const sum = (m) => Object.values(m).reduce((a, c) => a + Object.values(c).reduce((x, y) => x + y, 0), 0)
const sorted = Object.fromEntries(Object.keys(now).sort().map((k) => [k, now[k]]))
fs.writeFileSync(BASE, JSON.stringify({
  _comment: 'Yalnız KÜÇÜLÜR. Üretici: npm run shadcn:baseline. Kurallar: scripts/shadcn-only-rules.mjs. Hedef: boş.',
  total: sum(now),
  files: sorted,
}, null, 1) + '\n')
console.log(`shadcn-only tabanı: ${old ? sum(old) + ' → ' : ''}${sum(now)} öğe, ${Object.keys(now).length} dosya`)
