import { test, expect } from '@playwright/test'

/**
 * "Kişi Ekle" modalı hiçbir genişlikte KUTUNUN DIŞINA taşmaz.
 *
 * <p>Kullanıcı, sağ sütunun (Takım / Asgari Alarm Seviyesi / Webhook Türü) modalın beyaz
 * alanının dışına kaydığını bildirdi. Bu sınıf jsdom'da GÖRÜNMEZ — düzen hesaplanmadığı için
 * 1747 vitest testi yeşil kalır. Ölçüm ancak gerçek tarayıcıda yapılabilir; bu suite tam olarak
 * bunun için var (bkz. playwright.config.js gerekçesi).
 *
 * <p>Ölçülen şey görünüş değil, GEOMETRİ: her form alanının sağ kenarı modal kutusunun iç
 * kenarını geçmemeli ve sayfa yatay kaymamalı. Bu, ekran görüntüsü karşılaştırmasına göre
 * kırılgan değildir — yazı tipi/tema değişse de doğru kalır.
 */

const FIELDS = ['f-user', 'f-team', 'f-role', 'f-level', 'f-webhook', 'f-webhook-type'];

/** Modal kutusunun İÇ (padding hariç) sağ kenarı. */
async function contentRight(page) {
  return page.evaluate(() => {
    const box = document.querySelector('.modal-box');
    const r = box.getBoundingClientRect();
    const pad = parseFloat(getComputedStyle(box).paddingRight) || 0;
    return r.right - pad;
  });
}

for (const width of [1440, 1024, 820, 600, 390]) {
  test(`modal alanlari kutunun disina tasmaz @ ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/e2e/harness/contact-modal.html');
    await page.waitForSelector('.modal-box');

    const limit = await contentRight(page);

    for (const id of FIELDS) {
      const el = page.getByTestId(id);
      if (await el.count() === 0) continue;
      const right = await el.evaluate(n => n.getBoundingClientRect().right);
      // 1px tolerans: alt piksel yuvarlamasi taşma sayılmasın.
      expect(right, `${id} alani modal kutusunun disina tasiyor`).toBeLessThanOrEqual(limit + 1);
    }
  });
}

test('sayfa yatay kaymaz (govde tasmasi yok)', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.goto('/e2e/harness/contact-modal.html');
  await page.waitForSelector('.modal-box');

  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'sayfa yatay kayiyor - bir ogenin genisligi tasiyor').toBeLessThanOrEqual(1);
});

test('uzun kullanici metni alani sismez: secici KISALTILARAK gosterilir', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.goto('/e2e/harness/contact-modal.html');
  await page.waitForSelector('.modal-box');

  // Etiket metni kutusundan genisse ellipsis devrededir; asil sozlesme, tetikleyicinin
  // sutun genisligini ASMAMASI.
  const { triggerRight, columnRight } = await page.evaluate(() => {
    const label = document.querySelector('[data-testid="f-user"]');
    const trigger = label.querySelector('.ss-trigger');
    return {
      triggerRight: trigger.getBoundingClientRect().right,
      columnRight: label.getBoundingClientRect().right,
    };
  });
  expect(triggerRight).toBeLessThanOrEqual(columnRight + 1);
});
