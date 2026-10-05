// Ping / Port / DNS UÇTAN UCA TANILAMA penceresi — mobil web kapısı (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi
// eksik olan … izlemeler için tanılama ekleyelim … shadcn tasarım, mweb responsive"). Her tür için derin bağlantıyla
// (`pgdx` / `ptdx` / `dndx`) KAYITLI bir sonuç telefon (390×844), tablet (768×1024) ve dizüstünde (1280×800) açılır; uzun
// host, çok sayıda IP, uzun DNS cevapları ve uzun döküm satırlarıyla zorlanır:
//   - hüküm + en az bir bulgu görünür;
//   - pencerede ekran dışına taşan öğe yok; pencere görünüm alanına sığar (telefonda tam ekran);
//   - pencere gövdesi ve sayfa YATAY kaymaz;
//   - dokunmatikte başlık / altlık düğmeleri, adım tetikleri ve (Ping başlangıcında) traceroute anahtarının satırı ≥ 40 px.
// jsdom yerleşim yapmaz; bu kapı tarayıcıda ölçer (check-failure-history.spec.js ile aynı ölçüm).
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'
import { dnsStale, pingFiltered, portPathDiffers } from '../src/test/helpers/netDiagnoseFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const DIALOG = '[role="dialog"]:has([data-slot="ndx-body"])'

const LONG_HOST = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test'
const V6 = ['2001:db8:85a3:0000:0000:8a2e:0370:7334', '2001:db8:85a3:0000:0000:8a2e:0370:7335', '2001:db8:85a3:0000:0000:8a2e:0370:7336']
const V4 = ['192.0.2.10', '192.0.2.11', '198.51.100.20', '198.51.100.21', '203.0.113.30', '203.0.113.31']
const LONG_LINE = `* ${LONG_HOST} için uzun bir döküm satırı — vekil yanıtı: HTTP/1.1 403 Forbidden (kurumsal vekil politika kuralı: hedef port izinli listede değil, istek reddedildi)`

/** Ping: uzun host, çok IP, uzun döküm. Monitör kimliği mock listenin ilk satırı (100). */
function pingData() {
  const d = pingFiltered()
  d.monitor = { ...d.monitor, id: 100, host: LONG_HOST }
  d.target = { ...d.target, host: LONG_HOST }
  d.verdict.params.host = LONG_HOST
  d.findings[0].params.host = LONG_HOST
  d.steps[1].detail = { ...d.steps[1].detail, host: LONG_HOST, addresses: [...V4, ...V6] }
  d.transcript = `${d.transcript}${LONG_LINE}\n${LONG_LINE}\n`
  return d
}

/** Port: uzun host, çok denenen IP, uzun durum satırı. */
function portData() {
  const d = portPathDiffers()
  d.monitor = { ...d.monitor, id: 100, host: LONG_HOST }
  d.target = { ...d.target, host: LONG_HOST }
  const tried = [...V4, ...V6].map((ip, i) => ({ ip, ms: i ? 5000 : 7, result: i ? 'timeout' : 'open', error: i ? 'connect timed out after 5000 ms while waiting for SYN-ACK' : null }))
  for (const p of d.paths) {
    if (p.key === 'alternate') p.steps = [{ ...p.steps[0], detail: { ...p.steps[0].detail, tried } }]
  }
  d.findings.push({ code: 'SOME_IPS_DOWN', severity: 'warn', path: 'alternate', params: { down: 8, total: 9, ips: [...V4, ...V6].slice(1).join(', '), port: 5432 } })
  d.transcript = `${d.transcript}\n${LONG_LINE}`
  return d
}

