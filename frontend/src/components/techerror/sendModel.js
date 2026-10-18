import { PAGINATION_PRESETS } from '../../hooks/paginationPresets.js'

/**
 * Gönderim penceresi + kanal gönderim günlüğü modeli (R18, 2026-10-18 — kullanıcı onayı).
 *
 * <p><b>Ek Bilgi (CC), C13:</b> sunucu kuralının (`TechErrorReportSender.extraCc`) istemci aynası — istemcinin bilebildiği
 * kısmı: biçim (aynı desen, ≤ 254), en çok {@link MAX_EXTRA_CC} farklı adres, giriş ≤ {@link MAX_EXTRA_CC_INPUT}. Bilgi
 * toplamının 20'yi aşması sunucuda denetlenir (takım Bilgi listesini sunucu bilir) ve alan altında gösterilir.
 *
 * <p><b>Gönderim günlüğü, E22:</b> tür / durum süzgeçleri, etiket anahtarları ve adres anahtarları (`te_tab=sendlog`,
 * `te_slp` / `te_slps` sayfa, `te_slk` tür, `te_sls` durum).
 */

/** Bir gönderime eklenebilecek en çok ek Bilgi adresi — `TechErrorReportSender.MAX_EXTRA_CC`. */
export const MAX_EXTRA_CC = 10
/** Ek Bilgi girişinin üst uzunluğu — `TechErrorReportSender.MAX_EXTRA_CC_INPUT`. */
export const MAX_EXTRA_CC_INPUT = 4000
/** Sunucunun teslim edilebilir adres deseni (`TechErrorReportSender.DELIVERABLE`), tam eşleşme. */
const DELIVERABLE = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]{2,}$/
const SPLIT = /[,;\s]+/

/** Tek adres teslim edilebilir mi (boş değil, ≤ 254 — uzunluk desenden ÖNCE)? */
export function isDeliverable(e) {
  const v = String(e ?? '').trim()
  return v.length > 0 && v.length <= 254 && DELIVERABLE.test(v)
}

/**
 * Ek Bilgi girişini çözer. `{ addresses, error }`: `addresses` = farklı adresler (büyük / küçük harf tekrarı tek), `error`
 * = `null` ya da `{ key, params }` (i18n). Boş giriş = ek yok (hata değil).
 */
export function parseExtraCc(raw) {
  const text = String(raw ?? '')
  if (!text.trim()) return { addresses: [], error: null }
  if (text.length > MAX_EXTRA_CC_INPUT) return { addresses: [], error: { key: 'te.send.ccTooLong', params: [MAX_EXTRA_CC_INPUT, MAX_EXTRA_CC] } }
  const seen = new Set()
  const out = []
  for (const part of text.split(SPLIT)) {
    const e = part.trim()
    if (!e) continue
    if (!isDeliverable(e)) {
      const shown = e.length > 80 ? `${e.slice(0, 80)}…` : e
      return { addresses: [], error: { key: 'te.send.ccInvalid', params: [shown] } }
    }
    const k = e.toLowerCase()
    if (!seen.has(k)) { seen.add(k); out.push(e) }
  }
  if (out.length > MAX_EXTRA_CC) return { addresses: [], error: { key: 'te.send.ccTooMany', params: [MAX_EXTRA_CC, out.length] } }
  return { addresses: out, error: null }
}

/**
 * Yeniden denenebilir gönderim reddi kodları: hiçbir şey gönderilmedi, biraz sonra aynı istek geçebilir (PDF sırası dolu,
 * kişi başı sınır — gönderim talebi alınmadan reddedilir).
 */
const RETRYABLE_SEND_CODES = new Set(['PDF_BUSY', 'RATE_LIMITED'])
/**
 * Sonucu BİLİNMEYEN hatalar (inceleme 2026-10-18, 3. tur): istek sunucuya ulaştı mı, gönderim oldu mu belli değil.
 *  - ağ hatası / zaman aşımı / iptal (durum 0);
 *  - 409 `SEND_IN_PROGRESS`: o an bir gönderim SÜRÜYOR — çoğu kez yanıtı kaybolan ilk isteğin kendisi;
 *  - 503 `DB_UNAVAILABLE` (posta gitmiş, sonuç yazılamamış olabilir) ya da ağ geçidinin 502 / 503 / 504 sayfası (pod
 *    ölürken, istek işlenirken).
 * Kod listesinin dışında da her 5xx belirsizdir (4. tur): posta servise verildikten SONRA düşen bir 500 de "gitmiş olabilir"
 * — tek istisna 502 {@link FINAL_5XX_CODES} (posta sunucusu reddetti: kesin "gitmedi").
 */
