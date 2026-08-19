/**
 * Yüklenen görselleri tarayıcıda AGRESİF şekilde küçültür — mail içinde küçük
 * boyutla iletilebilsin diye (ör. 10 MB ekran görüntüsü → ~50–100 KB).
 *
 * Hedef-bayt güdümlü: maxDim'e ölçekle, sonra kaliteyi kademeli düşürerek
 * targetBytes altına in; en düşük kalitede bile çok büyükse boyutu bir kez
 * daha küçült. GIF passthrough (animasyon korunur, sıkıştırılmaz). Hata olursa
 * orijinal dosyayla devam (server limiti yine korur).
 */
export async function downscaleImage(file, {
  maxDim = 1000,
  targetBytes = 70 * 1024,
  qualities = [0.8, 0.7, 0.6, 0.5, 0.42],
} = {}) {
  if (!file || !file.type?.startsWith('image/')) return file
  if (file.type === 'image/gif') return file // animasyon korunsun

  try {
    const bitmap = await loadBitmap(file)
    let { width, height } = bitmap

    // 1) maxDim'e ölçekle
    let scale = Math.min(1, maxDim / Math.max(width, height))
    let blob = await encodeAtScale(bitmap, width, height, scale, qualities, targetBytes)

    // 2) En düşük kalitede bile hedefin çok üstündeyse boyutu bir kez daha kırp
    if (blob && blob.size > targetBytes * 1.6) {
      blob = await encodeAtScale(bitmap, width, height, scale * 0.7, qualities, targetBytes)
    }
    if (bitmap.close) bitmap.close()
    if (!blob) return file

    // Hiç kazanç yoksa (zaten küçük) orijinali koru
    if (blob.size >= file.size) return file

    const newName = file.name.replace(/\.[^.]+$/, '') + '.jpg'
    return new File([blob], newName, { type: 'image/jpeg' })
  } catch {
    return file
  }
}

/** Verilen ölçekte canvas'a çizip kaliteyi düşürerek targetBytes altını dener. */
async function encodeAtScale(bitmap, width, height, scale, qualities, targetBytes) {
  const w = Math.max(1, Math.round(width * scale))
  const h = Math.max(1, Math.round(height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#ffffff' // jpeg şeffaflık desteklemez — beyaz zemin
  ctx.fillRect(0, 0, w, h)
  ctx.drawImage(bitmap, 0, 0, w, h)

  let last = null
  for (const q of qualities) {
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', q))
    if (!blob) continue
    last = blob
    if (blob.size <= targetBytes) return blob // hedefin altına indi
  }
  return last // hedefe inilemese de en küçük (en düşük kalite) sonucu
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