/** DNS: uzun ad, uzun TXT cevapları, çok çözücü. */
function dnsData() {
  const d = dnsStale()
  d.monitor = { ...d.monitor, id: 100, domain: LONG_HOST }
  d.target = { ...d.target, host: LONG_HOST }
  const txt = 'v=spf1 include:_spf.example.test include:mail.example.test ip4:192.0.2.0/24 ip4:198.51.100.0/24 ip6:2001:db8::/32 ~all'
  d.dns.name = LONG_HOST
  d.dns.resolvers = [
    ...d.dns.resolvers,
    ...V4.map((server, i) => ({ server, label: 'propagation', rcode: 'NOERROR', answers: [txt, `${V6[i % 3]}`], ttl: 300, ms: 40 + i, aa: false,
      ad: i % 2 === 0, tc: i === 1, tcp: i === 1, error: null, error_kind: null })),
  ]
  d.findings.push({ code: 'RESOLVERS_DISAGREE', severity: 'warn', path: null, params: { sets: V4.map((s) => `${s}: ${txt}`).join(' | '), count: 3 } })
  return d
}

const CASES = [
  { type: 'ping', param: 'pgdx', runId: 501, data: pingData },
  { type: 'port', param: 'ptdx', runId: 602, data: portData },
  { type: 'dns', param: 'dndx', runId: 703, data: dnsData },
]

/** Tarayıcıda: `sel` kökü içinde görünüm alanının sağına taşan en dıştaki görünür öğeler (kendi kaydırma kabı dışında). */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push({ el: `${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''}`, right: Math.round(r.right),
      text: (el.textContent || '').trim().slice(0, 40) })
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

