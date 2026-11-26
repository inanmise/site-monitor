/**
 * Nöbet Rutinleri — satır içi not düzenleyicisi (görev notu, günün notu, sonraki nöbetçiye not). Pencere AÇILMAZ: liste
 * içinde açılır, yazdıkça kendini kaydeder (800 ms), alandan çıkınca / Esc / satır değişince / sayfa kapanırken anında
 * kaydeder. Kaydedilmemiş metin kişisel depoda (`sm.or.…`) kalır — kaydedilince silinir. Başkası aynı notu değiştirdiyse
 * (409 NOTE_CHANGED) iki metin yan yana gösterilir: "Benimkini kaydet" ya da "Onlarınkini al".
 */
import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { RotateCcw } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { api } from '../../api/client'
import { personalKey } from '../../utils/personalStorage.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { codeOf, hmOf, thrownResult } from './routineModel.js'
import { serverText } from './routineUi.jsx'

/** Kayıt gecikmesi (ms). */
export const NOTE_DEBOUNCE_MS = 800

/** Taslak ömrü: 7 günlük düzenleme penceresi + 1 gün. Daha eski taslak okunmaz, silinir. */
export const NOTE_DRAFT_MAX_AGE_MS = 8 * 86_400_000
const NOTE_DRAFT_MAX_CHARS = 20_000

/**
 * Kişisel not taslağı: `{v:1, text, base, at}` — metin + DAYANDIĞI not sürümü (`base`) + yazılma anı. Taslak geri
 * yüklenip gönderilirse kendi taban sürümüyle gider: o arada başkası kaydettiyse sunucu 409 `NOTE_CHANGED` verir ve
 * çakışma paneli açılır — dünkü taslak bugünkü notu sessizce ezmez. Eski biçim (düz metin), bozuk ya da bayat → null (silinir).
 */
export function readNoteDraft(key, nowMs = Date.now()) {
  if (!key) return null
  let raw = null
  try { raw = globalThis.localStorage?.getItem(personalKey(key)) ?? null } catch { return null }
  if (raw == null) return null
  let d = null
  try { d = JSON.parse(raw) } catch { d = null }
  const ok = !!d && typeof d === 'object' && d.v === 1 && typeof d.text === 'string' && d.text.length <= NOTE_DRAFT_MAX_CHARS
    && Number.isInteger(d.base) && d.base >= 0 && Number.isFinite(d.at) && nowMs - d.at <= NOTE_DRAFT_MAX_AGE_MS
  if (!ok) { writeNoteDraft(key, null); return null }
  return { text: d.text, base: d.base, at: d.at }
}
export function writeNoteDraft(key, text, base = 0) {
  if (!key) return
  try {
    if (text == null) globalThis.localStorage?.removeItem(personalKey(key))
    else globalThis.localStorage?.setItem(personalKey(key), JSON.stringify({ v: 1, text: String(text).slice(0, NOTE_DRAFT_MAX_CHARS), base: Number(base) || 0, at: Date.now() }))
  } catch { /* depolama kapalı */ }
}

/** Metin kaydedilecek biçime getirilir (sunucuyla aynı: CRLF → LF, kenar boşluğu atılır). */
export function normalizeNote(s) {
  return String(s ?? '').replace(/\r\n?/g, '\n').trim()
}

/**
 * Genel not düzenleyicisi.
 * @param {object} p
 * @param {string} p.slot          data-slot
 * @param {string} p.label         görünür etiket (alanın adı)
 * @param {string} p.value         sunucudaki not
 * @param {number} p.version       not sürümü (CAS)
 * @param {(note: string, version: number) => Promise<object|null>} p.save  istek — `{success, data}` ya da hata gövdesi
 * @param {(data: object) => void} [p.onSaved]   başarılı kayıt (sunucu verisi)
 * @param {string} [p.draftKey]    kişisel taslak anahtarı tabanı
 * @param {number} p.maxLength
 * @param {string[]} [p.chips]     hızlı metin çipleri (ör. "Sorun yok")
 * @param {boolean} [p.autoFocus]
 * @param {() => void} [p.onClose] Esc / Ctrl+Enter sonrası (kayıttan sonra)
 * @param {boolean} [p.readOnly]
 * @param {string} [p.placeholder]
 * @param {number} [p.rows]
 */
