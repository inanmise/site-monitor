// İzleme CSV dışa / içe aktarımı uçtan uca (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu kurulum").
//
// HTTP sayfası başlığında "İçe / dışa aktar" menüsü: dışa aktarım GERÇEK indirmedir (Blob → `downloadBlob`), inen dosya
// okunup ayrıştırılır (BOM + başlık + satır). İçe aktarım: gerçek bir dosya seçilir (`setInputFiles`), sunucuya giden ham
// gövde = dosyanın baytları; önizleme (oluştur / güncelle + değişen sütunlar / değişiklik yok / hatalı + sonradan girilecek
// sırlar) → "Uygula" → satır satır sonuç. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): sayfa taşmaz,
// adlar kesilmez, dokunma hedefleri ≥ 40 px. API mock — hiçbir şey kaydedilmez.
import fs from 'node:fs'
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const LONG_NAME = 'Kurumsal İnternet Şubesi Müşteri Girişi Sağlık Kontrolü (Ana Veri Merkezi / İstanbul Bölge Yedekli Küme)'
const LONG_TEAM = 'Dijital Kanallar Uygulama Geliştirme ve Operasyon Takımı (Bölge Müdürlüğü / Altyapı)'
const EXPORT_CSV = '﻿name,team,url,interval_seconds,basic_auth_pass_set\r\n'
  + `"${LONG_NAME}","${LONG_TEAM}",https://www.example.com/health,120,true\r\n`
  + 'Durum sayfası,Takım A,https://status.example.org/,60,false\r\n'
const IMPORT_CSV = 'name,team,url,interval_seconds\r\n'
  + `"${LONG_NAME}","${LONG_TEAM}",https://www.example.com/health,90\r\n`
  + 'Durum sayfası,Takım A,https://status.example.org/,60\r\n'
  + 'Yeni izleme,Takım A,https://new.example.net/,60\r\n'
  + 'Bozuk,Takım A,https://bad.example.net/,altmış\r\n'

const rows = (statuses) => [
  { row: 2, name: LONG_NAME, team: LONG_TEAM, action: 'update', changed: ['interval_seconds', 'timeout_ms', 'expected_status', 'follow_redirects'],
    secrets: ['basic_auth_pass', 'custom_headers'], status: statuses?.[0] ?? null },
  { row: 3, name: 'Durum sayfası', team: 'Takım A', action: 'unchanged', changed: [], secrets: [], status: statuses?.[1] ?? null,
    message: statuses ? 'No changes — the record was left as it is.' : null },
  { row: 4, name: 'Yeni izleme', team: 'Takım A', action: 'create', changed: [], secrets: ['env:BASIC_TOKEN_FOR_THE_LOGIN_SCENARIO'],
    status: statuses?.[2] ?? null },
  { row: 5, name: 'Bozuk', team: 'Takım A', action: 'error', changed: [], secrets: [], status: statuses?.[3] ?? null,
    message: 'interval_seconds: the value must be a whole number (for example 60).', field: 'interval_seconds' },
]
const data = (dryRun) => ({
  type: 'http', dry_run: dryRun, columns: ['name', 'team', 'url', 'interval_seconds'], budget_exceeded: false,
  counts: dryRun ? { update: 1, unchanged: 1, create: 1, error: 1 } : { done: 2, skipped: 1, failed: 1 },
  rows: rows(dryRun ? null : ['done', 'skipped', 'done', 'failed']),
  limits: { max_rows: 2000, max_bytes: 5242880 },
})

