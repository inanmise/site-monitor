/**
 * AKIŞ ZIP yazıcısı (2026-11-14, görsel aracı) — kanal raporlarının yıl arşivi deseni (`techerror/yearArchive.js`):
 * `fflate` `Zip` + `ZipPassThrough` (sıkıştırmasız — görseller zaten sıkıştırılmış). Her dosya gelir gelmez arşive
 * yazılır, çıktı parçaları hemen Blob'a çevrilir; çağıran baytları bırakabilir (bellekte her görselin üç kopyası birikmez).
 * `finish` arşivi kapatır ve `application/zip` Blob'u döner; `abort` yarım arşivi bırakır (iptal / hata).
 *
 * `fflate` burada yüklenir — yüklenemezse (dağıtım sonrası bayat paket) hiçbir görsel istenmeden fırlatır.
 * Adların tekilliği çağıranın işidir (`imageToolModel.uniqueName`).
 * @param {{ load?: () => Promise<object> }} [opts] test kancası
 */
export async function createZipWriter({ load = () => import('fflate') } = {}) {
  const { Zip, ZipPassThrough, strToU8 } = await load()
  let parts = []
  let failure = null
  let closed = false
  let count = 0
  const zip = new Zip((err, chunk) => {
    if (err) { if (!failure) failure = err; return }
    if (parts && chunk && chunk.length) parts.push(new Blob([chunk]))
  })
  const put = (name, bytes) => {
    if (closed) throw new Error('The archive is already closed.')
    const file = new ZipPassThrough(name)
    zip.add(file)
    file.push(bytes, true)
    if (failure) throw failure
    count += 1
  }
  return {
    /** Bir dosya ekler (`bytes` = Uint8Array). */
    add(name, bytes) { put(name, bytes) },
    /** Metin dosyası ekler (UTF-8). */
    addText(name, text) { put(name, strToU8(String(text))) },
    /** Eklenen dosya sayısı. */
    count: () => count,
    finish() {
      zip.end()
      if (failure) throw failure
      closed = true
      const blob = new Blob(parts, { type: 'application/zip' })
      parts = null
      return blob
    },
    abort() {
      if (closed) return
      closed = true
      try { zip.terminate() } catch { /* yarım arşiv zaten bırakılıyor */ }
      parts = null
    },
  }
}

/** Blob → Uint8Array (ZIP'e yazmak için). */
export async function bytesOfBlob(blob) {
  return new Uint8Array(await blob.arrayBuffer())
}
