import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ListChecks, Pencil, FileText, Users, Tags, Stethoscope, Mail, ChevronDown, RefreshCw, Save, AlertOctagon, Info,
  History, Lightbulb, Trash2, Undo2, Zap, Maximize2, Copy,
} from 'lucide-react'
import { useFileDropGuard } from '../techerror/capture/useFileDropGuard.js'
import { stripUploadPlaceholders, uploadInFlight, hasPendingUpload } from '../../utils/mdUpload.js'
import { QUICK_FIELDS, hasHiddenErrors } from './quickEntryModel.js'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { autoDurationMinutes } from '../../utils/incidentMeta.js'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../../utils/mailPreview.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { draftScope, readDraft, writeDraft, removeDraft, applyDraft, DRAFT_DEBOUNCE_MS } from './incidentDraft.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/shadcn/tabs'
import { FieldSet, FieldLegend } from '@/components/shadcn/field'
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import {
  SEVERITIES, STATUSES, CATEGORIES, PROBLEM_TYPES, SECTIONS, EMPTY_FORM, EDIT_KEYS, MARKDOWN_KEYS, validateIncident,
  errorCountBySection, firstErrorSection, formSnapshot, previewKind, payloadFromForm, uniqueCaption, formFromRow, editPayload,
  changedKeys, formChanges, consistencyNudges, applyNudge, resolvedNowIso,
} from './incidentHistoryModel.js'
import {
  TextInput, DateInput, SelectInput, CreatableSelect, CreatableMultiSelect, TagField, MdArea, CheckInput, YesNoInput,
} from './formFields.jsx'

const SECTION_ICON = { summary: FileText, impact: Users, codes: Tags, rca: Stethoscope, notify: Mail }

/** Hatalı alan: aria-invalid taşıyan kontrol ya da hatalı markdown alanının metin kutusu (grup data-invalid taşır). */
const INVALID_FIELD = '[aria-invalid="true"], [data-slot="ih-md-field"][data-invalid="true"] textarea'

/** Açılışta kaydın taze hâlini okuma süresi (ms) — istemci GET varsayılanı 90 sn; pencere bu kadar beklemez. */
export const FRESH_TIMEOUT_MS = 20_000

/**
 * Sunucunun alan hatasını (400 `VALIDATION_FAILED` + `fields`) ALANIN ALTINDA gösterebildiğimiz alanlar — formda hata
 * yuvası olan kontroller (2026-11-20, "Sağlam Kayıt": sunucunun adlandırabildiği her form alanı). Listede olmayan bir
 * alanın hatası (ör. `alert_event_id`, `version`) eskisi gibi üstteki bantta kalır.
 */
export const SERVER_ERROR_FIELDS = new Set([
  'title', 'occurred_at', 'team_id', 'severity', 'status', 'category', 'problem_types',
  'detected_at', 'resolved_at', 'duration_minutes',
  'channel', 'service', 'affected_services', 'affected_app', 'affected_systems', 'affected_customers',
  'affected_transactions', 'tech_error_count', 'error_budget_burn_pct', 'sla_breached', 'outage_related', 'outage_id',
  'business_impact', 'error_code', 'function_code', 'channel_code', 'tags', 'rca_summary', 'description', 'resolution_steps',
  'runbook_url',
])

/** Taslağın uygulanabileceği form anahtarları (takım adı takım kimliğiyle birlikte gelir). */
const DRAFT_KEYS = [...EDIT_KEYS, 'team_name']

/** Hızlı kayıtta görünen anahtarlar + birlikte gelen takım adı ve "Mail gönder" — taslak bunların dışını taşıyorsa tam görünüm. */
const QUICK_VISIBLE = new Set([...QUICK_FIELDS, 'team_name', 'send_notification'])

/** Taslak yazılırken süren / yarım yükleme yer tutucuları atılır (geri yüklenince sahipsiz kalırdı). */
function draftChanges(changes) {
  const out = { ...changes }
  for (const k of MARKDOWN_KEYS) if (typeof out[k] === 'string') out[k] = stripUploadPlaceholders(out[k])
  return out
}

/** Geri yüklenen taslağın metinlerinden yüklemesi bu sekmede SÜRMEYEN (sahipsiz) yer tutucular atılır. */
function restoredChanges(changes) {
  const out = { ...(changes || {}) }
  for (const k of MARKDOWN_KEYS) if (typeof out[k] === 'string') out[k] = stripUploadPlaceholders(out[k], uploadInFlight)
  return out
}

/** Etkin bölümdeki ilk hatalı alan (bölümler hep DOM'da — gizli bölümdeki alana odak gitmez). */
function firstInvalidField(root) {
  if (!root) return null
  const panel = root.querySelector('[data-slot="tabs-content"]:not([hidden])') || root
  return panel.querySelector(INVALID_FIELD)
}

/** Kaydın markdown metinlerini forma işler: kullanıcının (ya da geri yüklenen taslağın) değiştirdiği alan korunur. */
function mergeMarkdown(form, base, rec) {
  const out = { ...form }
  for (const k of MARKDOWN_KEYS) {
    if (String(form[k] ?? '') === String(base?.[k] ?? '')) out[k] = rec?.[k] ?? ''
  }
  return out
}
const pickMarkdown = (rec) => Object.fromEntries(MARKDOWN_KEYS.map((k) => [k, rec?.[k] ?? '']))

/** Form alanlarından biri (metin kutusu / metin alanı / düzenlenebilir içerik) şu an odakta mı — kullanıcı yazıyor sayılır. */
function focusedEditableIn(root) {
  const el = typeof document !== 'undefined' ? document.activeElement : null
  if (!el || !root || typeof root.contains !== 'function' || !root.contains(el)) return false
  return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable === true
}

/** Sunucudan gelen tekil kayıt geçerli mi (beklenen kimlik + düz nesne) — eski / sahte gövde formu ezmesin. */
function validRecord(res, id) {
  const d = res?.success ? res.data : null
  return d && typeof d === 'object' && !Array.isArray(d) && d.id != null && String(d.id) === String(id) ? d : null
}

/** Yanıtın HTTP durumu (sayılamayan künyeden). */
const statusOf = (res) => errorInfoOf(res)?.status

/**
 * Sunucu alan hataları (`{ alan: ileti }`) → `shown`: formda o an GÖRÜNEN alanların iletileri (yoksa null), `rest`: alanı
 * gösterilemeyen bir hata da var mı. Kesinti ID alanı yalnız cevap Evet iken çizilir.
 */
function splitServerErrors(fields, form) {
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) return { shown: null, rest: false }
  const shown = {}
  let rest = false
  for (const [k, msg] of Object.entries(fields).slice(0, 50)) {
    if (!msg) continue
    if (SERVER_ERROR_FIELDS.has(k) && (k !== 'outage_id' || form.outage_related === true)) shown[k] = String(msg)
    else rest = true
  }
  return { shown: Object.keys(shown).length ? shown : null, rest }
}

/**
 * Sunucu alan hatası gösterildiğinde (sayaç `tick` arttığında) ETKİN bölümdeki ilk hatalı alana odaklanır. Bölüm
 * içeriğinin İÇİNE çizilir: bölüm değiştiyse Radix Tabs yeni içeriği bir sonraki eşzamanlı çizimde DOM'a koyar; bu
 * bileşen o içerikle birlikte doğar ve etkisi alanlar DOM'dayken çalışır (üst bileşendeki etki, kayıt isteğinden sonraki
 * — tıklama olayının dışındaki — çizimde içerik gelmeden çalışıp alanı bulamıyordu). Zamanlayıcı / yeniden deneme YOK:
 * her sayaç değeri tam BİR kez işlenir (`handledRef`), kullanıcı sonradan bölüm değiştirince odak yeniden taşınmaz.
 * Eski (terk edilen) bölümün örneği `active=false` olduğu için hiçbir şey yapmaz.
 */
function FocusServerError({ active, tick, handledRef, bodyRef }) {
  useEffect(() => {
    if (!active || tick === handledRef.current) return
    handledRef.current = tick
    const el = firstInvalidField(bodyRef.current)
    if (el && typeof el.focus === 'function') { el.focus(); el.scrollIntoView?.({ block: 'center' }) }
  }, [active, tick, handledRef, bodyRef])
  return null
}

/**
 * Pencere boyutu SABİT (kullanıcı kuralı, MonitorDetailModal deseni): ≥640 px'te yükseklik `min(88vh, 100dvh-2rem)`,
 * bölüm (sekme) değişince kutu büyüyüp küçülmez, dikey ortalı pencere yeniden konumlanmaz; kaydırma çubuğu için yer
 * hep ayrılı (genişlik oynamaz). Telefonda (< 640) tam ekran. Alanlar telefonda ≥ 40 px dokunma hedefi.
 */