for (const vp of VIEWPORTS) {
  test.describe(`uçtan uca tanılama (ping/port/dns) @${vp.name}`, () => {
    test.use({ hasTouch: vp.width < 1024 })

    for (const c of CASES) {
      test(`${c.type}: kayıtlı sonuç ekrana sığar, yatay kayma yok @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
        const touch = vp.width < 1024
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page, { monitors: { ...MONITORS, [c.type]: MONITORS[c.type].map((m) => ({ ...m, can_diagnose: true })) } })
        const data = c.data()
        await page.route((u) => new RegExp(`^/api/monitoring/${c.type}/\\d+/diagnose/history/\\d+$`).test(new URL(u).pathname),
          json({ success: true, data }))
        await page.route((u) => new RegExp(`^/api/monitoring/${c.type}/\\d+/diagnose/history$`).test(new URL(u).pathname),
          json({ success: true, data: [{ id: c.runId, started_at: data.started_at, executed_by: 'operator1', verdict_code: data.verdict.code,
            verdict_status: data.verdict.status, route: data.route.own, target: LONG_HOST, findings: data.findings.length,
            traceroute: c.type === 'ping', duration_ms: data.duration_ms }] }))
        await page.goto(`/?tab=${c.type}&monitor=100&${c.param}=${c.runId}`)
        const dlg = page.locator(DIALOG)
        await dlg.locator('[data-slot="ndx-result"][data-stored="true"]').waitFor({ timeout: 20_000 })

        // hüküm + en az bir bulgu görünür
        await expect(dlg.locator('[data-slot="ndx-verdict"]')).toBeVisible()
        await expect(dlg.locator('[data-slot="ndx-verdict"]')).toHaveAttribute('data-code', data.verdict.code)
        await expect(dlg.locator('[data-slot="ndx-finding"]').first()).toBeVisible()

        const check = async (stage) => {
          await page.waitForTimeout(400)
          const m = await page.evaluate(measure, DIALOG)
          expect(m.offenders, `${c.type} ${stage} @${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
          expect(m.pageOverflow, `${c.type} ${stage} @${vp.name}: sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
          const box = await dlg.boundingBox()
          expect(box.x, `${c.type} ${stage} @${vp.name}: pencere solda taşıyor`).toBeGreaterThanOrEqual(-1)
          expect(box.x + box.width, `${c.type} ${stage} @${vp.name}: pencere sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
          expect(box.y + box.height, `${c.type} ${stage} @${vp.name}: pencere altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
          const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').first().evaluate((el) => el.scrollWidth - el.clientWidth)
          expect(hScroll, `${c.type} ${stage} @${vp.name}: pencere gövdesi yatay kayıyor (px)`).toBeLessThanOrEqual(1)
        }
        await check('sonuç')
        if (vp.name === 'phone') {
          const box = await dlg.boundingBox()
          expect(Math.round(box.width), `${c.type}: telefonda tam ekran`).toBe(vp.width)
        }

        // kapalı bir adımı aç (ayrıntı ızgarası + nesne dizisi satırları) ve yeniden ölç
        const closed = dlg.locator('[data-slot="ndx-step"][data-state="closed"] [data-slot="ndx-step-trigger"]').first()
        if (await closed.count()) {
          await closed.scrollIntoViewIfNeeded()
          if (touch) await closed.tap()
          else await closed.click()
          await page.waitForTimeout(300)
          await check('adım açık')
        }

        if (c.type === 'port') {
          await expect(dlg.locator('[data-slot="ndx-path"]')).toHaveCount(2)
          const tab = dlg.getByRole('tab', { name: /Öteki yol|Other route/ })
          if (touch) await tab.tap()
          else await tab.click()
          const connect = dlg.locator('[data-slot="ndx-steps"][data-path="alternate"] [data-step="connect"] [data-slot="ndx-step-trigger"]')
          await connect.waitFor()
          if (touch) await connect.tap()
          else await connect.click()
          await dlg.locator('[data-slot="ndx-steps"][data-path="alternate"] [data-key="tried"]').waitFor()
          await check('öteki yol + denenen IPler')
        }
        if (c.type === 'dns') {
          const view = await dlg.locator('[data-slot="ndx-dns"]').getAttribute('data-view')
          if (vp.name === 'phone') expect(view, 'DNS telefonda kart').toBe('cards')
          if (vp.name === 'desktop') expect(view, 'DNS geniş ekranda tablo').toBe('table')
          await dlg.locator('[data-slot="ndx-dns-resolvers"]').scrollIntoViewIfNeeded()
          await check('dns satırları')
        }

        if (touch) {
          // dokunma hedefleri ≥ 40 px: başlık eylemleri, altlık düğmeleri, adım tetikleri
          const targets = [
            ...(await dlg.locator('[data-slot="ndx-actions"] button').all()),
            dlg.locator('[data-slot="ndx-run"]'),
            dlg.locator('[data-slot="ndx-step-trigger"]').first(),
          ]
          for (const loc of targets) {
            await loc.scrollIntoViewIfNeeded()
            const b = await loc.boundingBox()
            expect(b.height, `${c.type} dokunma hedefi yüksekliği @${vp.name}`).toBeGreaterThanOrEqual(39)
            expect(b.width, `${c.type} dokunma hedefi genişliği @${vp.name}`).toBeGreaterThanOrEqual(39)
          }
        }

        // başlık eylemleri ve altlık düğmesi ekranda
        for (const sel of ['[data-slot="ndx-actions"]', '[data-slot="ndx-run"]']) {
          const b = await dlg.locator(sel).boundingBox()
          expect(b.x + b.width, `${sel} @${vp.name}`).toBeLessThanOrEqual(vp.width + 1)
        }

        // Ping başlangıç ekranı: pencereyi kapat, başlıktan yeniden aç → traceroute anahtarı satırı + ölçüm (koşu YOK)
        if (c.type === 'ping') {
          await dlg.getByRole('button', { name: /^(Kapat|Close)$/ }).last().click()
          await expect(dlg).toHaveCount(0)
          await page.locator('[data-slot="ndx-open"]').click()
          await dlg.locator('[data-slot="ndx-start"]').waitFor()
          const tr = dlg.locator('[data-slot="ndx-traceroute"]')
          await expect(tr).toBeVisible()
          if (touch) {
            const b = await tr.boundingBox()
            expect(b.height, `traceroute anahtarı satırı @${vp.name}`).toBeGreaterThanOrEqual(39)
          }
          await check('başlangıç')
        }
      })
    }
  })
}
