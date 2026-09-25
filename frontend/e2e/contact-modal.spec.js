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
  const { triggerRight, columnRight, clipped } = await page.evaluate(() => {
    const label = document.querySelector('[data-testid="f-user"]');
    // Tetik shadcn Button (role="combobox"); etiket metni içindeki kırpılan span'de.
    const trigger = label.querySelector('button[role="combobox"]');
    const text = trigger.querySelector('span');
    return {
      triggerRight: trigger.getBoundingClientRect().right,
      columnRight: label.getBoundingClientRect().right,
      clipped: text.scrollWidth > text.clientWidth,
    };
  });
  expect(triggerRight).toBeLessThanOrEqual(columnRight + 1);
  // Uzun ad gerçekten KISALTILIYOR (ellipsis) — sütuna sığdığı için değil.
  expect(clipped, 'uzun etiket kırpılmıyor').toBe(true);
});

/**
 * Açılır liste ModalShell (shadcn Dialog) İÇİNDE: body'ye PORTAL'lanır.
 *
 * <p>Eskiden liste tetiğin altına mutlak konumla çiziliyordu ve pencere kutusunun alt kenarında
 * KIRPILIYORDU. Şimdi Radix Popover: pencerenin DOM'unda değil, katmanı pencerenin üstünde
 * (`--z-menu`) ve kısa bir pencerenin alt kenarını aşsa bile görünür. Aynı katman yığını
 * Escape'i de doğru dağıtır: ilk Escape listeyi, ikincisi pencereyi kapatır.
 */
test('ModalShell içinde liste portal: pencere kenarında kırpılmaz, üstte kalır; Escape önce listeyi kapatır', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  await page.goto('/e2e/harness/contact-modal.html?mode=shell');
  // Radix PopoverContent de role="dialog" taşır → pencere ADIYLA seçilir (liste açıkken iki dialog olur).
  const dialog = page.getByRole('dialog', { name: 'Kişi Ekle' });
  await expect(dialog).toBeVisible();

  await page.getByRole('combobox', { name: 'Uzun liste' }).click();
  const list = page.getByRole('listbox');
  await expect(list).toBeVisible();

  const geo = await list.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const box = document.querySelector('[data-slot="dialog-content"]').getBoundingClientRect();
    // Liste yer durumuna göre aşağı YA DA yukarı açılır (Radix çarpışma çevirmesi; 1024×800'de yukarı).
    // Pencere kutusunun DIŞINDA kalan bir liste noktası seçilir: kırpılsaydı orada scrim/sayfa olurdu.
    const below = r.bottom > box.bottom
    const y = below ? Math.min(r.bottom - 6, box.bottom + 10) : Math.max(r.top + 6, box.top - 10);
    const hit = document.elementFromPoint(r.left + r.width / 2, y);
    return {
      inDialogDom: !!el.closest('[data-slot="dialog-content"]'),
      exceedsDialog: r.bottom > box.bottom || r.top < box.top,
      hitInList: el.contains(hit),
      listTop: r.top,
      listBottom: r.bottom,
      viewportH: window.innerHeight,
    };
  });
  expect(geo.inDialogDom, 'liste pencerenin DOM ağacında — portal değil').toBe(false);
  expect(geo.exceedsDialog, 'bu senaryoda liste pencere kutusunu aşmalı (aşağı ya da yukarı)').toBe(true);
  expect(geo.hitInList, 'liste pencere kenarında kırpılıyor ya da altında kalıyor').toBe(true);
  expect(geo.listTop).toBeGreaterThanOrEqual(-1);
  expect(geo.listBottom).toBeLessThanOrEqual(geo.viewportH + 1);

  // Seçim: tetik yeni değeri gösterir, pencere açık kalır. onChange TEK kez: seçim basışta olur ve
  // kapanış animasyonu sürerken gelen click (cmdk onSelect) yutulmalı.
  await list.getByRole('option', { name: 'Seçenek 3', exact: true }).click();
  await expect(list).toBeHidden();
  await expect(page.getByRole('combobox', { name: 'Uzun liste' })).toHaveText('Seçenek 3');
  await expect(page.getByTestId('change-count')).toHaveText('1');
  await expect(dialog).toBeVisible();

  // Escape: önce liste kapanır, pencere açık kalır; ikinci Escape pencereyi kapatır.
  await page.getByRole('combobox', { name: 'Uzun liste' }).click();
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
});

test('ModalShell içinde uzun seçenek listede de kısaltılır; liste en çok 360px', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 800 });
  await page.goto('/e2e/harness/contact-modal.html?mode=shell');
  await page.getByRole('combobox', { name: 'Kullanıcı' }).click();
  const option = page.getByRole('listbox').getByRole('option').first();
  await expect(option).toBeVisible();
  const { listWidth, clipped } = await option.evaluate((el) => {
    const label = el.querySelector('span span');
    return {
      listWidth: el.closest('[role="listbox"]').getBoundingClientRect().width,
      clipped: label.scrollWidth > label.clientWidth,
    };
  });
  expect(listWidth).toBeLessThanOrEqual(360 + 1);
  expect(clipped, 'uzun seçenek listede kırpılmıyor').toBe(true);
});
