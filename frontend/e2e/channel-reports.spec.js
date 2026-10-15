// Kanal teknik hata raporları (2026-10-13). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: kanal kartları
// sığar ve uzun adlar sarar; "Şablonu aç" TIKLANAN kanalın şablonunu açar; şablon kaydı okunan sürümle gider; rapor
// düzenleyicide Türkçe sayı çözülür ve sürümle kaydedilir, grafik yuvaları 768+ ekranda iki kolon (yarım yan yana, tam iki
// kolon), telefonda tek kolon; görsel yükleme ve gönderim onayı. API mock'lu (bildirim yok, e-posta gönderilmez).
// 2026-10-14: yönetici özeti (rakamlarla canlı, kalın), grup satırlı tablo (geniş kapta tablo, telefonda kart; yatay
// taşma yok), açıklamada madde / kalın araç çubuğu ve önizleme; şablonda özet cümlesi ve tablo düzenleme.
// 2026-10-15 (Faz C): otomatik trend grafikleri sığar, çubuk etiketleri üst üste binmez, ilk ay / Ocak altında yıl;
// büyük görsel tarayıcıda yuva ölçüsüne küçültülüp yüklenir (gerçek canvas), PDF yüklenmez.
import { test, expect } from '@playwright/test'
import zlib from 'node:zlib'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_CHANNEL = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')

// Büyük ekran görüntüsü yerine geçen PNG (çizgili, RGB) — küçültme gerçek tarayıcı canvas'ında denenir
const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function stripedPng(w, h) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
  const raw = Buffer.alloc((w * 3 + 1) * h)
  for (let y = 0; y < h; y++) {
    const o = y * (w * 3 + 1)
    for (let x = 0; x < w; x++) {
      const on = (Math.floor(x / 40) + Math.floor(y / 40)) % 2 === 0
      raw[o + 1 + x * 3] = on ? 20 : 240; raw[o + 2 + x * 3] = on ? 90 : 240; raw[o + 3 + x * 3] = on ? 200 : 240
    }
  }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
// Çok parçalı yükleme gövdesindeki PNG'nin boyutu (IHDR)
function pngSize(body) {
  const i = body.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47]))
  if (i < 0) return null
  return { w: body.readUInt32BE(i + 16), h: body.readUInt32BE(i + 20), bytes: body.length }
}

const TEMPLATE = {
  v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      '{kanal} Kanalından yapılan toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.',
      'SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} ile {f:sla-above?hedefin üzerinde|hedefin altında} gerçekleşmiştir.',
    ] },
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
    { id: 'sla', kind: 'table', title: 'SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'success-tx', label: 'Başarılı işlem adedi', type: 'number' },
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'failed-tx', label: 'Teknik başarısız işlem adedi (kesinti günleri dahil)', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik (kesintisiz)', type: 'percent' },
      { id: 'volume-share', label: 'Toplam işlem hacmi payı', type: 'percent' },
    ], rows: [
      { id: 'total', label: '{kanal} Kanalı Toplam', group: true },
      { id: 'sla-critical', label: 'SLA dahil ve kritik fonksiyonlar (müşteri işlemleri)' },
      { id: 'sla-noncritical', label: 'SLA dahil ve kritik olmayan' },
      { id: 'out-of-sla', label: 'SLA kapsamında olmayan' },
    ] },
    { id: 'trend-note', kind: 'text', title: 'Grafikler hakkında', text: 'Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.' },
    { id: 'auto-trend', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)',
      series: ['error-rate', 'total-tx', 'unmapped-count', 'outage-errors'] },
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
  neighbors: { prev: { id: 90, year: 2026, month: 8 }, next: null },
  // Faz C: kanalın önceki raporlarına girilmiş rakamlar (Ekim 2025 … Eylül 2026; bu ay düzenleyicideki taslaktan)
  trend: {
    months: Array.from({ length: 12 }, (_, i) => (i < 3 ? { year: 2025, month: 10 + i } : { year: 2026, month: i - 2 })),
    values: {
      'error-rate': [0.61, 0.58, 0.55, 0.52, 0.6, 0.49, 0.47, 0.51, 0.45, 0.48, 0.5, 0.42],
      'total-tx': [98.2e6, 101.4e6, 110e6, 120e6, 115.6e6, 118e6, 121.3e6, 119e6, 123e6, 124.9e6, 125.43e6, null],
      'unmapped-count': [1200, 1540, 1810, 2400, 1999, 2650, 3010, 2875, 3120, 2990, 3340, null],
      'outage-errors': [null, null, null, null, null, null, null, null, null, null, 412, null],
    },
    min_points: 2,
  },
}

