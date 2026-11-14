// Görsel motoru — hafif netleştirme (saf, satır satır akar). Yalnız GERÇEK bir küçültmeden sonra uygulanır: küçültme
// süzgeci ince ayrıntıyı bir miktar yumuşatır; 3×3 bulanıklık farkının küçük bir katı (unsharp mask) onu geri verir.
// Bulanıklık SAYDAMLIK AĞIRLIKLIDIR (saydam komşunun rengi karışmaz) ve saydamlık kanalına dokunulmaz — saydam kenarda
// hale oluşmaz. Gama kodlu değerler üstünde çalışır (algısal olarak eşit dağılır; koyu kenarda aşırı hale yapmaz).

/** Ölçeğe göre netleştirme miktarı: ölçek = hedef / kaynak (≤ 1). Küçük küçültmede 0 (dokunulmaz). */
export function sharpenAmount(scale) {
  if (!(scale > 0) || scale >= 0.95) return 0
  const amount = Math.min(0.3, 0.3 * (1 / scale - 1))
  return amount < 0.03 ? 0 : amount
}

/**
 * Akışlı netleştirici: satırlar sırayla `push` edilir, bir satır gecikmeyle `onRow(y, satır)` döner (son satır
 * `finish` ile). `amount = 0` ise satırlar olduğu gibi geçer.
 */
export function createSharpener(width, height, amount, onRow) {
  const rowLen = width * 4
  if (!(amount > 0) || width < 2 || height < 2) {
    let y0 = 0
    return { push: (row, offset = 0) => onRow(y0++, offset === 0 && row.length === rowLen ? row : row.subarray(offset, offset + rowLen)), finish: () => {} }
  }
  const rows = [new Uint8ClampedArray(rowLen), new Uint8ClampedArray(rowLen), new Uint8ClampedArray(rowLen)]
  const out = new Uint8ClampedArray(rowLen)
  let n = 0

  function emit(y, up, mid, down) {
    for (let x = 0; x < width; x++) {
      const i = x * 4
      const a0 = mid[i + 3]
      if (a0 === 0) { out[i] = 0; out[i + 1] = 0; out[i + 2] = 0; out[i + 3] = 0; continue }
      const l = x > 0 ? i - 4 : i
      const r = x < width - 1 ? i + 4 : i
      // 1 2 1 / 2 4 2 / 1 2 1 — saydamlıkla ağırlıklı
      const w0 = up[l + 3]
      const w1 = up[i + 3] * 2
      const w2 = up[r + 3]
      const w3 = mid[l + 3] * 2
      const w4 = a0 * 4
      const w5 = mid[r + 3] * 2
      const w6 = down[l + 3]
      const w7 = down[i + 3] * 2
      const w8 = down[r + 3]
      const den = w0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8
      for (let c = 0; c < 3; c++) {
        const v = mid[i + c]
        const blur = (up[l + c] * w0 + up[i + c] * w1 + up[r + c] * w2 + mid[l + c] * w3 + v * w4 + mid[r + c] * w5
          + down[l + c] * w6 + down[i + c] * w7 + down[r + c] * w8) / den
        out[i + c] = v + amount * (v - blur)               // Uint8ClampedArray: kırpar ve en yakına yuvarlar
      }
      out[i + 3] = a0
    }
    onRow(y, out)
  }

  return {
    push(row, offset = 0) {
      const slot = rows[n % 3]
      if (offset === 0 && row.length === rowLen) slot.set(row)
      else slot.set(row.subarray(offset, offset + rowLen))
      n += 1
      if (n >= 2) {
        const mid = rows[(n - 2) % 3]
        emit(n - 2, n >= 3 ? rows[(n - 3) % 3] : mid, mid, rows[(n - 1) % 3])
      }
    },
    finish() {
      if (n >= 1) {
        const mid = rows[(n - 1) % 3]
        emit(n - 1, n >= 2 ? rows[(n - 2) % 3] : mid, mid, mid)
      }
    },
  }
}

/** Tüm görseli tek çağrıda netleştirir (testler ve küçük görseller). */
export function sharpenRGBA(src, width, height, amount) {
  const out = new Uint8ClampedArray(width * height * 4)
  const sh = createSharpener(width, height, amount, (y, row) => out.set(row, y * width * 4))
  for (let y = 0; y < height; y++) sh.push(src, y * width * 4)
  sh.finish()
  return out
}
