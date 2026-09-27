/**
 * Denetim dışa aktarımını İNDİRİR — `window.open` yerine fetch + blob.
 *
 * <p>Neden: uç tek pod'un belleğini korumak için aynı anda en çok iki dışa aktarmaya izin veriyor ve fazlasına
 * **429** dönüyor (prod kapısı Y-2). Yeni sekmede açılan bir bağlantı bu yanıtı kullanıcıya düz metin bir boş sayfa
 * olarak gösteriyordu; burada durum kodu okunup çağıran dostça bir bildirim gösterebilir. Dosya adı sunucunun
 * `Content-Disposition` başlığından gelir (sunucunun verdiği adla aynı).
 *
 * @returns {Promise<{ ok: boolean, status: number }>}  ağ hatasında status 0
 */
export async function downloadAuditExport(url, fallbackName) {
  try {
    const res = await fetch(url, { credentials: 'include' })
    if (!res.ok) return { ok: false, status: res.status }
    const blob = await res.blob()
    const disp = res.headers.get('Content-Disposition') || ''
    const match = /filename="?([^";]+)"?/.exec(disp)
    const href = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = href
    a.download = match ? match[1] : fallbackName
    document.body.appendChild(a)
    a.click()
    a.remove()
    // Serbest bırakmayı ERTELE: click() indirmeyi eşzamanlı başlatmaz; URL hemen geçersiz kılınırsa bazı
    // tarayıcılar dosyayı boş indirir (api/client.js downloadWeeklyOutagePdf ile aynı gerekçe).
    setTimeout(() => URL.revokeObjectURL(href), 0)
    return { ok: true, status: res.status }
  } catch {
    return { ok: false, status: 0 }
  }
}

/** Sunucunun satır tavanı (AuditController#export): tam kapsam 50.000, ekip kapsamı 5.000. */
export function exportCap(fullScope) {
  return fullScope ? 50_000 : 5_000
}
