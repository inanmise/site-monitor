/**
 * Yüksek boyutlu görselleri yüklemeden önce tarayıcıda küçültür — mail içinde
 * makul boyutta iletilebilsin diye. GIF passthrough (animasyon bozulmasın);
 * diğer tipler limit aşıyorsa canvas ile maxDim'e ölçeklenip JPEG'e çevrilir
 * (beyaz zemin — şeffaflık jpeg'de desteklenmez). Herhangi bir hata olursa
 * orijinal dosyayla devam edilir (server limiti yine de korur).
 */
export async function downscaleImage(file, {
  maxDim = 1600,
  quality = 0.85,
  maxBytes = 1.5 * 1024 * 1024,
} = {}) {
  if (!file || !file.type?.startsWith('image/')) return file
  if (file.type === 'image/gif') return file

  try {
    const bitmap = await loadBitmap(file)
    const { width, height } = bitmap
    const withinLimits = file.size <= maxBytes && width <= maxDim && height <= maxDim
    if (withinLimits) return file

    const scale = Math.min(1, maxDim / Math.max(width, height))
    const w = Math.max(1, Math.round(width * scale))
    const h = Math.max(1, Math.round(height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#ffffff' // jpeg şeffaflık desteklemez — beyaz zemin
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(bitmap, 0, 0, w, h)
    if (bitmap.close) bitmap.close()

    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
    if (!blob) return file

    const newName = file.name.replace(/\.[^.]+$/, '') + '.jpg'
    return new File([blob], newName, { type: 'image/jpeg' })
  } catch {
    return file
  }
}

async function loadBitmap(file) {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(file)
  }
  // Eski tarayıcı fallback'i
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => { URL.revokeObjectURL(url); resolve(img) }
    img.onerror = (e) => { URL.revokeObjectURL(url); reject(e) }
    img.src = url
  })
}