// Faz B (2026-10-14): değişiklik geçmişi ve şablon sürümleri — gerçek uzunlukta adlar / değerler
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Demirtaş (Dijital Kanallar / Uygulama Geliştirme Yöneticisi)'
const HISTORY = {
  has_more: false, recorded_since: '2026-10-01T08:00:00Z',
  report: { created_at: '2026-10-01T08:00:00Z', created_by_name: LONG_PERSON, updated_at: '2026-10-13T09:00:00Z', updated_by_name: LONG_PERSON },
  events: [
    { id: 9, kind: 'SEND', at: '2026-10-13T10:00:00Z', actor_name: LONG_PERSON, system: false,
      detail: { state: 'SENT', recipient_count: 3, recipients: ['dijital-kanallar-uygulama-gelistirme@example.com', 'kanal-yonetimi-raporlama@example.com', 'ust-yonetim@example.com'] } },
    { id: 8, kind: 'VALUES', at: '2026-10-13T09:00:00Z', actor_name: LONG_PERSON, system: false, version: 5,
      detail: { changes: [
        { section: `${LONG_CHANNEL} Kanalı — Eylül 2026 Özeti`, label: 'Kesinti günleri hariç teknik hata oranı (kesintilerin olduğu günler çıkarıldığında)', type: 'percent', op: 'change', before: '%0,4235', after: '%0,5112' },
        { section: 'Teknik hataların anomali olarak arttığı konular', label: 'Kart Limiti Sorgulama Servisi Konusu ile yaşanan problem · Ne zaman, nerede, nasıl gerçekleşti?', type: 'textarea', op: 'change',
          before: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı.', after: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı; servis yeniden başlatıldı ve kalıcı çözüm için kapasite artırımı planlandı.', after_length: 640 },
        { section: 'SLA Erişilebilirlik Tablosu', label: `${LONG_CHANNEL} Kanalı Toplam — Teknik başarısız işlem adedi`, type: 'table', op: 'set', before: null, after: '125.430.112' },
      ] } },
    { id: 7, kind: 'IMAGE_ADD', at: '2026-10-12T08:00:00Z', actor_name: LONG_PERSON, system: false,
      detail: { slot: `${LONG_CHANNEL} Kanalı Ay Bazlı Toplam İşlem Adetleri`, file_name: 'aylik-toplam-islem-adetleri-grafigi-2026-eylul-son-surum.png', width: 1600, height: 900, size_bytes: 412000 } },
    { id: 1, kind: 'CREATE', at: '2026-10-01T08:00:00Z', actor_name: LONG_PERSON, system: false, detail: { template_version: 3 } },
  ],
}
const VERSIONS = { current_version: 3, max_versions: 100, versions: [
  { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 5, field_count: 8, slot_count: 3 },
  { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 4, field_count: 9, slot_count: 3 },
] }
const TEMPLATE_V2 = { ...TEMPLATE, title: 'Eski rapor başlığı: {kanal} Kanalı Aylık Teknik Hata ve Erişilebilirlik Değerlendirmesi - {ay} {yil}',
  sections: TEMPLATE.sections.map((x, i) => (i === TEMPLATE.sections.findIndex((y) => y.kind === 'metrics')
    ? { ...x, fields: [...x.fields, { id: 'eski-alan', label: 'Kaldırılan uzun alan adı: kota ve map yapılmamış hataların toplam içindeki payı', type: 'percent' }] } : x)) }

