// CANLI uçtan uca: kanal teknik hata raporu — otomatik trend + görsel hazırlama (Faz C, 2026-10-15). Route mock'u YOK:
// gerçek kanal / rapor açılır, önceki ayların rakamları API ile girilir, rapor ekranındaki trend SUNUCUNUN verisiyle
// çizilir, büyük görsel GERÇEK tarayıcı canvas'ında küçültülüp GERÇEK sunucu doğrulayıcısından geçer, PDF üretilir,
// şablonda trend seçimi kaydedilir. Hiçbir şey gönderilmez (e-posta yok). Sonda kanal (raporları, görselleri, geçmişi
// ile) KALICI silinir — yalnız `e2e-live-` önekli kanal.
import zlib from 'node:zlib'
import {
  test, expect, liveGate, liveRequest, openApp, apiCall, apiGet, expectCleanText, expectFits, stopwatch, LIVE_PREFIX,
} from './fixtures.js'

// ── Görsel (çizgili RGB PNG) ─────────────────────────────────────────────────────────────────────
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
const pngSize = (body) => (body.length >= 24 && body[0] === 0x89 ? { w: body.readUInt32BE(16), h: body.readUInt32BE(20) } : null)

// ── Paylaşılan durum ─────────────────────────────────────────────────────────────────────────────
const state = { channelId: null, channelName: null, period: null, reports: {}, teamName: null }
const monthMinus = ({ year, month }, n) => {
  const d = new Date(Date.UTC(year, month - 1 - n, 1))
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 }
}
const TR_MONTHS = ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']
const reportUrl = () => `/?tab=channelreports&te_view=report&te_ch=${state.channelId}&te_rep=${state.reports.cur}`

async function saveValues(request, id, fields) {
  const g = await apiGet(request, `/api/tech-error-reports/reports/${id}`)
  expect(g.status, `rapor #${id} okunamadı`).toBe(200)
  const version = g.json.data.report.version
  const r = await apiCall(request, 'PUT', `/api/tech-error-reports/reports/${id}`, { values: { fields, issues: {}, texts: {} }, version })
  expect(r.status, `rapor #${id} kaydedilemedi: ${JSON.stringify(r.json)?.slice(0, 200)}`).toBe(200)
}

test.describe.configure({ mode: 'serial' })

