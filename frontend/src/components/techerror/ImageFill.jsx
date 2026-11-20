import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Switch } from '@/components/shadcn/switch'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useLatestCallback } from '../../hooks/useLatestCallback.js'
import { markActivity } from '../../utils/crossTabActivity.js'
import { imageFromDataTransfer, shortcutFamily } from './capture/captureModel.js'
import { renameClipboardFile } from './capture/clipboardImage.js'
import { shortcutKeys } from './capture/SlotCapture.jsx'
import { nextEmptySlot, readSeqFill, reportSlotOrder, slotPosition, writeSeqFill } from './imageFillModel.js'

/**
 * GÖRSEL YUVALARINI DOLDURMA (2026-11-20, C2 / C3 / C4 — "her ay aynı tekrarı azaltalım"). Rapor düzenleyicisi ve Görsel paneli
 * görsel bölümlerini bu sağlayıcıyla sarar:
 *   - {@link ImageFillContext} (SABİT): yuvaların kaydı, bir yuvaya NORMAL yükleme yolundan yükleme (`uploadTo` — hazırlama,
 *     doğrulama, kilit çakışması yeniden denemesi, öteki pencerelere duyuru yuvanın kendi kodunda), yükleme bildirimi.
 *     Yuvalar memo'lu bölümlerde olduğundan değer HİÇ değişmez (yazarken yeniden çizim yok).
 *   - {@link ImageFillStateContext}: "Sıralı doldur" anahtarı ve sıradaki boş yuva — yalnız göstergeyi besler.
 *   - {@link PrevImagesContext}: geçen ayın raporunu paylaşılan, tembel okuma (C3 "Geçen ay" görünümü).
 * Sağlayıcı yoksa (yalnız ImagesBlock çizen testler) hiçbiri yoktur: yuvalar bugünkü gibi davranır.
 */
export const ImageFillContext = createContext(null)
export const ImageFillStateContext = createContext(null)
export const PrevImagesContext = createContext(null)

/** Yükleme sonrası sıradaki yuvaya odak denemeleri (ms): kapalı bölüm önce açılır, içerik bir sonraki çizimde görünür. */
export const SEQ_FOCUS_TRIES_MS = Object.freeze([0, 80, 250])

/**
 * Sıralı yapıştırmanın yüklenmeyen her sonucunun KENDİ son cümlesi (inceleme 2026-11-20: yalnız "dolu" ve "hata" ele
 * alınıyordu; meşgul / bekletilen görsel / yuva yok / salt okunur / yüklenmedi "yükleniyor…" diye takılı kalıyordu).
 * `{0}` = yuvanın adı. Yüklendiyse null (cümleyi `onUploaded` yazar); bilinmeyen neden "yüklenmedi".
 */
export const SEQ_OUTCOME_KEYS = Object.freeze({
  failed: 'te.seq.pasteFailed', filled: 'te.seq.filledMeanwhile', busy: 'te.seq.busy', kept: 'te.seq.kept',
  noSlot: 'te.seq.noSlot', readOnly: 'te.seq.readOnly', notUploaded: 'te.seq.notUploaded',
})
export function seqOutcomeKey(r) {
  if (r?.status === 'done') return null
  if (r?.status === 'failed') return SEQ_OUTCOME_KEYS.failed
  return SEQ_OUTCOME_KEYS[r?.reason] || SEQ_OUTCOME_KEYS.notUploaded
}

/** Yazı alanı mı (yapıştırma o alana aittir)? */
const isEditableTarget = (el) => !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName || ''))
/** Açık pencere / menü / liste içinde mi (yapıştırma oraya aittir)? */
const insideOverlay = (el) => {
  try {
    return !!el?.closest?.('[role="dialog"],[role="alertdialog"],[role="menu"],[role="listbox"],[data-radix-popper-content-wrapper]')
  } catch { return false }
}

/**
 * Odak "boşta" mı: kullanıcı başka bir yere geçmediyse yükleme sonrası odak taşınabilir (yuvanın `wasBusy` kuralıyla aynı —
 * inceleme 2026-11-20: yavaş yüklemeden sonra odak, kullanıcının yazdığı alandan sıradaki yuvaya atlıyor, tuşlar
 * kayboluyordu). Boşta = gövde / belge / kopuk / kapalı öğe, yüklenen yuvanın içi (`owns(el)`) ya da yükleme başladığında
 * odaklı olan öğe (`startEl`).
 */
export function focusIsFree(owns = null, startEl = null) {
  let a
  try { a = document.activeElement } catch { return false }
  if (!a || a === document.body || a === document.documentElement || !a.isConnected || a.disabled) return true
  if (startEl && a === startEl) return true
  try { return !!owns?.(a) } catch { return false }
}

