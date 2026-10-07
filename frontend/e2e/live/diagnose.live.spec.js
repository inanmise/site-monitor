// CANLI: "Uçtan uca tanıla" pencereleri (20.109.0) — Ping / Port / DNS (NetDiagnoseDialog) ve Sayfa Bütünlüğü / Sayfa Hızı
// (HttpDiagnoseDialog varyantı). GERÇEK arayüz + GERÇEK yerel backend: izlemenin detay penceresinden tanılama açılır,
// başlangıç ekranı görünür (Ping'de traceroute anahtarı KAPALI kalır — açılmaz), "Tanılamayı başlat" → sunucu gerçekten
// koşar → hüküm + en az bir bulgu/adım → Geçmiş'te kayıtlı çalıştırma (run_id) listelenir → aynı kayıtlı sonuç telefonda
// (390×844, derin bağlantı) ekrana sığar.
// Tanılama yan etkisizdir (kontrol satırı yazmaz, alarm değerlendirmez). Yerel veride `can_diagnose` satırı olmayan tür ATLANIR.
import { test, expect, liveGate, openApp, apiGet, expectCleanText, expectDialogFits, stopwatch } from './fixtures.js'

const DESKTOP = { width: 1280, height: 800 }
const PHONE = { width: 390, height: 844 }

const NET = {
  dialog: '[role="dialog"]:has([data-slot="ndx-body"])', open: '[data-slot="ndx-open"]', start: '[data-slot="ndx-start"]',
  run: '[data-slot="ndx-run"]', result: '[data-slot="ndx-result"]', verdict: '[data-slot="ndx-verdict"]',
  items: '[data-slot="ndx-finding"], [data-slot="ndx-step"]', historyBtn: (d) => d.locator('[data-slot="ndx-history-btn"]'),
  historyRow: '[data-slot="ndx-history-row"]', error: '[data-slot="ndx-error-msg"]',
  clean: ['[data-slot="ndx-findings"]', '[data-slot="ndx-step-trigger"]', '[data-slot="ndx-path-verdict"]'],
}
const HTTP = {
  dialog: '[role="dialog"]:has([data-slot="httpdx-body"])', start: '[data-slot="httpdx-start"]',
  run: '[data-slot="httpdx-run"]', result: '[data-slot="httpdx-result"]', verdict: '[data-slot="httpdx-verdict"]',
  items: '[data-slot="httpdx-findings"] li, [data-slot="httpdx-steps"] > *, [data-slot="pgdx-issue"], [data-slot="psdx-metric"]',
  historyBtn: (d) => d.locator('[data-slot="httpdx-actions"]').getByRole('button', { name: /^(Geçmiş|History)$/ }),
  historyRow: '[data-slot="httpdx-history-row"]', error: '[data-slot="httpdx-error-msg"]',
  clean: ['[data-slot="httpdx-findings"]', '[data-slot="httpdx-decision"]', '[data-slot="httpdx-path-card"]'],
}

// param = kayıtlı çalıştırmanın derin bağlantı adı (sayfa adrese yazar, açılışta okur)
const CASES = [
  { type: 'ping', ui: NET, open: '[data-slot="ndx-open"]', param: 'pgdx', path: (id) => `/api/monitoring/ping/${id}/diagnose` },
  { type: 'port', ui: NET, open: '[data-slot="ndx-open"]', param: 'ptdx', path: (id) => `/api/monitoring/port/${id}/diagnose` },
  { type: 'dns', ui: NET, open: '[data-slot="ndx-open"]', param: 'dndx', path: (id) => `/api/monitoring/dns/${id}/diagnose` },
  { type: 'page', ui: HTTP, open: '[data-slot="pgdx-open"]', param: 'pidx', path: (id) => `/api/monitoring/page/${id}/diagnose` },
  { type: 'pagespeed', ui: HTTP, open: '[data-slot="psdx-open"]', param: 'psdx', path: (id) => `/api/monitoring/pagespeed/${id}/diagnose` },
]

