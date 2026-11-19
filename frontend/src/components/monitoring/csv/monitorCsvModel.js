/**
 * İzleme CSV dışa / içe aktarımı — saf model (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu kurulum").
 *
 * Sunucu sözleşmesi (`/api/monitoring/csv/{type}/…`, MonitorCsvController):
 *  - Önizleme / gerçek koşum yanıtı `data = {type, dry_run, columns, counts, budget_exceeded, rows[], limits}`; her satır
 *    `{row, name, team, action create|update|unchanged|error, changed[], secrets[], status done|skipped|failed|null, message, field}`.
 *  - Sırlar ASLA taşınmaz: `secrets` = içe aktarmadan sonra elle girilmesi gereken alanlar (`basic_auth_pass`,
 *    `custom_headers`, `env:<AD>`).
 * Bu dosya React'tan bağımsızdır (birim testi `monitorCsvModel.test.js`).
 */

/** CSV'si olan on tür — sunucunun `MonitorCsvSpec.TYPES` listesiyle AYNI sıra (kapı testi Java kaynağını okur). */
export const MONITOR_CSV_TYPES = ['http', 'keyword', 'page', 'pagespeed', 'ping', 'port', 'dns', 'domain', 'scripted', 'dbquery']

/** Satır eylemleri (sunucu `A_*`). */
export const CSV_ACTIONS = ['create', 'update', 'unchanged', 'error']
/** Gerçek koşum satır durumları (sunucu `S_*`). */
export const CSV_STATUSES = ['done', 'skipped', 'failed']

/** İstemci tarafı dosya sınırları — sunucuyla aynı (5 MB gövde, 2000 satır). */
export const CSV_MAX_BYTES = 5 * 1024 * 1024
export const CSV_MAX_ROWS = 2000

export const isCsvType = (type) => MONITOR_CSV_TYPES.includes(type)

/**
 * Seçilen dosyanın okunmadan önceki denetimi: `null` (uygun) | 'empty' | 'tooLarge' | 'notCsv'. Uzantı / tür hoşgörülü:
 * `.csv` / `.txt` ya da `text/csv` / `text/plain` / boş tür (Windows bazı sürümlerde `application/vnd.ms-excel` verir — o da
 * kabul). Asıl doğrulama sunucudadır; burada yalnız açıkça yanlış dosya (görsel, PDF …) okunmadan reddedilir.
 */
export function fileProblem(file) {
  if (!file) return 'empty'
  if (file.size > CSV_MAX_BYTES) return 'tooLarge'
  if (file.size === 0) return 'empty'
  const name = String(file.name || '').toLowerCase()
  const type = String(file.type || '').toLowerCase()
  const extOk = name.endsWith('.csv') || name.endsWith('.txt')
  const typeOk = !type || type === 'text/csv' || type === 'text/plain' || type === 'application/vnd.ms-excel'
    || type === 'application/csv'
  return extOk || (typeOk && !name.includes('.')) ? null : 'notCsv'
}

/** Önizleme sayıları: eylem başına + uygulanabilir (oluştur + güncelle). Bilinmeyen eylem `error` sayılır (asla başarı). */
export function previewCounts(rows) {
  const c = { create: 0, update: 0, unchanged: 0, error: 0, total: 0, actionable: 0 }
  for (const r of rows || []) {
    const a = CSV_ACTIONS.includes(r?.action) ? r.action : 'error'
    c[a] += 1
    c.total += 1
  }
  c.actionable = c.create + c.update
  return c
}

/**
 * Gerçek koşum sayıları: uygulanan (oluşturulan / güncellenen ayrı), atlanan, uygulanamayan. Durumu olmayan ya da bilinmeyen
 * satır `failed` sayılır — sonuç bilinmiyorsa başarı denmez.
 */
export function resultCounts(rows) {
  const c = { done: 0, created: 0, updated: 0, skipped: 0, failed: 0, total: 0 }
  for (const r of rows || []) {
    c.total += 1
    if (r?.status === 'done') {
      c.done += 1
      if (r.action === 'create') c.created += 1
      else if (r.action === 'update') c.updated += 1
    } else if (r?.status === 'skipped') c.skipped += 1
    else c.failed += 1
  }
  return c
}

/** Satır süzgeci: 'all' | eylem (önizleme) ya da durum (sonuç). */
export function filterRows(rows, filter, phase = 'preview') {
  const list = rows || []
  if (!filter || filter === 'all') return list
  if (phase === 'result') {
    if (filter === 'failed') return list.filter((r) => r?.status !== 'done' && r?.status !== 'skipped')
    return list.filter((r) => r?.status === filter)
  }
  if (filter === 'error') return list.filter((r) => !CSV_ACTIONS.includes(r?.action) || r.action === 'error')
  return list.filter((r) => r?.action === filter)
}

/** Sonradan girilmesi gereken sırların İNSAN okur adı (çeviri anahtarı + parametre). Bilinmeyen kod olduğu gibi. */
export function secretLabel(t, code) {
  const s = String(code || '')
  if (s.startsWith('env:')) return t('mcsv.secret.env', s.slice(4))
  if (s === 'basic_auth_pass') return t('mcsv.secret.basic_auth_pass')
  if (s === 'custom_headers') return t('mcsv.secret.custom_headers')
  return s
}

/** Sır girilmesi gereken izlemeler (gerçek koşumda yalnız UYGULANANLAR; önizlemede uygulanabilir satırlar). */
export function rowsNeedingSecrets(rows, phase = 'preview') {
  return (rows || []).filter((r) => Array.isArray(r?.secrets) && r.secrets.length > 0
    && (phase === 'result' ? r.status === 'done' : (r.action === 'create' || r.action === 'update')))
}

/**
 * İçe aktarım yanıtının sınıfı: 'ok' (veri var) | 'unsupported' (eski sunucu: 404 / 405 — uç yok) | 'unsure' (yanıt yok:
 * zaman aşımı / ağ / 502-504 — gerçek koşumda sonuç BİLİNMİYOR) | 'error' (sunucu bir şey söyledi: 400 / 403 / 413 / 429 …).
 */
export function classifyImportResponse(res) {
  if (res && res.success !== false && res.data && Array.isArray(res.data.rows)) return 'ok'
  const status = Number(res?.status ?? 0)
  const code = res?.code
  if (status === 405 || (status === 404 && (code == null || code === 'NOT_FOUND'))) return 'unsupported'
  if (!res || status === 0 || status === 502 || status === 503 || status === 504
    || code === 'REQUEST_TIMEOUT' || code === 'NETWORK_ERROR' || code === 'OFFLINE') return 'unsure'
  return 'error'
}

/** Değişen sütunların okunur listesi (CSV başlık adları — dosyadaki adlarla aynı; ≤ 12, gerisi "+N"). */
export function changedText(changed, more) {
  const list = Array.isArray(changed) ? changed : []
  if (list.length <= 12) return list.join(', ')
  return `${list.slice(0, 12).join(', ')} ${more(list.length - 12)}`
}