let savedTemplate = null
let savedReport = null
let uploads = []
let uploadSizes = []
let sends = 0
async function mock(page) {
  savedTemplate = null; savedReport = null; uploads = []; uploadSizes = []; sends = 0
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
    } else if (p === '/api/tech-error-reports/reports/100/history') {
      body = { success: true, data: HISTORY }
    } else if (p === '/api/tech-error-reports/channels/10/template/versions') {
      body = { success: true, data: VERSIONS }
    } else if (/^\/api\/tech-error-reports\/channels\/10\/template\/versions\/[23]$/.test(p)) {
      const v = Number(p.split('/').pop())
      body = { success: true, data: { version: v, saved_at: '2026-10-0' + v + 'T08:00:00Z', saved_by_name: LONG_PERSON,
        template: v === 2 ? TEMPLATE_V2 : TEMPLATE, current: v === 3 } }
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
          updated_at: '2026-10-02T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: true, image_count: 0 },
        { id: 80, year: 2025, month: 12, status: 'sent', title: `${id === 10 ? LONG_CHANNEL : 'POS'} Kanalı Teknik Hata Mesajları - Aralık 2025`,
          updated_at: '2026-01-05T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: false, image_count: 2,
          last_sent_at: '2026-01-05T08:00:00Z' }] } }
    } else if (p === '/api/tech-error-reports/reports/100') {
      if (req.method() === 'PUT') {
        savedReport = JSON.parse(req.postData() || '{}')
        body = { success: true, data: { version: 5, changed: true, updated_at: '2026-10-13T09:00:00Z', values: savedReport.values } }
      } else body = { success: true, data: REPORT }
    } else if (p === '/api/tech-error-reports/reports/100/images/monthly-tx') {
      uploads.push(req.headers()['content-type'] || '')
      uploadSizes.push(pngSize(req.postDataBuffer() || Buffer.alloc(0)))
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
    // Onay kutusu 16 px görünür ama dokunmatikte ::after ile 40 px vuruş alanı taşır (shadcn/checkbox.jsx proje eki)
    if (r.width === 0 || r.height === 0 || b.disabled || b.type === 'file' || b.getAttribute('role') === 'checkbox') continue
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

      // Yönetici özeti cümlesi ve tablo düzenleyicisi de sığar; cümleye alan eklenir, tabloya grup satırı eklenir
      const sum = page.locator('[data-slot="te-tpl-section"][data-kind="summary"]')
      const firstLine = sum.locator('textarea').first()
      await firstLine.fill('Oran')
      await sum.locator('[data-slot="te-tpl-insert-field"]').first().click()
      await page.getByRole('menuitem', { name: /Kritik fonksiyonların SLA oranı/ }).click()
      await expect(firstLine).toHaveValue('Oran {f:sla-rate}')
      await expect(sum.locator('[data-slot="te-tpl-line-preview"]').first()).toContainText('[Kritik fonksiyonların SLA oranı]')
      const table = page.locator('[data-slot="te-tpl-section"][data-kind="table"]')
      await table.getByRole('button', { name: /Satır ekle|Add row/ }).click()
      await table.locator('[data-slot="te-tpl-row"]').last().getByRole('checkbox').click()
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon özet/tablo)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon özet/tablo)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon özet/tablo)').toEqual([])

      const slots = page.locator('[data-slot="te-tpl-section"][data-kind="images"]')
      await slots.getByRole('button', { name: /Görsel yuvası ekle|Add image slot/ }).click()
      await page.locator('[data-slot="te-tpl-save"]').click()
      await expect.poll(() => savedTemplate?.version).toBe(3)
      expect(savedTemplate.id).toBe(10)
      expect(savedTemplate.template.sections.find((s) => s.id === 'charts').slots).toHaveLength(4)
      expect(savedTemplate.template.sections.find((s) => s.id === 'exec').lines[0]).toBe('Oran {f:sla-rate}')
      expect(savedTemplate.template.sections.find((s) => s.id === 'sla').rows.at(-1)).toMatchObject({ group: true })
      expect(savedTemplate.template.sections.find((s) => s.id === 'auto-trend').series)
        .toEqual(['error-rate', 'total-tx', 'unmapped-count', 'outage-errors'])
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

      await editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true }).fill('1.250.000')

      // Yönetici özeti: girilen rakamla canlı yazılır, rakam kalın; bekleyen cümle neyi beklediğini söyler
      const summary = editor.locator('[data-slot="te-sum-text"]')
      await expect(summary).toContainText(`${LONG_CHANNEL} Kanalından yapılan toplam işlem adedi 1.250.000 olarak gerçekleşmiştir.`)
      await expect(summary.locator('strong', { hasText: '1.250.000' })).toBeVisible()
      await expect(editor.locator('[data-slot="te-sum-pending"]')).toContainText(/Kritik fonksiyonların SLA oranı/)

      // Tablo: geniş kapta gerçek tablo, dar kapta kart; ikisinde de yatay sayfa taşması yok
      const tableBlock = editor.locator('[data-slot="te-table"] [data-layout]')
      const layout = await tableBlock.getAttribute('data-layout')
      if (vp.width < 768) expect(layout, 'telefonda kart').toBe('cards')
      if (vp.width >= 1280) expect(layout, 'geniş ekranda tablo').toBe('table')
      await editor.getByLabel(`${LONG_CHANNEL} Kanalı Toplam — Fonksiyon adedi`).or(
        editor.locator('[data-row="total"]').getByLabel('Fonksiyon adedi', { exact: true })).fill('1250')
      await editor.getByLabel(`${LONG_CHANNEL} Kanalı Toplam — Erişilebilirlik (kesintisiz)`).or(
        editor.locator('[data-row="total"]').getByLabel('Erişilebilirlik (kesintisiz)', { exact: true })).fill('99,5')

      // Açıklama: madde + kalın araç çubuğu, önizleme madde işaretini gösterir
      const note = editor.getByLabel('Açıklama', { exact: true })
      await note.fill('Limit sorgusu yavaşladı')
      const noteBox = editor.locator('[data-field="values.unmapped-note"] [data-slot="te-rich-input"]')
      await noteBox.locator('[data-tool="bullet"]').click()
      await expect(note).toHaveValue('- Limit sorgusu yavaşladı')
      await noteBox.locator('[data-tool="preview"]').click()
      await expect(noteBox.locator('[data-slot="te-rich-item"]')).toContainText('Limit sorgusu yavaşladı')

      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (özet/tablo)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (özet/tablo)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (özet/tablo)').toEqual([])

      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.version).toBe(4)
      expect(savedReport.values.fields).toMatchObject({ 'error-rate': 0.42, 'total-tx': 1250000, 'had-outage': false,
        'unmapped-note': '- Limit sorgusu yavaşladı' })
      expect(savedReport.values.fields).not.toHaveProperty('error-rate-excl')
      expect(savedReport.values.tables).toEqual({ sla: { total: { functions: 1250, availability: 99.5 } } })
      await expect(page.locator('[data-slot="te-report-actions"]')).toContainText(/Tüm değişiklikler kaydedildi|All changes saved/)

      // Yuvaya sığan PNG (en az 16 px — sunucu kuralı) olduğu gibi yüklenir
      await editor.locator('[data-slot-id="monthly-tx"] input[type="file"]').setInputFiles({ name: 'aylik-islem.png', mimeType: 'image/png',
        buffer: stripedPng(400, 200) })
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
  
    test('Faz C: otomatik trend sığar, etiketler binmez; büyük görsel tarayıcıda küçültülür, PDF yüklenmez', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const block = editor.locator('[data-slot="te-trend"]')
      await expect(block).toBeVisible({ timeout: 20_000 })
      const charts = block.locator('[data-slot="te-trend-chart"]')
      await expect(charts).toHaveCount(4)
      await expect(block.locator('[data-field="error-rate"]')).toHaveAttribute('data-points', '12')
      await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-points', '11')
      await expect(block.locator('[data-field="total-tx"] figcaption')).toHaveText('Toplam işlem adedi (Son 12 Ay)')
      await expect(block.locator('[data-field="outage-errors"] [data-slot="te-trend-note"]'))
        .toContainText(/en az iki ayın rakamı|at least two months/)
      // Çubuklar çizildi; ay adları 12, ilk ayın ve Ocak'ın altında yıl
      await expect(block.locator('[data-field="total-tx"] .recharts-bar-rectangle')).toHaveCount(11, { timeout: 10_000 })
      // Ay adları: genişse 12, dar grafikte bir atlanarak (raporun ayı hep görünür); yıl görünen ilk ayda ve yıl değişince
      const ticks = block.locator('[data-field="total-tx"] [data-slot="te-trend-tick"]')
      await expect.poll(() => ticks.count()).toBeGreaterThanOrEqual(6)
      const tickTexts = await ticks.allTextContents()
      expect(tickTexts.at(-1)).toBe('Eyl')
      expect(tickTexts[0]).toMatch(/^(Eki|Kas)\s*2025$/)
      expect(tickTexts.filter((x) => /2026$/.test(x))).toHaveLength(1)
      if (vp.width >= 1280) expect(tickTexts).toHaveLength(12)
      await page.waitForTimeout(300)
      // Çubuk etiketleri komşusuyla çakışmaz ve grafiğin dışına taşmaz (telefonda yalnız bu ayınki yazılır)
      const labelProblems = await page.evaluate(() => {
        const out = []
        for (const fig of document.querySelectorAll('[data-slot="te-trend-chart"]')) {
          const svg = fig.querySelector('svg.recharts-surface')
          if (!svg) continue
          const s = svg.getBoundingClientRect()
          // Çubuk etiketleri ve ay adları ayrı ayrı: komşusuyla çakışmaz, grafiğin dışına taşmaz
          for (const sel of ['.recharts-label-list text', '[data-slot="te-trend-tick"]']) {
            const boxes = [...fig.querySelectorAll(sel)].filter((n) => n.textContent.trim())
              .map((n) => ({ t: n.textContent, r: n.getBoundingClientRect() })).sort((a, b) => a.r.left - b.r.left)
            for (let i = 0; i < boxes.length; i++) {
              const { t, r } = boxes[i]
              if (r.left < s.left - 1 || r.right > s.right + 1) out.push(`${fig.dataset.field}: ${t} dışarıda`)
              if (i && boxes[i - 1].r.right > r.left - 2) out.push(`${fig.dataset.field}: ${boxes[i - 1].t} / ${t} çakışıyor ${Math.round(boxes[i - 1].r.left)}-${Math.round(boxes[i - 1].r.right)} ${Math.round(r.left)}-${Math.round(r.right)}`)
            }
          }
        }
        return out
      })
      expect(labelProblems, 'çubuk etiketleri').toEqual([])
      if (vp.width < 768) await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-labels', 'current')
      for (const fig of await charts.all()) {
        const r = await fig.boundingBox()
        const b = await block.boundingBox()
        expect(r.x, 'grafik kartın içinde').toBeGreaterThanOrEqual(b.x - 1)
        expect(r.x + r.width, 'grafik kartın içinde').toBeLessThanOrEqual(b.x + b.width + 1)
      }
      expect(await page.evaluate(overflowX), 'yatay taşma (trend)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-trend"]'), 'kırpılan metin (trend)').toEqual([])

      // Bu ayın çubuğu taslaktan: "Toplam işlem" rakamı girilince 12. nokta (raporun ayı) eklenir
      await editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true }).fill('126.000.000')
      await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-points', '12')

      // Görsel: 3000 × 1500 PNG yarım yuvaya göre küçültülür, küçültülmüş dosya yüklenir, not yuvanın altında
      const big = stripedPng(3000, 1500)
      const slot = editor.locator('[data-slot-id="monthly-tx"]')
      await slot.locator('input[type="file"]').setInputFiles({ name: 'buyuk-ekran-goruntusu.png', mimeType: 'image/png', buffer: big })
      await expect.poll(() => uploads.length, { timeout: 15_000 }).toBe(1)
      const up = uploadSizes[0]
      expect(up, 'yüklenen PNG').not.toBeNull()
      expect(up.w, 'yarım yuva genişliği').toBeLessThanOrEqual(900)
      expect(up.w).toBeGreaterThanOrEqual(600)
      expect(Math.abs(up.w / up.h - 2), 'en-boy oranı korunur').toBeLessThan(0.02)
      await expect(slot.locator('[data-slot="te-image-note"]')).toContainText(`3000 × 1500 → ${up.w} × ${up.h}`)

      // PDF yuvaya yüklenmez; ne yapılacağı yuvanın altında
      const other = editor.locator('[data-slot-id="tech-errors"]')
      await other.locator('input[type="file"]').setInputFiles({ name: 'rapor.pdf', mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7\n%rapor') })
      await expect(other.locator('[data-slot="te-image-error"]')).toContainText(/PDF dosyası görsel yuvasına yüklenemez|A PDF file can't go/)
      expect(uploads.length).toBe(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (görsel)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (trend/görsel)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma (görsel)').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/channel-reports-trend-${vp.name}.png` })
      await block.screenshot({ path: `test-results/channel-reports-trend-block-${vp.name}.png` })
    })

    test('Faz B: geçmiş paneli ve şablon sürümleri sığar, uzun adlar kesilmez; yıllara göre liste; ay geçişi', async ({ page }) => {
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      await expect(page.locator('[data-slot="te-report-title"]')).toBeVisible()
      await expect(page.locator('[data-slot="te-nav-prev"]')).toContainText(/August 2026|Ağustos 2026/)
      await page.locator('[data-slot="te-history-open"]').click()
      const sheet = page.locator('[data-slot="te-history"]')
      await expect(sheet.locator('[data-kind="VALUES"]')).toBeVisible()
      await expect(sheet.locator('[data-kind="CREATE"]')).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="te-history"]')).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-history"]')).toEqual([])
      // Panel sağdan kayarak açılır: kenar, canlandırma bitince ölçülür (taşıyorsa yoklama yine düşer)
      await expect.poll(async () => { const b = await sheet.boundingBox(); return Math.round(b.x + b.width) }, { timeout: 4000 })
        .toBeLessThanOrEqual(vp.width + 1)
      expect((await sheet.boundingBox()).x).toBeGreaterThanOrEqual(-1)
      // Değişiklik satırı kendi kabından taşmaz (uzun ad + uzun değer sarar)
      const overflowing = await page.evaluate(() => [...document.querySelectorAll('[data-slot="te-history-change"]')]
        .filter((n) => n.scrollWidth > n.clientWidth + 1).length)
      expect(overflowing).toBe(0)
      await page.keyboard.press('Escape')
      await expect(sheet).toHaveCount(0)
      // Kapanınca odak açan düğmeye döner (inceleme 2026-10-14: sayfanın başına düşüyordu)
      await expect(page.locator('[data-slot="te-history-open"]')).toBeFocused()

      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10')
      const years = page.locator('[data-slot="te-report-year"]')
      await expect(years).toHaveCount(2)
      await expect(years.first()).toHaveAttribute('data-year', '2026')
      expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(0)

      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      await page.locator('[data-slot="te-versions-open"]').click()
      const dlg = page.locator('[data-slot="te-versions"]')
      await expect(dlg.locator('[data-slot="te-version-item"]')).toHaveCount(2)
      await dlg.locator('[data-slot="te-version-item"][data-version="2"]').click()
      await expect(dlg.locator('[data-slot="te-version-load"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="te-version-outline"]')).toBeVisible()
      if (vp.width < 768) {
        // Dar ekran: ayrıntıya geçince odak geri düğmesinde; bölme sonuna kaydırılsa da geri düğmesi görünür (yapışkan)
        const back = dlg.locator('[data-slot="te-versions-back"]')
        await expect(back).toBeFocused()
        const inside = await page.evaluate(() => {
          const pane = document.querySelector('[data-slot="te-version-detail"]')
          pane.scrollTop = pane.scrollHeight
          const p = pane.getBoundingClientRect()
          const b = document.querySelector('[data-slot="te-versions-back"]').getBoundingClientRect()
          return b.top >= p.top - 1 && b.bottom <= p.bottom + 1
        })
        expect(inside).toBe(true)
      }
      expect(await page.evaluate(clipped, '[data-slot="te-versions"]')).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-versions"]')).toEqual([])
      await expect.poll(async () => { const b = await dlg.boundingBox(); return Math.round(b.x + b.width) }, { timeout: 4000 })
        .toBeLessThanOrEqual(vp.width + 1)
      expect((await dlg.boundingBox()).x).toBeGreaterThanOrEqual(-1)
      // Sürüm 3'ün farkı (sürüm 2'ye göre): kaldırılan uzun adlı alan
      // Dar ekranda (md altı) liste ve ayrıntı ayrı görünümler: önce listeye dönülür
      const pick = async (v) => {
        if (vp.width < 768) await dlg.getByRole('button', { name: /Back to the version list|Sürüm listesine dön/ }).click()
        await dlg.locator(`[data-slot="te-version-item"][data-version="${v}"]`).click()
      }
      await pick(3)
      await expect(dlg.locator('[data-slot="te-version-diff"]')).toContainText('Kaldırılan uzun alan adı')
      await pick(2)
      await dlg.locator('[data-slot="te-version-load"]').click()
      await expect(dlg).toHaveCount(0)
      await expect(page.locator('[data-slot="te-versions-open"]')).toBeFocused()
      await expect(page.getByLabel(/Report title|Rapor başlığı/)).toHaveValue(/^Eski rapor başlığı/)
    })
})
}
