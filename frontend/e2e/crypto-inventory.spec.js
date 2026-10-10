// Kripto envanteri / PQC hazırlık (2026-10-10) — Zayıf Algoritma sayfasının sekmesi, mock'lu /api ile telefon (390),
// tablet (768) ve dizüstünde (1280): sayfa yatay taşmaz, görünür hiçbir öğe ekranın sağına taşmaz, telefonda geçiş
// listesi KART (tablo değil), 1280'de tablo; dokunulan denetimlerin hedefi telefonda ≥ 40 px; dışa aktarım menüsü açılır.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const LONG = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
const CATS = ['BROKEN', 'LEGACY', 'MODERN', 'MODERN', 'UNKNOWN', 'PQC_READY']
const BUCKET = { BROKEN: 'RSA_1024', LEGACY: 'RSA_2048', MODERN: 'EC_P256', UNKNOWN: 'UNKNOWN', PQC_READY: 'PQC' }
const BAND = (s) => (s >= 70 ? 'P1' : s >= 50 ? 'P2' : s >= 30 ? 'P3' : 'P4')

function rows() {
  return Array.from({ length: 30 }, (_, i) => {
    const cat = CATS[i % CATS.length]
    const manual = i % 7 === 3
    const score = cat === 'PQC_READY' ? 0 : Math.max(5, 100 - i * 3)
    return {
      rank: i + 1, domain: i === 0 ? LONG : manual ? `keystore-uygulama-${i}-prod` : `host-${i}.example.com`,
      source: manual ? 'MANUAL' : 'NETWORK', manual_version: manual ? 3 : undefined, port: i % 5 === 0 ? 8443 : 443,
      team_id: (i % 3) + 1, team_name: ['Kurumsal Ödeme Sistemleri ve Entegrasyon Takımı', 'Takım B', 'Takım C'][i % 3],
      ug_team_name: i % 4 === 0 ? 'Uygulama Geliştirme Takımı' : null, owner: i % 2 ? 'Platform Ekibi — Gece Vardiyası' : null,
      group_name: i % 3 === 0 ? 'Ödeme Sistemleri' : null, tier: (i % 4) + 1,
      key_algorithm: cat === 'UNKNOWN' ? null : cat === 'PQC_READY' ? 'ML-DSA-65' : cat === 'MODERN' ? 'EC' : 'RSA',
      key_size: cat === 'BROKEN' ? 1024 : cat === 'LEGACY' ? 2048 : cat === 'MODERN' ? 256 : null,
      key_bucket: BUCKET[cat], key_family: 'RSA',
      signature_algorithm: cat === 'BROKEN' ? 'SHA1withRSA' : cat === 'UNKNOWN' ? null : 'SHA256withRSA',
      sig_hash: cat === 'BROKEN' ? 'SHA1' : cat === 'UNKNOWN' ? 'UNKNOWN' : 'SHA256',
      remnants: cat === 'BROKEN' ? ['SHA1_LEAF', 'SHA1_INTERMEDIATE'] : [],
      weak_intermediates: [], intermediate_count: 1, tls_version: manual ? null : 'TLSv1.2', pfs: manual ? null : i % 2 === 0,
      not_after: '2027-01-15T00:00:00', days_remaining: [12, -4, 88, 400, null, 60][i % 6], checked_at: '2026-10-10T07:00:00',
      data_source: cat === 'UNKNOWN' ? 'NONE' : manual ? 'UPLOAD' : 'CHECK',
      pqc: cat === 'PQC_READY' ? 'PQC' : cat === 'UNKNOWN' ? 'UNKNOWN' : 'VULNERABLE', category: cat,
      action: { BROKEN: 'replace', LEGACY: 'reissue', MODERN: 'pqc_plan', UNKNOWN: 'collect', PQC_READY: 'none' }[cat],
      migrate_by: cat === 'LEGACY' ? '2030-12-31' : cat === 'BROKEN' ? '2026-10-10' : null,
      priority: { score, band: cat === 'PQC_READY' ? 'DONE' : BAND(score), exposure: 40, strength: 30, renewal: 20, hndl: 10 },
      exception: i === 1 ? { until: '2026-12-31', expired: false } : undefined,
    }
  })
}