const FIXED = cn(
  'grid-cols-[minmax(0,1fr)] sm:max-w-[min(1040px,calc(100%-2rem))] sm:h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
  '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable] max-sm:[&_[data-slot=dialog-close]]:size-10',
)
const PHONE_TOUCH = 'max-sm:[&_[data-slot=input]]:h-10 max-sm:[&_[data-slot=native-select]]:h-10 max-sm:[&_[data-slot=date-picker-trigger]]:h-10 max-sm:[&_[role=combobox]]:min-h-10'
const GRID = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3'
/** Hızlı kayıt penceresi: daha dar, yüksekliği içeriğe göre (tam görünüme geçince FIXED olur). */
const QUICK_SIZE = 'grid-cols-[minmax(0,1fr)] sm:max-w-[min(760px,calc(100%-2rem))] sm:w-full max-sm:[&_[data-slot=dialog-close]]:size-10'

/**
 * Olay oluştur / düzenle penceresi — beş bölüm (shadcn Tabs): Özet · Etki · Kodlar ve etiketler · Kök neden ve çözüm ·
 * Bildirim. Kabuk `ui/ModalShell` (scrollBody: yalnız gövde kayar, altlık sabit); bölüm çubuğu gövdenin üstünde yapışık.
 *
 * <p>Davranış: satır içi doğrulama (ilk kaydetme denemesinden sonra canlı; hatalı bölümün sekmesinde sayı rozeti, kayıt
 * ilk hatalı bölüme götürür ve ilk hatalı alana odaklanır) · Ctrl/⌘+Enter kaydeder · kaydedilmemiş değişiklik varken
 * kapatmak (Escape, X, Vazgeç) onay sorar · gönderim sürerken kapatma yolları kapalı (`busy`) · meşgul bayrakları
 * `finally`'de söner · sunucu hatası pencerede satır içi kalır (form kaybolmaz). Mail önizlemesi Bildirim bölümünde
 * katlanır; form önizlemeden sonra değiştiyse "yenileyin" uyarısı çıkar.
 *
 * <p>Sabit ana alanların hepsi korunur: seçenek ekle/sil (kanal, servis/domain, hata/fonksiyon/kanal kodu), oluş ↔ çözülme
 * farkından otomatik süre, markdown görsel yükleme (yeni kayıtta taslak), "Mail gönder".
 *
 * <p><b>Sağlam kayıt (2026-11-20).</b>
 *   - Düzenleme penceresi liste satırıyla HEMEN açılır, kaydın taze hâli arkada okunur (`FRESH_TIMEOUT_MS`, iptal
 *     edilebilir). Kullanıcı henüz bir şey yazmadıysa taze kayıt formun yerine geçer (farklıysa bilgi notu); yazmaya
 *     başladıysa ASLA ezilmez — "kayıt siz düzenlemeye başladıktan sonra değişti" notu + "Son hâli yükle". Okunamadıysa
 *     liste hâli kalır (sessiz not); 404 → "kayıt silinmiş", kaydetme kapalı.
 *   - Kayıt yalnız DEĞİŞEN alanları + `version` gönderir (`editPayload`); değişiklik yoksa istek gitmez. Sunucu başka
 *     birinin arada kaydettiğini söylerse (409 `CONCURRENT_MODIFICATION`) çakışma bandı kim / ne zaman yazar, kaydetme
 *     kapanır; "Son hâli yükle" yazılanları yerel taslağa koyup kaydın son hâlini açar ve taslağı geri yüklemeyi önerir.
 *   - Form değişiklikleri ~1 sn duraklamayla yerel taslağa yazılır (`incidentDraft.js`), pencere kapanırken / sayfa
 *     giderken de; taslak ASLA kendiliğinden uygulanmaz: açılışta bant "Taslağı geri yükle" / "Sil" sorar.
 *   - Durum ↔ zaman tutarsızlığında ENGELLEMEYEN öneri bantları (`consistencyNudges`).
 *
 * <p><b>Hızlı Giriş (2026-11-20).</b>
 *   - `quick` (yalnız yeni kayıt): AYNI pencerenin sade görünümü — başlık, oluş zamanı, takım, önem, durum + "Mail gönder";
 *     "Tüm alanları göster" tam görünüme geçer (yazılanlar kalır). Gövde, doğrulama ve taslak (`new`) tam formla aynıdır;
 *     görünmeyen bir alanda hata varsa tam görünüme geçilir.
 *   - `copyOf` (`{ id, imagesRemoved, teamDropped }`): bir kayıttan çoğaltılmış yeni kayıt — bilgi bandı + kendi taslak
 *     kapsamı (`new.copy.<id>`).
 *   - Özet listeden açılan düzenlemede (`initial.markdown_omitted`) dört markdown alanı taze okumaya dek "yükleniyor",
 *     okunamazsa kilitli (yeniden dene); o sürede gövdeye markdown GİRMEZ (kayıtlı metin boş sanılıp silinmesin).
 */
