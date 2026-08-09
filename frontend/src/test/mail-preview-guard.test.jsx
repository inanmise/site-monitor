import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { LOGO_OK, LOGO_WARNING, LOGO_CRITICAL } from '../assets/brand/mailLogos.js'

/**
 * İki bekçi:
 *
 * 1) Mail önizleyen HER iframe ortak yardımcıdan geçmeli. Yardımcı hem <base target="_blank">
 *    enjekte eder (aksi halde CTA linkleri iframe'i hedefler ve X-Frame-Options: DENY yüzünden
 *    "refused to connect" olur) hem de çözülemeyen cid:brand-logo referansını data: URI'ye çevirir.
 *    Ham srcDoc kullanan bir önizleme eklenirse logo yine kırık çıkar — bu test onu yakalar.
 *
 * 2) Üretilmiş mailLogos.js sabitleri public/brand PNG'leriyle BAYT-EŞİT olmalı. Marka otoritesi
 *    branding/ + BRAND.md'dir; sabitler yalnız bir kodlamadır. Logo güncellenip
 *    `npm run gen:mail-logos` unutulursa mail önizlemesi eski logoyu göstermeye devam eder.
 */
const SRC = path.resolve(__dirname, '..')
const FRONTEND = path.resolve(__dirname, '../..')

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name !== 'test') walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx?$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

describe('mail önizleme bekçileri', () => {
  it('her srcDoc kullanımı mailPreviewSrcDoc() üzerinden geçiyor', () => {
    const offenders = []
    for (const file of walk(SRC)) {
      if (file.endsWith(path.join('utils', 'mailPreview.js'))) continue
      const lines = fs.readFileSync(file, 'utf8').split('\n')
      lines.forEach((line, i) => {
        if (!line.includes('srcDoc=')) return
        if (line.includes('mailPreviewSrcDoc(')) return
        offenders.push(`${path.relative(SRC, file)}:${i + 1} → ${line.trim()}`)
      })
    }
    expect(offenders, 'Ham srcDoc: mail HTML\'i mailPreviewSrcDoc() ile hazırlanmalı '
      + '(cid:brand-logo çözümü + <base target="_blank">)').toEqual([])
  })

  it('mailLogos.js sabitleri public/brand PNG\'leriyle bayt-eşit', () => {
    const constants = { ok: LOGO_OK, warning: LOGO_WARNING, critical: LOGO_CRITICAL }
    for (const [variant, dataUri] of Object.entries(constants)) {
      const png = fs.readFileSync(path.join(FRONTEND, 'public/brand', `logo-${variant}-32.png`))
      expect(dataUri.startsWith('data:image/png;base64,')).toBe(true)
      const decoded = Buffer.from(dataUri.slice('data:image/png;base64,'.length), 'base64')
      expect(decoded.equals(png), `logo-${variant}-32.png değişmiş — "npm run gen:mail-logos" çalıştırın`).toBe(true)
    }
  })
})
