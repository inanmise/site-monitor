/**
 * KANALIN YIL ARŞİVİ — ZIP (2026-10-17, kullanıcı onayı C12). Bir yılın bütün raporlarının PDF'leri SIRAYLA (her seferinde
 * tek istek) alınır ve tarayıcıda tek ZIP'e paketlenir. Sunucu pod başına aynı anda en çok 2 PDF üretir; doluysa 429
 * (`PdfBusyException`) döner — o rapor 3 sn sonra yeniden istenir, en çok {@link ARCHIVE_MAX_RETRIES} kez. Yine olmazsa ya da
 * başka bir hata gelirse ay EKSİK sayılır: ZIP başarılı olanlarla yine verilir, içine eksik ayları ve nedenlerini yazan
 * `missing.txt` konur — sessiz, yarım arşiv yok. Kullanıcı iptal ederse hiçbir şey indirilmez.
 *
 * Bu dosya SAF parçaları taşır (plan, adlar, eksik listesi, sıralı koşucu); React ve API bağımlılığı yok — çağıran
 * `fetchPdf`'i verir. ZIP paketleyici (`fflate`) ilk kullanımda `import()` ile yüklenir (ekran paketine girmez). PDF zaten
 * sıkıştırılmış olduğundan girdiler sıkıştırılmadan (`level: 0`) yazılır.
 */

/** 429'da bir raporu yeniden istemeden önce beklenen süre. */
export const ARCHIVE_RETRY_WAIT_MS = 3000
/** 429'da aynı rapor için en çok bu kadar YENİDEN deneme (toplam ≤ 4 istek). */
export const ARCHIVE_MAX_RETRIES = 3
/** Eksik ayları anlatan dosyanın ZIP içindeki adı. */
export const MISSING_FILE = 'missing.txt'
/** UTF-8 BOM — `missing.txt` Windows not defterinde Türkçe karakterlerle doğru açılsın. */
const BOM = String.fromCharCode(0xfeff)

/**
 * Sunucunun dosya adı kısaltmasıyla aynı (ExecutiveSummaryPdfWriter.slug): Türkçe küçük harf, ç ğ ı ö ş ü â î û ASCII'ye,
 * harf / rakam dışı her dizi tek '-', en çok 40 karakter, baştaki / sondaki '-' yok.
 */
export function fileSlug(name) {
  if (name == null) return ''
  const folded = String(name).toLocaleLowerCase('tr')
    .replace(/ç/g, 'c').replace(/ğ/g, 'g').replace(/ı/g, 'i').replace(/ö/g, 'o').replace(/ş/g, 's').replace(/ü/g, 'u')
    .replace(/â/g, 'a').replace(/î/g, 'i').replace(/û/g, 'u')
  let out = ''
  let dash = false
  for (let i = 0; i < folded.length && out.length < 40; i++) {
    const ch = folded[i]
    if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
      out += ch
      dash = false
    } else if (!dash && out.length > 0) {
      out += '-'
      dash = true
    }
  }
  return out.replace(/-+$/, '')
}

const pad2 = (n) => String(n).padStart(2, '0')

/** Sunucu adı gelmezse: `<kanal>-<yıl>-<ay>.pdf` (kanal adı ASCII kısaltma; boşsa yalnız dönem). */
export function fallbackPdfName(channelName, year, month) {
  const slug = fileSlug(channelName)
  return `${slug ? `${slug}-` : ''}${year}-${pad2(month)}.pdf`
}

/** ZIP'in adı: `<kanal>-<yıl>-teknik-hata-raporlari.zip`. */
export function archiveFileName(channelName, year) {
  const slug = fileSlug(channelName)
  return `${slug ? `${slug}-` : ''}${year}-teknik-hata-raporlari.zip`
}

/**
 * Sunucunun verdiği adı ZIP içi için güvenli kılar: klasör ayırıcısı ve denetim karakteri atılır (ZIP içinde yol
 * oluşturmasın), `.pdf` uzantısı yoksa eklenir. Boş kalırsa null (çağıran yedek adı kullanır).
 */
export function safeEntryName(name) {
  if (name == null) return null
  // eslint-disable-next-line no-control-regex
  let s = String(name).replace(/[\u0000-\u001f\u007f]/g, '').replace(/[/\\]/g, '-').replace(/^\.+/, '').trim()
  if (!s) return null
  if (s.length > 120) s = s.slice(0, 120)
  if (!/\.pdf$/i.test(s)) s += '.pdf'
  return s
}