function payload() {
  const r = rows()
  const count = (pred) => r.filter(pred).length
  return {
    generated_at: '2026-10-10T08:00:00', data_as_of: '2026-10-10T07:55:00', oldest_check: '2026-10-09T07:00:00',
    scope: { all: false, teams: [{ id: 1, name: 'Kurumsal Ödeme Sistemleri ve Entegrasyon Takımı' }, { id: 2, name: 'Takım B' }] },
    summary: {
      total: r.length, checked: count((x) => x.data_source !== 'NONE'), unchecked: count((x) => x.data_source === 'NONE'),
      network: count((x) => x.source === 'NETWORK'), manual: count((x) => x.source === 'MANUAL'),
      by_pqc: { VULNERABLE: count((x) => x.pqc === 'VULNERABLE'), HYBRID: 0, PQC: count((x) => x.pqc === 'PQC'), UNKNOWN: count((x) => x.pqc === 'UNKNOWN') },
      by_category: Object.fromEntries(['BROKEN', 'LEGACY', 'MODERN', 'PQC_READY', 'UNKNOWN'].map((c) => [c, count((x) => x.category === c)])),
      by_band: Object.fromEntries(['P1', 'P2', 'P3', 'P4', 'DONE'].map((b) => [b, count((x) => x.priority.band === b)])),
      remnants: { md5_leaf: 0, sha1_leaf: 5, md5_intermediate: 0, sha1_intermediate: 5, sha1_root: 9, chains_examined: 24, affected: 5 },
      vulnerable_expiring_90d: 12, legacy_reissue: 5,
    },
    algorithms: [
      { bucket: 'RSA_1024', family: 'RSA', count: 5, share: 16.7 }, { bucket: 'RSA_2048', family: 'RSA', count: 5, share: 16.7 },
      { bucket: 'EC_P256', family: 'EC', count: 10, share: 33.3 }, { bucket: 'PQC', family: 'PQC', count: 5, share: 16.7 },
      { bucket: 'UNKNOWN', family: 'UNKNOWN', count: 5, share: 16.7 },
    ],
    signatures: [{ hash: 'SHA1', count: 5, share: 16.7, weak: true }, { hash: 'SHA256', count: 20, share: 66.7, weak: false }, { hash: 'UNKNOWN', count: 5, share: 16.7, weak: false }],
    signature_algorithms: [{ label: 'SHA256withRSA', hash: 'SHA256', count: 20 }],
    teams: [1, 2, 3].map((id) => ({
      team_id: id, team_name: ['Kurumsal Ödeme Sistemleri ve Entegrasyon Takımı', 'Takım B', 'Takım C'][id - 1], total: 10, vulnerable: 7, remnants: 2,
      by_category: { BROKEN: 2, LEGACY: 2, MODERN: 3, PQC_READY: 2, UNKNOWN: 1 }, by_band: { P1: 3, P2: 3, P3: 2, P4: 0, DONE: 2 },
      top_score: 100 - id, next_migrate_by: '2026-10-10',
    })),
    unowned: { total: 0 },
    rows: r,
    rule: { exposure: { 1: 40, 2: 25, 3: 10, 4: 5, none: 15 }, strength: { BROKEN: 30, LEGACY: 20, UNKNOWN: 15, MODERN: 10, PQC_READY: 0 },
      renewal: [{ max_days: 30, points: 20 }, { max_days: 90, points: 15 }, { max_days: 180, points: 10 }, { max_days: 365, points: 5 }],
      hndl: { external: 5, no_pfs: 5 }, bands: [{ band: 'P1', min: 70 }, { band: 'P2', min: 50 }, { band: 'P3', min: 30 }, { band: 'P4', min: 0 }], max: 100 },
    thresholds: { rsa_2030_min_bits: 3072, sunset: '2030-12-31', kex_observable: false },
  }
}

