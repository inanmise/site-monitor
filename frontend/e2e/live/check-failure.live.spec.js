// CANLI: kontrol geçmişinde HATA TEŞHİSİ (20.109.0) — GERÇEK arayüz + GERÇEK yerel backend. Her tür (Ping, Port, DNS,
// Sayfa Bütünlüğü, Sayfa Hızı, Alan Adı) için yerel veride son 24 saatte (yoksa 30 günde) başarısız kontrolü olan bir izleme
// "yalnız başarısızlar" süzgeciyle açılır; satırın neden rozeti görünür, "Ayrıntıyı göster" ile Neden / Etkisi / Ne yapmalı
// + kayıttaki ham hata paneli açılır.
// Yerel veride başarısız kontrol yoksa o tür açık bir mesajla ATLANIR (sahte veri üretilmez, kontrol tetiklenmez).
import {
  test, expect, liveGate, openApp, apiGet, expectCleanText, stopwatch,
} from './fixtures.js'

const DESKTOP = { width: 1280, height: 800 }

/** Tür → başarısız satır yüklemi + detay penceresinde geçmişin sekmesi (null = ilk sekme). */
// Yüklemler arayüzün `checkFailureModel.isHealthy` kuralının tersi (hangi satırda hücre çizildiğinin aynısı)
const up = (v) => String(v ?? '').toUpperCase()
const HIST_TAB = /^Kontrol Geçmişi|^Check History/i
const TYPES = [
  { type: 'ping', failed: (r) => r.up !== true, historyTab: null },
  { type: 'port', failed: (r) => r.open !== true, historyTab: null },
  { type: 'dns', failed: (r) => String(r.value ?? '') === '', historyTab: null },
  { type: 'page', failed: (r) => !((up(r.status) === 'OK' || up(r.status) === '') && r.ok !== false), historyTab: HIST_TAB },
  { type: 'pagespeed', failed: (r) => r.ok === false, historyTab: HIST_TAB },
  { type: 'domain', failed: (r) => !['OK', 'WARNING', 'CRITICAL'].includes(up(r.status)), historyTab: null },
]

/**
 * Başarısız kontrolü olan ilk izleme: önce son 24 saat (arayüzün varsayılanı), yoksa 30 gün. Sunucunun `status=fail`
 * süzgeci kullanılır — arayüz de aynı süzgeçle (`hst=fail`, `range=<gün>` adres parametreleri) açılır.
 */
async function findFailing(request, c) {
  const list = await apiGet(request, `/api/monitoring/${c.type}`)
  const rows = (Array.isArray(list.json?.data) ? list.json.data : []).filter((m) => Number(m?.id) > 0)
  // Önce DOWN görünenler (geçmişlerinde hata olması en olası)
  rows.sort((a, b) => Number(isDown(b)) - Number(isDown(a)))
  for (const days of [1, 30]) {
    for (const m of rows.slice(0, 15)) {
      const h = await apiGet(request, `/api/monitoring/${c.type}/${m.id}/history?days=${days}&status=fail&page=0&size=10`)
      const items = Array.isArray(h.json?.data?.items) ? h.json.data.items : []
      const bad = items.find(c.failed)
      if (bad) return { monitor: m, row: bad, days }
    }
  }
  return null
}
const isDown = (m) => ['down', 'DOWN', 'error', 'na'].includes(m?.status) || m?.up === false || m?.open === false || m?.ok === false

test.describe('kontrol geçmişi hata teşhisi — canlı', () => {
  liveGate()

  for (const c of TYPES) {
    test(`${c.type}: başarısız satır → neden rozeti → Neden / Etkisi / Ne yapmalı + ham hata`, async ({ page }) => {
      const done = stopwatch(`hata teşhisi ${c.type}`)
      await page.setViewportSize(DESKTOP)
      const hit = await findFailing(page.request, c)
      test.skip(!hit, `yerel veride son 30 günde başarısız ${c.type} kontrolü yok — atlandı`)
      console.log(`[live] ${c.type}: izleme #${hit.monitor.id}, ${hit.days} gün (failure_reason=${hit.row.failure_reason ?? '—'})`)

      await openApp(page, `/?tab=${c.type}&monitor=${hit.monitor.id}&hst=fail${hit.days > 1 ? `&range=${hit.days}` : ''}`)
      const dialog = page.getByRole('dialog').first()
      await dialog.waitFor({ timeout: 30_000 })
      if (c.historyTab) await dialog.getByRole('tab', { name: c.historyTab }).click()

      const cell = dialog.locator('[data-slot="chkfail-cell"]').first()
      await cell.waitFor({ timeout: 30_000 })
      const badge = cell.locator('[data-slot="chkfail-badge"]')
      await expect(badge).toBeVisible()
      const code = await cell.getAttribute('data-code')
      expect(code, 'satır neden kodu').toBeTruthy()
      if (hit.row.failure_reason) expect(code, 'arayüz kodu = sunucunun failure_reason').toBe(hit.row.failure_reason)
      const short = (await badge.textContent())?.trim()
      expect(short, 'rozet metni (ham kod değil)').toBeTruthy()
      expect(short).not.toMatch(/^[A-Z_]+$/)   // çevrilmemiş i18n anahtarı / ham kod sızmasın

      await cell.locator('[data-slot="chkfail-toggle"]').click()
      const panel = dialog.locator(`[data-slot="chkfail-panel"][data-code="${code}"]`).first()
      await expect(panel).toBeVisible()
      await expect(panel.locator('[data-slot="chkfail-why"]')).toContainText('Neden')
      await expect(panel.locator('[data-slot="chkfail-effect"]')).toContainText('Etkisi')
      await expect(panel.locator('[data-slot="chkfail-fix"]')).toContainText('Ne yapmalı')
      for (const slot of ['chkfail-why', 'chkfail-effect', 'chkfail-fix']) {
        const txt = (await panel.locator(`[data-slot="${slot}"]`).textContent()) || ''
        expect(txt.replace(/^(Neden|Etkisi|Ne yapmalı)/, '').trim().length, `${slot} boş değil`).toBeGreaterThan(10)
        expect(txt, `${slot}: çevrilmemiş anahtar`).not.toMatch(/chkfail\.|chkhist\.|\{\d\}/)
      }
      // Ham hata: kayıttaki error metni teknik ayrıntıda (kısaltılmış olabilir — ilk 30 karakter)
      // Ham hata: kayıttaki hata metni (Sayfa Hızı'nda error_message) teknik ayrıntıda; metinsiz satırda (ör. bozuk kaynak
      // ya da hata sütunundan önceki eski kayıt) blok çizilmez
      const raw = c.type === 'pagespeed' ? hit.row.error_message : hit.row.error
      if (raw) await expect(panel.locator('[data-slot="chkfail-technical"]')).toContainText(String(raw).trim().slice(0, 30))
      if (hit.row.failure_reason == null) await expect(panel.locator('[data-slot="chkfail-legacy"]')).toBeVisible()
      // Ham hata bloğu dışındaki metin (Neden/Etkisi/Ne yapmalı + ayrıntılar) sözleşme kırığı taşımaz
      for (const slot of ['chkfail-why', 'chkfail-effect', 'chkfail-fix', 'chkfail-details']) {
        const part = panel.locator(`[data-slot="${slot}"]`)
        if (await part.count()) await expectCleanText(part, `${c.type} ${slot}`)
      }
      done()
    })
  }
})
