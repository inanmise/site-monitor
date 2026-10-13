// Kanal teknik hata raporları (2026-10-13). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: kanal kartları
// sığar ve uzun adlar sarar; "Şablonu aç" TIKLANAN kanalın şablonunu açar; şablon kaydı okunan sürümle gider; rapor
// düzenleyicide Türkçe sayı çözülür ve sürümle kaydedilir, grafik yuvaları 768+ ekranda iki kolon (yarım yan yana, tam iki
// kolon), telefonda tek kolon; görsel yükleme ve gönderim onayı. API mock'lu (bildirim yok, e-posta gönderilmez).
import { test, expect } from '@playwright/test'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_CHANNEL = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')

const TEMPLATE = {
  v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Özeti', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', hint: 'İlgili ay/yıl verilerine göre' },
      { id: 'had-outage', label: 'Dönemde kesinti oldu mu?', type: 'yesno', hint: "“Hayır” işaretlenirse kesinti alanları istenmez, PDF'e girmez" },
      { id: 'error-rate-excl', label: 'Kesinti günleri hariç teknik hata oranı', type: 'percent', skip_if_no: 'had-outage' },
      { id: 'outage-errors', label: 'Kesinti kaynaklı teknik hata adedi', type: 'number', skip_if_no: 'had-outage' },
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'tx-increase', label: 'Önceki aya göre artış var mı?', type: 'yesno' },
      { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent' },
      { id: 'sla-above', label: 'SLA hedefin üstünde mi?', type: 'yesno' },
    ] },
    { id: 'issues', kind: 'issues', title: 'Teknik hataların anomali olarak arttığı konular', item_title: '{konu} Konusu ile yaşanan problem',
      description_label: 'Ne zaman, nerede, nasıl gerçekleşti?', customers_label: 'Etkilenen müşteri sayısı',
      errors_label: 'Bu sorundan kaynaklı teknik hata adedi' },
    { id: 'unmapped', kind: 'metrics', title: 'Map Yapılmamış Hata Adedi', fields: [
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'unmapped-note', label: 'Açıklama', type: 'textarea' },
    ] },
    { id: 'trend-note', kind: 'text', title: 'Grafikler hakkında', text: 'Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.' },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [
      { id: 'monthly-tx', label: '{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri', width: 'half' },
      { id: 'tech-errors', label: '{kanal} Kanalı Teknik Hata Grafiği', width: 'half' },
      { id: 'daily-errors', label: '{kanal} Kanalı Teknik Hata (Günlük)', width: 'full' },
    ] },
  ],
}

function channel(id, name) {
  return {
    id, team_id: 5, team_name: LONG_TEAM, name, description: 'Aylık teknik hata raporu üst yönetime e-posta ile iletilir.',
    active: true, sort_order: null, template_version: 3, lang: 'tr', section_count: 5, field_count: 8, slot_count: 3,
    can_edit: true, can_delete: true, report_count: 4,
    period_report: id === 10 ? { id: 100, status: 'draft', year: 2026, month: 9, last_send_status: null } : null,
  }
}

const REPORT = {
  report: { id: 100, channel_id: 10, team_id: 5, year: 2026, month: 9, status: 'draft', send_state: null, last_send_status: null,
    updated_at: '2026-10-02T07:00:00Z', last_sent_at: null, version: 4, title: '', updated_by_name: 'Ayşegül Nur Karaosmanoğlu' },
  channel: channel(10, LONG_CHANNEL), template: TEMPLATE,
  values: { fields: { 'error-rate': 0.42 }, issues: { issues: [{ id: 'i-1', topic: 'Kart Limiti Sorgulama Servisi', title: '', title_auto: true,
    description: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı; servis yeniden başlatıldı.',
    customers: 1200, errors: 3400 }] }, texts: {} },
  images: [], previous: { id: 90, year: 2026, month: 8, fields: { 'error-rate': 0.5, 'total-tx': 1000000 } },
  can_edit: true, can_delete: true, team_email_set: true, template_update: { differs: false, lost_values: 0, lost_images: 0 },
}