export default function IncidentFormModal({
  mode, initial, teams = [], options = {}, onAddOption, onDeleteOption, onSubmit, onClose, onFresh, quick = false, copyOf = null,
}) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const creating = mode === 'create'
  const editId = !creating && initial?.id != null && initial.id !== '' ? initial.id : null
  // Hızlı kayıt: yalnız yeni kayıtta; tam görünüme geçince geri dönülmez (yazılanlar kalır)
  const [compact, setCompact] = useState(() => !!quick && creating)
  // Özet satırdan açılan düzenleme: markdown metinleri taze okumaya dek bilinmiyor (boş DEĞİL)
  const mdOmitted = editId != null && initial?.markdown_omitted === true
  const [mdState, setMdState] = useState(() => (mdOmitted ? 'loading' : 'ready'))
  const mdStateRef = useRef(mdState)
  mdStateRef.current = mdState
  // Pencerenin bir yerine bırakılan dosya tarayıcıyı o dosyaya götürüp formu kaybettirmesin (editör kendi bırakmasını işler)
  useFileDropGuard(true)
  // Karşılaştırma tabanı: formun açıldığı kayıt; taze hâl (ya da "son hâli yükle") geldiğinde onunla değişir.
  const [baseline, setBaseline] = useState(() => (initial && typeof initial === 'object' ? initial : {}))
  const [form, setForm] = useState(() => formFromRow(initial))
  const [section, setSection] = useState('summary')
  const [showErrors, setShowErrors] = useState(false)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState(null)
  // Sunucunun son kayıt denemesinde döndürdüğü alan hataları ({ alan: ileti }) — alanın altında gösterilir, alan
  // düzenlenince o alanın iletisi kalkar (2026-11-20).
  const [serverErrors, setServerErrors] = useState(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [preview, setPreview] = useState({ html: null, snapshot: null })
  const [previewing, setPreviewing] = useState(false)
  // Taze kayıt durumu: loading | same | updated | pending | failed | latest — `changed` farklı alan sayısı için.
  const [fresh, setFresh] = useState(() => (editId != null ? { state: 'loading', changed: [] } : null))
  const [gone, setGone] = useState(false)                 // kayıt silinmiş (404) — kaydetme kapalı
  const [conflict, setConflict] = useState(null)          // { by, at } — 409 çakışma
  const [latestBusy, setLatestBusy] = useState(false)
  const [latestFailed, setLatestFailed] = useState(false)
  const draftKeyScope = useMemo(() => draftScope(mode, initial, copyOf?.id), []) // eslint-disable-line react-hooks/exhaustive-deps
  const [draftOffer, setDraftOffer] = useState(() => readDraft(draftKeyScope))
  // Markdown okunmadan (özet satır) geri yüklenen taslağın METİN değişiklikleri: uygulanmaz, saklı kalır; metinler okununca
  // yeniden önerilir (gizli alana yazılıp gövdeden düşmesin, taslaktan silinmesin)
  const [heldMd, setHeldMd] = useState(null)
  const [dismissedNudges, setDismissedNudges] = useState(() => new Set())
  const bodyRef = useRef(null)
  const focusErrorRef = useRef(false)
  const previewSeq = useRef(0)
  const touchedRef = useRef(false)                        // kullanıcı formda bir şeyi değiştirdi mi
  const doneRef = useRef(false)                           // kaydedildi / bilerek atıldı → taslak yazılmaz
  const savingRef = useRef(false)
  const onFreshRef = useRef(onFresh)
  onFreshRef.current = onFresh
  const latestAbortRef = useRef(null)
  // Açılıştaki taze okuma: "Son hâli yükle" onu geçersiz kılar (sıra + iptal) — geç dönen ilk okuma yeni tabanı ezmesin.
  const firstReadSeq = useRef(0)
  const firstReadAbort = useRef(null)
  // Bileşen DOM'da mı (StrictMode'un çift takılışında yeniden true olur — eski sürüm ikinci takılışta false kalıyordu ve
  // Ctrl+Enter geliştirme kipinde kaydetmiyordu).
  const aliveRef = useRef(true)
  useEffect(() => {
    aliveRef.current = true
    return () => { aliveRef.current = false }
  }, [])

  const clearServerError = useCallback((k) => setServerErrors((prev) => {
    if (!prev || !(k in prev)) return prev
    const next = { ...prev }
    delete next[k]
    return Object.keys(next).length ? next : null
  }), [])
  const set = useCallback((k, v) => {
    touchedRef.current = true
    doneRef.current = false
    setForm((f) => (f[k] === v ? f : { ...f, [k]: v }))
    clearServerError(k)
  }, [clearServerError])
  // İşlevsel güncelleme (EN GÜNCEL değer üzerinde): markdown editörü sökülürken kendi yarım yükleme yer tutucularını siler —
  // kullanıcı eylemi değildir (formu "yazıldı" saymaz).
  const update = useCallback((k, fn) => {
    setForm((f) => {
      const cur = f[k] ?? ''
      const next = fn(cur)
      return next === cur ? f : { ...f, [k]: next }
    })
  }, [])

  // "Kesinti kaynaklı mı?" cevabı: Evet seçilince Kesinti ID alanı açılır ve BOŞSA odak oraya gider (kullanıcı seçimiyle —
  // Evet kayıtlı bir olayı açmak odak taşımaz). Cevap değişince kimliğin sunucu hatası da geçersizdir.
  const focusOutageIdRef = useRef(false)
  const setOutageRelated = useCallback((v) => {
    focusOutageIdRef.current = v === true
    touchedRef.current = true
    doneRef.current = false
    setForm((f) => (f.outage_related === v ? f : { ...f, outage_related: v }))
    clearServerError('outage_related')
    clearServerError('outage_id')
  }, [clearServerError])

  // Güncel form / taban — zamanlayıcılar ve sökülme anı en son değeri okur.
  const formRef = useRef(form)
  formRef.current = form
  const baselineRef = useRef(baseline)
  baselineRef.current = baseline
  const draftOfferRef = useRef(draftOffer)
  draftOfferRef.current = draftOffer
  const goneRef = useRef(gone)
  goneRef.current = gone

  /** Formun kayda göre farkını yerel taslağa yazar (boşsa siler). Kayıt / atma sonrası, silinmiş kayıtta ve önerilen eski
   *  taslak yanıt beklerken yazılmaz (yanıtlanmamış eski taslak ezilmesin). */
  const flushDraft = useCallback((force = false) => {
    if (doneRef.current || goneRef.current || savingRef.current) return
    if (!force && draftOfferRef.current) return
    const base = baselineRef.current || {}
    const real = formChanges(formRef.current, base)
    // Yeni kayıt (yeni / hızlı / kopya / alarmdan): kullanıcı bir şey değiştirdiyse taslak BOŞ forma göre yazılır — ön dolu
    // oluş zamanı ve takım da saklanır (saatler sonra geri yüklenince o anın "şimdi"si kaydedilmesin)
    const changes = draftChanges(creating && Object.keys(real).length ? formChanges(formRef.current, {}) : real)
    // Markdown okunamadı / okunmadı: o alanların "değişikliği" kayıtlı metne göre değildir — formdan taslağa yazılmaz;
    // eldeki taslağın (önceki oturumun) markdown değişiklikleri korunur
    if (mdStateRef.current !== 'ready') {
      for (const k of MARKDOWN_KEYS) delete changes[k]
      const old = readDraft(draftKeyScope)?.changes
      if (old) for (const k of MARKDOWN_KEYS) if (k in old) changes[k] = old[k]
    }
    writeDraft(draftKeyScope, {
      changes,
      baseVersion: base.version ?? null,
      baseUpdatedAt: base.updated_at ?? null,
    })
  }, [draftKeyScope, creating])

  /**
   * Kayıt / bilerek kapatma: taslak silinir — ama kaydın metinleri okunmadıysa taslağın (uygulanmamış) METİN değişiklikleri
   * saklı kalır (yalnız uygulanmış anahtarlar silinir).
   */
  const settleDraft = useCallback(() => {
    if (mdStateRef.current === 'ready') { removeDraft(draftKeyScope); return }
    const old = readDraft(draftKeyScope)
    const keep = {}
    if (old?.changes) for (const k of MARKDOWN_KEYS) if (k in old.changes) keep[k] = old.changes[k]
    if (Object.keys(keep).length) {
      writeDraft(draftKeyScope, { changes: keep, baseVersion: old.base_version ?? null, baseUpdatedAt: old.base_updated_at ?? null })
    } else {
      removeDraft(draftKeyScope)
    }
  }, [draftKeyScope])

  // ~1 sn duraklamayla taslak; pencere kapanırken (gezinme, oturum) ve sayfa giderken de.
  useEffect(() => {
    if (draftOffer || gone) return undefined
    const tm = setTimeout(() => flushDraft(), DRAFT_DEBOUNCE_MS)
    return () => clearTimeout(tm)
  }, [form, baseline, draftOffer, gone, flushDraft])
  useEffect(() => {
    const onHide = () => flushDraft()
    window.addEventListener('pagehide', onHide)
    return () => {
      window.removeEventListener('pagehide', onHide)
      flushDraft()
    }
  }, [flushDraft])

  /**
   * Kaydın taze hâlini okur (açılışta; markdown okunamadıysa "yeniden dene"de). Kullanıcı henüz yazmadıysa taze kayıt formun
   * yerine geçer; yazdıysa yalnız (okunmamış) markdown metinleri işlenir, öteki farklar "arkada değişti" notu olur.
   */
  const readFresh = useCallback(async () => {
    if (editId == null) return
    firstReadAbort.current?.abort()
    const ctl = typeof AbortController === 'function' ? new AbortController() : null
    firstReadAbort.current = ctl
    const my = ++firstReadSeq.current
    let res = null
    try { res = await api.incidents.get(editId, { timeoutMs: FRESH_TIMEOUT_MS, signal: ctl?.signal }) } catch { res = null }
    if (!aliveRef.current || my !== firstReadSeq.current) return
    const rec = validRecord(res, editId)
    const mdPending = mdStateRef.current !== 'ready'
    if (!rec) {
      if (statusOf(res) === 404) { setGone(true); setFresh({ state: 'gone', changed: [] }) } else setFresh({ state: 'failed', changed: [] })
      if (mdPending) setMdState('failed')
      return
    }
    onFreshRef.current?.(rec)
    const next = { ...EMPTY_FORM, ...rec }
    const base = baselineRef.current || {}
    // Markdown okunmamışken karşılaştırma tabanına kaydın kendi metni konur: "boş ↔ metin" farkı başkasının değişikliği sayılmaz
    const changed = changedKeys(mdPending ? { ...base, ...pickMarkdown(rec) } : base, rec)
    const started = touchedRef.current || focusedEditableIn(bodyRef.current)
    if (!started) {
      setBaseline(next)
      setForm(formFromRow(next))
      setFresh({ state: changed.length ? 'updated' : 'same', changed })
    } else {
      if (mdPending) {
        setBaseline((b) => ({ ...b, ...pickMarkdown(rec) }))
        setForm((f) => mergeMarkdown(f, base, rec))
      }
      if (changed.length) {
        setFresh({ state: 'pending', changed })
      } else {
        // Alanlar aynı (yalnız sürüm / damga ilerlemiş olabilir): yazılanlara dokunmadan taban güncellenir.
        setBaseline((b) => ({ ...b, version: rec.version, updated_at: rec.updated_at, updated_by: rec.updated_by }))
        setFresh({ state: 'same', changed: [] })
      }
    }
    if (mdPending) setMdState('ready')
  }, [editId])

  // Taze kayıt (yalnız düzenleme): liste satırıyla açılan form arkada kaydın son hâlini okur.
  useEffect(() => {
    if (editId == null) return undefined
    const seqRef = firstReadSeq
    const abortRef = firstReadAbort
    readFresh()
    // Sökülmede (ve StrictMode'un çift takılışında) yoldaki okuma uygulanmaz
    return () => { seqRef.current++; abortRef.current?.abort() }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  /** Markdown okunamadı → yeniden dene (alanlar yeniden "yükleniyor"). */
  const retryMarkdown = useCallback(() => {
    if (mdStateRef.current !== 'failed') return
    setMdState('loading')
    readFresh()
  }, [readFresh])

  useEffect(() => () => { latestAbortRef.current?.abort() }, [])

  // Önerilen taslak formda zaten olanı söylüyorsa (aynı değerler kaydedilmiş ya da elle yazılmış) bant gereksizdir: silinir.
  useEffect(() => {
    if (!draftOffer) return
    if (formSnapshot(applyDraft(form, draftOffer.changes, DRAFT_KEYS)) !== formSnapshot(form)) return
    removeDraft(draftKeyScope)
    setDraftOffer(null)
  }, [draftOffer, form, draftKeyScope])

  /**
   * "Son hâli yükle": yazılanlar yerel taslağa konur, kaydın son hâli açılır, taslağı geri yükleme önerilir. Çakışma ve
   * "arkada değişti" notunun ortak eylemi.
   */
  async function loadLatest() {
    if (latestBusy || savingRef.current || editId == null) return
    const changes = draftChanges(formChanges(formRef.current, baselineRef.current))
    if (mdStateRef.current !== 'ready') {
      // Metinler okunmadı: formun metin "değişiklikleri" gerçek değil; eldeki taslağın (geri yüklenip uygulanmamış olanlar
      // dahil) metin değişiklikleri korunur
      for (const k of MARKDOWN_KEYS) delete changes[k]
      const old = readDraft(draftKeyScope)?.changes
      if (old) for (const k of MARKDOWN_KEYS) if (k in old) changes[k] = old[k]
    }
    if (Object.keys(changes).length) {
      const base = baselineRef.current || {}
      writeDraft(draftKeyScope, { changes, baseVersion: base.version ?? null, baseUpdatedAt: base.updated_at ?? null })
    }
    firstReadSeq.current++                 // açılıştaki okuma hâlâ yoldaysa artık uygulanmaz
    firstReadAbort.current?.abort()
    latestAbortRef.current?.abort()
    const ctl = typeof AbortController === 'function' ? new AbortController() : null
    latestAbortRef.current = ctl
    setLatestBusy(true)
    setLatestFailed(false)
    try {
      let res = null
      try { res = await api.incidents.get(editId, { timeoutMs: FRESH_TIMEOUT_MS, signal: ctl?.signal }) } catch { res = null }
      if (!aliveRef.current || ctl?.signal?.aborted) return
      const rec = validRecord(res, editId)
      if (!rec) {
        if (statusOf(res) === 404) { setGone(true); setFresh({ state: 'gone', changed: [] }); setConflict(null) } else setLatestFailed(true)
        return
      }
      onFreshRef.current?.(rec)
      const next = { ...EMPTY_FORM, ...rec }
      setBaseline(next)
      setForm(formFromRow(next))
      setMdState('ready')
      touchedRef.current = false
      setConflict(null)
      setFresh({ state: 'latest', changed: [] })
      setServerErrors(null)
      setFormError(null)
      setShowErrors(false)
      setHeldMd(null)                        // saklı metin değişiklikleri yeniden önerilen taslağın içinde
      setDraftOffer(Object.keys(changes).length ? readDraft(draftKeyScope) : null)
    } finally {
      if (aliveRef.current) setLatestBusy(false)
    }
  }

  function restoreDraft() {
    const d = draftOfferRef.current
    if (!d) return
    touchedRef.current = true
    doneRef.current = false
    // Sahipsiz yükleme yer tutucuları (eski sürümün / yarım kalmış oturumun taslağı) uygulanmaz
    const all = restoredChanges(d.changes)
    // Metinler okunmadıysa taslağın metin değişiklikleri UYGULANMAZ (gizli alana yazılıp gövdeden düşerdi): saklanır,
    // metinler okununca yeniden önerilir
    const mdReady = mdStateRef.current === 'ready'
    const apply = {}
    const held = {}
    for (const [k, v] of Object.entries(all)) {
      if (!mdReady && MARKDOWN_KEYS.includes(k)) held[k] = v
      else apply[k] = v
    }
    setForm((f) => applyDraft(f, apply, DRAFT_KEYS))
    setServerErrors(null)
    setDraftOffer(null)
    setHeldMd(Object.keys(held).length ? { ...d, changes: held } : null)
    // Hızlı görünümün göstermediği bir alan geri yüklendiyse tam görünüm (kullanıcı neyin geldiğini görsün)
    if (Object.keys(apply).some((k) => !QUICK_VISIBLE.has(k))) setCompact(false)
    toast.info(t('inc.draft.restored'))
  }
  // Metinler okununca saklı metin değişiklikleri yeniden önerilir (kullanıcı ister geri yükler ister siler)
  useEffect(() => {
    if (mdState !== 'ready' || !heldMd) return
    setDraftOffer(heldMd)
    setHeldMd(null)
  }, [mdState, heldMd])
  function deleteDraft() {
    removeDraft(draftKeyScope)
    setDraftOffer(null)
  }

  function takeNudge(kind) {
    touchedRef.current = true
    doneRef.current = false
    setForm((f) => applyNudge(f, kind, resolvedNowIso(f)))
    for (const k of ['status', 'resolved_at', 'duration_minutes']) clearServerError(k)
    setDismissedNudges((s) => new Set(s).add(kind))
  }
  const dismissNudge = (kind) => setDismissedNudges((s) => new Set(s).add(kind))

  // Süre (dk) otomatik: olayın gerçek toplam süresi = OLUŞ ↔ çözülme farkı (tespit gecikmesi süreden düşmez).
  useEffect(() => {
    if (!form.occurred_at || !form.resolved_at) return
    const diff = autoDurationMinutes(form.occurred_at, form.resolved_at)
    if (diff == null) return
    if (String(form.duration_minutes ?? '') !== String(diff)) setForm((f) => ({ ...f, duration_minutes: diff }))
  }, [form.occurred_at, form.resolved_at]) // eslint-disable-line react-hooks/exhaustive-deps

  // Kirli-form: karşılaştırma tabanının (açılıştaki ya da yerine geçen taze kayıt) anlık görüntüsü — türetilmiş süre hariç.
  const baseSnap = useMemo(() => formSnapshot(formFromRow(baseline)), [baseline])
  const snap = formSnapshot(form)
  const dirty = snap !== baseSnap
  // Düzenlemede değişmeyen (eski kayıttan kalan) değer yeniden doğrulanmaz — sunucuyla aynı kural.
  const errors = useMemo(() => validateIncident(form, creating ? null : baseline), [form, baseline, creating])
  // Gösterilen hatalar: alan → METİN. Sunucunun alan iletisi alan düzenlenene dek kalır; istemci doğrulaması (ilk kayıt
  // denemesinden sonra canlı) aynı alanda onun önüne geçer. Uzunluk hatası `{ key, params }` taşır.
  const shownErrors = useMemo(() => {
    const out = { ...(serverErrors || {}) }
    if (showErrors) {
      for (const [k, e] of Object.entries(errors)) out[k] = typeof e === 'string' ? t(e) : t(e.key, ...(e.params || []))
    }
    return out
  }, [serverErrors, showErrors, errors, t])
  const errCounts = errorCountBySection(shownErrors)
  const nErrors = Object.keys(shownErrors).length
  const err = (k) => shownErrors[k] || undefined
  const nudges = consistencyNudges(form, creating ? null : baseline).filter((k) => !dismissedNudges.has(k))

  // Görsel adı tekilleştirme (async yükleme en güncel formu okur).
  const reservedRef = useRef(new Set())
  const makeUniqueCaption = useCallback((desired) => {
    const name = uniqueCaption(desired, formRef.current, reservedRef.current)
    reservedRef.current.add(name)
    return name
  }, [])

  // Doğrulama sonrası ilk hatalı alana odak (bölüm değiştiyse yeni içerik çizildikten sonra).
  useEffect(() => {
    if (!focusErrorRef.current) return
    focusErrorRef.current = false
    const el = firstInvalidField(bodyRef.current)
    if (el && typeof el.focus === 'function') { el.focus(); el.scrollIntoView?.({ block: 'center' }) }
  }, [section, showErrors, compact])

  // SUNUCU alan hatasında odak: her gösterimde sayaç artar; odaklamayı ETKİN bölümün içine çizilen `FocusServerError`
  // yapar (bölüm değiştiyse yeni bölümün içeriği DOM'a girdikten sonra çalışır — bkz. bileşenin açıklaması).
  const [serverFocusTick, setServerFocusTick] = useState(0)
  const serverFocusHandledRef = useRef(0)
  const focusServerError = (s) => (
    <FocusServerError active={section === s} tick={serverFocusTick} handledRef={serverFocusHandledRef} bodyRef={bodyRef} />
  )

  // Evet seçilince açılan Kesinti ID alanına odak (yalnız kullanıcı seçiminde ve alan boşken).
  useEffect(() => {
    if (!focusOutageIdRef.current) return
    focusOutageIdRef.current = false
    if (form.outage_related !== true || String(form.outage_id ?? '').trim() !== '') return
    const el = bodyRef.current?.querySelector('[data-field="outage_id"] input')
    if (el && typeof el.focus === 'function') el.focus()
  }, [form.outage_related]) // eslint-disable-line react-hooks/exhaustive-deps

  // Tarih alanlarında okunamayan metin (alan önceki değeri geri getirdi ve nedenini altında yazıyor): kayıt durur, odak oraya
  // gider — fareyle "Kaydet"te de (odak çıkışı tıklamadan önce bu bilgiyi eşzamanlı yazar)
  const dateProblemsRef = useRef({})
  const dateProblem = (key) => (p) => { dateProblemsRef.current[key] = p }

  async function submit() {
    if (saving || savingRef.current || gone || conflict) return
    const pending = Object.keys(dateProblemsRef.current).find((k) => dateProblemsRef.current[k])
    if (pending) {
      const el = bodyRef.current?.querySelector(`[data-date-field="${pending}"] input`)
      if (el && typeof el.focus === 'function') { el.focus(); el.scrollIntoView?.({ block: 'center' }) }
      return
    }
    // Ctrl/⌘+Enter tarih alanının yazılan değerini çözdükten SONRA (bir sonraki görev) çağırır: güncel form ref'ten okunur
    const form = formRef.current
    const base = baselineRef.current
    // Düzenleme: yalnız DEĞİŞEN alanlar + sürüm gider; değişiklik yoksa (ve "Mail gönder" seçilmediyse) istek GİTMEZ.
    let edit = creating ? null : editPayload(form, base)
    // Markdown metni okunmadıysa / okunamadıysa o alanlar gövdeye GİRMEZ (kayıtlı metin boş sanılıp silinmesin).
    if (edit && mdStateRef.current !== 'ready') {
      for (const k of MARKDOWN_KEYS) delete edit.body[k]
      edit = { body: edit.body, changed: edit.changed.filter((k) => !MARKDOWN_KEYS.includes(k)) }
    }
    // Süren görsel yüklemesi tek "değişiklik" olsa da (yer tutucu gövdeye girmez) "kaydedilecek değişiklik yok" diye
    // kapatılmaz: pencere kapanırsa yükleme sonucu metne yazılamaz ve yapıştırılan görsel sessizce kaybolurdu —
    // doğrulama "görsel yükleniyor" der.
    const uploading = MARKDOWN_KEYS.some((k) => hasPendingUpload(form[k]))
    if (edit && edit.changed.length === 0 && form.send_notification !== true && !uploading) {
      if (!draftOfferRef.current) settleDraft()
      doneRef.current = true
      toast.info(t('inc.nothingToSave'))
      onClose()
      return
    }
    const errs = validateIncident(form, creating ? null : base)
    if (Object.keys(errs).length) {
      setShowErrors(true)
      const first = firstErrorSection(errs)
      focusErrorRef.current = true
      // Hızlı görünümün göstermediği alanda hata → tam görünüm, o bölüm
      if (compact && hasHiddenErrors(errs)) setCompact(false)
      if (first && first !== section && (!compact || hasHiddenErrors(errs))) setSection(first)
      else bodyRef.current?.scrollTo?.({ top: 0 })
      return
    }
    setFormError(null)
    setServerErrors(null)
    setSaving(true)
    savingRef.current = true
    try {
      const res = await onSubmit(creating ? payloadFromForm(form, null) : edit.body)
      if (res?.ok) {
        // Kaydedildi: taslak silinir (okunmamış metinlerin saklı değişiklikleri kalır); pencere çağıranca kapatılır.
        doneRef.current = true
        settleDraft()
        return
      }
      if (!creating && res?.code === 'CONCURRENT_MODIFICATION') {
        // Çakışma: yazılanlar hemen taslağa (pencere kapansa da kaybolmaz); kaydetme "son hâli yükle"ye dek kapalı.
        savingRef.current = false
        flushDraft(true)
        setConflict({ by: res?.conflict?.by ?? null, at: res?.conflict?.at ?? null })
        bodyRef.current?.scrollTo?.({ top: 0 })
        return
      }
      if (!creating && res?.status === 404) {
        setGone(true)
        setFresh({ state: 'gone', changed: [] })
        bodyRef.current?.scrollTo?.({ top: 0 })
        return
      }
      // Sunucu alan hatası (400 + fields): formda yuvası olan alanın ALTINDA gösterilir, o bölüme gidilir ve alana
      // odaklanılır. Yuvası olmayan alan (ya da alan bilgisi taşımayan hata) eskisi gibi üstteki bantta kalır.
      const { shown, rest } = splitServerErrors(res?.fields, form)
      if (shown) {
        setServerErrors(shown)
        const first = firstErrorSection(shown)
        setServerFocusTick((n) => n + 1)
        if (compact && hasHiddenErrors(shown)) setCompact(false)
        if (first && first !== section) setSection(first)
      }
      if (!shown || rest) setFormError(res?.error || t('inc.saveError'))
    } catch {
      setFormError(t('inc.saveError'))
    } finally {
      savingRef.current = false
      if (aliveRef.current) setSaving(false)
    }
  }

  const submitRef = useRef(submit)
  submitRef.current = submit

  async function requestClose() {
    if (saving) return
    if (dirty) {
      const ok = await showConfirm({
        title: t('inc.discardTitle'), message: t('inc.discardMessage'),
        confirmText: t('inc.discardConfirm'), cancelText: t('inc.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    // Bilerek kapatıldı (değişiklik yoksa ya da atıldıysa): bu oturumun taslağı silinir — yanıtlanmamış ESKİ taslak
    // öneriliyorsa o kalır (kullanıcı ona dokunmadı, sonraki açılışta yine sorulur).
    if (!draftOfferRef.current) settleDraft()
    doneRef.current = true
    onClose()
  }

  async function loadPreview() {
    if (mdStateRef.current !== 'ready') return          // metinleri bilinmeyen önizleme gönderilecek postayı göstermez
    const my = ++previewSeq.current
    const payload = { ...payloadFromForm(form), kind: previewKind(mode, form.status) }
    const at = snap
    setPreviewing(true)
    try {
      const res = await api.incidents.previewNotification(payload)
      if (my !== previewSeq.current) return
      if (res?.success) setPreview({ html: res.html ?? '', snapshot: at, error: null })
      else setPreview((p) => ({ ...p, error: res?.error || t('inc.previewError') }))
    } catch {
      if (my === previewSeq.current) setPreview((p) => ({ ...p, error: t('inc.previewError') }))
    } finally {
      // Yalnız EN SON istek bayrağı söndürür (bayat yanıt yenisinin göstergesini erken kapatmasın); en son istek her
      // koşulda buraya varır → bayrak asılı kalmaz.
      if (my === previewSeq.current) setPreviewing(false)
    }
  }
  const togglePreview = (next) => {
    if (next && mdStateRef.current !== 'ready') return
    setPreviewOpen(next)
    if (next && preview.html == null && !previewing) loadPreview()
  }
  const previewStale = preview.html != null && preview.snapshot !== snap

  // Takım seçenekleri — seçili takım yüklenen listede yoksa (kapsam dışı / eski kayıt) yine de göster.
  const teamOptions = [{ value: '', label: t('inc.selectTeam') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]
  if (form.team_id != null && form.team_id !== '' && !teams.some((tm) => String(tm.id) === String(form.team_id))) {
    teamOptions.push({ value: String(form.team_id), label: form.team_name || ('#' + form.team_id) })
  }
  const opts = (arr, pfx) => arr.map((x) => ({ value: x, label: t(pfx + x) }))
  const autoDuration = !!form.occurred_at && !!form.resolved_at

  const sectionHead = (key) => (
    <p className="mb-3 text-sm text-muted-foreground">{t('inc.sec.' + key + 'Hint')}</p>
  )

  const footer = (
    <div className="flex w-full flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
        {dirty
          ? <span data-slot="ih-form-dirty" className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />{t('inc.unsaved')}</span>
          : <span>{t('inc.noChanges')}</span>}
        {form.send_notification && (
          <Badge variant="outline" data-slot="ih-mail-on" className="gap-1 border-primary/30 bg-primary/5 text-primary"><Mail aria-hidden="true" />{t('inc.mailOnSave')}</Badge>
        )}
        <span className="hidden items-center gap-1 lg:inline-flex">
          <KbdGroup><Kbd>Ctrl</Kbd><span aria-hidden="true">+</span><Kbd>Enter</Kbd></KbdGroup>{t('inc.kbdSave')}
        </span>
      </div>
      <div className="flex gap-2 max-sm:*:h-10 max-sm:*:flex-1">
        <Button type="button" variant="outline" onClick={requestClose} disabled={saving}>{t('inc.formCancel')}</Button>
        <Button type="button" onClick={submit} disabled={saving || gone || !!conflict} aria-busy={saving || undefined} data-action="save">
          {saving ? <Spinner size={16} decorative /> : <Save aria-hidden="true" />}
          {saving ? t('inc.saving') : t('inc.save')}
        </Button>
      </div>
    </div>
  )

  // ── Bantlar: taze kayıt notu, yerel taslak önerisi (gövdenin başında, kayar); çakışma / silinmiş (yapışık) ──
  const plural = (n, key) => (n === 1 ? t(key + '.one', n) : t(key, n))
  const latestButton = (
    <Button type="button" size="sm" variant="outline" onClick={loadLatest} disabled={latestBusy} aria-busy={latestBusy || undefined}
      data-action="load-latest" className="max-sm:h-10 pointer-coarse:h-10">
      {latestBusy ? <Spinner size={14} decorative /> : <RefreshCw aria-hidden="true" />}{t('inc.fresh.loadLatest')}
    </Button>
  )
  const latestFailedNote = latestFailed
    ? <span data-slot="ih-latest-failed" className="mt-1 block">{t('inc.fresh.latestFailed')}</span>
    : null
  let freshNote = null
  if (fresh?.state === 'updated') {
    freshNote = (
      <div data-slot="ih-fresh-note" data-kind="updated">
        <AlertBanner tone="info" icon={History} className="mb-0" title={plural(fresh.changed.length, 'inc.fresh.updated')}
          onDismiss={() => setFresh((f) => ({ ...f, state: 'same' }))} dismissLabel={t('app.close')} />
      </div>
    )
  } else if (fresh?.state === 'pending' && !conflict) {
    freshNote = (
      <div data-slot="ih-fresh-note" data-kind="pending">
        <AlertBanner tone="warning" icon={History} className="mb-0" title={t('inc.fresh.pendingTitle')}>
          <span className="block">{plural(fresh.changed.length, 'inc.fresh.pendingBody')}</span>
          <span className="mt-2 flex flex-wrap gap-2">{latestButton}</span>
          {latestFailedNote}
        </AlertBanner>
      </div>
    )
  } else if (fresh?.state === 'failed') {
    freshNote = (
      <p data-slot="ih-fresh-note" data-kind="failed" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.fresh.failed')}
      </p>
    )
  }
  let draftNote = null
  if (draftOffer && !gone) {
    const n = Object.keys(draftOffer.changes || {}).filter((k) => k !== 'team_name').length
    let when = ''
    try { when = formatDate(new Date(draftOffer.at).toISOString()) } catch { when = '' }
    const older = draftOffer.base_version != null && baseline?.version != null
      && Number(draftOffer.base_version) !== Number(baseline.version)
    draftNote = (
      <div data-slot="ih-draft" data-older={older || undefined}>
        <AlertBanner tone="info" icon={Undo2} className="mb-0" title={t('inc.draft.title')}>
          <span className="block">{n === 1 ? t('inc.draft.body.one', when, n) : t('inc.draft.body', when, n)}</span>
          {older && <span data-slot="ih-draft-older" className="mt-1 block">{t('inc.draft.olderBase')}</span>}
          <span className="mt-2 flex flex-wrap gap-2">
            <Button type="button" size="sm" onClick={restoreDraft} data-action="draft-restore" className="max-sm:h-10 pointer-coarse:h-10">
              <Undo2 aria-hidden="true" />{t('inc.draft.restore')}
            </Button>
            <Button type="button" size="sm" variant="outline" onClick={deleteDraft} data-action="draft-delete" className="max-sm:h-10 pointer-coarse:h-10">
              <Trash2 aria-hidden="true" />{t('inc.draft.delete')}
            </Button>
          </span>
        </AlertBanner>
      </div>
    )
  }
  const conflictNote = conflict && !gone ? (
    <div data-slot="ih-conflict">
      <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.conflict.title')}>
        <span className="block">
          {conflict.by && conflict.at ? t('inc.conflict.by', conflict.by, formatDate(conflict.at)) : t('inc.conflict.anon')}
        </span>
        <span className="mt-2 flex flex-wrap gap-2">{latestButton}</span>
        {latestFailedNote}
      </AlertBanner>
    </div>
  ) : null
  const goneNote = gone ? (
    <div data-slot="ih-fresh-note" data-kind="gone">
      <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.fresh.gone')} />
    </div>
  ) : null
  // Çoğaltılmış kayıt (2026-11-20): neyin kopyalandığı / kopyalanmadığı
  const copyNote = copyOf ? (
    <div data-slot="ih-copy-note">
      <AlertBanner tone="info" icon={Copy} className="mb-0" title={t('inc.copy.title', copyOf.id)}>
        <span className="block">{t('inc.copy.body')}</span>
        {copyOf.imagesRemoved > 0 && (
          <span data-slot="ih-copy-images" className="mt-1 block">{plural(copyOf.imagesRemoved, 'inc.copy.images')}</span>
        )}
        {copyOf.teamDropped && <span data-slot="ih-copy-team" className="mt-1 block">{t('inc.copy.teamDropped')}</span>}
      </AlertBanner>
    </div>
  ) : null
  // Markdown okunamadı (özet satırdan açılan düzenleme): kaydın o alanlara dokunmayacağı söylenir
  const mdNote = mdState === 'failed' && !gone ? (
    <p data-slot="ih-md-note" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
      <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.md.failedNote')}
    </p>
  ) : null
  const heldNote = heldMd && !gone ? (
    <p data-slot="ih-draft-md-held" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
      <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.draft.mdHeld')}
    </p>
  ) : null
  const notes = [freshNote, mdNote, copyNote, draftNote, heldNote].filter(Boolean)

  const setTeam = (v) => {
    touchedRef.current = true
    doneRef.current = false
    setForm((f) => ({ ...f, team_id: v, team_name: teams.find((tm) => String(tm.id) === String(v))?.name || '' }))
    clearServerError('team_id')
  }
  const titleField = (
    <TextInput label={t('inc.fTitle')} req full name="title" value={form.title} autoFocus={creating} error={err('title')}
      placeholder={t('inc.titlePlaceholder')} onChange={(v) => set('title', v)} />
  )
  const occurredField = (
    <div data-date-field="occurred_at" className="min-w-0">
      <DateInput label={t('inc.fOccurredAt')} req name="occurred_at" value={form.occurred_at} error={err('occurred_at')} hint={t('sdt.hint')}
        onProblem={dateProblem('occurred_at')} onChange={(v) => set('occurred_at', v)} />
    </div>
  )
  const teamField = (
    <SelectInput label={t('inc.fTeam')} req name="team_id" value={form.team_id != null ? String(form.team_id) : ''} options={teamOptions}
      error={err('team_id')} onChange={setTeam} />
  )
  const severityField = (
    <SelectInput label={t('inc.fSeverity')} name="severity" value={form.severity} error={err('severity')}
      onChange={(v) => set('severity', v)} options={opts(SEVERITIES, 'inc.sev')} />
  )
  const statusField = (
    <SelectInput label={t('inc.fStatus')} name="status" value={form.status} error={err('status')}
      onChange={(v) => set('status', v)} options={opts(STATUSES, 'inc.st')} />
  )
  const mailBox = (
    <div className="rounded-lg border bg-card p-3 sm:p-4">
      <CheckInput label={t('inc.sendMail')} hint={t('inc.sendMailHint')} checked={!!form.send_notification}
        onChange={(v) => set('send_notification', v)} />
      <p className="m-0 flex items-start gap-2 text-xs text-muted-foreground">
        <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
        {t('inc.mailKind.' + previewKind(mode, form.status))}
      </p>
    </div>
  )
  // Durum ↔ zaman tutarlılığı (2026-11-20): ENGELLEMEYEN öneriler — kayıt yine yapılabilir.
  const nudgesBlock = nudges.length > 0 ? (
    <div data-slot="ih-nudges" className="mb-3 flex min-w-0 flex-col gap-2">
      {nudges.map((k) => (
        <div key={k} data-slot="ih-nudge" data-kind={k}>
          <AlertBanner tone="info" icon={Lightbulb} className="mb-0" title={t('inc.nudge.' + k)}
            onDismiss={() => dismissNudge(k)} dismissLabel={t('inc.nudge.dismiss')}>
            <span className="block text-xs">{t('inc.nudge.hint')}</span>
            <span className="mt-2 flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" data-action={'nudge-' + k} onClick={() => takeNudge(k)}
                className="h-auto min-h-8 whitespace-normal text-left max-sm:min-h-10 pointer-coarse:min-h-10">
                {t('inc.nudge.' + k + 'Action')}
              </Button>
            </span>
          </AlertBanner>
        </div>
      ))}
    </div>
  ) : null
  const banners = (
    <>
      {nErrors > 0 && (
        <AlertBanner tone="danger" icon={AlertOctagon} className="mb-0" title={t('inc.fixErrors', nErrors)} />
      )}
      {goneNote}
      {conflictNote}
      {formError && (
        <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.saveError')}
          onDismiss={() => setFormError(null)} dismissLabel={t('app.close')}>{formError}</AlertBanner>
      )}
    </>
  )
  const hasBanners = nErrors > 0 || !!goneNote || !!conflictNote || !!formError
  const mdProps = (k) => ({
    name: k, error: err(k), value: form[k], incidentId: form.id, makeUniqueCaption, onChange: (v) => set(k, v),
    onUpdate: (fn) => update(k, fn), state: mdState, onRetry: retryMarkdown,
  })

  let modalTitle = t('inc.editTitle')
  let modalIcon = Pencil
  if (compact) { modalTitle = t('inc.quick.title'); modalIcon = Zap }
  else if (copyOf) { modalTitle = t('inc.copy.formTitle'); modalIcon = Copy }
  else if (creating) { modalTitle = t('inc.newTitle'); modalIcon = ListChecks }

  return (
    <ModalShell open onClose={requestClose} title={modalTitle} icon={modalIcon}
      size="xl" scrollBody dismissOnBackdrop={false} busy={saving} bodyRef={bodyRef}
      className={cn(compact ? QUICK_SIZE : FIXED, PHONE_FULLSCREEN)} footer={footer}>
      <div data-slot="incident-form" data-mode={mode} data-view={compact ? 'quick' : 'full'} className={cn('min-w-0', PHONE_TOUCH)}
        onKeyDown={(e) => {
          if (!((e.ctrlKey || e.metaKey) && e.key === 'Enter')) return
          e.preventDefault()
          // Tarih alanı bu tuşta yazılanı çözer (onChange → yeni çizim); kayıt ondan sonra, güncel formla
          setTimeout(() => { if (aliveRef.current) submitRef.current() }, 0)
        }}>
        {notes.length > 0 && (
          <div data-slot="ih-form-notes" className="mb-3 flex min-w-0 flex-col gap-2">{notes}</div>
        )}
        {compact ? (
          <div data-slot="ih-quick" className="flex min-w-0 flex-col gap-3">
            <FocusServerError active tick={serverFocusTick} handledRef={serverFocusHandledRef} bodyRef={bodyRef} />
            {hasBanners && <div className="flex min-w-0 flex-col gap-2">{banners}</div>}
            <p className="m-0 text-sm text-muted-foreground">{t('inc.quick.hint')}</p>
            <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
              {titleField}
              {occurredField}
              {teamField}
              {severityField}
              {statusField}
            </div>
            {nudgesBlock}
            {mailBox}
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="outline" data-action="quick-expand" className="max-sm:h-10 pointer-coarse:h-10"
                onClick={() => setCompact(false)}>
                <Maximize2 aria-hidden="true" />{t('inc.quick.expand')}
              </Button>
              <span className="text-xs text-muted-foreground">{t('inc.quick.expandHint')}</span>
            </div>
          </div>
        ) : (
        <Tabs value={section} onValueChange={setSection} className="gap-0">
          {/* Bölüm çubuğu + hata özetleri gövdenin üstünde yapışık kalır (uzun bölümde kaydırınca da görünür). Telefonda
              sekmeler SARAR (yatay kaydırma yok — "Hızlı Giriş", 2026-11-20). */}
          <div className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 bg-background px-1 pb-3">
            <TabsList aria-label={t('inc.sectionsLabel')}
              className="h-auto w-full flex-wrap justify-start max-sm:rounded-none max-sm:bg-transparent max-sm:p-0 pointer-coarse:group-data-[orientation=horizontal]/tabs:h-auto group-data-[orientation=horizontal]/tabs:h-auto">
              {SECTIONS.map((s) => {
                const Icon = SECTION_ICON[s]
                const n = errCounts[s] || 0
                return (
                  <TabsTrigger key={s} value={s} data-section={s} data-invalid={n > 0 || undefined}
                    className="h-9 flex-none gap-1.5 px-3 max-sm:h-10 max-sm:rounded-full max-sm:border-border max-sm:data-[state=active]:border-primary max-sm:data-[state=active]:bg-primary/10 pointer-coarse:h-10">
                    <Icon aria-hidden="true" />
                    <span>{t('inc.sec.' + s)}</span>
                    {n > 0 && (
                      <Badge variant="destructive" className="h-4 min-w-4 px-1 text-[10px] tabular-nums">
                        <span aria-hidden="true">{n}</span><span className="sr-only">{t('inc.sectionErrors', n)}</span>
                      </Badge>
                    )}
                  </TabsTrigger>
                )
              })}
            </TabsList>
            {banners}
          </div>

          <TabsContent value="summary" forceMount hidden={section !== 'summary'} className="min-w-0">
            {focusServerError('summary')}
            {sectionHead('summary')}
            <div className={GRID}>
              {titleField}
              {occurredField}
              {teamField}
              {severityField}
              {statusField}
              <SelectInput label={t('inc.fCategory')} name="category" value={form.category} error={err('category')}
                onChange={(v) => set('category', v)} options={opts(CATEGORIES, 'inc.cat')} />
              <CreatableMultiSelect label={t('inc.fProblemType')} name="problem_types" value={form.problem_types} options={PROBLEM_TYPES}
                error={err('problem_types')} onChange={(v) => set('problem_types', v)} placeholder={t('inc.selectProblemType')} />
            </div>
            {nudgesBlock}
            <FieldSet className="mt-2 min-w-0 gap-0">
              <FieldLegend className="mb-2 text-sm font-semibold">{t('inc.timelineLegend')}</FieldLegend>
              <div className={GRID}>
                <div data-date-field="detected_at" className="min-w-0">
                  <DateInput label={t('inc.fDetectedAt')} name="detected_at" value={form.detected_at} min={form.occurred_at || undefined}
                    minLabel={t('inc.fOccurredAt')} error={err('detected_at')} onProblem={dateProblem('detected_at')}
                    anchor={form.occurred_at || undefined} anchorLabel={t('inc.fOccurredAt')} onChange={(v) => set('detected_at', v)} />
                </div>
                <div data-date-field="resolved_at" className="min-w-0">
                  <DateInput label={t('inc.fResolvedAt')} name="resolved_at" value={form.resolved_at} min={form.detected_at || form.occurred_at || undefined}
                    minLabel={form.detected_at ? t('inc.fDetectedAt') : t('inc.fOccurredAt')} onProblem={dateProblem('resolved_at')}
                    anchor={form.occurred_at || undefined} anchorLabel={t('inc.fOccurredAt')}
                    error={err('resolved_at')} onChange={(v) => set('resolved_at', v)} />
                </div>
                <TextInput label={t('inc.fDuration')} type="number" name="duration_minutes" value={form.duration_minutes} disabled={autoDuration}
                  hint={autoDuration ? t('inc.durationAuto') : t('inc.durationManual')} error={err('duration_minutes')}
                  onChange={(v) => set('duration_minutes', v)} />
              </div>
            </FieldSet>
          </TabsContent>

          <TabsContent value="impact" forceMount hidden={section !== 'impact'} className="min-w-0">
            {focusServerError('impact')}
            {sectionHead('impact')}
            <div className={GRID}>
              <CreatableMultiSelect label={t('inc.fChannel')} name="channel" value={form.channel} options={options.channel || []}
                error={err('channel')} onChange={(v) => set('channel', v)} placeholder={t('inc.selectChannel')}
                onCreate={(v) => onAddOption('CHANNEL', v)} onDelete={(v) => onDeleteOption('CHANNEL', v)} />
              <CreatableMultiSelect label={t('inc.fService')} name="service" value={form.service} options={options.domain || []}
                error={err('service')} onChange={(v) => set('service', v)} placeholder={t('inc.selectService')}
                onCreate={(v) => onAddOption('DOMAIN', v)} onDelete={(v) => onDeleteOption('DOMAIN', v)} />
              <TextInput label={t('inc.fAffected')} name="affected_services" value={form.affected_services} error={err('affected_services')}
                onChange={(v) => set('affected_services', v)} />
              <TextInput label={t('inc.fAffectedApp')} name="affected_app" value={form.affected_app} error={err('affected_app')}
                onChange={(v) => set('affected_app', v)} />
              <TextInput label={t('inc.fAffectedSystems')} name="affected_systems" value={form.affected_systems} error={err('affected_systems')}
                onChange={(v) => set('affected_systems', v)} />
              <TextInput label={t('inc.fAffectedCustomers')} type="number" name="affected_customers" value={form.affected_customers} error={err('affected_customers')}
                onChange={(v) => set('affected_customers', v)} />
              <TextInput label={t('inc.fAffectedTransactions')} type="number" name="affected_transactions" value={form.affected_transactions} error={err('affected_transactions')}
                onChange={(v) => set('affected_transactions', v)} />
              {/* Teknik hata adedi (2026-11-20): metin kutusu + sayısal tuş takımı — yazılan metin olduğu gibi tutulur ki
                  "1.250" / "12,5" sessizce boşa dönmesin, alanın altında nedeni yazsın. Boş = bilinmiyor. */}
              <TextInput label={t('inc.fTechFaultCount')} name="tech_error_count" inputMode="numeric" autoComplete="off"
                value={form.tech_error_count} error={err('tech_error_count')} hint={t('inc.techFaultCountHint')}
                onChange={(v) => set('tech_error_count', v)} />
              <TextInput label={t('inc.fErrorBudget')} type="number" name="error_budget_burn_pct" value={form.error_budget_burn_pct} error={err('error_budget_burn_pct')}
                onChange={(v) => set('error_budget_burn_pct', v)} />
              <div className="flex items-end">
                <CheckInput label={t('inc.fSla')} hint={t('inc.slaHint')} name="sla_breached" error={err('sla_breached')}
                  checked={form.sla_breached} onChange={(v) => set('sla_breached', v)} />
              </div>
              {/* Kesinti kaynaklı mı? — üç durumlu (Evet / Hayır / seçilmemiş = bilinmiyor). Kesinti ID yalnız Evet'te. */}
              <YesNoInput label={t('inc.fOutageRelated')} name="outage_related" value={form.outage_related}
                hint={t('inc.outageRelatedHint')} error={err('outage_related')}
                yesLabel={t('inc.outageYes')} noLabel={t('inc.outageNo')} onChange={setOutageRelated} />
              {form.outage_related === true && (
                <TextInput label={t('inc.fOutageId')} name="outage_id" autoComplete="off" value={form.outage_id}
                  error={err('outage_id')} hint={t('inc.outageIdHint')} placeholder={t('inc.outageIdPlaceholder')}
                  onChange={(v) => set('outage_id', v)} />
              )}
              <MdArea label={t('inc.fBusinessImpact')} hint={t('inc.businessImpactHint')} {...mdProps('business_impact')} />
            </div>
          </TabsContent>

          <TabsContent value="codes" forceMount hidden={section !== 'codes'} className="min-w-0">
            {focusServerError('codes')}
            {sectionHead('codes')}
            <div className={GRID}>
              <CreatableSelect label={t('inc.fErrorCode')} name="error_code" error={err('error_code')} value={form.error_code} options={options.errorCode || []}
                onChange={(v) => set('error_code', v)} onCreate={(v) => onAddOption('ERROR_CODE', v)} onDelete={(v) => onDeleteOption('ERROR_CODE', v)} />
              <CreatableSelect label={t('inc.fFunctionCode')} name="function_code" error={err('function_code')} value={form.function_code} options={options.functionCode || []}
                onChange={(v) => set('function_code', v)} onCreate={(v) => onAddOption('FUNCTION_CODE', v)} onDelete={(v) => onDeleteOption('FUNCTION_CODE', v)} />
              <CreatableSelect label={t('inc.fChannelCode')} name="channel_code" error={err('channel_code')} value={form.channel_code} options={options.channelCode || []}
                onChange={(v) => set('channel_code', v)} onCreate={(v) => onAddOption('CHANNEL_CODE', v)} onDelete={(v) => onDeleteOption('CHANNEL_CODE', v)} />
              <TagField label={t('inc.fTags')} name="tags" error={err('tags')} value={form.tags} onChange={(v) => set('tags', v)} />
              {/* Runbook bağlantısı ("Hızlı Giriş", 2026-11-20): yalnız http(s); ayrıntıda yeni sekmede açılan bağlantı olur */}
              <TextInput label={t('inc.fRunbookUrl')} full type="url" inputMode="url" autoComplete="off" name="runbook_url"
                value={form.runbook_url} error={err('runbook_url')} hint={t('inc.runbookUrlHint')} placeholder={t('inc.runbookUrlPlaceholder')}
                onChange={(v) => set('runbook_url', v)} />
            </div>
          </TabsContent>

          <TabsContent value="rca" forceMount hidden={section !== 'rca'} className="min-w-0">
            {focusServerError('rca')}
            {sectionHead('rca')}
            <div className="grid grid-cols-1">
              <MdArea label={t('inc.fRca')} hint={t('inc.rcaHint')} {...mdProps('rca_summary')} />
              <MdArea label={t('inc.fDescription')} {...mdProps('description')} />
              <MdArea label={t('inc.fResolution')} {...mdProps('resolution_steps')} />
            </div>
          </TabsContent>

          <TabsContent value="notify" forceMount hidden={section !== 'notify'} className="min-w-0">
            {focusServerError('notify')}
            {sectionHead('notify')}
            <div className="flex min-w-0 flex-col gap-3">
              {mailBox}
              <Collapsible open={previewOpen} onOpenChange={togglePreview} className="min-w-0 rounded-lg border">
                <div className="flex flex-wrap items-center gap-2 p-2 sm:p-3">
                  <CollapsibleTrigger asChild>
                    <Button type="button" variant="ghost" disabled={mdState !== 'ready'} data-action="preview-toggle"
                      className="h-auto min-h-10 flex-1 justify-start gap-2 px-2 text-left font-semibold whitespace-normal sm:min-h-9">
                      <Mail aria-hidden="true" className="text-primary" />
                      {t('inc.previewMail')}
                      <ChevronDown aria-hidden="true" className={cn('ml-auto transition-transform motion-reduce:transition-none', previewOpen && 'rotate-180')} />
                    </Button>
                  </CollapsibleTrigger>
                  {previewOpen && (
                    <Button type="button" variant="outline" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={loadPreview} disabled={previewing} aria-busy={previewing || undefined}>
                      {previewing ? <Spinner size={14} decorative /> : <RefreshCw aria-hidden="true" />}
                      {previewing ? t('inc.previewing') : t('inc.previewRefresh')}
                    </Button>
                  )}
                  {mdState !== 'ready' && (
                    <div data-slot="ih-preview-blocked" data-state={mdState} role={mdState === 'failed' ? 'alert' : 'status'}
                      className="flex w-full min-w-0 flex-wrap items-center gap-2 px-2 text-xs text-muted-foreground">
                      <Info aria-hidden="true" className="size-3.5 shrink-0" />
                      <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
                        {mdState === 'failed' ? t('inc.previewMdFailed') : t('inc.previewWaitMd')}
                      </span>
                      {mdState === 'failed' && (
                        <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={retryMarkdown}
                          data-action="preview-md-retry">
                          <RefreshCw aria-hidden="true" />{t('inc.md.retry')}
                        </Button>
                      )}
                    </div>
                  )}
                </div>
                <CollapsibleContent className="min-w-0 border-t p-2 sm:p-3">
                  {previewStale && <AlertBanner tone="warning" className="mb-2" title={t('inc.previewStale')} />}
                  {preview.error && <AlertBanner tone="danger" className="mb-2" title={preview.error} />}
                  {preview.html == null
                    ? <div className="grid h-[420px] place-items-center text-sm text-muted-foreground"><Spinner size={20} label={t('inc.previewing')} /></div>
                    : (
                      <iframe title={t('inc.previewTitle')} srcDoc={mailPreviewSrcDoc(preview.html ?? '')} sandbox={MAIL_PREVIEW_SANDBOX}
                        className="h-[420px] w-full rounded-md border bg-[#f4f6f8] sm:h-[480px]" />
                    )}
                </CollapsibleContent>
              </Collapsible>
            </div>
          </TabsContent>
        </Tabs>
        )}
      </div>
    </ModalShell>
  )
}
