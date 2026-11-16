// Tüm site taramasının istemci sınırları sunucunun AYNASI olmalı (2026-11-16 regresyonu: sunucu 2500 sayfaya
// çıkarıldı, istemci tavanı 1000'de kaldı; "Tüm siteyi tara" hem 1.000 gösterdi hem `max_pages: 1000` gönderdi).
// Bu test Java kaynağını okur: sunucu sınırı değişip istemci değişmezse kırmızı olur.
import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  FULL_SITE_DEFAULT_MAX_PAGES,
  FULL_SITE_DEFAULT_MAX_SECONDS,
  FULL_SITE_MAX_PAGES_CAP,
  fullSiteLimits,
} from '../components/page/resourcescan/resourceScanReportModel.js'
import { FULL_SITE_POLL_DEFAULT_SECONDS } from '../components/page/resourcescan/resourceScanModel.js'

const SERVICE = path.resolve(process.cwd(),
  '../backend/src/main/java/com/sitemonitor/service/page/scan/PageResourceScanService.java')
const src = fs.readFileSync(SERVICE, 'utf8')

function javaInt(name) {
  const m = src.match(new RegExp(`\\b${name}\\s*=\\s*([0-9_]+)`))
  if (!m) throw new Error(`${name} PageResourceScanService.java içinde bulunamadı`)
  return Number(m[1].replaceAll('_', ''))
}

describe('tüm site taraması — istemci sınırları sunucuyla aynı', () => {
  const serverMaxPages = javaInt('FULL_MAX_PAGES_MAX')
  const serverDefaultPages = javaInt('FULL_MAX_PAGES_DEFAULT')
  const serverDefaultSeconds = javaInt('FULL_MAX_SECONDS_DEFAULT')

  it('sayfa tavanı ve varsayılanı sunucununki', () => {
    expect(FULL_SITE_MAX_PAGES_CAP).toBe(serverMaxPages)
    expect(FULL_SITE_DEFAULT_MAX_PAGES).toBe(serverDefaultPages)
  })

  it('süre varsayılanı (istek ve izleme tavanı) sunucununki', () => {
    expect(FULL_SITE_DEFAULT_MAX_SECONDS).toBe(serverDefaultSeconds)
    expect(FULL_SITE_POLL_DEFAULT_SECONDS).toBe(serverDefaultSeconds)
  })

  it('sunucunun gönderdiği en büyük değer kırpılmadan kullanılır', () => {
    expect(fullSiteLimits({ full_site_max_pages: serverMaxPages, full_site_max_seconds: serverDefaultSeconds }))
      .toEqual({ supported: true, maxPages: serverMaxPages, maxSeconds: serverDefaultSeconds })
  })
})
