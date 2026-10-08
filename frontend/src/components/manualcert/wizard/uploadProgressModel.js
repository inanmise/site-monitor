/**
 * YÜKLEME DURUMU — saf model (2026-10-08, kullanıcı isteği: "manuel yükleme yaparken yükleme durumunu gösterelim").
 * React yok: sihirbaz (UploadWizard) bir koşu (`run`) nesnesini bu fonksiyonlarla ilerletir, panel (UploadProgress) çizer,
 * testler aynı geçişleri doğrudan sınar.
 *
 * <p>İki koşu türü:
 *  - `analyze` ("Analiz et"): [dosyaysa `read`] → `extract` (tarayıcıda ayıklama) → `upload` (sunucuya gönderim, GERÇEK
 *    yüzde: XMLHttpRequest `upload.onprogress`) → `analyze` (sunucu çözümlüyor). Analiz hiçbir şey yazmaz: her an iptal
 *    edilebilir.
 *  - `save` (Takibe al / toplu / yeni sürüm / "Yine de yükle"): `upload` → `save`. İptal YALNIZ gövde gönderilirken
 *    (`upload`) mümkündür: gövde tamamlanınca (`sent`) istek sunucudadır, durdurmak "kaydedilmedi" diyemez.
 *
 * <p>Aşama durumu: `pending` (bekliyor) → `active` (sürüyor) → `done` (tamam) | `error` (hata). Aşamalar YALNIZ ileri
 * gider (geç gelen bir ilerleme bildirimi bitmiş aşamayı yeniden açamaz). Koşu durumu `running` | `failed`; başarıda koşu
 * kaldırılır (adım değişir), iptalde de.
 */

export const STAGE_STATES = Object.freeze(['pending', 'active', 'done', 'error'])

/** Koşunun aşama listesi — sabit (koşu boyunca satır eklenip çıkmaz: yerleşim zıplamaz). */
export function stagesFor(kind, source = 'file') {
  if (kind === 'save') return ['upload', 'save']
  return source === 'file' ? ['read', 'extract', 'upload', 'analyze'] : ['extract', 'upload', 'analyze']
}

/** Sunucunun işlediği (gövde gönderildikten sonraki) aşama. */
export const serverStage = (run) => (run?.kind === 'save' ? 'save' : 'analyze')

/**
 * @param {{ kind: 'analyze'|'save', source?: 'file'|'text', saveKind?: 'created'|'batch'|'renewed', count?: number,
 *   now?: number }} opts
 */
export function startRun({ kind, source = 'file', saveKind = null, count = 0, now = Date.now() } = {}) {
  const stages = stagesFor(kind, source)
  const state = Object.fromEntries(stages.map((s, i) => [s, i === 0 ? 'active' : 'pending']))
  return {
    kind, source, saveKind, count, stages, state, status: 'running', active: stages[0],
    startedAt: { [stages[0]]: now }, upload: null, extract: { zip: null, pkcs12: null, keystore: null }, failedStage: null,
  }
}

/** Aşamaya geçer: öncekiler `done`, bu `active`. Geriye gidiş ve bilinmeyen aşama yok sayılır (aynı nesne döner). */
export function enterStage(run, stage, now = Date.now()) {
  if (!run || run.status !== 'running') return run
  const idx = run.stages.indexOf(stage)
  const cur = run.stages.indexOf(run.active)
  if (idx < 0 || idx <= cur) return run
  const state = { ...run.state }
  run.stages.forEach((s, i) => { if (i < idx) state[s] = 'done'; else if (i === idx) state[s] = 'active' })
  return { ...run, state, active: stage, startedAt: { ...run.startedAt, [stage]: now } }
}

/**
 * Tarayıcıdaki ayıklamanın bildirimi (`extract/index.js` + çekirdek): `read`, `extract`, `zip` {done,total},
 * `pkcs12` {step}, `keystore` {format}. Yalnız sayaç / biçim tutulur.
 */