function NoteEditorImpl({
  slot, label, value = '', version = 0, save, onSaved, draftKey, maxLength, chips = null, autoFocus = false, onClose,
  readOnly = false, placeholder, rows = 3, hint = null, className,
}) {
  const t = useT()
  const id = useId()
  // Açılış: sunucudakinden farklı bir taslak varsa metin taslaktır ve taban SÜRÜMÜ taslağınkidir (taban değeri sunucudaki not —
  // "kirli" karşılaştırması ona göre); aynıysa taslak atılır
  const init = useRef(null)
  if (init.current == null) {
    const serverText = value ?? ''
    const d = readNoteDraft(draftKey)
    const useDraft = !!d && normalizeNote(d.text) !== normalizeNote(serverText)
    if (d && !useDraft) writeNoteDraft(draftKey, null)
    init.current = useDraft
      ? { text: d.text, status: 'dirty', base: { value: serverText, version: d.base } }
      : { text: serverText, status: 'idle', base: { value: serverText, version: Number(version) || 0 } }
  }
  const [text, setText] = useState(init.current.text)
  const [status, setStatus] = useState(init.current.status)
  const [savedAt, setSavedAt] = useState(null)
  const [conflict, setConflict] = useState(null)
  const [error, setError] = useState(null)
  const base = useRef(init.current.base)
  // Kirliyken gelen daha yeni sunucu notu (taban TAŞINMAZ; yalnız değişiklik geri alınırsa ekrana alınır)
  const incoming = useRef(null)
  const textRef = useRef(text)
  textRef.current = text
  const timer = useRef(null)
  const saving = useRef(null)
  const alive = useRef(true)
  const saveRef = useRef(save)
  saveRef.current = save
  const savedRef = useRef(onSaved)
  savedRef.current = onSaved
  const areaRef = useRef(null)
  const conflictRef = useRef(null)
  conflictRef.current = conflict
  const maxRef = useRef(maxLength)
  maxRef.current = maxLength

  // Sunucudaki not değişti (yoklama / başkası). Yazılmamış değişiklik ya da süren kayıt varken taban TAŞINMAZ: sonraki kayıt
  // eski sürümle gider → sunucu 409 NOTE_CHANGED verir, çakışma paneli açılır (başkasının notu sessizce ezilmez). Yoksa yeni
  // not ekrana alınır.
  useEffect(() => {
    const v = value ?? ''
    const ver = Number(version) || 0
    if (ver === base.current.version && v === base.current.value) return
    const local = normalizeNote(textRef.current)
    if (saving.current || local !== normalizeNote(base.current.value)) {
      if (!saving.current && local === normalizeNote(v)) {
        // Başkası tam bu metni kaydetti: çakışma yok, taban yeni sürüme geçer
        base.current = { value: v, version: ver }
        incoming.current = null
        writeNoteDraft(draftKey, null)
        setStatus('idle')
        return
      }
      incoming.current = { value: v, version: ver }
      return
    }
    base.current = { value: v, version: ver }
    incoming.current = null
    setText(v)
    writeNoteDraft(draftKey, null)
  }, [value, version, draftKey])

  const doSave = useCallback(async ({ force = null } = {}) => {
    if (readOnly) return
    if (timer.current) { clearTimeout(timer.current); timer.current = null }
    const note = normalizeNote(textRef.current)
    // Sınırı aşan metin gönderilmez (sunucu reddeder); alan altında "çok uzun" görünür, taslak kalır
    if (maxRef.current && note.length > maxRef.current) { if (alive.current) setStatus('dirty'); return }
    if (force == null && note === normalizeNote(base.current.value)) {
      // Değişiklik geri alındı; o arada sunucuya daha yeni not geldiyse o gösterilir
      const inc = incoming.current
      if (inc) {
        incoming.current = null
        base.current = inc
        if (alive.current) setText(inc.value)
      }
      writeNoteDraft(draftKey, null)
      if (alive.current) setStatus((s) => (s === 'dirty' ? 'idle' : s))
      return
    }
    if (saving.current) { await saving.current; if (normalizeNote(textRef.current) === normalizeNote(base.current.value)) return }
    const ver = force != null ? force : base.current.version
    if (alive.current) { setStatus('saving'); setError(null) }
    const p = (async () => {
      let res
      try { res = await saveRef.current(note, ver) } catch (e) { res = thrownResult(e) }
      if (res === null) return
      if (res?.success) {
        const d = res.data || {}
        const cur = d.item || d.day || d.handover || d
        const nextVer = Number(cur?.note_version ?? (ver + 1))
        base.current = { value: cur?.note ?? note, version: nextVer }
        const stillSame = normalizeNote(textRef.current) === note
        const inc = incoming.current
        incoming.current = null
        if (inc && inc.version > nextVer && stillSame) {
          // Kaydımızdan SONRA başkası da kaydetti (yoklama erken getirdi) ve biz yazmıyoruz: onunki gösterilir
          base.current = inc
          if (alive.current) setText(inc.value)
        } else if (inc && inc.version > nextVer) {
          incoming.current = inc
        }
        if (stillSame) writeNoteDraft(draftKey, null)
        else writeNoteDraft(draftKey, textRef.current, base.current.version)
        if (alive.current) {
          setConflict(null)
          setSavedAt(hmOf(cur?.note_at) || hmOf(new Date().toISOString()))
          setStatus(normalizeNote(textRef.current) === note ? 'saved' : 'dirty')
        }
        savedRef.current?.(d)
        return
      }
      if (codeOf(res) === 'NOTE_CHANGED' && res.current) {
        if (alive.current) { setConflict(res.current); setStatus('conflict') }
        return
      }
      if (alive.current) {
        setStatus('failed')
        setError(isUnsureResult(res) ? t('or.note.unsure') : serverText(res, t, 'or.note.saveFailed'))
      }
    })()
    saving.current = p
    try { await p } finally { if (saving.current === p) saving.current = null }
  }, [draftKey, readOnly, t])

  const schedule = useCallback(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => { timer.current = null; doSave() }, NOTE_DEBOUNCE_MS)
  }, [doSave])

  // Yazılmamış değişiklik var mı (bekleyen zamanlayıcı ya da geri yüklenen taslak); çakışma açıkken kendiliğinden gönderilmez
  const pendingWrite = useCallback(() => !!timer.current
    || (!conflictRef.current && normalizeNote(textRef.current) !== normalizeNote(base.current.value)), [])

  // Söküm + sayfa kapanışı: bekleyen kayıt hemen gönderilir (yanıt beklenmez)
  useEffect(() => {
    alive.current = true
    const onHide = () => { if (pendingWrite()) doSave() }
    window.addEventListener('pagehide', onHide)
    return () => {
      window.removeEventListener('pagehide', onHide)
      alive.current = false
      if (pendingWrite()) doSave()
    }
  }, [doSave, pendingWrite])

  useEffect(() => {
    if (autoFocus && areaRef.current) {
      const el = areaRef.current
      el.focus()
      try { el.setSelectionRange(el.value.length, el.value.length) } catch { /* yok */ }
    }
  }, [autoFocus])

  const onChange = (e) => {
    const v = e.target.value
    setText(v)
    writeNoteDraft(draftKey, v, base.current.version)
    setStatus('dirty')
    schedule()
  }

  const close = async () => {
    await doSave()
    onClose?.()
  }

  const onKeyDown = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); close() }
  }

  const applyChip = (chip) => {
    const cur = textRef.current
    const next = normalizeNote(cur) ? `${cur.replace(/\s+$/, '')}\n${chip}` : chip
    setText(next)
    writeNoteDraft(draftKey, next, base.current.version)
    setStatus('dirty')
    schedule()
    areaRef.current?.focus()
  }

  const keepMine = () => { incoming.current = null; setConflict(null); doSave({ force: Number(conflict?.note_version) || 0 }) }
  const takeTheirs = () => {
    const v = conflict?.note ?? ''
    base.current = { value: v, version: Number(conflict?.note_version) || 0 }
    incoming.current = null
    setText(v)
    writeNoteDraft(draftKey, null)
    setConflict(null)
    setStatus('idle')
  }

  const over = maxLength && text.length > maxLength
  const statusText = status === 'saving' ? t('or.note.saving')
    : status === 'saved' ? t('or.note.saved', savedAt || '')
      : status === 'dirty' ? t('or.note.unsaved')
        : null

  return (
    <div data-slot={slot} className={cn('flex flex-col gap-2', className)}>
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">{label}</label>
      {chips?.length > 0 && !readOnly && (
        <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('or.note.chipsLabel')}>
          {chips.map((c) => (
            <Button key={c} type="button" size="sm" variant="outline" data-slot="or-note-chip"
              className="h-7 rounded-full px-3 text-xs pointer-coarse:h-10" onClick={() => applyChip(c)}>
              {c}
            </Button>
          ))}
        </div>
      )}
      <Textarea id={id} ref={areaRef} value={text} rows={rows} readOnly={readOnly} placeholder={placeholder}
        aria-invalid={over || undefined} aria-describedby={`${id}-st`}
        onChange={onChange} onBlur={() => { if (pendingWrite()) doSave() }} onKeyDown={onKeyDown}
        className="min-h-16 resize-y text-sm" />
      <div id={`${id}-st`} className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <span data-slot={`${slot}-status`} data-status={status} aria-live="polite" className="inline-flex items-center gap-1.5">
          {status === 'saving' && <Spinner className="size-3" />}
          {status === 'failed' ? <span className="text-destructive">{error}</span> : statusText}
          {status === 'failed' && (
            <Button type="button" size="sm" variant="ghost" className="h-6 px-2 text-xs pointer-coarse:h-10" onClick={() => doSave({ force: null })}
              data-slot={`${slot}-retry`}>
              <RotateCcw aria-hidden="true" />{t('or.note.retry')}
            </Button>
          )}
        </span>
        <span className={cn('tabular-nums', over && 'text-destructive')}>
          {hint}{maxLength ? ` ${text.length} / ${maxLength}` : ''}
        </span>
      </div>
      {over && <p className="m-0 text-xs text-destructive">{t('or.note.tooLong', maxLength)}</p>}
      {conflict && (
        <div data-slot={`${slot}-conflict`} role="alert"
          className="flex flex-col gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <p className="m-0">{t('or.note.conflict', conflict.note_by_name || t('or.resp.unknownPerson'), hmOf(conflict.note_at) || '')}</p>
          <blockquote className="m-0 border-l-2 border-border pl-3 whitespace-pre-wrap text-muted-foreground [overflow-wrap:anywhere]">
            {conflict.note || t('or.note.empty')}
          </blockquote>
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={keepMine} data-slot={`${slot}-keep-mine`}>{t('or.note.keepMine')}</Button>
            <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={takeTheirs} data-slot={`${slot}-take-theirs`}>{t('or.note.takeTheirs')}</Button>
          </div>
        </div>
      )}
    </div>
  )
}

export const NoteEditor = memo(NoteEditorImpl)

/** Görev notu: çipler "Sorun yok", "Takip ediliyor", "Bilgi verildi"; en fazla 1000 karakter. */
export default function TaskNote({ teamId, contextDate, item, onSaved, onClose, readOnly }) {
  const t = useT()
  const chips = useMemo(() => [t('or.note.chip.ok'), t('or.note.chip.tracking'), t('or.note.chip.informed')], [t])
  const save = useCallback((note, version) => api.oncallRoutines.saveItemNote(teamId, {
    date: contextDate, task_id: item.task_id, period_kind: item.period_kind, period_key: item.period_key,
    note, note_version: version,
  }), [teamId, contextDate, item.task_id, item.period_kind, item.period_key])
  return (
    <NoteEditor slot="or-task-note" label={t('or.note.taskLabel')} value={item.note || ''} version={item.note_version || 0}
      save={save} onSaved={onSaved} onClose={onClose} readOnly={readOnly} autoFocus maxLength={1000} rows={2}
      draftKey={`sm.or.note.${teamId}.${item.key || `${item.period_kind}:${item.period_key}:${item.task_id}`}`}
      chips={chips}
      placeholder={t('or.note.taskPlaceholder')} />
  )
}
