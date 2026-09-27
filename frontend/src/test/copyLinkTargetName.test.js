import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI — kart/satır içindeki "Bağlantıyı kopyala" düğmesi HEDEFİ adında taşır (2026-09-27 a11y A1).
 *
 * <p>`ui/CopyLinkButton` sabit `share.copyLink` adıyla on kart türünde tekrar ediyordu: 50 kartlık ızgarada 50 özdeş
 * "Bağlantıyı kopyala" — ekran okuyucu hangisinin hangi monitöre ait olduğunu duyurmuyordu. Kural: derin bağlantı
 * üreten her kullanım (`url={monitorDeepLink(…)}` / `url={domainDeepLink(…)}` — yani bir LİSTE öğesi) `targetName`
 * geçer. Sayfa başlığı / tek pencere kullanımları (url yok, adres çubuğu) kapsam dışı.
 */
const SRC = path.resolve(__dirname, '..')

/**
 * Devredilen dosyalar: aynı anda başka ajanların düzenlediği kart modülleri — `targetName` onlar tarafından eklenecek.
 * Cırcır YALNIZ küçülür; düzeltilen dosya buradan silinir.
 */
const PENDING = new Map([
  // 2026-09-27: HTTP (targetName={m.url}) ve Port (targetName={host:port}) kartları eklendi — liste boş kalmalı.
])

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'node_modules') walk(full, out) }
    else if (/[.]jsx?$/.test(e.name)) out.push(full)
  }
  return out
}
const rel = (f) => path.relative(SRC, f).split(path.sep).join('/')

describe('CopyLinkButton — liste öğesinde hedef adı', () => {
  const files = walk(SRC)

  it('tarama vakum değil', () => {
    expect(files.length).toBeGreaterThan(100)
  })

  it('derin bağlantılı her CopyLinkButton targetName taşır', () => {
    const offenders = []
    let seen = 0
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf8')
      for (const m of src.matchAll(/<CopyLinkButton\b[^>]*?\/>/g)) {
        if (!/url=\{(monitorDeepLink|domainDeepLink)\(/.test(m[0])) continue
        seen++
        if (/\btargetName=/.test(m[0])) continue
        if (PENDING.has(rel(f))) continue
        offenders.push(`${rel(f)}:${src.slice(0, m.index).split('\n').length}`)
      }
    }
    expect(seen, 'kalıp hiçbir kullanım bulmadı — tarama bozuk').toBeGreaterThan(5)
    expect(offenders, "Kart/satırdaki bağlantı kopyalama düğmesine targetName={…} geçin (ad: '<hedef> — Bağlantıyı kopyala').").toEqual([])
  })
})