export function applyExtractProgress(run, p, now = Date.now()) {
  if (!run || run.status !== 'running' || !p || typeof p !== 'object') return run
  switch (p.phase) {
    case 'read': return enterStage(run, 'read', now)
    case 'extract': return enterStage(run, 'extract', now)
    case 'zip': {
      const total = Math.max(0, Number(p.total) || 0)
      const done = Math.max(0, Math.min(total, Number(p.done) || 0))
      return { ...enterStage(run, 'extract', now), extract: { ...run.extract, zip: { done, total } } }
    }
    case 'pkcs12': return { ...enterStage(run, 'extract', now), extract: { ...run.extract, pkcs12: String(p.step || 'kdf') } }
    case 'keystore': return { ...enterStage(run, 'extract', now), extract: { ...run.extract, keystore: p.format || null } }
    default: return run
  }
}

/**
 * Sunucuya gönderimin bildirimi (`api.manualCerts.*` → XMLHttpRequest): `upload` {loaded, total|null} → yüzde;
 * `sent` → gövde tamamen gitti, sunucu aşamasına geçilir.
 */
export function applyUploadProgress(run, p, now = Date.now()) {
  if (!run || run.status !== 'running' || !p || typeof p !== 'object') return run
  if (p.phase === 'sent') {
    const total = run.upload?.total ?? run.upload?.loaded ?? null
    return enterStage({ ...run, upload: { loaded: total ?? 0, total } }, serverStage(run), now)
  }
  if (p.phase !== 'upload') return run
  if (run.active !== 'upload' && run.stages.indexOf(run.active) > run.stages.indexOf('upload')) return run
  const loaded = Math.max(0, Number(p.loaded) || 0)
  const total = Number(p.total) > 0 ? Number(p.total) : null
  const next = { ...enterStage(run, 'upload', now), upload: { loaded: total != null ? Math.min(loaded, total) : loaded, total } }
  // Tüm baytlar gitti: Chromium küçük gövdelerde `upload.onload`u ancak yanıt başlıklarıyla verir — "sent"i beklemeden
  // sunucu aşamasına geçilir (kayıtta iptal de burada kilitlenir: istek artık sunucuda sayılır).
  return total != null && loaded >= total ? enterStage(next, serverStage(run), now) : next
}

/** Koşu başarısız: etkin aşama `error`, koşu `failed` (panel hangi aşamada durduğunu gösterir). */
export function failRun(run) {
  if (!run || run.status !== 'running') return run
  return { ...run, status: 'failed', failedStage: run.active, state: { ...run.state, [run.active]: 'error' } }
}

/** Etkin aşamanın yüzdesi (0..100) — bilinmiyorsa null (belirsiz çubuk). */
export function stagePercent(run) {
  if (!run || run.status !== 'running') return null
  if (run.active === 'upload' && run.upload?.total > 0) return Math.round((run.upload.loaded / run.upload.total) * 100)
  if (run.active === 'extract' && run.extract?.zip?.total > 0) return Math.round((run.extract.zip.done / run.extract.zip.total) * 100)
  return null
}

/**
 * Etkin aşamanın belirli ilerlemesi `{ value, max }` (ProgressBar) — bilinmiyorsa null. Yükleme bayt, ZIP girdi sayısı.
 */
export function stageMeter(run) {
  if (!run || run.status !== 'running') return null
  if (run.active === 'upload' && run.upload?.total > 0) return { value: run.upload.loaded, max: run.upload.total }
  if (run.active === 'extract' && run.extract?.zip?.total > 0) return { value: run.extract.zip.done, max: run.extract.zip.total }
  return null
}

/** "Vazgeç" şu an güvenli mi: analiz her an; kayıt yalnız gövde gönderilirken (istek sunucuya ulaşmadan). */
export function canCancel(run) {
  if (!run || run.status !== 'running') return false
  if (run.kind === 'analyze') return true
  return run.active === 'upload'
}

/** Aşama adının sözlük anahtarı (+ parametre). */
export function stageLabelKey(run, stage) {
  if (stage === 'save') {
    if (run?.saveKind === 'batch') return ['mcert.prog.stage.saveBatch', run.count || 0]
    if (run?.saveKind === 'renewed') return ['mcert.prog.stage.saveVersion']
    return ['mcert.prog.stage.save']
  }
  return [`mcert.prog.stage.${stage}`]
}

/** Yüzdeyi ekran okuyucu duyurusu için kaba adıma yuvarlar (her bayt güncellemesinde konuşmasın). */
export const coarsePercent = (pct, step = 25) => (pct == null ? null : Math.min(100, Math.floor(pct / step) * step))
