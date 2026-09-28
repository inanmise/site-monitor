import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { alertNavParams, alertLink } from '../components/admin/alerts/alertHistoryModel.js'

/**
 * Alarm Geçmişi derin bağlantı sözleşmesi — KAPI (E2, 2026-09-28e).
 *
 * <p>Alarm Geçmişi açılacak kaydı YALNIZ `alert` anahtarından okur (+ süzgeç `type`/`q`, çözülmüş kayıt için `view=closed`).
 * Kontrol Geçmişi'nin kesinti çizelgesi, alarm olay kartı, Bugün paneli, SMTP / Push gönderim logu ve Genel Bakış kartı
 * `{ incident: id }` gönderiyordu — `incident` uygulamanın (Olaylar) anahtarı; sayfa açılıyor ama hedef alarm hiç açılmıyordu.
 * Birim testleri o yanlış sözleşmeyi pinliyordu (AlertHistory ISSUE-002 ile aynı desen). Bu kapı kaynağı tarar: Alarm
 * Geçmişi'ne giden hiçbir `navigateTo` / `tabDeepLink` çağrısı `incident` taşımaz. Doğru biçim `alertNavParams`.
 */
const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'assets') continue
      walk(p, out)
    } else if (/\.jsx?$/.test(entry.name)) {
      out.push(p)
    }
  }
  return out
}

/** `navigateTo('alerthistory', …)` / `tabDeepLink('alerthistory', …)` çağrısının argüman metni (parantez dengeli). */
function callsTo(src) {
  const out = []
  const re = /\b(navigateTo|tabDeepLink)\(\s*['"]alerthistory['"]/g
  let m
  while ((m = re.exec(src))) {
    let depth = 0
    let end = m.index
    for (let i = src.indexOf('(', m.index); i < src.length; i++) {
      if (src[i] === '(') depth++
      else if (src[i] === ')' && --depth === 0) { end = i + 1; break }
    }
    out.push({ at: src.slice(0, m.index).split('\n').length, text: src.slice(m.index, end) })
  }
  return out
}

describe('Alarm Geçmişi derin bağlantı sözleşmesi', () => {
  it('Alarm Geçmişi\'ne giden hiçbir çağrı `incident` taşımaz (sayfa onu okumaz)', () => {
    const bad = []
    let total = 0
    for (const file of walk(SRC)) {
      const src = fs.readFileSync(file, 'utf8')
      for (const c of callsTo(src)) {
        total++
        if (/\bincident\s*:/.test(c.text)) bad.push(`${path.relative(SRC, file)}:${c.at}  ${c.text.replace(/\s+/g, ' ')}`)
      }
    }
    expect(total).toBeGreaterThan(5)   // tarayıcı çağrıları gerçekten buluyor (boş tarama sahte yeşil olmasın)
    expect(bad).toEqual([])
  })

  it('alertNavParams: alert + tür + alan adı; çözülmüşse view=closed; paylaşılan bağlantı aynı biçimde', () => {
    expect(alertNavParams({ id: 7, alert_type: 'HTTP_DOWN', domain: 'a.example.com', resolved: true }))
      .toEqual({ alert: '7', type: 'HTTP_DOWN', q: 'a.example.com', view: 'closed' })
    expect(alertNavParams({ id: 8, alert_type: 'PING_DOWN', resolved: false })).toEqual({ alert: '8', type: 'PING_DOWN' })
    expect(alertNavParams({ id: 9 })).toEqual({ alert: '9' })
    expect(alertLink({ id: 7, alert_type: 'HTTP_DOWN', domain: 'a.example.com', resolved: true }))
      .toMatch(/\?tab=alerthistory&alert=7&type=HTTP_DOWN&q=a\.example\.com&view=closed$/)
  })
})