test.describe('kanal teknik hata raporu — canlı uçtan uca (otomatik trend + görsel)', () => {
  liveGate()

  test.beforeAll(async ({ playwright }) => {
    const request = await liveRequest(playwright)
    try {
      const ov = await apiGet(request, '/api/tech-error-reports/overview')
      expect(ov.status, 'overview').toBe(200)
      state.period = ov.json.data.period
      const team = (ov.json.data.teams || []).find((x) => x.can_edit && x.active !== false)
      expect(team, 'yazılabilir takım yok — canlı koşu kanal açamaz').toBeTruthy()
      state.teamName = team.name
      state.channelName = `${LIVE_PREFIX}trend-${Date.now().toString(36)}`
      const ch = await apiCall(request, 'POST', '/api/tech-error-reports/channels', { team_id: team.id, name: state.channelName, lang: 'tr' })
      expect(ch.status, `kanal açılamadı: ${JSON.stringify(ch.json)?.slice(0, 200)}`).toBe(200)
      state.channelId = ch.json.data.id
      // Kanal şablonu (2026-10-16): varsayılanda trend bölümü YOK ve teknik hata oranı adetlerden hesaplanıyor. Bu koşu
      // Faz C trend akışını sınadığından teknik hata oranı ELLE girilir ve trend bölümü eklenir; kalan oranlar
      // (map yapılmamış / kota / değişim) hesaplı kalır — 6. adım onları sınar
      const tg = await apiGet(request, `/api/tech-error-reports/channels/${state.channelId}/template`)
      expect(tg.status, 'kanal şablonu okunamadı').toBe(200)
      const tpl = JSON.parse(JSON.stringify(tg.json.data.template))
      for (const s of tpl.sections) for (const f of s.fields || []) if (f.id === 'error-rate') delete f.calc
      const at = tpl.sections.findIndex((s) => s.id === 'charts')
      tpl.sections.splice(at, 0, { id: 'auto-trend', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)',
        series: ['error-rate', 'total-tx', 'error-count', 'unmapped-count', 'quota-count'] })
      const tp = await apiCall(request, 'PUT', `/api/tech-error-reports/channels/${state.channelId}/template`,
        { version: tg.json.data.version, template: tpl })
      expect(tp.status, `kanal şablonu kaydedilemedi: ${JSON.stringify(tp.json)?.slice(0, 200)}`).toBe(200)
      // Raporun ayı (dönem) + önceki iki ay; önceki aylara rakam girilir
      const months = { cur: state.period, m1: monthMinus(state.period, 1), m2: monthMinus(state.period, 2) }
      for (const [k, m] of Object.entries(months)) {
        const r = await apiCall(request, 'POST', '/api/tech-error-reports/reports', { channel_id: state.channelId, year: m.year, month: m.month })
        expect(r.status, `rapor açılamadı (${m.year}-${m.month})`).toBe(200)
        state.reports[k] = r.json.data.id
      }
      state.months = months
      await saveValues(request, state.reports.m2, { 'error-rate': 0.61, 'total-tx': 118_400_000, 'error-count': 2400, 'unmapped-count': 310, 'quota-count': 45 })
      await saveValues(request, state.reports.m1, { 'error-rate': 0.48, 'total-tx': 125_430_112, 'error-count': 2950, 'unmapped-count': 0, 'quota-count': 60 })
      console.log(`[live] kanal #${state.channelId} (${state.channelName}), raporlar ${JSON.stringify(state.reports)}`)
    } finally {
      await request.dispose()
    }
  })

  test.afterAll(async ({ playwright }) => {
    if (!state.channelId) return
    const request = await liveRequest(playwright)
    try {
      const d = await apiCall(request, 'DELETE', `/api/tech-error-reports/channels/${state.channelId}`)
      console.log(`[live] kanal #${state.channelId} kalıcı silindi → ${d.status} (${JSON.stringify(d.json?.data)})`)
      expect(d.status).toBe(200)
      const after = await apiGet(request, `/api/tech-error-reports/channels/${state.channelId}/reports`)
      expect(after.status, 'silinen kanal hâlâ okunuyor').toBe(404)
    } finally {
      await request.dispose()
    }
  })

  test('1 · rapor ekranı: trend SUNUCUNUN verisiyle çizilir; bu ayın çubuğu taslaktan; kayıt sonrası yeniden açılışta korunur @1280', async ({ page }) => {
    const done = stopwatch('trend akışı')
    await page.setViewportSize({ width: 1280, height: 900 })
    // Sözleşme: GET /reports/{id} trend bloğu (aylar + değerler)
    const api = await apiGet(page.request, `/api/tech-error-reports/reports/${state.reports.cur}`)
    expect(api.status).toBe(200)
    const trend = api.json.data.trend
    expect(trend?.unavailable, 'trend okunamadı').toBeFalsy()
    expect(trend.months).toHaveLength(12)
    expect(trend.months.at(-1)).toEqual({ year: state.period.year, month: state.period.month })
    expect(trend.values['error-rate'].slice(-3)).toEqual([0.61, 0.48, null])
    expect(trend.values['unmapped-count'].at(-2), '0 sıfırdır, boş değil').toBe(0)

    await openApp(page, reportUrl())
    const block = page.locator('[data-slot="te-trend"]')
    await expect(block).toBeVisible({ timeout: 30_000 })
    await expect(block.locator('[data-slot="te-trend-chart"]')).toHaveCount(5)
    const rate = block.locator('[data-field="error-rate"]')
    await expect(rate).toHaveAttribute('data-points', '2')
    const m1 = state.months.m1
    await expect(rate.locator('li', { hasText: `${TR_MONTHS[m1.month - 1]} ${m1.year}:` })).toHaveText(`${TR_MONTHS[m1.month - 1]} ${m1.year}: %0,48`)
    await expect(block.locator('[data-field="unmapped-count"]')).toHaveAttribute('data-points', '2')
    await expectCleanText(block, 'trend bölümü')

    // Bu ay: rakam girilince 3. nokta; kaydedilir; yeniden açılışta sunucudan gelir
    const editor = page.locator('[data-slot="te-report"]')
    await editor.getByLabel('Teknik hata oranı', { exact: true }).fill('0,42')
    await expect(rate).toHaveAttribute('data-points', '3')
    const saved = page.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/tech-error-reports/reports/${state.reports.cur}`)
    await page.locator('[data-slot="te-save"]').click()
    expect((await saved).status()).toBe(200)
    await page.reload()
    await expect(page.locator('[data-slot="te-trend"] [data-field="error-rate"]')).toHaveAttribute('data-points', '3', { timeout: 30_000 })
    await expectFits(page, '[data-slot="te-report"]', 'rapor 1280')
    done()
  })

  test('2 · görsel: 3000 × 1500 PNG OLDUĞU GİBİ, 7200 × 1800 alan ortalamasıyla 6000 × 1500 PNG; GERÇEK sunucu kabul eder; PDF yüklenmez @1280', async ({ page }) => {
    const done = stopwatch('görsel yükleme')
    await page.setViewportSize({ width: 1280, height: 900 })
    await openApp(page, reportUrl())
    const slot = page.locator('[data-slot-id="annual-summary"]')
    await expect(slot).toBeVisible({ timeout: 30_000 })
    const big = stripedPng(3000, 1500)
    const up = page.waitForResponse((r) => r.request().method() === 'POST' && /\/images\/annual-summary$/.test(new URL(r.url()).pathname))
    await slot.locator('input[type="file"]').setInputFiles({ name: 'buyuk.png', mimeType: 'image/png', buffer: big })
    const res = await up
    expect(res.status(), 'sunucu doğrulayıcısı').toBe(200)
    const body = await res.json()
    // 2026-10-16: çözünürlük korunur — sınır içindeki görsel aynı dosya, aynı ölçü; not yok
    expect({ w: body.data.width, h: body.data.height, bytes: body.data.size_bytes }).toEqual({ w: 3000, h: 1500, bytes: big.length })
    await expect(slot.locator('img')).toBeVisible()
    await expect(slot.locator('[data-slot="te-image-note"]')).toHaveCount(0)
    const img = await page.request.get(`/api/tech-error-reports/images/${body.data.id}`)
    expect(img.status()).toBe(200)
    expect(pngSize(await img.body())).toEqual({ w: 3000, h: 1500 })
    // Sunucu sınırını (6000 px kenar) aşan görsel tarayıcıda alan ortalamasıyla sınıra iner, PNG kalır, sunucu kabul eder
    const wide = page.locator('[data-slot-id="annual-table"]')
    const huge = stripedPng(7200, 1800)
    const up2 = page.waitForResponse((r) => r.request().method() === 'POST' && /\/images\/annual-table$/.test(new URL(r.url()).pathname))
    await wide.locator('input[type="file"]').setInputFiles({ name: 'cok-genis.png', mimeType: 'image/png', buffer: huge })
    const res2 = await up2
    expect(res2.status(), 'sunucu doğrulayıcısı (sınıra inen)').toBe(200)
    const body2 = await res2.json()
    expect({ w: body2.data.width, h: body2.data.height, type: body2.data.content_type }).toEqual({ w: 6000, h: 1500, type: 'image/png' })
    await expect(wide.locator('[data-slot="te-image-note"]')).toContainText('7200 × 1800 → 6000 × 1500')
    // PDF yuvaya yüklenmez — istek bile gitmez
    let posted = 0
    page.on('request', (r) => { if (r.method() === 'POST' && /\/images\/annual-tech-rate$/.test(new URL(r.url()).pathname)) posted++ })
    const other = page.locator('[data-slot-id="annual-tech-rate"]')
    await other.locator('input[type="file"]').setInputFiles({ name: 'rapor.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n%rapor') })
    await expect(other.locator('[data-slot="te-image-error"]')).toContainText('PDF dosyası görsel yuvasına yüklenemez')
    expect(posted).toBe(0)
    done()
  })

  test('3 · PDF: trendli rapor üretilir; rapor okuma ve PDF süreleri ölçülür', async ({ page }) => {
    // Rapor okuma (komşular + trend: kanal satırları BİR kez + ≤ 11 rapor) — 10 ölçüm
    const times = []
    for (let i = 0; i < 10; i++) {
      const t0 = Date.now()
      const r = await apiGet(page.request, `/api/tech-error-reports/reports/${state.reports.cur}`)
      times.push(Date.now() - t0)
      expect(r.status).toBe(200)
    }
    times.sort((a, b) => a - b)
    console.log(`[live] GET /reports/{id} (trendli) ms: medyan ${times[5]}, en çok ${times[9]}`)
    expect(times[5], 'rapor okuma medyanı').toBeLessThan(1500)
    const t0 = Date.now()
    const pdf = await page.request.get(`/api/tech-error-reports/reports/${state.reports.cur}/pdf`)
    const ms = Date.now() - t0
    expect(pdf.status()).toBe(200)
    expect(pdf.headers()['content-type']).toContain('application/pdf')
    const bytes = await pdf.body()
    expect(bytes.subarray(0, 4).toString()).toBe('%PDF')
    console.log(`[live] PDF: ${(bytes.length / 1024).toFixed(0)} KB, ${ms} ms`)
    expect(ms, 'PDF üretim süresi').toBeLessThan(15_000)
  })

  test('4 · şablon: trend seçimi kaydedilir, sürüm farkında görünür; rapor kendi kopyasını korur @1280', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await openApp(page, `/?tab=channelreports&te_view=channel&te_ch=${state.channelId}&te_tab=template`)
    const sec = page.locator('[data-slot="te-tpl-section"][data-kind="trend"]')
    await expect(sec).toBeVisible({ timeout: 30_000 })
    const quota = sec.getByRole('checkbox', { name: /Kota hatası adedi/ })
    await expect(quota).toBeChecked()
    await quota.click()
    const put = page.waitForResponse((r) => r.request().method() === 'PUT' && /\/channels\/\d+\/template$/.test(new URL(r.url()).pathname))
    await page.locator('[data-slot="te-tpl-save"]').click()
    expect((await put).status()).toBe(200)
    const tpl = await apiGet(page.request, `/api/tech-error-reports/channels/${state.channelId}/template`)
    const trendSec = tpl.json.data.template.sections.find((s) => s.kind === 'trend')
    expect(trendSec.series).toEqual(['error-rate', 'total-tx', 'error-count', 'unmapped-count'])
    await page.locator('[data-slot="te-versions-open"]').click()
    const dlg = page.locator('[data-slot="te-versions"]')
    await expect(dlg.locator('[data-slot="te-version-diff"]')).toContainText('Otomatik trend alanları değişti', { timeout: 15_000 })
    await page.keyboard.press('Escape')
    await expectFits(page, '[data-slot="te-template-editor"]', 'şablon 1280')
    // Açılmış rapor kendi kopyasını korur: hâlâ 5 grafik
    const rep = await apiGet(page.request, `/api/tech-error-reports/reports/${state.reports.cur}`)
    expect(rep.json.data.template.sections.find((s) => s.kind === 'trend').series).toHaveLength(5)
  })

  test('5 · telefon 390: trend sığar, taşma yok; yazarken uzun görev ölçülür', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openApp(page, reportUrl())
    const block = page.locator('[data-slot="te-trend"]')
    await expect(block).toBeVisible({ timeout: 30_000 })
    await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-labels', 'current')
    await expectFits(page, '[data-slot="te-report"]', 'rapor 390')
    // Yazarken ana iş parçacığı: 5 grafik her tuşta yeniden çiziliyor mu?
    await page.evaluate(() => {
      window.__lt = []
      new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lt.push(Math.round(e.duration)) }).observe({ entryTypes: ['longtask'] })
    })
    const field = page.locator('[data-slot="te-report"]').getByLabel('Ay içindeki teknik hata adedi', { exact: true })
    await field.click()
    await page.keyboard.type('1234567890', { delay: 30 })
    await page.waitForTimeout(500)
    const lt = await page.evaluate(() => window.__lt)
    console.log(`[live] yazarken uzun görevler (ms): ${JSON.stringify(lt)}`)
    // Gerileme bekçisi (2026-10-15): grafikler her tuşta çizilirken 10 tuşta 10 uzun görev (100–145 ms) vardı; artık
    // yazarken grafik çizilmez, yazma durunca bir kez
    expect(lt.length, 'yazarken uzun görev sayısı').toBeLessThanOrEqual(2)
    // Kaydedilmedi: sonraki koşuları etkilemez (kanal zaten silinecek)
  })

  test('6 · değişiklik geçmişi, aylar arası geçiş, SLA ekran görüntüsü yuvaları (Faz B + şablon kararları) @1280', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    // Geçmiş (API): test 1'in kaydı bir VALUES olayı bıraktı — "Teknik hata oranı" boş → %0,42
    const h = await apiGet(page.request, `/api/tech-error-reports/reports/${state.reports.cur}/history`)
    expect(h.status).toBe(200)
    const values = (h.json.data.events || []).filter((e) => e.kind === 'VALUES')
    expect(values.length, 'değer olayı').toBeGreaterThan(0)
    const change = values.flatMap((e) => e.detail?.changes || []).find((c) => c.label === 'Teknik hata oranı')
    expect(change, 'oran değişikliği geçmişte').toMatchObject({ op: 'set', after: '%0,42' })
    await openApp(page, reportUrl())
    // SLA tablosu elle girilmez: tam genişlik iki ekran görüntüsü yuvası
    for (const id of ['sla-shot-1', 'sla-shot-2']) {
      await expect(page.locator(`[data-slot-id="${id}"]`)).toHaveAttribute('data-width', 'full')
    }
    // Geçmiş paneli
    await page.locator('[data-slot="te-history-open"]').click()
    const sheet = page.locator('[data-slot="te-history"]')
    await expect(sheet.locator('[data-slot="te-history-change"]', { hasText: 'Teknik hata oranı' }).first()).toBeVisible({ timeout: 15_000 })
    await expectCleanText(sheet, 'geçmiş paneli')
    await page.keyboard.press('Escape')
    // Önceki ay: aynı kanalın komşu raporu açılır
    const m1 = state.months.m1
    const prev = page.locator('[data-slot="te-nav-prev"]')
    await expect(prev).toContainText(`${TR_MONTHS[m1.month - 1]} ${m1.year}`)
    await prev.click()
    await expect.poll(() => new URL(page.url()).searchParams.get('te_rep')).toBe(String(state.reports.m1))
    await expect(page.locator('[data-slot="te-report-title"]')).toContainText(`${TR_MONTHS[m1.month - 1]} ${m1.year}`)
    // Önceki ayın trendi: bir önceki ayın (m2) rakamıyla + kendi rakamı
    await expect(page.locator('[data-slot="te-trend"] [data-field="error-rate"]')).toHaveAttribute('data-points', '2', { timeout: 15_000 })
  })

  test('6 · otomatik oranlar (2026-10-16): SUNUCU adetlerden hesaplar (istemcinin oranı yok sayılır); geçen ayla değişim; ekranda adet altında yüzde @1280', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    // Geçen ay (m1) toplam işlem 125.430.112; bu ay 131.701.618 → %5 artış
    await saveValues(page.request, state.reports.cur, { 'error-rate': 0.42, 'total-tx': 131_701_618, 'error-count': 3000,
      'unmapped-count': 300, 'quota-count': 90, 'unmapped-rate': 99, 'tx-increase-pct': 1 })
    const g = await apiGet(page.request, `/api/tech-error-reports/reports/${state.reports.cur}`)
    const f = g.json.data.values.fields
    expect(f['unmapped-rate'], 'map yapılmamış oran sunucuda hesaplanır (istemcinin 99\'u yok sayılır)').toBe(10)
    expect(f['quota-rate']).toBe(3)
    expect(f['tx-increase']).toBe(true)
    expect(f['tx-increase-pct']).toBe(5)
    expect(f['error-rate'], 'bu kanalda teknik hata oranı elle girilir').toBe(0.42)

    await openApp(page, reportUrl())
    const editor = page.locator('[data-slot="te-report"]')
    await expect(editor.getByLabel('Map yapılmamış hataların teknik hatalara oranı', { exact: true })).toHaveText(/^(%10|10%)$/, { timeout: 30_000 })
    await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-calc-derived"]')).toContainText(/%3|3%/)
    await editor.getByLabel('Kota hatası adedi', { exact: true }).fill('150')
    await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-calc-derived"]')).toContainText(/%5|5%/)
    await expectFits(page, '[data-slot="te-report"]', 'oranlar 1280')
  })
})