const UNSURE_SEND_CODES = new Set(['NETWORK_ERROR', 'OFFLINE', 'REQUEST_TIMEOUT', 'REQUEST_ABORTED', 'SEND_IN_PROGRESS',
  'DB_UNAVAILABLE', 'HTTP_502', 'HTTP_503', 'HTTP_504'])
/** Sonucu KESİN 5xx: 502 `MAIL_FAILED` (posta sunucusu raporu kabul etmedi — talep sonuçlandı, gönderim yok). */
const FINAL_5XX_CODES = new Set(['MAIL_FAILED'])

/**
 * Gönderim hatası penceresi KAPATMALI mı (inceleme 2026-10-18)? Yeniden denenebilir hatada pencere AÇIK kalır ve yazılan ek
 * Bilgi (CC) korunur — eskiden pencere kapanıyor, bir sonraki "Gönder" ek adreslerSİZ (geri alınamaz) gidebiliyordu.
 * `res` bir yanıt yükü ya da fırlatılan `ApiError` olabilir. Dönüş:
 *  - `'retry'`  — 429 (PDF sırası dolu / kişi başı sınır): posta gitmedi, yeniden denenebilir;
 *  - `'unsure'` — ağ hatası / zaman aşımı (durum 0), 409 `SEND_IN_PROGRESS`, `MAIL_FAILED` dışındaki HER 5xx (500, 503,
 *                 504, ağ geçidinin 502 sayfası — 4. tur: posta servise verildikten sonra düşen 500 de "gitmiş olabilir"):
 *                 gönderim olmuş ya da sürüyor olabilir — pencere açık, rapor yeniden okunup durum bilinene kadar "Gönder"
 *                 KİLİTLİ (`unsureSendOutcome`; inceleme 2026-10-18, 3. tur: kilit yokken ikinci tıklama raporu
 *                 yöneticilere İKİNCİ kez yolluyordu). Yeniden okuma ucuzdur;
 *  - `null`     — kesin sonuç (başarı ya da kalıcı ret: posta kapalı, adres yok, onay gerekli, 502 `MAIL_FAILED`…) →
 *                 pencere kapanır.
 */
export function sendFailureKind(res) {
  if (!res || typeof res !== 'object' || res.success === true) return null
  const info = res.errorInfo && typeof res.errorInfo === 'object' ? res.errorInfo : {}
  const code = res.code || info.code || null
  const status = typeof res.status === 'number' ? res.status : (typeof info.status === 'number' ? info.status : null)
  if (code && UNSURE_SEND_CODES.has(code)) return 'unsure'
  if (code && RETRYABLE_SEND_CODES.has(code)) return 'retry'
  if (code && FINAL_5XX_CODES.has(code)) return null
  if (status != null && status >= 500) return 'unsure'
  if (status === 429) return 'retry'
  if (status === 0) return 'unsure'
  return null
}