async function mockCsv(page) {
  const sink = { exports: 0, imports: [] }
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [1] })
  await page.route((u) => new URL(u).pathname.startsWith('/api/monitoring/csv/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.pathname === '/api/monitoring/csv/http/export' && req.method() === 'GET') {
      sink.exports += 1
      return route.fulfill({
        status: 200, contentType: 'text/csv; charset=UTF-8', body: EXPORT_CSV,
        headers: { 'Content-Disposition': 'attachment; filename="http-izlemeler-20261119.csv"', 'Cache-Control': 'no-store',
          'X-Export-Rows': '2', 'X-Export-Truncated': 'false', 'X-Export-Max-Rows': '5000' },
      })
    }
    if (url.pathname === '/api/monitoring/csv/http/import' && req.method() === 'POST') {
      const dryRun = url.searchParams.get('dry_run') !== 'false'
      sink.imports.push({ dryRun, body: req.postData(), type: req.headers()['content-type'] })
      return route.fulfill(json({ success: true, data: data(dryRun) }))
    }
    return route.fulfill(json({ success: false, code: 'NOT_FOUND', error: 'not found' }, 404))
  })
  return sink
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`izleme CSV — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('dışa aktar (gerçek indirme, dosya ayrıştırılır) → içe aktar (önizleme → uygula → sonuç)', async ({ page }) => {
      const sink = await mockCsv(page)
      await page.goto('/?tab=http')
      const header = page.locator('.app-main [data-slot="page-header"]').first()
      await expect(header).toBeVisible({ timeout: 20_000 })
      const trigger = header.locator('[data-slot="monitor-csv-menu"]')
      await expect(trigger).toBeVisible()
      await expectInViewport(trigger, vp.width, 'CSV menü düğmesi')
      if (vp.touch) {
        const box = await trigger.boundingBox()
        expect(box.height, 'CSV menü düğmesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      }
      await expectLayout(page, '[data-slot="monitor-header-tools"]', { touch: vp.touch, label: 'başlık araçları' })

      // ── Dışa aktar: gerçek indirme ──
      await trigger.click()
      const exportItem = page.locator('[data-slot="monitor-csv-export"]')
      await expect(exportItem).toBeVisible()
      const [download] = await Promise.all([page.waitForEvent('download'), exportItem.click()])
      expect(download.suggestedFilename()).toBe('http-izlemeler-20261119.csv')
      const text = fs.readFileSync(await download.path(), 'utf8')
      expect(text.charCodeAt(0), 'BOM').toBe(0xFEFF)
      const lines = text.replace(/^﻿/, '').split('\r\n').filter(Boolean)
      expect(lines[0]).toBe('name,team,url,interval_seconds,basic_auth_pass_set')
      expect(lines[1]).toContain(LONG_NAME)
      expect(lines).toHaveLength(3)
      expect(sink.exports).toBe(1)
      await expect(page.getByText('2 monitors downloaded as CSV.')).toBeVisible()

      // ── İçe aktar: dosya seç → önizleme ──
      // Menü kapanış animasyonu (~150 ms) bitmeden tetiğe yeniden basılırsa, hâlâ DOM'da duran kapanmakta olan katman
      // basışı "dışarı tıklama" sayar ve aynı basışın tıklamasında yeni açılan menüyü kapatır (Radix; CI'da 6 / 6, yerelde
      // 6'da 1 düştü — animasyon 1,5 sn'ye uzatılınca her koşuda). Önce eski menünün tamamen söküldüğünü bekle.
      await expect(page.locator('[data-slot="monitor-csv-export"]')).toHaveCount(0)
      await trigger.click()
      await page.locator('[data-slot="monitor-csv-import"]').click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await expect(dialog.locator('[data-slot="mcsv-dialog"]')).toHaveAttribute('data-phase', 'pick')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: 'seçim evresi' })
      await dialog.locator('[data-slot="mcsv-file"]').setInputFiles({
        name: 'http-izlemeler.csv', mimeType: 'text/csv', buffer: Buffer.from(IMPORT_CSV, 'utf8'),
      })
      await expect(dialog.locator('[data-slot="mcsv-dialog"]')).toHaveAttribute('data-phase', 'preview')
      expect(sink.imports).toHaveLength(1)
      expect(sink.imports[0].dryRun).toBe(true)
      expect(sink.imports[0].body, 'sunucuya giden ham gövde = dosya').toBe(IMPORT_CSV)
      expect(sink.imports[0].type).toContain('text/csv')
      const rowsEl = dialog.locator('[data-slot="mcsv-row"]')
      await expect(rowsEl).toHaveCount(4)
      await expect(rowsEl.nth(0)).toContainText(LONG_NAME)
      await expect(rowsEl.nth(0)).toContainText(LONG_TEAM)
      await expect(rowsEl.nth(0).locator('[data-slot="mcsv-changed"]')).toContainText('interval_seconds, timeout_ms')
      await expect(rowsEl.nth(0).locator('[data-slot="mcsv-row-secrets"]')).toContainText('Basic auth password')
      await expect(rowsEl.nth(3).locator('[data-slot="mcsv-row-message"]')).toContainText('whole number')
      await expect(dialog.locator('[data-slot="mcsv-secrets"]')).toBeVisible()
      const apply = dialog.locator('[data-slot="mcsv-apply"]')
      await expect(apply).toContainText('(2)')
      await expect(apply).toBeEnabled()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: 'önizleme' })
      await expectInViewport(apply, vp.width, 'Uygula düğmesi')

      // ── Uygula → sonuç ──
      await apply.click()
      await expect(dialog.locator('[data-slot="mcsv-dialog"]')).toHaveAttribute('data-phase', 'result')
      expect(sink.imports).toHaveLength(2)
      expect(sink.imports[1].dryRun).toBe(false)
      expect(sink.imports[1].body).toBe(IMPORT_CSV)
      await expect(rowsEl).toHaveCount(4)
      await expect(rowsEl.nth(0)).toHaveAttribute('data-status', 'done')
      await expect(rowsEl.nth(3)).toHaveAttribute('data-status', 'failed')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: 'sonuç' })
      await dialog.locator('[data-slot="mcsv-close"]').click()
      await expect(dialog).toBeHidden()
    })

    test('istek hatası önizlemeyi korur; uygulanacak satır yoksa "Uygula" kapalı', async ({ page }) => {
      await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [1] })
      let calls = 0
      await page.route((u) => new URL(u).pathname === '/api/monitoring/csv/http/import', async (route) => {
        calls += 1
        if (calls === 1) return route.fulfill(json({ success: true, data: data(true) }))
        if (calls === 2) return route.fulfill(json({ success: false, code: 'RATE_LIMITED', error: 'An import is already running. Wait for it to finish and try again.', retry_after_seconds: 5 }, 429))
        return route.fulfill(json({ success: true, data: { ...data(true), rows: rows(null).filter((r) => r.action !== 'create' && r.action !== 'update') } }))
      })
      await page.goto('/?tab=http')
      const trigger = page.locator('.app-main [data-slot="monitor-csv-menu"]').first()
      await expect(trigger).toBeVisible({ timeout: 20_000 })
      await trigger.click()
      await page.locator('[data-slot="monitor-csv-import"]').click()
      const dialog = page.getByRole('dialog')
      await dialog.locator('[data-slot="mcsv-file"]').setInputFiles({ name: 'a.csv', mimeType: 'text/csv', buffer: Buffer.from(IMPORT_CSV) })
      await expect(dialog.locator('[data-slot="mcsv-row"]')).toHaveCount(4)
      await dialog.locator('[data-slot="mcsv-apply"]').click()
      await expect(dialog.locator('[data-slot="mcsv-error"]')).toContainText('already running')
      await expect(dialog.locator('[data-slot="mcsv-dialog"]')).toHaveAttribute('data-phase', 'preview')
      await expect(dialog.locator('[data-slot="mcsv-row"]')).toHaveCount(4)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: 'hata şeridi' })
      // Başka dosya: hiç uygulanacak satır yok
      await dialog.locator('[data-slot="mcsv-pick-another"]').click()
      await dialog.locator('[data-slot="mcsv-file"]').setInputFiles({ name: 'b.csv', mimeType: 'text/csv', buffer: Buffer.from(IMPORT_CSV) })
      await expect(dialog.locator('[data-slot="mcsv-row"]')).toHaveCount(2)
      await expect(dialog.locator('[data-slot="mcsv-apply"]')).toBeDisabled()
      await expect(dialog.locator('[data-slot="mcsv-nothing"]')).toBeVisible()
    })
  })
}
