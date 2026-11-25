/**
 * Markdown alanına YAPIŞTIRILAN / BIRAKILAN görselin yükleme yer tutucusu (2026-11-20, "Hızlı Giriş").
 *
 * <p>Yükleme sürerken imleç konumuna `![<ad> — yükleniyor…](sm-upload:<belirteç>)` yazılır; yükleme bitince AYNI belirteç
 * aranıp gerçek görsel bağlantısıyla değiştirilir (bu arada yazılan metin korunur: değiştirme her zaman EN GÜNCEL metin
 * üzerinde yapılır). Yükleme başarısızsa yer tutucu silinir.
 *
 * <p>Süren yüklemeler bu sekmenin bellekteki KAYDINDADIR (`beginUpload` / `endUpload`). Form yalnız yüklemesi GERÇEKTEN
 * süren bir yer tutucu varken kaydetmez (`hasPendingUpload`); sahipsiz kalmış yer tutucu (yarım yazılmış taslak, kapanmış
 * pencere, elle yazılmış metin) kaydı hiçbir zaman kalıcı olarak kilitlemez — taslağa yazılırken / taslaktan geri yüklenirken
 * ve gövdeye girerken silinir (`stripUploadPlaceholders`). Belirteç yalnız `[a-z0-9]`; tüm taramalar doğrusal (`indexOf`),
 * düzenli ifade yok.
 */

export const UPLOAD_SCHEME = 'sm-upload:'
const MARK = '](' + UPLOAD_SCHEME

let seq = 0

/** Bu sekmede tekil belirteç (zaman + sayaç + rastgele, yalnız `[a-z0-9]`). */
export function uploadToken(now = Date.now()) {
  seq = (seq + 1) % 1_000_000
  const rnd = Math.floor(Math.random() * 36 ** 4).toString(36)
  return `${now.toString(36)}${seq.toString(36)}${rnd}`
}

/** Görsel adını alt metne güvenli çevirir: köşeli ayraç ve satır sonu yok, en çok 120 karakter. */
export function safeAlt(label) {
  let out = ''
  for (const ch of String(label ?? '')) {
    if (out.length >= 120) break
    out += ch === '[' || ch === ']' || ch === '\n' || ch === '\r' ? ' ' : ch
  }
  return out.trim() || 'image'
}

/** Yer tutucu metni. */
export function uploadPlaceholder(label, token) {
  return `![${safeAlt(label)}](${UPLOAD_SCHEME}${token})`
}

/** Metinde (yarım kalmış ya da süren) yükleme yer tutucusu var mı? */
export function hasUploadPlaceholder(text) {
  return typeof text === 'string' && text.includes(MARK)
}

// ── Süren yüklemelerin kaydı (bu sekme) ────────────────────────────────────────────────────────────────────

/** Kayıttaki belirteç üst sınırı (yükleme sırası en çok 10 + 3; savunma amaçlı). */
export const MAX_TRACKED_UPLOADS = 500
const inFlight = new Set()

/** Bu belirteçli yükleme başladı (editör yer tutucuyu yazarken). */
export function beginUpload(token) {
  if (!token) return
  if (inFlight.size >= MAX_TRACKED_UPLOADS) inFlight.delete(inFlight.values().next().value)
  inFlight.add(String(token))
}

/** Yükleme bitti (başarılı, başarısız ya da editör söküldü). */
export function endUpload(token) {
  inFlight.delete(String(token))
}

/** Bu belirteçli yükleme hâlâ sürüyor mu. */
export function uploadInFlight(token) {
  return inFlight.has(String(token))
}

/** Testler için: kaydı boşaltır. */
export function resetUploadsForTests() {
  inFlight.clear()
}

/** Metindeki yer tutucuların belirteçleri (en çok 1000, doğrusal). */
export function uploadTokensIn(text) {
  const s = typeof text === 'string' ? text : ''
  const out = []
  let from = 0
  for (let at = s.indexOf(MARK, from); at >= 0 && out.length < 1000; at = s.indexOf(MARK, from)) {
    const start = at + MARK.length
    const close = s.indexOf(')', start)
    if (close < 0) break
    out.push(s.slice(start, close))
    from = close + 1
  }
  return out
}

/** Metinde yüklemesi GERÇEKTEN süren bir yer tutucu var mı (kaydı yalnız bu durdurur). */
export function hasPendingUpload(text) {
  if (!hasUploadPlaceholder(text) || inFlight.size === 0) return false
  return uploadTokensIn(text).some((tk) => inFlight.has(tk))
}

/** Yer tutucunun başladığı `![` — aynı satırda değilse (kullanıcı alt metni düzenledi) satırın başı. */
function spanStart(text, markAt) {
  const lineStart = text.lastIndexOf('\n', markAt) + 1
  const bang = text.lastIndexOf('![', markAt)
  return bang >= lineStart ? bang : lineStart
}

/**
 * `token` belirtecinin yer tutucusunu `replacement` ile değiştirir (boş metin = siler). Dönüş `{ text, found }`; yer tutucu
 * kullanıcı tarafından silindiyse `found=false` ve metin AYNEN döner.
 */
export function replaceUploadPlaceholder(text, token, replacement) {
  const s = String(text ?? '')
  const needle = MARK + token + ')'
  const at = s.indexOf(needle)
  if (at < 0) return { text: s, found: false }
  const start = spanStart(s, at)
  return { text: s.slice(0, start) + replacement + s.slice(at + needle.length), found: true }
}

/**
 * Metindeki yükleme yer tutucularını siler (yerel taslağa / gövdeye yarım yükleme girmesin). `keep(belirteç)` true dönen
 * yer tutucu kalır (ör. yalnız sahipsizleri silmek için `uploadInFlight`); verilmezse HEPSİ silinir. Doğrusal.
 */
export function stripUploadPlaceholders(text, keep = null) {
  const s = String(text ?? '')
  if (!s.includes(MARK)) return s
  let out = ''
  let from = 0
  for (let at = s.indexOf(MARK, from); at >= 0; at = s.indexOf(MARK, from)) {
    const close = s.indexOf(')', at + MARK.length)
    if (close < 0) break
    if (keep && keep(s.slice(at + MARK.length, close))) {
      out += s.slice(from, close + 1)
    } else {
      const start = Math.max(spanStart(s, at), from)
      out += s.slice(from, start)
    }
    from = close + 1
  }
  return out + s.slice(from)
}

/** Yalnız verilen belirteçlerin yer tutucularını siler (editör sökülürken kendi yarım yüklemeleri). */
export function removeUploadPlaceholders(text, tokens) {
  const set = new Set((tokens || []).map(String))
  if (set.size === 0) return String(text ?? '')
  return stripUploadPlaceholders(text, (tk) => !set.has(tk))
}