/**
 * Sonucu bilinmeyen gönderimden sonra raporun YENİDEN OKUNAN hâli ne diyor (inceleme 2026-10-18, 3. tur)? `before` =
 * gönderimden önce eldeki rapor satırı, `after` = yeniden okunan (`null` = okunamadı). Dönüş:
 *  - `'sending'` — bir gönderim sürüyor (taze talep): pencere kapanır, "sürüyor" bildirimi;
 *  - `'queued'`  — posta kuyrukta (posta servisi yeniden deneyecek): pencere kapanır;
 *  - `'sent'`    — gönderim gitmiş (son gönderim zamanı değişti ya da gönderim sayısı arttı): pencere kapanır;
 *  - `'notSent'` — açıkça gitmemiş (son gönderim aynı, süren ya da kuyrukta gönderim yok; deneme olduysa başarısız
 *                  bitmiş): "Gönder" yeniden açılır, yazılan ek Bilgi korunur;
 *  - `'unknown'` — rapor okunamadı: "Gönder" KİLİTLİ kalır (yeniden denetlenir).
 * Önceki satır bilinmiyorsa gönderilmiş bir rapor "gitmiş" sayılır (çift gönderimden kaçınılır).
 *
 * <p><b>Tıklamadan ÖNCE kuyrukta bekleyen gönderim (inceleme 2026-10-18, 4. tur):</b> değişmemiş eski kuyruk ("queued") ya
 * da onun sonucunun okunması (rapor okuması `reconcileQueued` → `confirmQueued`: `last_sent_at` / `send_count` değişir)
 * BU gönderimin işareti DEĞİLDİR — bu gönderim (ek Bilgi'si, yeni içeriği) hiç olmamış olabilir. Bu gönderim sunucuda
 * sonuçlandıysa `last_attempt_at` damgalanır (`finishSent` / `finishQueued` / `finishNotSent`; `confirmQueued` damgalamaz,
 * `releaseClaim` de — o gönderim hiçbir şey denemedi). Önceki satır QUEUED iken: damga değişmediyse `'notSent'`; değiştiyse
 * sonuç `send_state`'ten (QUEUED → `'queued'`, SENT → `'sent'`, başarısız / atlandı → `'notSent'`).
 */
export function unsureSendOutcome(before, after) {
  if (!after || typeof after !== 'object') return 'unknown'
  if (after.status === 'sending') return 'sending'
  if (before && (before.send_state === 'QUEUED' || before.status === 'queued')) {
    const attemptMoved = (after.last_attempt_at ?? null) !== (before.last_attempt_at ?? null)
    if (!attemptMoved) return 'notSent'
    if (after.status === 'queued' || after.send_state === 'QUEUED') return 'queued'
    return after.send_state === 'SENT' ? 'sent' : 'notSent'
  }
  if (after.status === 'queued' || after.send_state === 'QUEUED') return 'queued'
  const sentAt = after.last_sent_at ?? null
  if (sentAt != null && sentAt !== (before?.last_sent_at ?? null)) return 'sent'
  const nowCount = Number(after.send_count ?? 0)
  const wasCount = Number(before?.send_count ?? 0)
  if (Number.isFinite(nowCount) && Number.isFinite(wasCount) && nowCount > wasCount) return 'sent'
  return 'notSent'
}

/**
 * Süren / kuyruktaki gönderimin YOKLAMASI (inceleme 2026-10-18, 4. tur): sonucu bilinmeyen gönderim "sürüyor" ya da
 * "kuyrukta" okununca (ve kuyruğa alınan gönderimden sonra) düzenleyici raporun gönderim durumunu her
 * {@link SEND_POLL_MS}'de yeniden okur — en çok {@link SEND_POLL_MAX_MS}; eskiden hiçbir şey yeniden okumuyordu, "Gönder"
 * kapalı kalıyor, sonuç sayfa yenilenmeden görünmüyordu. Taslağa dokunmaz: yalnız {@link SEND_STATUS_FIELDS} alanları.
 */
export const SEND_POLL_MS = 10_000
export const SEND_POLL_MAX_MS = 10 * 60_000
/** Yoklamanın rapor satırına taşıdığı gönderim alanları (sürüm / içerik / onay alanları DEĞİL — taslak tabanı değişmez). */
export const SEND_STATUS_FIELDS = Object.freeze(['status', 'send_state', 'last_send_status', 'last_sent_at', 'last_attempt_at',
  'last_sent_to', 'last_sent_count', 'last_sent_by_name', 'send_count'])

/**
 * Rapor satırına yoklamanın okuduğu gönderim alanlarını işler (yalnız yanıtta OLAN alanlar). `keepChanged` (inceleme
 * 2026-10-18, 5. tur): yoklama isteği gittikten SONRA düzenleyici durumu kendisi "changed" yaptıysa (kayıt yanıtı, görsel
 * yükleme / silme) yanıttaki "sent" onun üstüne YAZILMAZ — o okuma kayıttan önceydi; onay kartının "gönderildi" ipucu bu
 * duruma bakar.
 */