test.describe('uçtan uca tanılama — canlı', () => {
  liveGate()

  for (const c of CASES) {
    test(`${c.type}: tanıla → hüküm + bulgu/adım → geçmişte kayıtlı`, async ({ page }) => {
      test.setTimeout(240_000)
      const done = stopwatch(`tanılama ${c.type}`)
      await page.setViewportSize(DESKTOP)
      const list = await apiGet(page.request, `/api/monitoring/${c.type}`)
      const rows = (Array.isArray(list.json?.data) ? list.json.data : [])
        .filter((m) => Number(m?.id) > 0 && m.can_diagnose === true && m.active !== false)
      test.skip(!rows.length, `yerel veride tanılanabilir (can_diagnose) ${c.type} izlemesi yok — atlandı`)
      const m = rows[0]
      console.log(`[live] ${c.type}: izleme #${m.id}`)

      await openApp(page, `/?tab=${c.type}&monitor=${m.id}`)
      const detail = page.getByRole('dialog').first()
      await detail.waitFor({ timeout: 30_000 })
      const opener = detail.locator(c.open).first()
      await expect(opener, '"Uçtan uca tanıla" düğmesi').toBeVisible({ timeout: 20_000 })
      await expect(opener).toHaveAttribute('aria-label', /Uçtan uca tanıla/)
      await opener.click()

      const dlg = page.locator(c.ui.dialog)
      await dlg.locator(c.ui.start).waitFor({ timeout: 15_000 })
      if (c.type === 'ping') {
        // traceroute isteğe bağlı ve KAPALI kalır (yavaş + ağ dışı yük) — yalnız varsayılanı doğrulanır
        await expect(dlg.locator('[data-slot="ndx-traceroute"]')).toHaveAttribute('data-on', 'false')
      }

      const resp = page.waitForResponse((r) => new URL(r.url()).pathname === c.path(m.id) && r.request().method() === 'POST',
        { timeout: 200_000 })
      await dlg.locator(c.ui.run).click()
      const r = await resp
      const body = await r.json().catch(() => null)
      expect(r.status(), `${c.type} tanılama HTTP: ${JSON.stringify(body?.error || body?.code || '')}`).toBe(200)
      expect(body?.success, `${c.type} tanılama success`).toBe(true)
      if (c.type === 'ping') expect(body?.data?.options?.traceroute ?? false, 'traceroute çalışmadı').toBe(false)
      const runId = body?.data?.run_id
      expect(runId, 'kayıtlı çalıştırma kimliği (run_id)').toBeTruthy()

      await dlg.locator(c.ui.result).waitFor({ timeout: 30_000 })
      await expect(dlg.locator(c.ui.error)).toHaveCount(0)
      const verdict = dlg.locator(c.ui.verdict).first()
      await expect(verdict).toBeVisible()
      await expect(verdict).toHaveAttribute('data-code', /\S/)
      const vtext = (await verdict.textContent()) || ''
      expect(vtext, 'hüküm metni: çevrilmemiş anahtar').not.toMatch(/(ndx|httpdx|pgdx|psdx)\.[a-z]/i)
      expect(await dlg.locator(c.ui.items).count(), 'en az bir bulgu / adım').toBeGreaterThan(0)
      // Hüküm, bulgular ve adım başlıkları gerçek yanıtla temiz (ham döküm hariç — orada her metin olabilir)
      await expectCleanText(verdict, `${c.type} hüküm`)
      for (const sel of c.ui.clean) {
        for (const part of await dlg.locator(sel).all()) await expectCleanText(part, `${c.type} ${sel}`)
      }

      // Geçmiş: az önceki çalıştırma listede; geçmişten açılan KAYITLI sonuç aynı hükmü verir
      const code = await verdict.getAttribute('data-code')
      await c.ui.historyBtn(dlg).click()
      const hrow = dlg.locator(`${c.ui.historyRow}[data-run="${runId}"]`)
      await expect(hrow).toBeVisible({ timeout: 20_000 })
      await expectCleanText(hrow, `${c.type} geçmiş satırı`)
      await hrow.click()
      await expect(dlg.locator(c.ui.verdict).first()).toHaveAttribute('data-code', code, { timeout: 20_000 })

      // Telefon (390×844): aynı KAYITLI sonuç derin bağlantıyla açılır — GERÇEK veriyle (uzun adresler, gerçek bulgular)
      // pencere tam ekran, taşma ve yatay kayma yok. Yeni tanılama koşturulmaz.
      await page.setViewportSize(PHONE)
      await openApp(page, `/?tab=${c.type}&monitor=${m.id}&${c.param}=${runId}`)
      const stored = page.locator(c.ui.dialog)
      await stored.locator(`${c.ui.result}[data-stored="true"]`).waitFor({ timeout: 30_000 })
      await expect(stored.locator(c.ui.verdict).first()).toHaveAttribute('data-code', code)
      await expectDialogFits(page, stored, PHONE, `${c.type} kayıtlı sonuç @390`)
      expect(Math.round((await stored.boundingBox()).width), 'telefonda tam ekran').toBe(PHONE.width)
      done()
    })
  }
})
