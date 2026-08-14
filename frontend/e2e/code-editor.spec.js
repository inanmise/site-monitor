import { test, expect } from '@playwright/test'

/**
 * CodeEditor — YERLEŞİM testleri. Bunlar jsdom'da yazılamaz: düzen hesaplanmadığı için
 * `position`/`overflow`/sarma/üst üste binme orada görünmez. 2026-08-14'te cetvelin ilk hâli
 * editörü kullanılamaz yaptı ve 664 vitest testi + `npm run build` yeşildi.
 *
 * Mantık testleri vitest'te KALIR; buraya yalnız yerleşime bağlı olanlar girer.
 */

const HARNESS = '/e2e/harness/editor.html'

/**
 * Bir mantıksal satırın İLK karakterinin GLİF kutusunun y'si — boyalı `pre` üzerinden.
 *
 * Neden Range: element sınır kutusu satır KUTUSUNUN tepesini verir, glif kutusu ise yarım-satır
 * payı (half-leading) kadar aşağıdadır (13px font / 1.5 satır → ~2px). Numara ile kodu
 * karşılaştırırken ikisini de AYNI yöntemle ölçmezsek sabit 2px'lik sahte bir sapma çıkar.
 */
async function codeLineTop(page, lineNo) {
  return page.evaluate((n) => {
    const pre = document.querySelector('.code-editor-wrap pre')
    // pre'nin metin düğümlerini sırayla gez, n. satırın başlangıç karakterine Range koy.
    const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT)
    let seen = 1, node
    while ((node = walker.nextNode())) {
      const text = node.nodeValue
      for (let i = 0; i < text.length; i++) {
        if (seen === n) {
          const r = document.createRange()
          r.setStart(node, i)
          r.setEnd(node, Math.min(i + 1, text.length))
          const rect = r.getClientRects()[0] || r.getBoundingClientRect()
          return rect.top
        }
        if (text[i] === '\n') seen++
      }
    }
    return null
  }, lineNo)
}

/** Cetvel numarasının GLİF kutusunun y'si — kodla aynı yöntem (bkz. codeLineTop). */
async function gutterNumTop(page, lineNo) {
  return page.evaluate((n) => {
    const el = document.querySelector(`.cg-num[data-line="${n}"]`)
    if (!el) return null
    const r = document.createRange()
    r.selectNodeContents(el)
    const rect = r.getClientRects()[0] || r.getBoundingClientRect()
    return rect.top
  }, lineNo)
}