export function withSendStatus(report, fresh, { keepChanged = false } = {}) {
  if (!report || !fresh || typeof fresh !== 'object') return report
  const out = { ...report }
  for (const k of SEND_STATUS_FIELDS) if (Object.prototype.hasOwnProperty.call(fresh, k)) out[k] = fresh[k]
  if (keepChanged && report.status === 'changed' && fresh.status === 'sent') out.status = 'changed'
  return out
}

const attemptOf = (r) => r?.last_attempt_at ?? null
/** Tabandan beri raporda bir teslim görünüyor mu: gönderim sayısı arttı ya da son gönderim zamanı değişti. */
const deliveredSince = (base, fresh) => {
  const sentAt = fresh?.last_sent_at ?? null
  if (sentAt != null && sentAt !== (base?.last_sent_at ?? null)) return true
  const now = Number(fresh?.send_count ?? 0)
  const was = Number(base?.send_count ?? 0)
  return Number.isFinite(now) && Number.isFinite(was) && now > was
}
const isQueuedRow = (r) => r?.status === 'queued' || r?.send_state === 'QUEUED'

/**
 * Yoklamanın tabanı, BU denemenin kuyruğa alındığı BİLİNİNCE (kuyruğa alınan gönderimin yanıtı ya da belirsiz gönderimde
 * "kuyrukta" okuması): `row` = o andan sonra okunan satır. Satır yeni bir deneme damgası taşıyorsa (`last_attempt_at`
 * gönderimden öncekinden farklı; süren talep değil) taban O satırdır (`own: true` — kuyruk bu denemenindir); yoksa (okuma
 * yok / düşürüldü, damga aynı) gönderimden önceki satır (`own: false`).
 */
export function pollBaselineAfterQueued(before, row) {
  if (row && typeof row === 'object' && row.status !== 'sending' && row.send_state !== 'SENDING'
      && attemptOf(row) !== attemptOf(before)) return { baseline: row, own: true }
  return { baseline: before ?? null, own: false }
}

/**
 * Yoklama TURUNUN kararı (inceleme 2026-10-18, 5. tur). Eskiden yalnız raporun ŞU ANKİ `send_state`'i okunuyordu: bırakılan
 * talep (`releaseClaim` — PDF sırası dolu, onay eskidi; hiçbir şey denenmedi) satırı ÖNCEKİ durumuna döndürür, ve önceden
 * gönderilmiş raporda "rapor gönderildi" (hiçbir şey gitmeden), önceden kuyruktaki raporda ESKİ kuyruğun sonucu bu
 * gönderimin sonucu (ek Bilgi hiç gitmeden), hiç gönderilmemiş raporda 10 sn sonra "10 dakikada kesinleşmedi" deniyordu.
 *
 * `poll` = `{ baseline, own }`:
 *  - `own: false` — `baseline` gönderimden ÖNCEKİ satır (belirsiz gönderimin `before`'u): karar her turda
 *    {@link unsureSendOutcome}`(baseline, fresh)` — gitti yalnız `last_sent_at` / `send_count` değiştiyse (önceden kuyruktaki
 *    raporda: `last_attempt_at` SENT ile değiştiyse), kuyrukta yalnız BU deneme kuyruğa aldıysa; tabana dönen durum, yeni
 *    deneme yok = gitmedi ("yeniden gönderebilirsiniz", yazılan ek Bilgi geri gelir). Bu denemenin kuyruğa alındığı
 *    görülünce taban o satıra geçer (`own: true`).
 *  - `own: true` — `baseline` BU denemenin kuyruğunu gösteren satır ({@link pollBaselineAfterQueued}). `unsureSendOutcome` onu
 *    ESKİ kuyruk sayardı (damga aynı → "gitmedi", ilk turda); burada damga aynıyken sonuç kuyruğun KENDİ sonucudur:
 *    `confirmQueued` → gitti, `failQueued` → gitmedi (ikisi damgalamaz), kuyrukta → sürüyor. Damga değiştiyse sonraki bir
 *    deneme kuyruğun yerini aldı: o gittiyse gitti, kuyruğa aldıysa onu izler; gitmediyse `superseded` — bu kuyruğun sonucu
 *    artık raporda okunmaz (posta servisi onu yine de teslim edebilir: "yeniden gönder" DENMEZ). Ama o sonraki deneme
 *    gitmediği hâlde raporda tabandan beri bir TESLİM görünüyorsa (`send_count` arttı ya da `last_sent_at` değişti — kuyruk
 *    `confirmQueued` ile teslim edildi, sonra başka bir deneme başarısız oldu; 20.157.0 incelemesi) sonuç `sent`: rapor
 *    gönderildi, "yerini aldı" demek yanlış olurdu.
 *
 * Süren talep (durum "sending") her zaman sürüyor; taze OLMAYAN talep (`send_state` SENDING, durum başka — 10 dakikadan
 * eski: pod talepten sonra düşmüş olabilir, posta gitmiş olabilir) `unknown` — "yeniden gönder" denmez.
 *
 * Dönüş `{ outcome, poll }`: `outcome` 'pending' | 'sent' | 'notSent' | 'superseded' | 'unknown' | `null` (okunamadı —
 * yoklama sürer); `poll` sonraki turun tabanı (değişmediyse aynı nesne).
 */