/** Aynı ad ikinci kez gelirse `ad-2.pdf`, `ad-3.pdf` … (büyük / küçük harf duyarsız). */
export function uniqueName(name, used) {
  const key = (s) => s.toLocaleLowerCase('tr')
  if (!used.has(key(name))) { used.add(key(name)); return name }
  const dot = name.lastIndexOf('.')
  const base = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : ''
  for (let i = 2; i < 1000; i++) {
    const candidate = `${base}-${i}${ext}`
    if (!used.has(key(candidate))) { used.add(key(candidate)); return candidate }
  }
  const last = `${base}-${Date.now()}${ext}`
  used.add(key(last))
  return last
}

/** Raporları olan yıllar ve rapor sayıları — en yeni yıl önce (menü seçenekleri). */
export function archiveYears(reports) {
  const counts = new Map()
  for (const r of reports || []) {
    const y = Number(r?.year)
    const m = Number(r?.month)
    if (!Number.isInteger(y) || y <= 0 || !Number.isInteger(m) || m < 1 || m > 12 || r?.id == null) continue
    counts.set(y, (counts.get(y) || 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => b[0] - a[0]).map(([year, count]) => ({ year, count }))
}

/**
 * Bir yılın indirme planı: o yılın raporları, ay sırasıyla (Ocak → Aralık), kimliği tekil. Geçersiz ay / kimlik atlanır.
 * @returns {{ id: any, year: number, month: number }[]}
 */
export function zipPlan(reports, year) {
  const y = Number(year)
  const seen = new Set()
  const out = []
  for (const r of reports || []) {
    const m = Number(r?.month)
    if (Number(r?.year) !== y || !Number.isInteger(m) || m < 1 || m > 12 || r?.id == null) continue
    const key = String(r.id)
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ id: r.id, year: y, month: m })
  }
  return out.sort((a, b) => a.month - b.month || String(a.id).localeCompare(String(b.id)))
}

/**
 * İndirilen PDF'lerin ZIP içindeki adları: sunucunun adı (Content-Disposition) varsa o, yoksa {@link fallbackPdfName};
 * çakışan adlar numaralanır.
 * @param {{ item: { year: number, month: number }, name?: string|null, bytes: Uint8Array }[]} files
 */
export function namedEntries(files, channelName) {
  const used = new Set([MISSING_FILE])
  return (files || []).map((f) => ({
    ...f,
    entry: uniqueName(safeEntryName(f.name) || fallbackPdfName(channelName, f.item.year, f.item.month), used),
  }))
}

/**
 * `missing.txt` içeriği (arayüz dilinde): başlık, her eksik ay için "- Ay Yıl — neden", tek tek deneme önerisi. Satır
 * sonu CRLF, başta BOM (Windows not defteri Türkçe karakterleri doğru açsın).
 * @param {{ item, reason: 'busy'|'notPdf'|'failed', status?: number, error?: string|null }[]} missing
 * @param {{ t: Function, channelName: string, year: number, labelOf: (item) => string }} ctx
 */
export function missingText(missing, { t, channelName, year, labelOf }) {
  const lines = [t('te.zip.missingHeading', channelName || '—', year), '']
  for (const m of missing || []) lines.push(`- ${labelOf(m.item)} — ${missingReason(m, t)}`)
  lines.push('', t('te.zip.missingAdvice'))
  return `${BOM}${lines.join('\r\n')}\r\n`
}

/**
 * Eksik ayın nedeni (arayüz dilinde): meşgul, PDF olmayan yanıt, ağ (durum 0 — tarayıcının teknik metni yazılmaz) ya da
 * sunucunun kendi iletisi / HTTP durumu.
 */
export function missingReason(m, t) {
  if (m?.reason === 'busy') return t('te.zip.reason.busy', ARCHIVE_MAX_RETRIES + 1)
  if (m?.reason === 'notPdf') return t('te.zip.reason.notPdf')
  if (!m?.status) return t('te.zip.reason.network')
  if (m?.error) return String(m.error)
  return t('te.zip.reason.failed', m.status)
}

/** Baytlar PDF imzasıyla (`%PDF-`) mı başlıyor — sunucu yerine ara katmanın HTML / JSON sayfası arşive girmesin. */
export function isPdfBytes(bytes) {
  return !!bytes && bytes.length >= 5 && bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46 && bytes[4] === 0x2d
}

