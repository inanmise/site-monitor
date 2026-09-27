import { test, expect } from '@playwright/test'

/**
 * PAYLAŞILAN ALAN KUTULARI TARAYICI VARSAYILANIYLA ÇİZİLMEZ.
 *
 * <p>Kullanıcı, Sorun Bildirimleri'ndeki "Çözüm Notu" alanının "basic" göründüğünü, projenin
 * geri kalanı gibi standart bir alan olmadığını bildirdi. Sebep: paylaşılan
 * {@code .threshold-field} kuralı yalnız {@code input} seçiyordu; aynı kutudaki
 * {@code select}/{@code textarea} hiçbir stil almıyor ve tarayıcı varsayılanına düşüyordu
 * (textarea'da monospace yazı tipi, dolgu yok, ince gri kenarlık).
 *
 * <p>Boşluk daha önce fark edilmiş ama ekran ekran yamanmıştı; o kapsayıcıların dışındaki her
 * ekran açıkta kalmıştı. Bu kapı, yamanın PAYLAŞILAN sınıfta durduğunu pinler.
 *
 * <p>2026-09-27: `.threshold-field` alan kuralları shadcn geçişiyle silindi; harness artık shadcn Input /
 * NativeSelect / Textarea çizer (uygulamanın tek alan ailesi) — kural aynı: tarayıcı varsayılanı YOK.
 *
 * <p>Ölçüm görünüş karşılaştırması değil: aynı sayfadaki SINIFSIZ bir alanla kıyaslanır.
 * Yazı tipi/tema değişse de doğru kalır, ekran görüntüsü testleri gibi kırılgan değildir.
 */

const FIELDS = ['tf-input', 'tf-select', 'tf-textarea'];

async function style(page, testId, props) {
  return page.getByTestId(testId).evaluate((el, p) => {
    const cs = getComputedStyle(el);
    return Object.fromEntries(p.map(k => [k, cs[k]]));
  }, props);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/e2e/harness/field-style.html');
  await page.waitForSelector('[data-testid="tf"]');
});

for (const id of FIELDS) {
  test(`${id}: projenin alan stilini alir (dolgu + kenarlik + yuvarlatma)`, async ({ page }) => {
    const s = await style(page, id, ['paddingLeft', 'borderTopWidth', 'borderRadius', 'borderStyle']);

    expect(parseFloat(s.paddingLeft), `${id} dolgusuz — tarayici varsayilani`).toBeGreaterThan(4);
    expect(parseFloat(s.borderTopWidth), `${id} kenarliksiz`).toBeGreaterThan(0);
    expect(parseFloat(s.borderRadius), `${id} yuvarlatilmamis`).toBeGreaterThan(0);
    expect(s.borderStyle).toBe('solid');
  });
}

test('textarea MONOSPACE kalmaz — "basic" gorunumun asil kaynagi buydu', async ({ page }) => {
  const styled = await style(page, 'tf-textarea', ['fontFamily']);
  const bare = await style(page, 'bare-textarea', ['fontFamily']);

  expect(styled.fontFamily, 'textarea hala tarayici varsayilani yazi tipinde')
    .not.toBe(bare.fontFamily);
  expect(styled.fontFamily.toLowerCase()).not.toContain('monospace');
});

test('alanlar SINIFSIZ olanlardan gorunur sekilde farkli (kural gercekten uyguluyor)', async ({ page }) => {
  const styledInput = await style(page, 'tf-input', ['paddingLeft', 'borderRadius']);
  const bareInput = await style(page, 'bare-input', ['paddingLeft', 'borderRadius']);

  expect(parseFloat(styledInput.paddingLeft)).toBeGreaterThan(parseFloat(bareInput.paddingLeft));
  expect(parseFloat(styledInput.borderRadius)).toBeGreaterThan(parseFloat(bareInput.borderRadius));
});

test('tema token\'lari uygulaniyor — alan renksiz kalmaz', async ({ page }) => {
  // shadcn alanı açık temada BİLEREK saydam zeminlidir (üstünde durduğu yüzeyin rengini alır); "hayalet token"
  // vakası (tanımsız var(--x) → özellik hesaplanırken geçersiz → `currentColor`a, yani YAZI rengine düşer) KENAR
  // rengiyle ölçülür. Sınıfsız alanla kıyas işe yaramaz: globals.css tüm öğelere tema kenar rengini verir
  // (2026-09-27, shadcn geçişi).
  const s = await style(page, 'tf-textarea', ['borderTopColor', 'color']);
  expect(s.borderTopColor, 'kenar rengi token\'i uygulanmamis (yazi rengine dustu)').not.toBe(s.color);
  expect(s.borderTopColor).not.toBe('rgba(0, 0, 0, 0)');
  expect(s.color).not.toBe('');
});
