// Görsel motoru — sRGB ↔ doğrusal ışık dönüşüm tabloları (saf). Yeniden örnekleme DOĞRUSAL ışıkta yapılır: gama
// kodlu değerlerin ortalaması görseli karartır (1 piksellik siyah / beyaz dama 2× küçülünce 128 değil ≈ 188 olmalı).

/** sRGB 8 bit → doğrusal (0..1). */
export const SRGB_TO_LINEAR = new Float32Array(256)
for (let i = 0; i < 256; i++) {
  const c = i / 255
  SRGB_TO_LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

const LUT_SIZE = 65536
/** Doğrusal (0..1, 16 bit adım) → sRGB 8 bit. 16 bit adım eğrinin en dik yerinde bile < 0,06 seviye hata verir. */
export const LINEAR_TO_SRGB8 = new Uint8Array(LUT_SIZE)
for (let i = 0; i < LUT_SIZE; i++) {
  const v = i / (LUT_SIZE - 1)
  const s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055
  LINEAR_TO_SRGB8[i] = Math.max(0, Math.min(255, Math.round(s * 255)))
}

/** Doğrusal değer (taşma olabilir) → sRGB 8 bit. */
export function linearToSrgb8(v) {
  if (!(v > 0)) return 0
  if (v >= 1) return 255
  return LINEAR_TO_SRGB8[(v * (LUT_SIZE - 1) + 0.5) | 0]
}

/** Rec. 601 parlaklık (gama kodlu değerler üstünde — algısal ölçümler için). */
export const luma = (r, g, b) => 0.299 * r + 0.587 * g + 0.114 * b