export function sendPollStep(poll, fresh) {
  if (!fresh || typeof fresh !== 'object') return { outcome: null, poll }
  const baseline = poll?.baseline ?? null
  const own = !!poll?.own && !!baseline
  if (fresh.status === 'sending') return { outcome: 'pending', poll }
  if (fresh.send_state === 'SENDING') return { outcome: 'unknown', poll }
  let o
  if (own && attemptOf(fresh) === attemptOf(baseline)) {
    o = isQueuedRow(fresh) ? 'queued' : (fresh.send_state === 'SENT' ? 'sent' : 'notSent')
  } else {
    o = unsureSendOutcome(baseline, fresh)
    if (own && o === 'notSent') o = deliveredSince(baseline, fresh) ? 'sent' : 'superseded'
  }
  if (o === 'queued') {
    // Bu denemenin (ya da onun yerini alan denemenin) kuyruğu: sonraki turlar onun sonucuna bakar
    const same = own && attemptOf(fresh) === attemptOf(baseline)
    return { outcome: 'pending', poll: same ? poll : { ...poll, baseline: fresh, own: true } }
  }
  if (o === 'sending') return { outcome: 'pending', poll }
  return { outcome: o, poll }
}

// ── Gönderim günlüğü ────────────────────────────────────────────────────────────────────────────────

export const TAB_SENDLOG = 'sendlog'
export const TE_SLP = 'te_slp'
export const TE_SLPS = 'te_slps'
export const TE_SLK = 'te_slk'
export const TE_SLS = 'te_sls'
/** Kanal görünümünden çıkınca silinen günlük adres anahtarları (başka kanala eski süzgeç taşınmasın). */
export const SENDLOG_KEYS = Object.freeze([TE_SLP, TE_SLPS, TE_SLK, TE_SLS])

export const SENDLOG_KINDS = Object.freeze(['all', 'report', 'test', 'approval', 'reminder'])
export const SENDLOG_STATUSES = Object.freeze(['all', 'sent', 'queued', 'skipped', 'failed'])
/** Sunucu sayfası en çok 100 satır — `panel` ön ayarının 100'e kadar olan boyutları (boyut listesi kaynakta yazılmaz). */
export const SENDLOG_SIZE_OPTIONS = Object.freeze(PAGINATION_PRESETS.panel.sizeOptions.filter((n) => n <= 100))

export function normalizeSendLogKind(v) {
  return SENDLOG_KINDS.includes(v) ? v : 'all'
}

export function normalizeSendLogStatus(v) {
  return SENDLOG_STATUSES.includes(v) ? v : 'all'
}

/** Tür etiketleri (tam anahtar — dinamik anahtar yok). */
export const SENDLOG_KIND_KEYS = Object.freeze({
  all: 'te.slog.kind.all', report: 'te.slog.kind.report', test: 'te.slog.kind.test', approval: 'te.slog.kind.approval',
  reminder: 'te.slog.kind.reminder',
})