let savedTemplate = null
let savedReport = null
let uploads = []
let sends = 0
async function mock(page) {
  savedTemplate = null; savedReport = null; uploads = []; sends = 0
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    if (p === '/api/tech-error-reports/images/501') {
      await route.fulfill({ status: 200, contentType: 'image/png', body: PNG })
      return
    }
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'USER', global_admin: false, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'tech_error_reports.read': { view: true }, 'tech_error_reports.crud': { edit: true } } }
    else if (p === '/api/tech-error-reports/overview') {
      body = { success: true, data: { period: { year: 2026, month: 9 }, can_create: true,
        teams: [{ id: 5, name: LONG_TEAM, active: true, has_email: true, can_edit: true, can_manage: false }],
        channels: [channel(10, LONG_CHANNEL), channel(20, 'POS')] } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/template$/.test(p)) {
      const id = Number(p.split('/')[4])
      if (req.method() === 'PUT') {
        savedTemplate = { id, ...JSON.parse(req.postData() || '{}') }
        body = { success: true, data: { template: savedTemplate.template, version: 4 } }
      } else body = { success: true, data: { channel: channel(id, id === 10 ? LONG_CHANNEL : 'POS'), template: TEMPLATE, version: 3 } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/reports$/.test(p)) {
      const id = Number(p.split('/')[4])
      body = { success: true, data: { channel: channel(id, id === 10 ? LONG_CHANNEL : 'POS'), default_period: { year: 2026, month: 9 },
        reports: [{ id: 100, year: 2026, month: 9, status: 'draft', title: `${id === 10 ? LONG_CHANNEL : 'POS'} Kanalı Teknik Hata Mesajları - Eylül 2026`,
          updated_at: '2026-10-02T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: true, image_count: 0 }] } }
    } else if (p === '/api/tech-error-reports/reports/100') {
      if (req.method() === 'PUT') {
        savedReport = JSON.parse(req.postData() || '{}')
        body = { success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z', values: savedReport.values } }
      } else body = { success: true, data: REPORT }
    } else if (p === '/api/tech-error-reports/reports/100/images/monthly-tx') {
      uploads.push(req.headers()['content-type'] || '')
      body = { success: true, data: { id: 501, slot_id: 'monthly-tx', content_type: 'image/png', file_name: 'aylik-islem.png', width: 1, height: 1, size_bytes: PNG.length } }
    } else if (p === '/api/tech-error-reports/reports/100/send') {
      sends++
      body = { success: true, data: { state: 'SENT', to: ['dijital-kanallar@example.com'], subject: 'x', pdf_bytes: 1000 } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input, textarea')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled || b.type === 'file') continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  // Ekran okuyucuya özel metin (sr-only) kasıtlı olarak 1 px'tir — kırpılma sayılmaz
  return [...root.querySelectorAll('span, p, li, h2, h3, label, button')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 20
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Kanal teknik hata raporları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kanallar sığar; "Şablonu aç" tıklanan kanalın şablonunu açar; şablon sürümle kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports')
      const cards = page.locator('[data-slot="te-channel-card"]')
      await expect(cards).toHaveCount(2, { timeout: 20_000 })
      await expect(cards.first()).toContainText(LONG_CHANNEL)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channels"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel-list"]'), 'küçük hedef').toEqual([])

      await cards.nth(1).locator('[data-slot="te-open-template"]').click()   // POS
      await expect(page.locator('[data-slot="te-template-editor"]')).toBeVisible()
      await expect(page.locator('[data-slot="te-channel-title"]')).toHaveText('POS')
      await expect.poll(() => new URL(page.url()).searchParams.get('te_ch')).toBe('20')

      await page.locator('[data-slot="te-back"]').click()
      await cards.first().locator('[data-slot="te-open-template"]').click()   // uzun adlı kanal
      await expect(page.locator('[data-slot="te-channel-title"]')).toHaveText(LONG_CHANNEL)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_ch')).toBe('10')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon)').toEqual([])

      const slots = page.locator('[data-slot="te-tpl-section"][data-kind="images"]')
      await slots.getByRole('button', { name: /Grafik yuvası ekle|Add chart slot/ }).click()
      await page.locator('[data-slot="te-tpl-save"]').click()
      await expect.poll(() => savedTemplate?.version).toBe(3)
      expect(savedTemplate.id).toBe(10)
      expect(savedTemplate.template.sections.find((s) => s.id === 'charts').slots).toHaveLength(4)
      await page.screenshot({ path: `test-results/channel-reports-template-${vp.name}.png` })
    })

    test('rapor: sayı çözülür ve sürümle kaydedilir; grafik ızgarası; görsel yükleme; gönderim onayı', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor.locator('[data-slot="te-report-title"]')).toContainText(`${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026`, { timeout: 20_000 })
      await expect(editor.locator('[data-slot="te-issue-title"]')).toContainText('Kart Limiti Sorgulama Servisi Konusu ile yaşanan problem')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (rapor)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (rapor)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (rapor)').toEqual([])

      // Grafik ızgarası: 768+ yarımlar yan yana, tam iki kolon; telefonda alt alta
      const a = await editor.locator('[data-slot-id="monthly-tx"]').boundingBox()
      const b = await editor.locator('[data-slot-id="tech-errors"]').boundingBox()
      const full = await editor.locator('[data-slot-id="daily-errors"]').boundingBox()
      if (vp.width >= 768) {
        expect(Math.abs(a.y - b.y), 'yarım yuvalar aynı satırda').toBeLessThanOrEqual(2)
        expect(full.width, 'tam yuva iki kolon').toBeGreaterThan(a.width * 1.8)
      } else {
        expect(b.y, 'telefonda alt alta').toBeGreaterThan(a.y + a.height - 1)
        expect(Math.abs(full.width - a.width)).toBeLessThanOrEqual(2)
      }

      // 2026-10-14: "Dönemde kesinti oldu mu?" → "Hayır" kesinti alanlarını muaf eder (gizli + not), "Evet" geri getirir
      await expect(editor.getByLabel('Kesinti günleri hariç teknik hata oranı')).toBeVisible()
      const outage = editor.getByLabel('Dönemde kesinti oldu mu?')
      await outage.getByRole('radio', { name: /^(Hayır|No)$/ }).click()
      await expect(editor.getByLabel('Kesinti günleri hariç teknik hata oranı')).toHaveCount(0)
      await expect(editor.getByLabel('Kesinti kaynaklı teknik hata adedi')).toHaveCount(0)
      await expect(editor.locator('[data-slot="te-exempt-note"]')).toBeVisible()
      expect(await page.evaluate(overflowX), 'yatay taşma (muaf)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (muaf)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (muaf)').toEqual([])

      await editor.getByLabel('Toplam işlem adedi').fill('1.250.000')
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.version).toBe(4)
      expect(savedReport.values.fields).toMatchObject({ 'error-rate': 0.42, 'total-tx': 1250000, 'had-outage': false })
      expect(savedReport.values.fields).not.toHaveProperty('error-rate-excl')
      await expect(page.locator('[data-slot="te-report-actions"]')).toContainText(/Tüm değişiklikler kaydedildi|All changes saved/)

      await editor.locator('[data-slot-id="monthly-tx"] input[type="file"]').setInputFiles({ name: 'aylik-islem.png', mimeType: 'image/png', buffer: PNG })
      await expect.poll(() => uploads.length).toBe(1)
      expect(uploads[0]).toContain('multipart/form-data')
      await expect(editor.locator('[data-slot-id="monthly-tx"] img')).toBeVisible()

      await page.locator('[data-slot="te-send"]').click()
      const dlg = page.getByRole('dialog', { name: /Rapor takıma gönderilsin mi\?|Send the report to the team\?/ })
      await expect(dlg).toBeVisible()
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect.poll(() => sends).toBe(1)
      await page.screenshot({ path: `test-results/channel-reports-report-${vp.name}.png` })
    })
  })
}
