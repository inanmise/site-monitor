// İzleme kartları — uzun BAŞLIK, izleme ADI ve kompakt alt satır KESİLMEZ, sarar (2026-11-14, kullanıcı kararı: "adlar ve uzun
// metin kesilmez"). Önce yalnız HTTP kartında yapıldı (e2e/http-card-title.spec.js); şimdi her izleme kartında paylaşılan
// MonitorCardTitle'ın VARSAYILANI. Eskiden URL / host / alan adı tek satıra kırpılıyordu ("odeme-servisleri-yedek-bol…"),
// Alan Adı ve Sentetik başlıkları iki satıra sınırlıydı; tam metin yalnız fareyle `title`'da görünüyordu (dokunmatikte hiç).
//
// jsdom yerleşim yapmaz — bu kapı GERÇEK tarayıcıda, on bir kart türünde, Zengin ve Kompakt yoğunlukta (Uptime'da yoğunluk
// seçici yok, yalnız Zengin), 390 / 768 dokunmatik ve 1280 genişlikte ölçer:
//   - başlık metni ve ad / kompakt alt satır kendi kutusuna sığar (scrollWidth ≤ clientWidth), üç nokta yok, satır sınırı
//     (`-webkit-line-clamp`) yok, `white-space: nowrap` yok; kartın içinde kalır; sayfa yatay taşmaz;
//   - uzun başlık ve uzun ad telefonda birden çok satıra iner (gerçekten sardığının kanıtı);
//   - başlığın yanındaki kopyala düğmesi dokunmatikte ≥ 40 px ve dikeyde başlığın yüksekliği içinde kalır.
// Bilinçli tek satırlık NEDEN / özet satırları (`*-reason`, `*-why`), ölçü değerleri, rozetler ve alt çubuk zamanı kapsam dışı.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const LONG_HOST = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
// Gerçek uzunlukta ad: boşluklu sözcükler + boşluksuz uzun host (her ikisi de sarmalı) — AD tarzı birim adı
const LONG_NAME = `Ödeme servisleri yedek bölge 2 — ${LONG_HOST} uç noktası (Kurumsal ve Bireysel Bankacılık Ağ Operasyonları / Takım A)`
const LONG_SCENARIO = MONITORS.scripted[2].name
const LONG_DBQ = MONITORS.dbquery[1].name

// Ortak mock'a dokunmadan (başka tarayıcı testleri aynı veriyi kullanır) yalnız bu kapı için uzun ad / hedef eklenir:
// her türün 3. satırı (i = 2) uzun host'u taşır; adı olmayan türlere (ping, port, dns, domain) de uzun ad verilir.
const withLongName = (list) => list.map((m, i) => (i === 2 ? { ...m, name: LONG_NAME } : m))
const MON = {
  ...MONITORS,
  http: withLongName(MONITORS.http),
  keyword: withLongName(MONITORS.keyword),
  page: withLongName(MONITORS.page),
  ping: withLongName(MONITORS.ping),
  port: withLongName(MONITORS.port),
  dns: withLongName(MONITORS.dns),
  domain: withLongName(MONITORS.domain),
  scripted: MONITORS.scripted.map((m, i) => (i === 2
    ? { ...m, env: [{ name: 'BASE_URL', secret: false, value: `https://${LONG_HOST}/giris/kullanici?lang=tr` }] } : m)),
}

/**
 * Tür → { başlık metni (kartı bulmak için), ad satırının seçicisi (Zengin / Kompakt; yoksa null), yoğunluk seçici var mı }.
 * Ad seçicisi kartın içinde aranır. Kompakt'ta HTTP / Anahtar Kelime / Ping adı CardCompactSub'da aynı kancayla durur;
 * Port'ta kompakt uç nokta satırında; Alan Adı'nın adı yalnız Zengin'de; Sentetik'te başlığın altındaki hedef host.
 */
const TYPES = [
  { tab: 'http', title: LONG_HOST, name: '[data-slot="http-name"]', compactName: '[data-slot="card-compact-sub"] [data-slot="http-name"]' },
  { tab: 'keyword', title: LONG_HOST, name: '[data-slot="keyword-name"]', compactName: '[data-slot="card-compact-sub"] [data-slot="keyword-name"]' },
  { tab: 'page', title: LONG_HOST, name: '[data-slot="page-name"]', compactName: '[data-slot="page-name"]' },
  { tab: 'pagespeed', title: LONG_HOST, name: null, compactName: null },
  { tab: 'ping', title: LONG_HOST, name: '[data-slot="ping-name"]', compactName: '[data-slot="card-compact-sub"] [data-slot="ping-name"]' },
  { tab: 'port', title: LONG_HOST, name: '[data-slot="port-name"]', compactName: '[data-slot="port-endpoint"] [data-slot="port-name"]' },
  { tab: 'dns', title: LONG_HOST, name: '[data-slot="dns-name"]', compactName: '[data-slot="dns-name"]' },
  { tab: 'domain', title: LONG_HOST, name: '[data-slot="domain-name"]', compactName: null },
  { tab: 'scripted', title: LONG_SCENARIO, name: '[data-slot="scripted-target"] span.font-mono', compactName: '[data-slot="scripted-target"] span.font-mono', nameText: LONG_HOST },
  { tab: 'dbquery', title: LONG_DBQ, name: null, compactName: null },
  { tab: 'uptime', title: LONG_HOST, name: null, compactName: null, noDensity: true },
]