/** Durum sınıfı etiketleri ("gönderilemedi" anahtarı "undelivered": anahtar adında failed geçmez — ileti değil etiket). */
export const SENDLOG_STATUS_KEYS = Object.freeze({
  all: 'te.slog.st.all', sent: 'te.slog.st.sent', queued: 'te.slog.st.queued', skipped: 'te.slog.st.skipped',
  failed: 'te.slog.st.undelivered',
})

/** Rol etiketleri; bilinmeyen rol "diğer" (ham değer gösterilmez). */
export const SENDLOG_ROLE_KEYS = Object.freeze({
  TEAM: 'te.slog.role.team', EXECUTIVE: 'te.slog.role.executive', SELF: 'te.slog.role.self', APPROVER: 'te.slog.role.approver',
  APPROVER_INFO: 'te.slog.role.approverInfo', REQUESTER: 'te.slog.role.requester', OTHER: 'te.slog.role.other',
})

/** Durum rozetinin tonu (shadcn Badge sınıfı; durum metniyle birlikte — renk tek başına anlam taşımaz). */
export const SENDLOG_STATUS_TONE = Object.freeze({
  sent: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  queued: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  skipped: 'bg-muted text-muted-foreground',
  failed: 'bg-destructive/10 text-destructive',
})

export function sendLogStatusOf(row) {
  return SENDLOG_STATUS_TONE[row?.status] ? row.status : 'failed'
}

export function sendLogKindOf(row) {
  return SENDLOG_KIND_KEYS[row?.kind] && row.kind !== 'all' ? row.kind : 'report'
}

export function sendLogRoleKey(row) {
  return SENDLOG_ROLE_KEYS[row?.role] || SENDLOG_ROLE_KEYS.OTHER
}

/**
 * Sebep kodu → metin anahtarı (inceleme 2026-10-18): sunucu ham durum metnini (posta sunucusunun iletisi — sunucu adı, port,
 * Türkçe iç metin) DÖNMEZ; yalnız `reason` kodu. Sıra ve küme `TechErrorSendLogService.REASON_CODES` ile aynı (test Java
 * kaynağını okur). Bilinmeyen / boş kod → satır yok (ham anahtar gösterilmez).
 */
export const SENDLOG_REASON_KEYS = Object.freeze({
  QUEUED: 'te.slog.reason.QUEUED',
  MAIL_OFF: 'te.slog.reason.MAIL_OFF',
  INACTIVE_RECIPIENTS: 'te.slog.reason.INACTIVE_RECIPIENTS',
  NO_RECIPIENT: 'te.slog.reason.NO_RECIPIENT',
  NO_APPROVER_EMAIL: 'te.slog.reason.NO_APPROVER_EMAIL',
  TEAM_INACTIVE: 'te.slog.reason.TEAM_INACTIVE',
  MODULE_HIDDEN: 'te.slog.reason.MODULE_HIDDEN',
  APPROVAL_FLOW_REMOVED: 'te.slog.reason.APPROVAL_FLOW_REMOVED',
  SYSTEM_MAINTENANCE: 'te.slog.reason.SYSTEM_MAINTENANCE',
  PDF_BUSY: 'te.slog.reason.PDF_BUSY',
  PDF_TOO_LARGE: 'te.slog.reason.PDF_TOO_LARGE',
  APPROVAL_STALE: 'te.slog.reason.APPROVAL_STALE',
  SMTP_FAILED: 'te.slog.reason.SMTP_FAILED',
  INTERNAL_ERROR: 'te.slog.reason.INTERNAL_ERROR',
  SKIPPED_OTHER: 'te.slog.reason.SKIPPED_OTHER',
  UNKNOWN: 'te.slog.reason.UNKNOWN',
})

export function sendLogReasonKey(row) {
  const code = row?.reason
  return typeof code === 'string' && Object.prototype.hasOwnProperty.call(SENDLOG_REASON_KEYS, code) ? SENDLOG_REASON_KEYS[code] : null
}

/** Sığmayan adres sayısı (`to_more` / `cc_more`; sunucu sığdırma ekini ayırır) — sayı değilse 0. */
export function sendLogMore(row, which) {
  const n = Number(row?.[which === 'cc' ? 'cc_more' : 'to_more'])
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
}