async function open(page, vp) {
  await page.setViewportSize({ width: vp.width, height: vp.height })
  await mockApi(page)
  // mockApi'den SONRA kaydedilen yol önce çalışır (Playwright: ters kayıt sırası)
  await page.route((u) => new URL(u).pathname === '/api/crypto-inventory', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: payload() }) }))
  await page.goto('/?tab=weakalgo&ci_view=crypto')
  await page.getByTestId('crypto-inventory').waitFor({ timeout: 30_000 })
  await page.locator('[data-slot="cinv-list"]').waitFor()
}

/** Görünür ve ekranın sağına taşan öğeler — kendi içinde kayan (overflow ≠ visible) ve ekrana sığan kap içindekiler hariç. */
function overflowing(page, vw) {
  return page.evaluate((w) => [...document.querySelectorAll('[data-testid="crypto-inventory"] *, [data-slot="wa-tabs"] *')]
    .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 1 && r.right > w + 1 && getComputedStyle(el).visibility !== 'hidden' })
    .filter((el) => { for (let p = el.parentElement; p; p = p.parentElement) { if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= w + 1) return false } return true })
    .map((el) => `${el.tagName}.${String(el.className).slice(0, 50)}`).slice(0, 8), vw)
}

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }, { name: 'laptop', width: 1280, height: 800 }]) {
  test(`kripto envanteri — ${vp.name} (${vp.width})`, async ({ page }) => {
    await open(page, vp)
    // sayfa düzeyinde yatay kaydırma yok
    const pageOverflow = await page.evaluate(() => document.scrollingElement.scrollWidth - window.innerWidth)
    expect(pageOverflow, 'sayfa yatay taşması (px)').toBeLessThanOrEqual(1)
    expect(await overflowing(page, vp.width)).toEqual([])

    const cards = await page.locator('[data-slot="cinv-card"]').count()
    const tableRows = await page.locator('[data-slot="cinv-row"]').count()
    if (vp.width < 640) {
      expect(cards, 'telefonda kart listesi').toBeGreaterThan(0)
      expect(tableRows, 'telefonda tablo satırı yok').toBe(0)
      // dokunma hedefleri ≥ 40 px: sekmeler, araç çubuğu, süzgeçler, KPI, grafik satırları, puan çipi, sertifika aç, takım "Listele"
      const sel = ['[data-slot="wa-tabs"] [role="tab"]', '[data-slot="cinv-export"]', '[data-slot="cinv-list"] select',
        '[data-slot="cinv-list"] input[type="search"]', '[data-slot="cinv-kpis"] [data-slot="stat-item"]', '[data-slot="cinv-key-row"]',
        '[data-slot="cinv-pqc-row"]', '[data-slot="cinv-card"] [data-slot="cinv-priority"]', '[data-slot="cinv-card"] button[aria-label*="example.com"]',
        '[data-slot="cinv-team-row"] button[aria-pressed]']
      for (const s of sel) {
        const boxes = await page.locator(s).evaluateAll((els) => els.filter((e) => e.getBoundingClientRect().width > 0)
          .map((e) => { const r = e.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)] }))
        expect(boxes.length, `${s} görünür`).toBeGreaterThan(0)
        for (const [w, h] of boxes) {
          expect(h, `${s} yükseklik`).toBeGreaterThanOrEqual(40)
          expect(w, `${s} genişlik`).toBeGreaterThanOrEqual(40)
        }
      }
    } else if (vp.width >= 1280) {
      expect(tableRows, '1280\'de tablo').toBeGreaterThan(0)
    }

    // dışa aktarım menüsü açılır ve ekrana sığar
    await page.locator('[data-slot="cinv-export"]').click()
    const menu = page.getByRole('menu')
    await menu.waitFor()
    const box = await menu.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    expect(await page.getByRole('menuitem').count()).toBe(3)
    await page.keyboard.press('Escape')

    // süzgeç sonrası da taşma yok (çip satırı)
    await page.locator('[data-slot="cinv-key-row"][data-key="EC_P256"]').click()
    await page.locator('[data-chip="bucket"]').waitFor()
    expect(await overflowing(page, vp.width)).toEqual([])
  })
}