const VIEWPORTS = [
  { name: 'telefon', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'dizüstü', width: 1280, height: 800, touch: false },
]

/** Tarayıcıda: kartın başlık metni, ad satırı ve başlığın yanındaki kopyala düğmesi ölçüleri. */
function measure(card, nameSel) {
  const cr = card.getBoundingClientRect()
  const box = (n) => {
    if (!n) return null
    const r = n.getBoundingClientRect()
    const s = getComputedStyle(n)
    return {
      text: n.textContent, scrollW: n.scrollWidth, clientW: n.clientWidth, height: r.height, top: r.top, bottom: r.bottom,
      lineHeight: parseFloat(s.lineHeight) || parseFloat(s.fontSize) * 1.25,
      whiteSpace: s.whiteSpace, textOverflow: s.textOverflow, lineClamp: s.getPropertyValue('-webkit-line-clamp') || 'none',
      inside: r.left >= cr.left - 1 && r.right <= cr.right + 1,
    }
  }
  const btn = card.querySelector('[data-monitor-open]')
  const title = btn?.querySelector(':scope > span') || null
  // kopyala düğmesi: başlıkla AYNI satırdaki (aynı ebeveyn) diğer düğme — Sayfa Hızı / Sentetik / dbquery / Uptime'da yok
  const copy = btn ? [...btn.parentElement.children].find((c) => c !== btn && c.tagName === 'BUTTON') : null
  const cb = copy ? copy.getBoundingClientRect() : null
  return {
    title: box(title), name: nameSel ? box(card.querySelector(nameSel)) : null,
    copy: cb ? { w: cb.width, h: cb.height, mid: cb.top + cb.height / 2 } : null,
    pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  }
}

function expectNotClipped(b, what) {
  expect(b, `${what} bulunamadı`).not.toBeNull()
  expect(b.whiteSpace, `${what} tek satıra zorlanmamalı`).not.toBe('nowrap')
  expect(b.textOverflow, `${what} üç noktayla kesiliyor`).not.toBe('ellipsis')
  expect(b.lineClamp, `${what} satır sınırı taşıyor`).toBe('none')
  expect(b.scrollW, `${what} kendi kutusunda kırpılıyor`).toBeLessThanOrEqual(b.clientW + 1)
  expect(b.inside, `${what} kartın dışına taşıyor`).toBe(true)
}

for (const vp of VIEWPORTS) {
  test.describe(`Kart başlığı / adı sarar — ${vp.name} ${vp.width}×${vp.height}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    for (const type of TYPES) {
      test(`${type.tab}: uzun başlık ve ad kesilmez (Zengin${type.noDensity ? '' : ' + Kompakt'})`, async ({ page }) => {
        await mockApi(page, { monitors: MON })
        await page.goto(`/?tab=${type.tab}`)
        const cards = page.locator('.upt-grid [data-slot="card"]')
        await expect(cards.first()).toBeVisible({ timeout: 20_000 })
        await page.evaluate(() => document.fonts.ready)
        const card = cards.filter({ has: page.locator('[data-monitor-open]', { hasText: type.title }) }).first()
        await expect(card).toBeVisible()

        const densities = type.noDensity ? ['rich'] : ['rich', 'compact']
        for (const density of densities) {
          if (density === 'compact') {
            // yoğunluk seçicinin İLK öğesi Kompakt (CardDensityToggle); seçim sayfada kalır, ızgara data-density alır
            await page.locator('[data-slot="card-density-toggle"] button').first().click()
            await expect(page.locator('.upt-grid').first()).toHaveAttribute('data-density', 'compact')
            await expect(card).toHaveAttribute('data-density', 'compact')
          }
          const label = `${type.tab} ${density}`
          const nameSel = density === 'compact' ? type.compactName : type.name
          const m = await card.evaluate(measure, nameSel)

          expectNotClipped(m.title, `${label}: başlık metni`)
          expect(m.title.text, `${label}: başlık tam metni`).toContain(type.title)
          if (nameSel) {
            expectNotClipped(m.name, `${label}: ad satırı`)
            expect(m.name.text, `${label}: ad tam metni`).toBe(type.nameText || LONG_NAME)
          }
          expect(m.pageOverflow, `${label}: sayfa yatay taşıyor (px)`).toBeLessThanOrEqual(1)
          // 390 px'te bu uzunlukta metin tek satıra sığmaz — sardığının kanıtı: birden çok satır yüksekliği
          if (vp.width === 390) {
            expect(m.title.height, `${label}: uzun başlık telefonda sarmalı`).toBeGreaterThan(m.title.lineHeight * 1.5)
            if (nameSel) expect(m.name.height, `${label}: uzun ad telefonda sarmalı`).toBeGreaterThan(m.name.lineHeight * 1.5)
          }
          if (m.copy) {
            if (vp.touch) {
              expect(m.copy.w, `${label}: kopyala dokunma genişliği`).toBeGreaterThanOrEqual(40)
              expect(m.copy.h, `${label}: kopyala dokunma yüksekliği`).toBeGreaterThanOrEqual(40)
            }
            // sarılmış başlığın yanında kopyala düğmesi dikeyde başlık metninin yüksekliği içinde kalır (kaymaz)
            expect(m.copy.mid, `${label}: kopyala düğmesi başlığın üstünde`).toBeGreaterThanOrEqual(m.title.top - 1)
            expect(m.copy.mid, `${label}: kopyala düğmesi başlığın altında`).toBeLessThanOrEqual(m.title.bottom + 1)
          }
        }
      })
    }
  })
}