/**
 * Sağlayıcı. `sections`: şablon bölümleri (görsel bölümleri sırayla kullanılır), `images`: raporun görsel listesi, `readOnly`:
 * salt okunur / onay kilitli (sıralı doldurma ve toplu bırakma kapalı), `onOpenSection(id)`: kapalı bölümü açar (odak
 * taşınmadan önce), `resolve`: yuva adının yer tutucuları, `prev`: {@link PrevImagesContext} değeri (yoksa null), `inPanel`:
 * Görsel paneli. "Sıralı doldur" yalnız anahtarı GÖRÜNEN yerde çalışır (masaüstü / tablet ya da panel — {@link SeqFillBar}
 * ile aynı kural): telefonda anahtar yokken pencere düzeyindeki yapıştırma dinlenmez, yüklemeden sonra odak taşınmaz.
 */
export function ImageFillProvider({ sections, images, readOnly = false, onOpenSection = null, resolve = (s) => s, prev = null,
  inPanel = false, children }) {
  const t = useT()
  const isMobile = useIsMobile()
  const active = inPanel || !isMobile
  const activeRef = useRef(active)
  activeRef.current = active
  const order = useMemo(() => reportSlotOrder(sections), [sections])
  const [enabled, setEnabledState] = useState(readSeqFill)
  const [cursor, setCursor] = useState(null)
  const [msg, setMsg] = useState(null)
  const registry = useRef(new Map())
  const pending = useRef(new Set())
  // Pencere düzeyindeki yapıştırma başladığında odaklı olan öğe (yuva başına) — yükleme bitince odak hâlâ oradaysa taşınabilir
  const startFocus = useRef(new Map())
  const orderRef = useRef(order)
  orderRef.current = order
  const imagesRef = useRef(images)
  imagesRef.current = images
  const cursorRef = useRef(cursor)
  cursorRef.current = cursor
  const enabledRef = useRef(enabled)
  enabledRef.current = enabled
  const readOnlyRef = useRef(readOnly)
  readOnlyRef.current = readOnly
  const timers = useRef(new Set())
  useEffect(() => () => {
    for (const h of timers.current) clearTimeout(h)
    timers.current.clear()
  }, [])

  const handleOf = (id) => registry.current.get(id)?.current || null
  /**
   * Şu an boş mu: listede görsel yok, kayıtlı yuva görselsiz, meşgul değil, BEKLETİLEN dosyası yok (inceleme 2026-11-20: kilit
   * çakışması / ağ hatasından sonra yuvada tutulan kırpılmış ekran görüntüsü yeni yüklemeyle sessizce bırakılırdı) ve sıralı
   * yapıştırma ona yüklemiyor.
   */
  const emptyNow = (alsoFilled = null) => {
    const filled = new Set((imagesRef.current || []).map((i) => i?.slot_id))
    return (id) => {
      if (id === alsoFilled || filled.has(id) || pending.current.has(id)) return false
      const h = handleOf(id)
      return !(h && (h.isBusy?.() || h.hasImage?.() || h.hasKept?.()))
    }
  }
  const labelOf = (entry) => resolve(entry?.label) || entry?.id || ''
  /** Yüklenen yuvadan sonra odak taşınabilir mi (kullanıcı başka bir yere geçmediyse). */
  const freeAfter = (srcId) => focusIsFree((el) => !!handleOf(srcId)?.ownsFocus?.(el), startFocus.current.get(srcId) || null)

  /**
   * Sıradaki yuvanın bölümünü açar ve odağı oraya taşır (sınırlı deneme; zamanlayıcılar sökülünce temizlenir). Her denemede
   * odağın hâlâ boşta olduğu yeniden denetlenir — kullanıcı arada bir alana geçtiyse odağa dokunulmaz.
   */
  const focusSlot = useLatestCallback((entry, srcId) => {
    if (!entry) return
    try { onOpenSection?.(entry.sectionId) } catch { /* yok */ }
    let done = false
    for (const ms of SEQ_FOCUS_TRIES_MS) {
      const h = setTimeout(() => {
        timers.current.delete(h)
        if (done) return
        if (!freeAfter(srcId)) { done = true; return }
        done = !!handleOf(entry.id)?.focus?.()
      }, ms)
      timers.current.add(h)
    }
  })

  /** Bir yuvaya o yuvanın NORMAL yükleme yolundan yükler: `{ status: 'done'|'skipped'|'failed', reason?, message? }`. */
  const uploadTo = useLatestCallback(async (id, file, opts = {}) => {
    const h = handleOf(id)
    if (!h) return { status: 'skipped', reason: 'noSlot' }
    try {
      return (await h.upload(file, opts)) || { status: 'failed', message: '' }
    } catch (e) {
      return { status: 'failed', message: e?.message || '' }
    }
  })

  /**
   * Yuva yükledi (yalnız sıralı yapıştırma / olağan yükleme): açıksa odak sıradaki boş yuvaya — kullanıcı arada başka bir
   * yere (yazı alanı vb.) geçtiyse odak yerinde kalır, cümle sıradaki yuvayı yine söyler.
   */
  const onUploaded = useLatestCallback((id) => {
    if (!enabledRef.current || readOnlyRef.current || !activeRef.current) return
    setCursor(id)
    const next = nextEmptySlot(orderRef.current, emptyNow(id), id)
    if (!next) { setMsg(t('te.seq.allFilled')); return }
    const pos = slotPosition(orderRef.current, next.id)
    if (!freeAfter(id)) {
      setMsg(t('te.seq.nextStay', labelOf(next), pos?.pos ?? '—', pos?.total ?? '—'))
      return
    }
    setMsg(t('te.seq.movedTo', labelOf(next), pos?.pos ?? '—', pos?.total ?? '—'))
    focusSlot(next, id)
  })

  /** Sıralı yapıştırmanın yüklenmeyen sonucu → yuvanın adıyla son cümle (yüklendiyse cümleyi `onUploaded` yazar). */
  const pasteOutcome = (r, label) => {
    const key = seqOutcomeKey(r)
    return key ? t(key, label) : null
  }

  /** Pencere düzeyindeki yapıştırma: dosya SIRADAKİ BOŞ yuvaya — dolu yuva asla hedef olmaz. */
  const pasteNext = useLatestCallback(async (file, image) => {
    const next = nextEmptySlot(orderRef.current, emptyNow(null), cursorRef.current)
    if (!next) { setMsg(t('te.seq.noEmptyPaste')); return }
    const pos = slotPosition(orderRef.current, next.id)
    const label = labelOf(next)
    setMsg(t('te.seq.pasting', label, pos?.pos ?? '—', pos?.total ?? '—'))
    markActivity()
    pending.current.add(next.id)
    let a = null
    try { a = document.activeElement } catch { /* yok */ }
    startFocus.current.set(next.id, a)
    let r
    try {
      r = await uploadTo(next.id, image ? renameClipboardFile(file, next.id) : file, { mode: 'empty', advance: true })
    } finally {
      pending.current.delete(next.id)
      // Odak denemeleri (≤ 250 ms) başlangıç öğesini okuyabilsin diye biraz sonra bırakılır
      const h = setTimeout(() => { timers.current.delete(h); if (startFocus.current.get(next.id) === a) startFocus.current.delete(next.id) },
        SEQ_FOCUS_TRIES_MS[SEQ_FOCUS_TRIES_MS.length - 1] + 50)
      timers.current.add(h)
    }
    const line = pasteOutcome(r, label)
    if (line) setMsg(line)
  })

  const isFilled = useLatestCallback((id) => {
    if ((imagesRef.current || []).some((i) => i?.slot_id === id)) return true
    return !!handleOf(id)?.hasImage?.()
  })
  /** Yuvada gönderilememiş (bekletilen) bir görsel var mı — toplu bırakma o yuvayı hedef seçmez. */
  const isKept = useLatestCallback((id) => !!handleOf(id)?.hasKept?.())
  const register = useCallback((id, ref) => {
    registry.current.set(id, ref)
    return () => { if (registry.current.get(id) === ref) registry.current.delete(id) }
  }, [])
  const api = useMemo(() => ({ register, uploadTo, onUploaded, isFilled, isKept, order: () => orderRef.current }),
    [register, uploadTo, onUploaded, isFilled, isKept])

  // Pencere düzeyinde Ctrl/⌘+V (yalnız açıkken ve anahtar GÖRÜNÜRKEN — telefonda anahtar yok, gizli yükleme olmaz):
  // yazı alanı, açık pencere / menü ya da yuvanın kendi yapıştırması değil
  useEffect(() => {
    if (!enabled || readOnly || !active || !order.length) return undefined
    const onPaste = (e) => {
      if (e.defaultPrevented) return
      const el = e.target
      if (isEditableTarget(el) || insideOverlay(el)) return
      const { file, image } = imageFromDataTransfer(e.clipboardData)
      if (!file) return
      e.preventDefault()
      pasteNext(file, image)
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
  }, [enabled, readOnly, active, order.length, pasteNext])

  const setEnabled = useCallback((on) => {
    setEnabledState(!!on)
    writeSeqFill(!!on)
    setCursor(null)
    setMsg(null)
  }, [])

  // Gösterge: sıradaki boş yuva (listeye göre — meşgul yuva görsel gelince düşer)
  const filledKey = (images || []).map((i) => i?.slot_id).sort().join('|')
  const next = useMemo(() => {
    const filled = new Set(filledKey ? filledKey.split('|') : [])
    const e = nextEmptySlot(order, (id) => !filled.has(id), cursor)
    if (!e) return null
    const pos = slotPosition(order, e.id)
    return { id: e.id, label: resolve(e.label) || e.id, pos: pos?.pos ?? null }
  }, [order, filledKey, cursor, resolve])
  const state = useMemo(() => ({ enabled, setEnabled, next, total: order.length, msg, readOnly: !!readOnly }),
    [enabled, setEnabled, next, order.length, msg, readOnly])

  return (
    <ImageFillContext.Provider value={api}>
      <ImageFillStateContext.Provider value={state}>
        <PrevImagesContext.Provider value={prev}>
          {children}
        </PrevImagesContext.Provider>
      </ImageFillStateContext.Provider>
    </ImageFillContext.Provider>
  )
}

/**
 * "Sıralı doldur" anahtarı + "Sıradaki: <yuva> (7/15)" (ilk görsel bölümünün başlık alanında; Görsel panelinde de). Klavye
 * kısayoluyla çalıştığından masaüstüne özgü (telefonda yok — ekrandan alma ve panel gibi); salt okunur raporda yok.
 */
export function SeqFillBar({ inPanel = false }) {
  const s = useContext(ImageFillStateContext)
  const t = useT()
  const isMobile = useIsMobile()
  if (!s || s.readOnly || !s.total || (isMobile && !inPanel)) return null
  const id = inPanel ? 'te-seqfill-panel' : 'te-seqfill'
  const keys = shortcutKeys(shortcutFamily())
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 px-3 py-2" data-slot="te-seqfill"
      data-enabled={s.enabled ? 'true' : 'false'}>
      <div className="flex items-start gap-3">
        <Switch id={id} checked={s.enabled} onCheckedChange={(c) => s.setEnabled(c === true)} aria-describedby={`${id}-h`}
          className="mt-0.5" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <label htmlFor={id} className="text-sm font-medium leading-snug">{t('te.seq.label')}</label>
          <p id={`${id}-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.seq.hint', keys.paste)}</p>
        </div>
      </div>
      {s.enabled && (
        <p className="m-0 text-sm font-medium [overflow-wrap:anywhere]" data-slot="te-seqfill-next" data-next={s.next?.id || ''}>
          {s.next ? t('te.seq.next', s.next.label, s.next.pos ?? '—', s.total) : t('te.seq.noEmpty')}
        </p>
      )}
      {/* Son işlemin cümlesi (yapıştırma hangi yuvaya gitti, odak nereye geçti) — ekran okuyucuya da söylenir */}
      <p className={s.enabled && s.msg ? 'm-0 text-xs text-muted-foreground [overflow-wrap:anywhere]' : 'sr-only'}
        role="status" aria-live="polite" data-slot="te-seqfill-msg">{s.enabled ? s.msg || '' : ''}</p>
    </div>
  )
}

/**
 * Yuvanın "Geçen ay" görünümü (C3): açılınca geçen ayın raporu PAYLAŞILAN okumayla BİR KEZ okunur (`prev.load()`), aynı
 * yuva kimliğinin görseli bulunur. Okunamazsa "okunamadı" (ASLA "yoktu"); okunduysa ve yuva boşsa "görsel yoktu". Görsel
 * kopyalanmaz — yalnız gösterilir.
 */
export function usePrevSlotImage(prev, slotId) {
  const [on, setOn] = useState(false)
  const [st, setSt] = useState({ status: 'idle', image: null, error: null })
  const alive = useRef(true)
  const seq = useRef(0)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  // Başka rapor (geçen ayın kaynağı değişti): görünüm kapanır, okunan sonuç bırakılır
  const prevId = prev?.id ?? null
  const [seenPrev, setSeenPrev] = useState(prevId)
  if (seenPrev !== prevId) {
    setSeenPrev(prevId)
    setOn(false)
    setSt({ status: 'idle', image: null, error: null })
  }
  const load = useCallback(() => {
    if (!prev?.load) return
    const my = ++seq.current
    setSt({ status: 'loading', image: null, error: null })
    prev.load().then((data) => {
      if (!alive.current || my !== seq.current) return
      const list = Array.isArray(data?.images) ? data.images : null
      if (!list) { setSt({ status: 'failed', image: null, error: 'failed' }); return }
      setSt({ status: 'ok', image: list.find((i) => i?.slot_id === slotId) || null, error: null })
    }, (e) => {
      if (!alive.current || my !== seq.current) return
      setSt({ status: 'failed', image: null, error: e?.kind || 'failed' })
    })
  }, [prev, slotId])
  const onRef = useRef(on)
  onRef.current = on
  const stRef = useRef(st)
  stRef.current = st
  const toggle = useCallback(() => {
    const nextOn = !onRef.current
    setOn(nextOn)
    if (nextOn && (stRef.current.status === 'idle' || stRef.current.status === 'failed')) load()
  }, [load])
  return { on, toggle, state: st, retry: load }
}