test.describe('CodeEditor cetveli', () => {
  test('T1 — uzun satırın SONUNA yazmak çalışıyor ve boyalı katmana yansıyor', async ({ page }) => {
    // v1 regresyonu: textarea'ya değer giriyordu ama kullanıcının GÖRDÜĞÜ `pre` güncellenmiyordu.
    await page.goto(`${HARNESS}?lines=6&long=1`)
    const ta = page.locator('#k6-script-editor')
    await ta.click()
    await page.keyboard.press('Control+End')
    await page.keyboard.type('ZZTEST')

    await expect(ta).toHaveValue(/ZZTEST/)
    // ASIL kanıt: boyalı katman da güncellendi (kullanıcı yazdığını GÖRÜYOR).
    await expect(page.locator('.code-editor-wrap pre')).toContainText('ZZTEST')
    await expect(page.getByTestId('mirror-value')).toContainText('ZZTEST')
  })

  test('T2 — pre ile textarea yerleşim olarak HİÇ ayrışmıyor', async ({ page }) => {
    // Tek başına tüm hizalama arıza sınıfını yakalar.
    await page.goto(`${HARNESS}?lines=8&long=1`)
    const diff = await page.evaluate(() => {
      const pre = document.querySelector('pre')
      const ta = document.querySelector('textarea')
      const keys = ['paddingLeft', 'paddingRight', 'paddingTop', 'paddingBottom', 'fontFamily',
        'fontSize', 'lineHeight', 'whiteSpace', 'wordBreak', 'overflowWrap', 'tabSize', 'letterSpacing']
      const a = getComputedStyle(pre), b = getComputedStyle(ta)
      const mismatched = keys.filter(k => a[k] !== b[k])
      const ra = pre.getBoundingClientRect(), rb = ta.getBoundingClientRect()
      return { mismatched, dx: Math.abs(ra.x - rb.x), dy: Math.abs(ra.y - rb.y),
               dw: Math.abs(ra.width - rb.width) }
    })
    expect(diff.mismatched).toEqual([])
    expect(diff.dx).toBeLessThan(0.5)
    expect(diff.dy).toBeLessThan(0.5)
    expect(diff.dw).toBeLessThan(0.5)
  })

  test('T3 — her numara kendi kod satırıyla hizalı', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=12`)
    for (const n of [1, 2, 5, 9, 12]) {
      const code = await codeLineTop(page, n)
      const num = await gutterNumTop(page, n)
      expect(code, `satır ${n} kodda bulunamadı`).not.toBeNull()
      expect(Math.abs(code - num), `satır ${n} hizası`).toBeLessThanOrEqual(1.5)
    }
  })

  test('T4 — SARILMIŞ uzun satırdan sonra numaralar kaymıyor', async ({ page }) => {
    // Hayalet-ayna varsayımının tek gerçek sınavı: 2. satır sarıyor, 3+ kaymamalı.
    await page.goto(`${HARNESS}?lines=8&long=1`)
    const wrapped = await page.evaluate(() => {
      const row = document.querySelector('.cg-row[data-line="2"]')
      return row.getBoundingClientRect().height
    })
    expect(wrapped, 'uzun satır sarmadı — test anlamsız').toBeGreaterThan(30)

    for (const n of [3, 4, 6, 9]) {
      const code = await codeLineTop(page, n)
      const num = await gutterNumTop(page, n)
      expect(Math.abs(code - num), `sarma sonrası satır ${n}`).toBeLessThanOrEqual(1.5)
    }
  })

  test('T5 — yatay kaydırma OLUŞMUYOR (sarma kararının kilidi)', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=6&long=1`)
    const over = await page.evaluate(() => {
      const w = document.querySelector('.code-editor-wrap')
      return w.scrollWidth - w.clientWidth
    })
    expect(over).toBeLessThanOrEqual(1)
  })

  test('T7 — numaraya tıklayınca o satır seçiliyor', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=5`)
    await page.locator('.cg-num[data-line="3"]').click()
    const sel = await page.evaluate(() => {
      const ta = document.querySelector('textarea')
      return { start: ta.selectionStart, end: ta.selectionEnd,
               text: ta.value.slice(ta.selectionStart, ta.selectionEnd),
               focused: document.activeElement === ta }
    })
    expect(sel.text).toBe('satır 3')
    expect(sel.focused).toBe(true)
  })

  test('T8 — aktif satır imleci takip eder, odak çıkınca söner', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=5`)
    await page.locator('#k6-script-editor').click()
    await page.keyboard.press('Control+Home')
    await page.keyboard.press('ArrowDown')
    await expect(page.locator('.cg-row--active')).toHaveCount(1)
    await expect(page.locator('.cg-row--active .cg-num')).toHaveText('2')

    await page.keyboard.down('Shift')
    await page.keyboard.press('ArrowDown')
    await page.keyboard.up('Shift')
    await expect(page.locator('.cg-row--active')).toHaveCount(2)

    await page.locator('#k6-script-editor').blur()
    await expect(page.locator('.cg-row--active')).toHaveCount(0)
  })

  test('T6 — hata işareti doğru satırda ve o satıra kaydırıyor', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=60&marker=45`)
    await expect(page.locator('.cg-row--error')).toHaveCount(1)
    await expect(page.locator('.cg-row--error')).toHaveAttribute('data-line', '45')
    const code = await codeLineTop(page, 45)
    const num = await gutterNumTop(page, 45)
    expect(Math.abs(code - num)).toBeLessThanOrEqual(1.5)
    // revealMarkers: 45. satır 520px'lik kutuya sığmaz → kaydırılmış olmalı
    const scrolled = await page.evaluate(() =>
      document.querySelector('.code-editor-wrap').scrollTop)
    expect(scrolled).toBeGreaterThan(0)
  })

  test('T9 — readOnly önizlemede yazılamaz ama cetvel hizalı', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=6&readonly=1`)
    const ta = page.locator('#k6-script-editor')
    await ta.click()
    await page.keyboard.type('OLMAMALI')
    await expect(ta).not.toHaveValue(/OLMAMALI/)
    const code = await codeLineTop(page, 4)
    const num = await gutterNumTop(page, 4)
    expect(Math.abs(code - num)).toBeLessThanOrEqual(1.5)
  })

  test('T11 — hayalet metin kopyalamayı kirletmiyor', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=4`)
    const selected = await page.evaluate(() => {
      const r = document.createRange()
      r.selectNodeContents(document.querySelector('.code-editor-wrap'))
      const s = getSelection(); s.removeAllRanges(); s.addRange(r)
      return s.toString()
    })
    // `.cg { user-select:none }` koruması: "satır 2" metni İKİ kez geçmemeli.
    const hits = selected.split('satır 2').length - 1
    expect(hits).toBeLessThanOrEqual(1)
  })

  test('T12 — geri alma anahtarı: cetvel yok, DOM sadeleşiyor', async ({ page }) => {
    await page.goto(`${HARNESS}?lines=5&gutter=0`)
    await expect(page.locator('.cg')).toHaveCount(0)
    const pad = await page.evaluate(() =>
      getComputedStyle(document.querySelector('pre')).paddingLeft)
    expect(pad).toBe('12px')
    // Yazma yine çalışıyor
    await page.locator('#k6-script-editor').click()
    await page.keyboard.type('X')
    await expect(page.locator('#k6-script-editor')).toHaveValue(/X/)
  })
})