/** İptal edilebilir bekleme: süre dolunca true, iptal edilirse (ya da baştan iptalse) false. */
export function abortableSleep(ms, signal) {
  return new Promise((resolve) => {
    if (signal?.aborted) { resolve(false); return }
    let timer = null
    const onAbort = () => { clearTimeout(timer); resolve(false) }
    timer = setTimeout(() => { signal?.removeEventListener?.('abort', onAbort); resolve(true) }, ms)
    signal?.addEventListener?.('abort', onAbort, { once: true })
  })
}

async function bytesOf(res) {
  // ArrayBuffer.isView: başka gerçeklikten (realm) gelen Uint8Array'de `instanceof` yanılır
  if (res?.bytes && ArrayBuffer.isView(res.bytes)) return new Uint8Array(res.bytes.buffer, res.bytes.byteOffset, res.bytes.byteLength)
  if (res?.blob && typeof res.blob.arrayBuffer === 'function') return new Uint8Array(await res.blob.arrayBuffer())
  return null
}

/**
 * Planı SIRAYLA koşar — aynı anda tek istek. 429 → {@link ARCHIVE_RETRY_WAIT_MS} bekleyip aynı raporu yeniden ister (en
 * çok `maxRetries` kez); başka hata, PDF olmayan yanıt ya da tükenen deneme → ay `missing`'e. İptal (signal) bir sonraki
 * adımda (istek / bekleme sonunda; `fetchPdf` sinyali isteğe de geçirebilir) durdurur ve `{ aborted: true }` döner —
 * çağıran hiçbir şey indirmez.
 *
 * @param {{ id, year, month }[]} plan
 * @param {{ fetchPdf: (item, signal) => Promise<{ success: boolean, status?: number, error?: string, aborted?: boolean,
 *           blob?: Blob, bytes?: Uint8Array, name?: string|null }>, signal?: AbortSignal,
 *           onProgress?: (p: { index: number, total: number, item, waiting: boolean, attempt: number }) => void,
 *           sleep?: (ms: number, signal?: AbortSignal) => Promise<boolean>, waitMs?: number, maxRetries?: number }} opts
 * @returns {Promise<{ aborted: boolean, files: { item, name: string|null, bytes: Uint8Array }[], missing: object[] }>}
 */
export async function runArchive(plan, {
  fetchPdf, signal = null, onProgress = () => {}, sleep = abortableSleep, waitMs = ARCHIVE_RETRY_WAIT_MS,
  maxRetries = ARCHIVE_MAX_RETRIES,
} = {}) {
  const ABORTED = { aborted: true, files: [], missing: [] }
  const files = []
  const missing = []
  const total = (plan || []).length
  for (let i = 0; i < total; i++) {
    if (signal?.aborted) return ABORTED
    const item = plan[i]
    let retries = 0
    onProgress({ index: i + 1, total, item, waiting: false, attempt: 1 })
    for (;;) {
      let res
      try {
        res = await fetchPdf(item, signal)
      } catch (e) {
        res = { success: false, status: 0, error: e?.message || null, aborted: e?.name === 'AbortError' }
      }
      if (signal?.aborted || res?.aborted) return ABORTED
      if (res?.success) {
        let bytes = null
        try { bytes = await bytesOf(res) } catch { bytes = null }
        if (signal?.aborted) return ABORTED
        if (isPdfBytes(bytes)) files.push({ item, name: res.name || null, bytes })
        else missing.push({ item, reason: bytes ? 'notPdf' : 'failed', status: 0, error: null })
        break
      }
      if (res?.status === 429 && retries < maxRetries) {
        retries++
        onProgress({ index: i + 1, total, item, waiting: true, attempt: retries + 1 })
        const waited = await sleep(waitMs, signal)
        if (!waited || signal?.aborted) return ABORTED
        onProgress({ index: i + 1, total, item, waiting: false, attempt: retries + 1 })
        continue
      }
      missing.push({ item, reason: res?.status === 429 ? 'busy' : 'failed', status: res?.status ?? 0, error: res?.error || null })
      break
    }
  }
  return { aborted: false, files, missing }
}

/**
 * ZIP baytları: her PDF sıkıştırmasız (`level: 0`), varsa `missing.txt`. `fflate` ilk çağrıda yüklenir.
 * @param {{ entry: string, bytes: Uint8Array }[]} entries
 * @param {string|null} missing  `missing.txt` içeriği (eksik yoksa null)
 */
export async function buildArchiveZip(entries, missing = null) {
  const { zipSync, strToU8 } = await import('fflate')
  const files = {}
  for (const e of entries || []) files[e.entry] = [e.bytes, { level: 0 }]
  if (missing) files[MISSING_FILE] = [strToU8(missing), { level: 0 }]
  return zipSync(files)
}
