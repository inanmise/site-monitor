import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, ChevronLeft, ChevronRight, CircleAlert, Download, Eye, History, LayoutTemplate, MailWarning, RefreshCw, Save, Send,
  Trash2, Undo2, ShieldCheck } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ImagesBlock } from './ReportImages.jsx'
import { TrendBlock } from './ReportTrend.jsx'
import ReportHistorySheet from './ReportHistory.jsx'
import {
  ExpandAllButton, SectionOpenContext, sectionOpenKey, useSectionOpenState, useSectionOpenValue,
} from './SectionCollapse.jsx'
import { MetricsBlock, PendingInputContext, SummaryBlock, TableBlock, TextBlock } from './ReportSections.jsx'
import { IssuesBlock } from './ReportIssues.jsx'
import { StatusBadge, fmtDateTime, periodLabel, CopyTextButton } from './TechErrorParts.jsx'
import ApprovalCard from './ApprovalCard.jsx'
import MailPreviewDialog from './MailPreviewDialog.jsx'
import SendDialog from './SendDialog.jsx'
import {
  SEND_POLL_MAX_MS, SEND_POLL_MS, pollBaselineAfterQueued, sendFailureKind, sendPollStep, unsureSendOutcome, withSendStatus,
} from './sendModel.js'
import { bumpMailPreviewContent, mailPreviewContentRev } from './mailPreviewFetch.js'
import RecipientsSummary from './RecipientsSummary.jsx'
import {
  allFields, allSectionsOpen, applyCalc, calcResults, canonicalValues, embeddedSections, errorCountsBySection, hostOf, normalizeValues,
  reportErrorSection, fieldExempt, issueDisplayTitle, issueImageAlive, issueOptions, issueValidation, monthName, resolvePlaceholders,
  resolveTextPlaceholders, revertInvalidValues, stableJson, trendRefs,
} from './techErrorModel.js'
import { storageOwner } from '../../utils/personalStorage.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { useElementHeight } from '../../hooks/useElementHeight.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { cn } from '@/lib/utils'

// Kişisel kayıt: çıkışta ve kullanıcı değişince silinir (utils/personalStorage PERSONAL_PREFIXES)
export const BACKUP_PREFIX = 'sm.te.draft.'

function readBackup(id) {
  try {
    const raw = localStorage.getItem(BACKUP_PREFIX + id)
    if (!raw) return null
    const v = JSON.parse(raw)
    return v && typeof v === 'object' && v.values ? v : null
  } catch {
    return null
  }
}

function writeBackup(id, payload) {
  try { localStorage.setItem(BACKUP_PREFIX + id, JSON.stringify(payload)) } catch { /* depolama yok / dolu */ }
}

function dropBackup(id) {
  try { localStorage.removeItem(BACKUP_PREFIX + id) } catch { /* depolama yok */ }
}

const hasAny = (o) => Object.values(o).some(Boolean)
/** Kısaltma üssü (2026-10-18): üs varsa yazılır, yoksa anahtar silinir (değişmediyse aynı nesne — sahte değişiklik yok). */
const withUnit = (units, key, exp) => {
  const cur = units?.[key] ?? null
  const next = exp ?? null
  if (cur === next) return units
  const out = { ...(units || {}) }
  if (next == null) delete out[key]
  else out[key] = next
  return out
}
const NO_ISSUES = Object.freeze([])
/** Anomali denetimindeki alanın görünen adı (durum satırı): anahtar → i18n; Kesinti ID şablonun etiketi. */
const ISSUE_FIELD_LABEL = Object.freeze({ date: 'te.iss.date', start: 'te.iss.start', end: 'te.iss.end' })

/** Raporun gönderim izi (son deneme / son gönderim / sayaç) — "o okumadan beri gönderim oldu mu?" karşılaştırması için. */
const sendMarkOf = (r) => ({ attempt: r?.last_attempt_at ?? null, sent: r?.last_sent_at ?? null, count: r?.send_count ?? null,
  state: r?.send_state ?? null })
const sameSendMark = (mark, r) => {
  if (!mark || !r) return false
  const now = sendMarkOf(r)
  return now.attempt === mark.attempt && now.sent === mark.sent && now.count === mark.count && now.state === mark.state
}

/** Otomatik kayıt aralığı (2026-10-16, kullanıcı kararı: "30 saniyede bir"). */
export const AUTOSAVE_MS = 30_000
/** Otomatik kayıt isteğinin zaman aşımı: asılı bir istek geri / kaydet / PDF'i sonsuza dek bekletmesin. */
export const AUTOSAVE_TIMEOUT_MS = 30_000
/** Ağ / sunucu hatasından sonra bekleme: 30 sn'den başlar, her hatada iki katı, en çok 5 dk. */
export const AUTOSAVE_BACKOFF_MAX_MS = 5 * 60_000

function clockTime(ms, lang) {
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
    hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
  }).format(new Date(ms))
}

/** Sürüklenen bir DOSYA mı (metin / bağlantı sürüklemesine dokunulmaz)? */
function isFileDrag(e) {
  const types = e?.dataTransfer?.types
  return !!types && Array.from(types).includes('Files')
}

/**
 * AYLIK RAPOR DÜZENLEYİCİSİ. Bölümler raporun KOPYALANMIŞ şablonundan çizilir (şablon sonradan değişse de bu rapor
 * değişmez; taslakta "şablonun son hâlini uygula" isteyerek alır). Değer kaydı sürüm denetimlidir (409 → uyarı +
 * "en son hâli yükle"); yazarken taslak tarayıcıda yedeklenir; kaydedilmemiş değişiklikle ayrılmak onay ister.
 * PDF ve gönderim KAYITLI içeriği kullanır — kaydedilmemiş değişiklik varsa önce kaydetmeyi önerir.
 *
 * <p>2. inceleme turu: kaydedilmemiş taslak varken kayıt arada başkasınca değiştiyse taban İLERLETİLMEZ (çakışma
 * uyarısı; sonraki kayıt 409 alır, başkasının değişikliği ezilmez); dil değişimi taslağı yeniden yüklemez; aynı
 * değerle yeniden yükleme girişleri yeniden kurmaz (odak / yarım yazım kaybolmaz); yedek zamanlayıcısı vazgeç / kayıtta
 * iptal edilir, taslak temizlenince bu sayfanın yazdığı yedek silinir, sayfadan çıkarken son saniye de yedeklenir.
 *
 * <p>İnceleme 2026-10-13: kayıt sürerken yazılan değişiklik korunur (yanıt yalnız gönderilen taslağın yerini alır),
 * sürüm bir ref'te tutulur (kaydı izleyen "şablonu uygula" eski sürümle 409 almaz), sunucunun alan hataları alan
 * düzenlenince kalkar ve kaydı kilitlemez, yeniden yükleme hatası görünür, sayfaya bırakılan dosya tarayıcıyı
 * rapordan uzaklaştırmaz.
 *
 * <p>2026-10-16 (kullanıcı kararları): bölümler KATLANIR (kapalı başlar, açılanlar bu rapor için tarayıcıda hatırlanır,
 * hatası olan bölüm kendiliğinden açılır) ve rapor değişiklik varken 30 sn'de bir — sekme gizlenirken de — OTOMATİK
 * kaydedilir. Geçersiz alan otomatik kaydı durdurmaz: diğer alanlar kaydedilir, geçersiz alan kayıtlı (eski) değerinde
 * kalır. Otomatik kayıt sessizdir (bildirim yok; durum alt çubukta), çakışmada durur (uyarı), aynı içeriği reddeden bir
 * doğrulama hatasını içerik değişene kadar yinelemez. Elle kayıt, PDF, gönderim, şablon uygulama ve ayrılma süren bir
 * kaydı bekler (iki kayıt aynı sürümle yarışmaz). Sunucu aynı kişinin 15 dk içindeki otomatik kayıtlarını geçmişte tek
 * satırda birleştirir.
 */
export default function ReportEditor({ reportId, onBack, onOpenTemplate, onOpenReport, focusTitleRef, backLabel = null }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null })
  const [data, setData] = useState(null)
  const [draft, setDraft] = useState(null)
  const [base, setBase] = useState({ json: null, version: null })
  const [gen, setGen] = useState(0)                       // taslak bütünüyle değişince girişler yeniden kurulur
  const [images, setImages] = useState([])
  const [clientErrors, setClientErrors] = useState({})    // istemcinin alan denetimi (kaydı kilitler)
  const [serverErrors, setServerErrors] = useState({})    // sunucunun alan hataları (gösterilir; alan düzenlenince kalkar)
  const [conflict, setConflict] = useState(false)
  const [backup, setBackup] = useState(null)
  const [busy, setBusy] = useState(null)   // 'save' | 'pdf' | 'send' | 'refresh' | 'delete'
  const [autoSaving, setAutoSaving] = useState(false)
  const [autoInfo, setAutoInfo] = useState(null)        // son otomatik kayıt: { at, ok }
  const autoBlocked = useRef(null)                      // doğrulamayla reddedilen içerik — değişene kadar yinelenmez
  const autoBackoff = useRef({ until: 0, n: 0 })        // ağ / sunucu hatasından sonra bekleme
  // Sunucunun kabul ettiği son otomatik gönderim ({sent, base}): taban buyken taslak bunu veriyorsa KAYITLIDIR. Otomatik
  // kayıt yanıtı taslağın yerine geçmez (yazarken sondaki boşluk / "- " silinip imleç kaymasın — inceleme 2026-10-16);
  // sunucunun kırptığı boşluk "kaydedilmemiş" sayılmaz, aynı içerik yeniden gönderilmez
  const accepted = useRef(null)
  // ESKİ sürümlü yedek geri yüklendiyse otomatik kayıt DURUR (inceleme 2026-10-16, 2. tur): yoksa 30 sn içinde o eski taslak
  // başkasının yeni kaydının üstüne yazılırdı. Elle Kaydet, Vazgeç ya da tam yükleme kaldırır
  const [autoHold, setAutoHold] = useState(false)
  const leaving = useRef(false)                         // ayrılma sürüyor (çift tıklama iki kez sormasın / gitmesin)
  const [pending, setPendingMap] = useState({})         // yarım yazılmış sayı alanları (taslağa girmemiş)
  // Anomali konusunun boş ZORUNLU alanı (Kesinti ID, saat aralığının eksik ucu) kayıt / PDF / gönderim denemesinden sonra
  // alanın altında gösterilir (2026-10-18); geçersiz değer (bitiş başlangıçtan önce, ay dışı tarih) hemen
  const [showRequired, setShowRequired] = useState(false)
  const savePromise = useRef(null)                      // süren kayıt (elle ya da otomatik): sıradaki onu bekler
  // Son kaydın neden OLMADIĞI (inceleme 2026-10-18): konu görselinden önceki kayıt başarısızsa yuva nedene göre konuşur —
  // 'invalid' | 'conflict' | 'network' | 'server'; başarıda null
  const lastSaveFailure = useRef(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [mailPreviewOpen, setMailPreviewOpen] = useState(false)   // e-posta önizlemesi (10. sürüm)
  const [sendDialog, setSendDialog] = useState(null)              // gönderim penceresi (R18 / C13): { error } | null
  // Sonucu bilinmeyen gönderim (inceleme 2026-10-18, 3. tur): { before, msg, details } — rapor yeniden okunup durum
  // bilinene kadar "Gönder" kilitli; pencere kapatılıp yeniden açılırsa kilit VE yazılan ek Bilgi taşınır (4. tur: bant "ek
  // Bilgi korundu" diyordu ama alan boş açılıyor, rapor ek adreslersiz gidiyordu) ve durum yeniden denetlenir
  const unsureSend = useRef(null)
  // Gönderim "gitmemiş" çözüldü (belirsiz gönderim ya da yoklama): { details, mark } — bir sonraki açılış "gitmemiş" bandını
  // ve ek Bilgi'yi getirir (yeni deneme siler)
  const resumeSend = useRef(null)
  // Yeniden açılışa taşınan ek Bilgi (5. tur): belirsiz / gitmemiş sonuçtan sonra İLK açılış o denemenin adresleriyle açılır,
  // sonra alanın her değişikliği buraya yazılır — sonraki açılış kullanıcının SON yazdığını getirir (eskiden her açılışta
  // hatadan önceki adresler geri geliyordu); alan boşaltılınca boş, gönderim kesinleşince silinir
  const ccCarry = useRef('')
  const sendDialogRef = useRef(null)
  sendDialogRef.current = sendDialog
  // Süren / kuyruktaki gönderimin yoklaması (4. tur): { id, until, baseline, own, extraCc } — `SEND_POLL_MS`'de bir gönderim
  // alanları yeniden okunur; karar `sendPollStep` ile gönderimden ÖNCEKİ satıra (ya da bu denemenin kuyruğuna) göre (5. tur)
  const [sendPoll, setSendPoll] = useState(null)
  const sendPollRef = useRef(null)
  const pollSeq = useRef(0)
  const pollIds = useRef(0)
  // Düzenleyicinin raporun durumunu KENDİSİ değiştirdiği anlar (kayıt yanıtı, görsel yükleme / silme — 5. tur): yoklama
  // yanıtı istekten sonra gelen yerel bir "changed"ın üstüne "sent" yazmasın
  const statusGen = useRef(0)
  // E-posta önizlemesinin görsel revizyonu (5. tur): görsel değişikliği sürümü değiştirmez — önizleme eski isteğe katılmasın
  const [previewRev, setPreviewRev] = useState(() => mailPreviewContentRev(reportId))
  // A4 (2026-10-17): yapışkan eylem çubuğunun GERÇEK yüksekliği → kabın alt boşluğu (sabit pb-24 üç satıra sarılan
  // çubukta son bölüm kartını örtüyordu); ölçüm yokken (jsdom / ilk boyama) pb-24 taban kalır
  const [actionsRef, actionsHeight] = useElementHeight()
  const historyBtnRef = useRef(null)
  const titleRef = useRef(null)
  const seq = useRef(0)
  const loadInFlight = useRef(false)
  const inFlightKeepDraft = useRef(true)
  const draftRef = useRef(draft)
  draftRef.current = draft
  const baseRef = useRef(base)
  // Kanonik karşılaştırma şablonu bilir: "Hayır" cevabıyla muaf alanların (gizli) değeri sayılmaz / gönderilmez
  const canonTpl = useRef(null)
  const canonJson = useCallback((v) => stableJson(canonicalValues(v, canonTpl.current)), [])
  // Dil değişimi taslağı yeniden yüklemesin: yükleyici t'ye bağlı değil (yalnız hata metni için okunur)
  const tRef = useRef(t)
  tRef.current = t
  const backupTimer = useRef(null)
  const wroteBackup = useRef(false)
  const skipFlush = useRef(false)

  const applyBase = useCallback((b) => { baseRef.current = b; setBase(b) }, [])
  /** Bu kanonik içerik sunucuda kayıtlı mı (taban ya da tabanı değişmemiş son kabul edilen otomatik gönderim)? */
  const isSavedJson = useCallback((json) => json === baseRef.current.json
    || (accepted.current != null && accepted.current.base === baseRef.current.json && accepted.current.sent === json), [])
  /** Süren kaydı bekler (hata yutulur — kaydın kendisi raporlar). Otomatik kaydın zaman aşımı beklemeyi sınırlar. */
  const settleSave = useCallback(async () => {
    const p = savePromise.current
    if (p) { try { await p } catch { /* kayıt kendi hatasını gösterir */ } }
  }, [])

  /** Sunucudan okunan raporu uygular. `keepDraft`: kaydedilmemiş taslak korunur (gönderim sonrası / yeniden dene). */
  const applyLoaded = useCallback((resData, keepDraft) => {
    // Taban, hangi şablonla kanonikleştirildiyse onunla karşılaştırılır: "temiz miydi?" ESKİ şablonla, yeni hâl YENİ
    // şablonla (yeni şablonun eklediği bir bağ temiz taslağı sahte çakışmaya düşürmesin)
    const prevTpl = canonTpl.current
    const nextTpl = resData.template || prevTpl
    const canonWith = (v, tpl) => stableJson(canonicalValues(v, tpl))
    const values = normalizeValues(resData.values)
    const json = canonWith(values, nextTpl)
    const cur = draftRef.current
    const wasClean = cur == null || isSavedJson(canonWith(cur, prevTpl))
    const versionMoved = baseRef.current.version != null && resData.report.version !== baseRef.current.version
    setData(resData)
    setImages(resData.images || [])
    if (keepDraft && !wasClean && versionMoved && json === canonWith(cur, nextTpl)) {
      // Sunucu tam olarak bu taslağı tutuyor (ör. kayıt yanıtı ağda kayboldu): çakışma değil — taban benimsenir
      canonTpl.current = nextTpl
      applyBase({ json, version: resData.report.version })
      setConflict(false)
      return
    }
    if (keepDraft && !wasClean && versionMoved) {
      // Elde kaydedilmemiş değişiklik var VE kayıt arada başkası tarafından değişti: taban İLERLETİLMEZ — sonraki
      // kayıt 409 alır, başkasının değişikliği sessizce ezilmez; kullanıcıya çakışma söylenir
      setConflict(true)
      return
    }
    canonTpl.current = nextTpl
    applyBase({ json, version: resData.report.version })
    if (!keepDraft || (wasClean && canonWith(cur, nextTpl) !== json)) {
      setDraft(values)
      setGen((g) => g + 1)
      setClientErrors({})
      setServerErrors({})
    }
    if (!keepDraft) {
      setAutoHold(false)
      const b = readBackup(reportId)
      const same = b && canonWith(normalizeValues(b.values), nextTpl) === json
      setBackup(b && !same ? b : null)
      if (same) dropBackup(reportId)
    }
    // Kaydedilmemiş taslak korunurken eldeki çakışma uyarısı kaldırılmaz
    if (!keepDraft || wasClean) setConflict(false)
  }, [reportId, applyBase, isSavedJson])

  const load = useCallback(async ({ keepDraft = false } = {}) => {
    // Süren otomatik kayıt önce biter: kayıttan ÖNCE okunmuş bir yanıt tabanı geri sarmasın (sahte çakışma)
    await settleSave()
    const my = ++seq.current
    loadInFlight.current = true
    inFlightKeepDraft.current = keepDraft
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.report(reportId)
      if (my !== seq.current) return null
      if (res?.success && res.data?.report) {
        applyLoaded(res.data, keepDraft)
        setState({ loading: false, error: null, status: null })
        // Okunan rapor çağırana döner (sonucu bilinmeyen gönderimin denetimi); geç kalan / hatalı okuma null
        return res.data
      }
      setState({ loading: false, error: res?.error || tRef.current('te.loadError'), status: res?.status ?? null })
    } catch (e) {
      if (my !== seq.current) return null
      setState({ loading: false, error: e?.message || tRef.current('te.loadError'), status: 0 })
    } finally {
      if (my === seq.current) loadInFlight.current = false
    }
    return null
  }, [reportId, applyLoaded, settleSave])
  useEffect(() => { load() }, [load])
  // Ay geçişiyle açıldıysa odak yeni raporun başlığına (tıklanan düğme yeniden kurulumda kaybolur — inceleme 2026-10-14)
  const hasData = !!data && !!draft
  useEffect(() => {
    if (!hasData || !focusTitleRef?.current) return
    focusTitleRef.current = false
    titleRef.current?.focus()
  }, [hasData, focusTitleRef])

  const dirty = draft != null && base.json != null && !isSavedJson(canonJson(draft))
  const readOnly = !data?.can_edit
  // Gönderim / onay isteği takımın kendi kapsamı: paylaşımla düzenleyen (9. sürüm) `can_send:false` alır; eski sunucuda can_edit
  const canSend = data?.can_send != null ? !!data.can_send : !readOnly
  // Anomali konularının istemci denetimi (2026-10-18; sunucu kuralının aynası — techErrorModel.issueProblems): kaydı kilitler,
  // otomatik kayıt bu alanları kayıtlı değerinde gönderir
  // Ay adı ARAYÜZ dilinde (inceleme 2026-10-18: İngilizce arayüzde Türkçe ay adı çıkıyordu)
  const issueChecks = useMemo(() => issueValidation(data?.template, draft?.issues,
    data?.report ? { year: data.report.year, month: data.report.month } : null, uiLang), [data?.template, draft?.issues, data?.report, uiLang])
  const issueChecksRef = useRef(issueChecks)
  issueChecksRef.current = issueChecks
  const hasClientErrors = hasAny(clientErrors) || Object.keys(issueChecks).length > 0
  const clientErrorsRef = useRef(clientErrors)
  clientErrorsRef.current = clientErrors
  const hasPending = hasAny(pending)
  const pendingRef = useRef(pending)
  pendingRef.current = pending
  // Yarım yazılmış sayı da kaydedilmemiş sayılır (ayrılırken sorulur, sekme kapanırken uyarılır)
  const unsaved = dirty || hasPending
  const setFieldPending = useCallback((key, p) => setPendingMap((m) => {
    if (!!m[key] === !!p) return m
    const out = { ...m }
    if (p) out[key] = true
    else delete out[key]
    return out
  }), [])
  const issueErrorTexts = useMemo(() => {
    const out = {}
    for (const [k, v] of Object.entries(issueChecks)) out[k] = t(v.key, ...(v.params || []))
    return out
  }, [issueChecks, t])
  const shownIssueErrors = useMemo(() => {
    if (showRequired) return issueErrorTexts
    const out = {}
    for (const [k, v] of Object.entries(issueChecks)) if (!v.required) out[k] = issueErrorTexts[k]
    return out
  }, [issueChecks, issueErrorTexts, showRequired])
  const fieldErrors = useMemo(() => ({ ...serverErrors, ...shownIssueErrors, ...clientErrors }),
    [serverErrors, shownIssueErrors, clientErrors])
  // Bölüm rozeti ("N alan düzeltilmeli") HENÜZ GÖSTERİLMEYEN boş zorunlu anomali alanlarını da sayar (inceleme 2026-10-18:
  // otomatik kayıt onları sessizce kayıtlı değerinde gönderiyordu, kapalı bölüm "temiz" görünüyordu)
  const countedErrors = useMemo(() => ({ ...serverErrors, ...issueErrorTexts, ...clientErrors }),
    [serverErrors, issueErrorTexts, clientErrors])
  /** Kaydı durduran istemci sorunu var mı (alan hatası ya da anomali denetimi)? */
  const blockingNow = () => hasAny(clientErrorsRef.current) || Object.keys(issueChecksRef.current).length > 0

  // Yazarken yerel yedek (1 sn sonra) — sekme kapanır / ağ düşerse emek kaybolmasın. Taslak yeniden temizlenince bu
  // sayfanın yazdığı yedek silinir (sonraki ziyarette boşuna "geri yükle" önerilmesin).
  useEffect(() => {
    if (readOnly) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropBackup(reportId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
      wroteBackup.current = true
    }, 1000)
    backupTimer.current = h
    return () => clearTimeout(h)
  }, [draft, dirty, readOnly, reportId, base.version])

  // Sayfadan çıkarken (uygulamada başka sekme, geri) son saniyenin düzenlemesi de yedeklenir
  const flushBackup = useRef(null)
  flushBackup.current = () => {
    // Çıkış (logout) kişisel kayıtları sildikten sonra yeniden yazılmaz: sahip işareti yoksa oturum kapanmıştır
    if (skipFlush.current || readOnly || !dirty || !draft || !storageOwner()) return
    writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
  }
  useEffect(() => () => flushBackup.current?.(), [])

  useEffect(() => {
    if (!unsaved) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [unsaved])

  // Grafik yuvasının DIŞINA bırakılan dosya tarayıcıyı görsele götürmesin (kaydedilmemiş taslak kaybolurdu)
  useEffect(() => {
    if (readOnly) return undefined
    const guard = (e) => { if (isFileDrag(e)) e.preventDefault() }
    window.addEventListener('dragover', guard)
    window.addEventListener('drop', guard)
    return () => {
      window.removeEventListener('dragover', guard)
      window.removeEventListener('drop', guard)
    }
  }, [readOnly])

  const tpl = data?.template
  const report = data?.report
  const channel = data?.channel
  // Görsel bölümlerinin geri çağrısı SABİT: ImagesBlock memo'su yazarken yeniden çizimi atlasın
  const onImagesChange = useCallback((fn) => {
    setImages(fn)
    statusGen.current++
    setPreviewRev(bumpMailPreviewContent(reportId))
    setData((d) => (d ? { ...d, report: { ...d.report, status: d.report.status === 'sent' ? 'changed' : d.report.status } } : d))
  }, [reportId])
  const resolve = useCallback((p) => resolvePlaceholders(p, channel?.name, report?.year, report?.month, tpl?.lang),
    [channel?.name, report?.year, report?.month, tpl?.lang])
  const resolveText = useCallback((p) => resolveTextPlaceholders(p, channel?.name, report?.year, report?.month, tpl?.lang),
    [channel?.name, report?.year, report?.month, tpl?.lang])

  /** Sunucu hatalarından verilen öneklerle eşleşenleri kaldırır (+ belge geneli `values`). */
  const clearServer = (match) => setServerErrors((e) => {
    const keys = Object.keys(e).filter((k) => k === 'values' || match(k))
    if (!keys.length) return e
    const out = { ...e }
    for (const k of keys) delete out[k]
    return out
  })
  // Konu adedinin kısaltma üssü SABİT geri çağrı (anomali kartları memo — her tuşta yeniden kurulmasın)
  const setUnit = useCallback((key, exp) => setDraft((d) => {
    const units = withUnit(d.units, key, exp)
    return units === d.units ? d : { ...d, units }
  }), [])
  const setField = (id, v, exp = null) => {
    setDraft((d) => {
      const units = withUnit(d.units, id, v == null ? null : exp)
      return { ...d, fields: { ...d.fields, [id]: v }, ...(units === d.units ? {} : { units }) }
    })
    clearServer((k) => k === `values.${id}`)
    if (v === false) {
      // Muaf olan (gizlenen) alanın hatası görünmeden kaydı kilitlemesin
      for (const dep of allFields(data?.template).filter((f) => f.skip_if_no === id)) {
        setFieldError(`values.${dep.id}`, null)
        clearServer((k) => k === `values.${dep.id}`)
      }
    }
  }
  /** Raporun o aya özel hedefi (2026-10-17, 4b): boş = şablon hedefi (anahtar silinir, kayda gitmez). */
  const setTarget = (id, v) => {
    setDraft((d) => {
      const targets = { ...(d.targets || {}) }
      if (v == null || v === '') delete targets[id]
      else targets[id] = v
      return { ...d, targets }
    })
    clearServer((k) => k === `targets.${id}`)
  }
  // Konu güncellemesi SABİT ve işlevsel (inceleme 2026-10-18): kartlar memo — geri çağrı her tuşta yeniden kurulmasın, güncelleme
  // en son taslak üzerinde yapılsın (kapanıştaki eski liste başka kartın değişikliğini ezmesin)
  const updateIssues = useCallback((sid, fn) => {
    setDraft((d) => ({ ...d, issues: { ...d.issues, [sid]: fn(d.issues?.[sid] || []) } }))
    setServerErrors((e) => {
      const keys = Object.keys(e).filter((k) => k === 'values' || k.startsWith(`issues.${sid}.`))
      if (!keys.length) return e
      const out = { ...e }
      for (const k of keys) delete out[k]
      return out
    })
  }, [])
  const setText = (sid, v) => {
    setDraft((d) => ({ ...d, texts: { ...d.texts, [sid]: v } }))
    clearServer((k) => k === `text.${sid}`)
  }
  /** Elle düzenlenmiş özeti kaldırır (anahtar silinir — "boş metin" bölümü basmamak demektir, "yok" ise otomatik). */
  const clearText = (sid) => {
    setDraft((d) => {
      if (!(sid in (d.texts || {}))) return d
      const texts = { ...d.texts }
      delete texts[sid]
      return { ...d, texts }
    })
    clearServer((k) => k === `text.${sid}`)
  }
  const setCell = (sid, rid, cid, v, exp = null) => {
    setDraft((d) => {
      const tables = { ...(d.tables || {}) }
      const rows = { ...(tables[sid] || {}) }
      rows[rid] = { ...(rows[rid] || {}), [cid]: v }
      tables[sid] = rows
      const units = withUnit(d.units, `tables.${sid}.${rid}.${cid}`, v == null ? null : exp)
      return { ...d, tables, ...(units === d.units ? {} : { units }) }
    })
    clearServer((k) => k === `tables.${sid}.${rid}.${cid}`)
  }
  const setFieldError = useCallback((key, msg) => setClientErrors((e) => {
    if ((e[key] || null) === (msg || null)) return e
    const out = { ...e }
    if (msg) out[key] = msg
    else delete out[key]
    return out
  }), [])

  /**
   * Kaydeder; başarıyla (ya da değişiklik yoksa) true döner. Süren bir kayıt varsa önce onu bekler (aynı sürümle iki
   * istek gitmez). `auto`: otomatik kayıt — sessiz, geçersiz alanlar kayıtlı değerinde gönderilir.
   */
  function save(opts = {}) {
    const run = (async () => {
      await settleSave()
      return doSave(opts)
    })()
    savePromise.current = run
    const clear = () => { if (savePromise.current === run) savePromise.current = null }
    run.then(clear, clear)
    return run
  }

  async function doSave({ quiet = false, auto = false } = {}) {
    if (!draftRef.current || readOnly) return false
    const invalidKeys = Object.keys(clientErrorsRef.current).filter((k) => clientErrorsRef.current[k])
    for (const k of Object.keys(issueChecksRef.current)) if (!invalidKeys.includes(k)) invalidKeys.push(k)
    if (!auto && invalidKeys.length) { lastSaveFailure.current = 'invalid'; revealErrors(); toast.error(t('te.fixFieldErrors')); return false }
    // Otomatik kayıtta yarım yazılmış sayı da geçersiz gibi kayıtlı değerinde gider
    if (auto) for (const k of Object.keys(pendingRef.current)) if (!invalidKeys.includes(k)) invalidKeys.push(k)
    // Otomatik kayıtta geçersiz alan sunucudaki (eski) değerinde kalır; diğer alanlar kaydedilir
    const source = auto && invalidKeys.length
      ? revertInvalidValues(draftRef.current, baseRef.current.json ? JSON.parse(baseRef.current.json) : null, invalidKeys)
      : draftRef.current
    const sent = canonicalValues(source, canonTpl.current)
    const sentJson = stableJson(sent)
    if (isSavedJson(sentJson)) {
      lastSaveFailure.current = null
      // Süren otomatik kaydı bekleyen elle kayıt da geri bildirim alır (Kaydet'e basıldı, iş zaten yapılmıştı)
      if (!auto && !quiet) toast.success(t('te.saved'))
      return true
    }
    if (auto && sentJson === autoBlocked.current) return false
    // Uçuştaki bir yükleme (ör. gönderim sonrası) bu kaydın sürümünü eskisiyle ezmesin: düşürülür ve kayıttan SONRA
    // yeniden yapılır (gönderim durumu / "yeniden gönder" düğmesi eski kalmasın)
    const droppedLoad = loadInFlight.current
    const droppedKeepDraft = inFlightKeepDraft.current
    seq.current++
    loadInFlight.current = false
    if (auto) setAutoSaving(true)
    else setBusy('save')
    try {
      const res = await api.techErrors.saveReport(reportId, sent, baseRef.current.version,
        ...(auto ? [{ auto: true, timeoutMs: AUTOSAVE_TIMEOUT_MS }] : []))
      if (res?.success && res.data) {
        const values = normalizeValues(res.data.values)
        const baseJson = canonJson(values)
        applyBase({ json: baseJson, version: res.data.version })
        accepted.current = auto ? { sent: sentJson, base: baseJson } : null
        // Kayıt sürerken yazılan değişiklik korunur: yanıt yalnız GÖNDERİLEN taslağın yerini alır. Otomatik kayıt taslağa
        // HİÇ dokunmaz (kullanıcı yazıyor olabilir); yedek yine silinir — içerik sunucuda
        const untouched = canonJson(draftRef.current) === sentJson
        if (untouched && !auto) {
          // Otomatik kayıt "bulunan taslak" uyarısını kaldırmaz (2. tur): kullanıcı ona henüz cevap vermemiş olabilir —
          // eski taslağın son kopyası o uyarıdadır
          setDraft(values)
          dropBackup(reportId)
          setBackup(null)
        }
        if (!auto) setAutoHold(false)
        if (!auto) setShowRequired(false)
        // Silinen konunun görselini sunucu bu kayıtta sildi (2026-10-18): yerel liste de aynı kuralla süzülür
        setImages((list) => {
          const next = list.filter((i) => issueImageAlive(i.slot_id, canonTpl.current, values))
          return next.length === list.length ? list : next
        })
        setServerErrors({})
        setState((s) => (s.loading ? { ...s, loading: false } : s))
        // Durumu YALNIZ içeriği değiştiren kayıt "changed" yapar — ancak o zaman yoklamaya "yerel değişiklik var" denir
        // (20.157.0 incelemesi: hiçbir şey değiştirmeyen kayıt da sayacı artırıyor, yoklama sonraki "sent"i yazmıyor, bayat
        // "değişti" rozeti kalıyordu)
        if (res.data.changed) statusGen.current++
        setData((d) => (d ? { ...d, report: { ...d.report, version: res.data.version, updated_at: res.data.updated_at,
          updated_by_name: res.data.updated_by_name ?? d.report.updated_by_name,
          status: d.report.status === 'sent' && res.data.changed ? 'changed' : d.report.status } } : d))
        autoBlocked.current = null
        autoBackoff.current = { until: 0, n: 0 }
        lastSaveFailure.current = null
        setAutoInfo(auto ? { at: Date.now(), ok: true } : null)
        if (!quiet && !auto) toast.success(t('te.saved'))
        return true
      }
      const failStatus = errorInfoOf(res)?.status
      lastSaveFailure.current = res?.code === 'CONCURRENT_MODIFICATION' ? 'conflict'
        : res?.code === 'VALIDATION_FAILED' && res?.field ? 'invalid'
          : failStatus === 0 || res?.code === 'NETWORK_ERROR' || res?.code === 'OFFLINE' ? 'network' : 'server'
      if (res?.code === 'CONCURRENT_MODIFICATION') {
        setConflict(true)                          // uyarı görünür; otomatik kayıt çakışma çözülene kadar durur
      } else if (res?.code === 'VALIDATION_FAILED' && res?.field) {
        setServerErrors((e) => ({ ...e, [res.field]: res.error }))
        if (auto) { autoBlocked.current = sentJson; setAutoInfo({ at: Date.now(), ok: false, blocked: true }) }
        else toast.error(res.error)
      } else if (auto) {
        // İstemci kaynaklı ret (4xx) aynı içerikle yinelenmez; sunucu / ağ hatası / zaman aşımı artan aralıkla denenir
        const status = errorInfoOf(res)?.status
        if (status >= 400 && status < 500) {
          autoBlocked.current = sentJson
          // Alan hatası değil (403 / 404 / 413 …): sunucunun söylediği gösterilir — "işaretli alan" yoktur
          setAutoInfo({ at: Date.now(), ok: false, blocked: true, message: res?.error || null })
        } else {
          backOff()
          setAutoInfo({ at: Date.now(), ok: false })
        }
      } else {
        toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
      }
      return false
    } catch (e) {
      lastSaveFailure.current = 'network'
      if (auto) { backOff(); setAutoInfo({ at: Date.now(), ok: false }) }
      else toast.error(e?.message || t('te.saveError'))
      return false
    } finally {
      if (auto) setAutoSaving(false)
      else setBusy(null)
      // Düşürülen yükleme AYNI kiple yeniden: "En son hâli yükle" tam yüklemeydi (taslak yerine sunucu + yedek önerisi)
      if (droppedLoad) load({ keepDraft: droppedKeepDraft })
    }
  }

  /** PDF / gönderim / şablon uygulama öncesi: geçersiz alan varsa durur; kaydedilmemiş değişiklik varsa kaydetmeyi önerir. */
  /** Ağ / sunucu hatasından sonra bir sonraki otomatik denemeyi öteler (30 sn → 60 → … ≤ 5 dk). */
  function backOff() {
    const n = autoBackoff.current.n + 1
    autoBackoff.current = { n, until: Date.now() + Math.min(AUTOSAVE_MS * 2 ** (n - 1), AUTOSAVE_BACKOFF_MAX_MS) }
  }

  async function ensureSaved() {
    if (readOnly) return true
    await settleSave()
    if (blockingNow()) { revealErrors(); toast.error(t('te.fixFieldErrors')); return false }
    if (isSavedJson(canonJson(draftRef.current))) return true
    const ok = await showConfirm({ title: t('te.saveFirstTitle'), message: t('te.saveFirstBody'), variant: 'warning',
      confirmText: t('te.saveAndContinue'), cancelText: t('te.cancel') })
    if (!ok) return false
    return save({ quiet: true })
  }

  async function downloadPdf() {
    if (!(await ensureSaved())) return
    setBusy('pdf')
    try {
      const res = await api.techErrors.downloadPdf(reportId)
      // DOWNLOAD_FAILED (inceleme 2026-10-18, 3. tur): PDF geldi ama tarayıcı indirmeyi başlatmadı — sessiz "başarı" yok
      if (!res?.success) toast.error(res?.code === 'DOWNLOAD_FAILED' ? t('te.pdfDownloadFailed') : (res?.error || t('te.pdfError')),
        { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.pdfError'))
    } finally {
      setBusy(null)
    }
  }

  // PO onayı (2026-10-17, S6): yönetici alıcısı tanımlı kanalda gönderim onaya bağlı — "Onaya gönder" / "Yöneticilere gönder"
  const approvalDetail = data?.approval_detail || null
  const approvalRequired = !!approvalDetail?.required

  async function requestApproval() {
    if (!(await ensureSaved())) return
    const names = (approvalDetail?.approvers || []).map((a) => a?.name).filter(Boolean)
    const ok = await showConfirm({
      title: t('te.appr.requestTitle'),
      message: t('te.appr.requestBody', names.length, names.join(', ') || '—', approvalDetail?.executive_count || 0),
      variant: 'info', confirmText: t('te.appr.requestConfirm'), cancelText: t('te.cancel'),
    })
    if (!ok) return
    setBusy('approval')
    try {
      const res = await api.techErrors.requestApproval(reportId)
      if (res?.success && res.data) {
        if ((res.data.mailed || 0) > 0) toast.success(t('te.appr.requested', res.data.mailed))
        else toast.info(t('te.appr.requestedNoMail', res.data.mail_problem || '—'))
      } else {
        toast.error(res?.error || t('te.appr.requestError'), { details: res?.errorInfo })
      }
    } catch (e) {
      toast.error(e?.message || t('te.appr.requestError'))
    } finally {
      setBusy(null)
      load({ keepDraft: true })
    }
  }

  /**
   * Gönderim: önce kayıt (ensureSaved), sonra gönderim penceresi (R18 / C13 — eski onay sorusunun metni + isteğe bağlı ek
   * Bilgi (CC) alanı). Onay penceresi açık kalırken istek gider; sunucu ek Bilgi'yi reddederse (alan `extra_cc`) hata alanın
   * altında, pencere açık. Yeniden denenebilir hatada (429 PDF sırası / kişi başı sınır — `sendFailureKind`) pencere de AÇIK
   * kalır, hata pencerenin içinde, yazılan ek Bilgi korunur (inceleme 2026-10-18: pencere kapanıyor, bir sonraki "Gönder" ek
   * adresleri olmadan — geri alınamaz — gidebiliyordu); rapor arkada yenilenir. Başarıda ve kesin hatada pencere kapanır,
   * bildirim gösterilir.
   *
   * Sonucu BİLİNMEYEN hatada (ağ hatası, 409 `SEND_IN_PROGRESS`, 503 / 504 — inceleme 2026-10-18, 3. tur) gönderim gitmiş ya
   * da sürüyor olabilir: pencere açık, "Gönder" (ve Ctrl+Enter) rapor YENİDEN OKUNUP durum bilinene kadar KİLİTLİ
   * (`checkUnsureSend`, okuma beklenir). Gitmiş / sürüyor / kuyrukta → pencere kapanır, durum bildirilir; açıkça gitmemiş →
   * "Gönder" yeniden açılır, ek Bilgi korunur; okunamadı → kilit kalır, "Durumu yeniden denetle". Eskiden kilit yoktu: ilk
   * istek sunucuda tamamlandıysa ikinci tıklama raporu yöneticilere İKİNCİ kez yolluyordu (`claimSend` gönderilmiş raporun
   * yeniden gönderimine izin verir).
   */
  async function send() {
    if (!(await ensureSaved())) return
    if (unsureSend.current) {
      // Çözülmemiş belirsiz gönderim: pencere kilitli açılır, taşınan ek Bilgi'yle (bant "korundu" der) ve durum yeniden
      // denetlenir (taze, kilitsiz, boş pencere değil)
      const u = unsureSend.current
      setSendDialog({ error: null, failure: unsureFailure(u, 'checking'), initialCc: ccCarry.current })
      checkUnsureSend()
      return
    }
    const r = resumeSend.current
    resumeSend.current = null
    // Gönderim "gitmemiş" çözülmüştü: "gitmemiş" bandı + taşınan ek Bilgi (ilk açılışta o denemenin adresleri, sonra
    // kullanıcının son yazdığı) — yalnız o okumadan beri raporda gönderim olmadıysa. Eldeki satır bayat olabilir (20.157.0
    // incelemesi: başka sekme / kişi arada göndermişse bant "yeniden gönderebilirsiniz" deyip ikinci gönderime çağırıyordu):
    // önce rapor YENİDEN okunur, bant yalnız TAZE satır hâlâ yeni gönderim göstermiyorsa çıkar
    if (r) {
      const fresh = await readSendStatus()
      if (fresh && sameSendMark(r.mark, fresh)) {
        resumeSend.current = r
        setSendDialog({ error: null, failure: notSentFailure(r.details), initialCc: ccCarry.current })
        return
      }
      if (!fresh) {
        // Okunamadı: "gitmemiş" denmez (bilinmiyor) — pencere taze açılır; sonraki açılış yeniden denetler
        resumeSend.current = r
        setSendDialog({ error: null, failure: null })
        return
      }
    }
    ccCarry.current = ''
    setSendDialog({ error: null, failure: null })
  }

  /**
   * Gönderim alanlarının taze okuması ("gitmemiş" bandından önce — 20.157.0 incelemesi): raporu okur, yalnız gönderim
   * alanlarını işler (yoklamayla aynı: taslak / sürüm değişmez; istekten sonra yerelde "changed" olduysa "sent" yazılmaz;
   * okuma sürerken tam yükleme başladıysa satıra yazılmaz). Okuma sürerken `busy` 'sendcheck'. Okunamazsa `null`.
   */
  async function readSendStatus() {
    setBusy('sendcheck')
    const loadSeq = seq.current
    const statusMark = statusGen.current
    let fresh = null
    try {
      const res = await api.techErrors.report(reportId)
      fresh = res?.success ? res.data?.report || null : null
    } catch {
      fresh = null
    } finally {
      setBusy(null)
    }
    if (fresh && loadSeq === seq.current) {
      const keepChanged = statusGen.current !== statusMark
      setData((d) => (d ? { ...d, report: withSendStatus(d.report, fresh, { keepChanged }) } : d))
    }
    return fresh
  }

  /** "Gitmemiş" bandı: metin taşınan ek Bilgi'ye göre (boşsa "korundu" demez). */
  function notSentFailure(details) {
    return { kind: 'retry', title: t('te.send.checkedNotSentTitle'),
      message: ccCarry.current.trim() ? t('te.send.checkedNotSent') : t('te.send.checkedNotSentNoCc'), details: details ?? null }
  }

  /** Pencere içi yeniden denenebilir hata bandı: başlık, metin (+ "ek Bilgi korundu"), künye. */
  function sendFailure(kind, res, fallback) {
    const msg = res?.message || res?.error || fallback
    return { kind, title: t('te.send.retryTitle'), message: `${msg} ${t('te.send.retryKept')}`, details: res?.errorInfo ?? errorInfoOf(res) }
  }

  /** Sonucu bilinmeyen gönderimin bandı: `check` 'checking' (rapor okunuyor) | 'unknown' (okunamadı) — ikisinde de kilitli. */
  function unsureFailure(u, check) {
    const tail = check === 'unknown' ? t('te.send.unsureCheckFailed') : t('te.send.unsureBody')
    return { kind: 'unsure', check, title: t('te.send.unsureTitle'), message: `${u.msg} ${tail}`, details: u.details }
  }

  /**
   * Sonucu bilinmeyen gönderimden sonra raporu YENİDEN OKUR (beklenir) ve durumu pencereye taşır. Okuma sürerken `busy`
   * 'sendcheck': düzenleyicinin gönder / gezinme düğmeleri ve otomatik kayıt bekler; pencere kapatılabilir (sonuç yine
   * bildirilir; çözülmezse yeniden açılışta kilit taşınır).
   */
  async function checkUnsureSend() {
    const u = unsureSend.current
    if (!u) return
    setBusy('sendcheck')
    setSendDialog((d) => (d ? { ...d, failure: unsureFailure(u, 'checking') } : d))
    let after = null
    try {
      const loaded = await load({ keepDraft: true })
      after = loaded?.report || null
    } catch {
      after = null
    } finally {
      setBusy(null)
    }
    if (unsureSend.current !== u) return
    const outcome = unsureSendOutcome(u.before, after)
    if (outcome === 'unknown') {
      setSendDialog((d) => (d ? { ...d, failure: unsureFailure(u, 'unknown') } : d))
      return
    }
    unsureSend.current = null
    if (outcome === 'notSent') {
      // Yeniden açılış da bu bandı ve taşınan ek Bilgi'yi getirir (pencere şimdi kapalıysa bildirim söyler)
      resumeSend.current = { details: u.details, mark: sendMarkOf(after) }
      if (!sendDialogRef.current) {
        toast.info(ccCarry.current.trim() ? t('te.send.checkedNotSentClosed') : t('te.send.checkedNotSentClosedNoCc'), 12000)
      }
      setSendDialog((d) => (d ? { ...d, failure: notSentFailure(u.details) } : d))
      return
    }
    // Yoklama bu denemenin yazılan ek Bilgi'sini taşır ("gitmedi" çıkarsa ilk açılış onunla); pencere taze açılır
    const extraCc = ccCarry.current
    ccCarry.current = ''
    setSendDialog(null)
    if (outcome === 'sent') toast.success(t('te.send.checkedSent'))
    else if (outcome === 'queued') toast.info(t('te.sendQueued'))
    else toast.info(t('te.send.checkedSending'))
    // Sürüyor / kuyrukta: sonuç gelene kadar gönderim durumu yoklanır ("Gönder" kendiliğinden açılır, sonuç bildirilir).
    // Taban gönderimden ÖNCEKİ satır; kuyruktaysa bu denemenin kuyruğunu gösteren okuma (5. tur)
    if (outcome !== 'sent') {
      startSendPoll(outcome === 'queued' ? pollBaselineAfterQueued(u.before, after) : { baseline: u.before, own: false }, extraCc)
    }
  }

  function setPoll(p) {
    sendPollRef.current = p
    setSendPoll(p)
  }

  /**
   * Gönderim durumunu yoklamaya başlar (en çok `SEND_POLL_MAX_MS`; yeni deneme ya da kesin sonuç durdurur). `base` =
   * `{ baseline, own }` (`sendPollStep`), `extraCc` = bu denemenin ek Bilgi'si. Yoklamanın kimliğini döner.
   */
  function startSendPoll(base, extraCc = '') {
    pollSeq.current++
    const id = ++pollIds.current
    setPoll({ id, until: Date.now() + SEND_POLL_MAX_MS, baseline: base?.baseline ?? null, own: !!base?.own, extraCc: extraCc || '' })
    return id
  }

  function stopSendPoll() {
    pollSeq.current++
    setPoll(null)
  }

  /**
   * Yoklama turu (inceleme 2026-10-18, 4. tur): raporu okur ve YALNIZ gönderim alanlarını işler (`withSendStatus` — taslak,
   * taban sürümü, şablon değişmez). Sıra korumalı: yoklama durdurulduysa / yeniden başladıysa ya da tur sürerken tam yükleme
   * ya da kayıt BAŞLADIYSA (daha taze veri) yanıt düşer; istekten sonra kayıt yanıtı / görsel değişikliği durumu yerelde
   * "changed" yaptıysa yanıttaki "sent" onun üstüne yazılmaz (5. tur). Karar `sendPollStep` ile gönderimden ÖNCEKİ satıra
   * (ya da bu denemenin kuyruğuna) göre: bırakılan talep (PDF sırası dolu, onay eskidi) "gitmedi" sayılır — "Gönder" açılır,
   * sonraki açılış yazılan ek Bilgi'yle. Kesin sonuçta durur ve bildirir; süre dolunca "sonuç gelmedi" der. Hiç reddetmez
   * (yoklama kancası reddi yüzeye çıkarırdı).
   */
  async function pollSendStatus() {
    const p = sendPollRef.current
    if (!p) return
    if (Date.now() > p.until) {
      stopSendPoll()
      toast.info(t('te.send.pollUnknown', Math.round(SEND_POLL_MAX_MS / 60000)), 12000)
      return
    }
    const my = ++pollSeq.current
    const loadSeq = seq.current
    const statusMark = statusGen.current
    let res = null
    try { res = await api.techErrors.report(reportId) } catch { res = null }
    const cur = sendPollRef.current
    if (my !== pollSeq.current || loadSeq !== seq.current || !cur || cur.id !== p.id) return
    const fresh = res?.success ? res.data?.report : null
    const step = sendPollStep(cur, fresh)
    if (!step.outcome) return                         // okunamadı: bir sonraki turda yeniden
    const keepChanged = statusGen.current !== statusMark
    setData((d) => (d ? { ...d, report: withSendStatus(d.report, fresh, { keepChanged }) } : d))
    if (step.outcome === 'pending') {
      if (step.poll !== cur) setPoll(step.poll)       // bu denemenin kuyruğu görüldü: sonraki turlar onun sonucuna bakar
      return
    }
    stopSendPoll()
    if (step.outcome === 'sent') {
      ccCarry.current = ''
      resumeSend.current = null
      toast.success(t('te.send.pollSent'))
    } else if (step.outcome === 'notSent') {
      // Gitmedi: "Gönder" açık; sonraki açılış "gitmemiş" bandı + bu denemenin ek Bilgi'siyle (pencere açıksa kullanıcının
      // yazdığına dokunulmaz)
      if (!sendDialogRef.current) ccCarry.current = cur.extraCc || ''
      resumeSend.current = { details: null, mark: sendMarkOf(fresh) }
      setSendDialog((d) => (d ? { ...d, failure: notSentFailure(null) } : d))
      toast.error(cur.extraCc ? t('te.send.pollNotSentCc') : t('te.send.pollNotSent'), 12000)
    } else if (step.outcome === 'superseded') {
      toast.info(t('te.send.pollSuperseded'), 12000)
    } else {
      toast.info(t('te.send.pollUnknown', Math.round(SEND_POLL_MAX_MS / 60000)), 12000)
    }
  }
  useVisibleInterval(pollSendStatus, sendPoll ? SEND_POLL_MS : null, false)
  // Ayrılınca uçuştaki yoklama yanıtı düşer
  useEffect(() => () => { pollSeq.current++ }, [])

  async function confirmSend(extraCc) {
    if (busy != null || unsureSend.current) return
    // Gönderimden ÖNCEKİ rapor satırı: sonucu bilinmeyen hatada yeniden okunanla karşılaştırılır
    const before = data?.report || null
    setBusy('send')
    setSendDialog((d) => (d ? { ...d, failure: null } : d))
    // Yeni deneme: önceki "gitmemiş" bandı ve süren yoklama biter (bu denemenin sonucu kendi yolundan)
    resumeSend.current = null
    stopSendPoll()
    let keepOpen = false
    let refresh = true
    let unsure = null
    let queued = false
    let refreshing = null
    const markUnsure = (r) => {
      keepOpen = true
      refresh = false
      // Ek Bilgi taşınır: pencere kapatılıp yeniden açılırsa alan bu adreslerle (sonra kullanıcının son yazdığıyla) açılır
      ccCarry.current = extraCc || ''
      unsure = { before, msg: r?.message || r?.error || t('te.sendError'), details: errorInfoOf(r) }
      unsureSend.current = unsure
      setSendDialog({ error: null, failure: unsureFailure(unsure, 'checking'), initialCc: ccCarry.current })
    }
    try {
      // Ek Bilgi yoksa gövdesiz (eski çağrı aynen); varsa yalnız `extra_cc`
      const res = extraCc ? await api.techErrors.send(reportId, { extra_cc: extraCc }) : await api.techErrors.send(reportId)
      const kind = sendFailureKind(res)
      if (res?.success && res.data) {
        const cc = (res.data.cc || []).join(', ')
        const to = (res.data.to || []).join(', ')
        ccCarry.current = ''
        // Kuyruk: posta servisinin sonucu gelene kadar durum yoklanır (bildirim "kendiliğinden Gönderildi olur" der)
        if (res.data.state === 'QUEUED') { toast.info(t('te.sendQueued')); queued = true }
        // Kime boş (inceleme 2026-10-18, 2. tur: yöneticilerin hepsi pasif, posta yalnız Bilgi'ye gitti) → boş liste yazılmaz
        else if (!to && cc) toast.success(t('te.sendDoneCcOnly', cc))
        else if (!to) toast.success(t('te.sendDonePlain'))
        else if (extraCc && cc) toast.success(t('te.sendDoneCc', to, cc))
        else toast.success(t('te.sendDone', to))
        // Dönüşümlü dağıtım (inceleme 2026-10-18): ek Bilgi'yi bilmeyen eski bir pod gövdeyi yok sayıp gönderir ve yanıtta
        // `cc` olmaz — ek adreslere posta gitmemiş olabilir; sessiz "gönderildi" yerine kullanıcıya söylenir
        if (extraCc && !Array.isArray(res.data.cc)) toast.info(t('te.sendCcNotConfirmed'), 12000)
      } else if (res?.field === 'extra_cc') {
        keepOpen = true
        refresh = false
        setSendDialog({ error: res.error || t('te.send.ccRejected'), failure: null })
      } else if (kind === 'unsure') {
        markUnsure(res)
      } else if (kind) {
        keepOpen = true
        setSendDialog({ error: null, failure: sendFailure(kind, res, t('te.sendError')) })
      } else {
        toast.error(res?.error || t('te.sendError'), { details: res?.errorInfo })
      }
    } catch (e) {
      const kind = sendFailureKind(e)
      if (kind === 'unsure') markUnsure(e)
      else if (kind) {
        keepOpen = true
        setSendDialog({ error: null, failure: sendFailure(kind, e, t('te.sendError')) })
      } else toast.error(e?.message || t('te.sendError'))
    } finally {
      setBusy(null)
      if (!keepOpen) setSendDialog(null)
      // Pencere açık kalsa da rapor arkada yenilenir (gönderim başka sekmede sürüyor)
      if (refresh) refreshing = load({ keepDraft: true })
    }
    if (queued) {
      // Kuyruğa alındı: yoklama gönderimden önceki satırla başlar; yanıttan hemen sonraki okuma bu denemenin kuyruğunu
      // gösteriyorsa taban o satır olur (5. tur — sonuç o kuyruğun kendi sonucu; eski satıra göre karar bayat olabilirdi)
      const id = startSendPoll({ baseline: before, own: false }, extraCc)
      const loaded = refreshing ? await refreshing : null
      const cur = sendPollRef.current
      if (cur && cur.id === id && !cur.own) {
        const next = pollBaselineAfterQueued(before, loaded?.report)
        if (next.own) setPoll({ ...cur, ...next })
      }
    }
    // Belirsiz sonuç: rapor yeniden okunur ve BEKLENİR — durum bilinene kadar "Gönder" kilitli
    if (unsure) await checkUnsureSend()
  }

  async function applyTemplate() {
    if (!(await ensureSaved())) return
    setBusy('refresh')
    try {
      // Kayıttan SONRAKİ önizleme ve sürüm: kaybolacak sayılar kaydedilen değerlere göre değişmiş olabilir
      let fresh
      try {
        fresh = await api.techErrors.report(reportId)
      } catch (e) {
        toast.error(e?.message || t('te.loadError'))
        return
      }
      if (!fresh?.success || !fresh.data?.report) {
        toast.error(fresh?.error || t('te.loadError'), { details: fresh?.errorInfo })
        return
      }
      const clean = isSavedJson(canonJson(draftRef.current))
      if (fresh.data.report.version !== baseRef.current.version && !clean) { setConflict(true); return }
      // Temiz taslak: başkasının kaydı sorun değil — taze hâl alınır (sahte çakışma uyarısı yok)
      applyLoaded(fresh.data, true)
      const upd = fresh.data.template_update
      if (!upd?.differs) {
        toast.info(t('te.applyTemplateNone'))
        return
      }
      const loss = upd.lost_values || upd.lost_images
      // Girilmiş oranların yerini adetlerden hesaplanan değer alacaksa ayrıca söylenir (2026-10-16)
      const recalc = upd.recalculated > 0 ? ` ${t('te.applyTemplateRecalc', upd.recalculated)}` : ''
      // Yeni şablon "konuyu rapora alma" diyorsa müşteri etkisi olmayan (0 müşteriden çıkarılan dahil) konular PDF / e-postadan
      // düşer — sessiz kalmasın (inceleme 2026-10-18)
      const hidden = upd.hidden_topics > 0 ? ` ${t('te.applyTemplateHidden', upd.hidden_topics)}` : ''
      const ok = await showConfirm({
        title: t('te.applyTemplateTitle'),
        message: (loss ? t('te.applyTemplateLoss', upd.lost_values || 0, upd.lost_images || 0) : t('te.applyTemplateBody')) + recalc + hidden,
        variant: loss ? 'danger' : 'warning',
        confirmText: t('te.applyTemplateConfirm'), cancelText: t('te.cancel'),
      })
      if (!ok) return
      // Onay sırasında yazılan değişiklik şablon uygulamasıyla kaybolmasın
      if (!isSavedJson(canonJson(draftRef.current))) { toast.error(t('te.fixUnsavedFirst')); return }
      const res = await api.techErrors.refreshTemplate(reportId, baseRef.current.version)
      if (res?.success) { toast.success(t('te.applyTemplateDone')); dropBackup(reportId); await load() }
      else if (res?.code === 'CONCURRENT_MODIFICATION') setConflict(true)
      else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setBusy(null)
    }
  }

  async function remove() {
    const ok = await showConfirm({
      title: t('te.deleteReportTitle'),
      message: t('te.deleteReportBody', periodLabel(report, t), channel?.name || ''),
      variant: 'danger', confirmText: t('te.deleteReportConfirm'), cancelText: t('te.cancel'),
    })
    if (!ok) return
    setBusy('delete')
    try {
      const res = await api.techErrors.deleteReport(reportId)
      if (res?.success) {
        skipFlush.current = true
        clearTimeout(backupTimer.current)
        dropBackup(reportId)
        toast.success(t('te.reportDeleted'))
        onBack(channel?.id)
      } else {
        toast.error(res?.error || t('te.deleteError'), { details: res?.errorInfo })
        if (res?.code === 'REPORT_SENT_OR_SENDING') load({ keepDraft: true })
      }
    } catch (e) {
      toast.error(e?.message || t('te.deleteError'))
    } finally {
      setBusy(null)
    }
  }

  async function leave(next) {
    if (leaving.current) return
    leaving.current = true
    try {
      await leaveNow(next)
    } finally {
      leaving.current = false
    }
  }

  async function leaveNow(next) {
    // Süren (otomatik) kayıt önce biter; sonra GÜNCEL taslağa bakılır
    await settleSave()
    const notSaved = draftRef.current != null && !isSavedJson(canonJson(draftRef.current))
    // Alanda yazılı ama geçersiz ya da yarım (taslağa girmemiş) metin de kaybolmadan sorulur (inceleme 2026-10-14)
    if ((notSaved || hasAny(clientErrorsRef.current) || hasAny(pendingRef.current)) && !readOnly) {
      const ok = await showConfirm({ title: t('te.leaveTitle'), message: t('te.leaveBody'), variant: 'warning',
        confirmText: t('te.leaveConfirm'), cancelText: t('te.stay') })
      if (!ok) return
    }
    next()
  }

  function replaceDraft(values) {
    setDraft(values)
    setGen((g) => g + 1)
    setClientErrors({})
    setServerErrors({})
    setPendingMap({})
    setShowRequired(false)
  }

  const sections = useMemo(() => tpl?.sections || [], [tpl])
  // Anomali kartlarının dönemi SABİT nesne (kart memo'su her çizimde yeni nesneyle bozulmasın)
  const reportYear = report?.year
  const reportMonth = report?.month
  const issuePeriod = useMemo(() => (reportYear ? { year: reportYear, month: reportMonth, monthName: monthName(reportMonth, uiLang) } : null),
    [reportYear, reportMonth, uiLang])
  // Kaydı durduran anomali alanlarının ADLARI (durum satırı, inceleme 2026-10-18): "Kesinti ID (Kart Limiti: Hata Artışı)" —
  // boş zorunlu alan henüz alanın altında gösterilmese de otomatik kaydın onu kaydetmediği söylenir
  const draftIssues = draft?.issues
  const issueProblemNames = useMemo(() => {
    const out = []
    for (const k of Object.keys(issueChecks)) {
      const [, sid, iid, f] = k.split(':')
      const sec = (tpl?.sections || []).find((x) => x.id === sid)
      const list = draftIssues?.[sid] || []
      const idx = list.findIndex((i) => i?.id === iid)
      if (!sec || idx < 0) continue
      const name = f === 'outage_id' ? resolve(issueOptions(sec, tpl?.lang).outageIdLabel) : t(ISSUE_FIELD_LABEL[f] || 'te.iss.date')
      out.push(`${name} (${issueDisplayTitle(list[idx], sec.item_title) || t('te.iss.topicN', idx + 1)})`)
    }
    return out
  }, [issueChecks, tpl, draftIssues, resolve, t])
  const problemList = issueProblemNames.length > 3
    ? `${issueProblemNames.slice(0, 3).join(', ')} +${issueProblemNames.length - 3}` : issueProblemNames.join(', ')
  // Adı listede OLMAYAN başka hatalı / yarım alan da var mı (inceleme 2026-10-18): liste yalnız anomali denetimini adlandırır;
  // bir gösterge alanı da hatalıyken "diğer alanlar kaydedilir" eksik kalıyordu — durum satırı onları da söyler
  const otherInvalid = useMemo(() => {
    const named = new Set(Object.keys(issueChecks))
    const isOther = (m) => Object.entries(m || {}).some(([k, v]) => v && !named.has(k))
    return isOther(clientErrors) || isOther(pending)
  }, [issueChecks, clientErrors, pending])
  // Rapor toplamı ve yükleme ipucu yalnız ilk görsel bölümünde (varsayılan şablonda beş görsel bölümü var)
  const firstImagesId = sections.find((s) => s.kind === 'images')?.id
  // Otomatik oranlar (2026-10-16): taslağın adetlerinden CANLI hesap — yalnız gösterim (alan çıktısı, özet önizlemesi,
  // trendin bu ayı); taslağa yazılmaz, kaydedilen değeri sunucu hesaplar
  const prevFields = data?.previous?.fields || null
  // Raporun o aya özel hedefleri (4b) hedef karşılaştırmasını etkiler
  const draftTargets = draft?.targets
  const calc = useMemo(() => calcResults(tpl, draft?.fields, prevFields, draftTargets), [tpl, draft?.fields, prevFields, draftTargets])
  const shownFields = useMemo(() => applyCalc(tpl, draft?.fields, prevFields, calc, draftTargets),
    [tpl, draft?.fields, prevFields, calc, draftTargets])
  const shownValues = useMemo(() => (draft && shownFields !== draft.fields ? { ...draft, fields: shownFields } : draft),
    [draft, shownFields])

  // Katlanır bölümler (2026-10-16): açılanlar bu rapor için hatırlanır; hatalı alan sayısı yalnız hata KÜMESİ değişince
  // yeni Map olur (her tuşta değil — görsel bölümleri yazarken yeniden çizilmesin)
  // Hiçbir şey çizmeyen bölüm (grafiğe dökülecek alanı olmayan trend) "tümü açık" hesabına girmez
  const draftFields = draft?.fields
  // Özet kartına gömülü bölüm (2026-10-17) kendi kartı olmadığı için listede yok — özetin kartıyla açılır / kapanır
  const sectionIds = useMemo(() => sections
    .filter((s) => !hostOf(tpl, s))
    .filter((s) => s.kind !== 'trend'
      || trendRefs(tpl, s).some((r) => r.status === 'ok' && !fieldExempt(r.field, draftFields)))
    .map((s) => s.id), [sections, tpl, draftFields])
  const openState = useSectionOpenState(sectionOpenKey('r', reportId), sectionIds)
  const countsKey = useMemo(() => [...errorCountsBySection(countedErrors, (k) => reportErrorSection(k, tpl))]
    .map(([id, n]) => `${id}:${n}`).join('|'), [countedErrors, tpl])
  const invalidCounts = useMemo(() => new Map(countsKey ? countsKey.split('|').map((p) => {
    const i = p.lastIndexOf(':')
    return [p.slice(0, i), Number(p.slice(i + 1))]
  }) : []), [countsKey])
  const sectionCtx = useSectionOpenValue(openState, invalidCounts)
  // Hata YENİ gelen bölüm kendiliğinden açılır (sunucunun alan hatası, kapalı bölümdeki bağlı alan)
  const errSections = useRef(new Set())
  const { openMany } = openState
  useEffect(() => {
    const now = new Set(invalidCounts.keys())
    const fresh = [...now].filter((id) => !errSections.current.has(id))
    errSections.current = now
    if (fresh.length) openMany(fresh)
  }, [invalidCounts, openMany])
  /** Kaydı / PDF'i durduran hatalı alanların bölümlerini açar (kullanıcı kapatmış olabilir); zorunlu anomali alanları da görünür. */
  function revealErrors() {
    setShowRequired(true)
    const ids = new Set(invalidCounts.keys())
    for (const k of Object.keys(issueChecksRef.current)) {
      const sid = reportErrorSection(k, tpl)
      if (sid) ids.add(sid)
    }
    if (ids.size) openMany([...ids])
  }

  // Konu görseli yüklemeden önce (2026-10-18): konu sunucuda kayıtlı değilse rapor kaydedilir — kaydedilmemiş konunun görseli
  // sunucuda sahipsiz kalırdı (sunucu da reddeder). Geri çağrı SABİT (kartlar yazarken yeniden kurulmasın). Sonuç `{ ok, reason }`
  // (inceleme 2026-10-18): ESKİ sürümlü yedek geri yüklendiyse (autoHold) kayıt YAPILMAZ — sessizce başkasının daha yeni kaydını
  // ezerdi ve autoHold'u kaldırırdı ('held'); çakışma varsa da yapılmaz ('conflict'); geçersiz alan 'invalid'; kayıt başarısızsa
  // sunucunun / ağın nedeni
  const conflictRef = useRef(conflict)
  conflictRef.current = conflict
  const autoHoldRef = useRef(autoHold)
  autoHoldRef.current = autoHold
  const beforeIssueUploadRef = useRef(null)
  beforeIssueUploadRef.current = async (sid, iid) => {
    if (readOnly) return { ok: false, reason: 'server' }
    await settleSave()
    const inBase = () => {
      try { return (JSON.parse(baseRef.current.json || '{}').issues?.[sid] || []).some((i) => i?.id === iid) } catch { return false }
    }
    if (inBase()) return { ok: true }
    if (conflictRef.current) return { ok: false, reason: 'conflict' }
    if (autoHoldRef.current) return { ok: false, reason: 'held' }
    if (blockingNow()) { revealErrors(); return { ok: false, reason: 'invalid' } }
    const ok = await save({ quiet: true })
    if (ok && inBase()) return { ok: true }
    return { ok: false, reason: lastSaveFailure.current || 'server' }
  }
  const beforeIssueUpload = useCallback(async (sid, iid) => (await beforeIssueUploadRef.current?.(sid, iid)) ?? { ok: false, reason: 'server' }, [])

  // Otomatik kayıt: 30 sn'de bir ve sekme gizlenirken; değişiklik yoksa, salt okunurda, çakışmada, başka bir işlem ya da
  // kayıt / yükleme sürerken atlanır
  const autoRef = useRef(null)
  autoRef.current = () => {
    if (readOnly || conflict || autoHold || busy != null || savePromise.current || loadInFlight.current || !dirty) return
    if (Date.now() < autoBackoff.current.until) return
    save({ quiet: true, auto: true })
  }
  useEffect(() => {
    if (readOnly) return undefined
    const h = setInterval(() => autoRef.current?.(), AUTOSAVE_MS)
    const onVisibility = () => { if (document.visibilityState === 'hidden') autoRef.current?.() }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      clearInterval(h)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [readOnly])

  const back = (
    <Button type="button" variant="ghost" size="sm" data-slot="te-back"
      className="h-auto min-h-8 w-fit max-w-full justify-start whitespace-normal py-1.5 text-left pointer-coarse:min-h-10"
      onClick={() => leave(() => onBack(channel?.id))}>
      <ArrowLeft aria-hidden="true" />
      <span className="[overflow-wrap:anywhere]">{backLabel || (channel?.name ? t('te.backToChannel', channel.name) : t('te.backToChannels'))}</span>
    </Button>
  )

  if (state.loading && !data) {
    return (
      <div className="flex flex-col gap-4" role="status" aria-live="polite">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-28 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-72 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.error && !data) {
    const gone = state.status === 404 || state.status === 403
    return (
      <div className="flex flex-col gap-4">
        <Button type="button" variant="ghost" size="sm" className="w-fit pointer-coarse:min-h-10" onClick={() => onBack(null)}>
          <ArrowLeft aria-hidden="true" /> {backLabel || t('te.backToChannels')}
        </Button>
        <StatusBlock tone={gone ? 'neutral' : 'danger'} icon={CircleAlert} title={gone ? t('te.reportGoneTitle') : t('te.loadErrorTitle')}
          description={state.error}
          actions={gone ? null : <Button type="button" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>} />
      </div>
    )
  }
  if (!data || !draft) return null

  const upd = data.template_update
  // Şablon sürümleri (D19): yalnız pozitif tam sayı gösterilir (null / 0 / bozuk → çizilmez)
  const posInt = (v) => (Number.isInteger(Number(v)) && Number(v) > 0 && v !== null && v !== '' ? Number(v) : null)
  const reportTplVersion = posInt(report.template_version)
  const channelTplVersion = posInt(channel?.template_version)
  return (
    <div className={cn('flex flex-col gap-4', actionsHeight > 0 ? '' : 'pb-24')}
      style={actionsHeight > 0 ? { paddingBottom: actionsHeight + 16 } : undefined} data-slot="te-report" data-report={reportId}>
      {back}
      <header className="flex flex-col gap-2">
        <div data-slot="te-copy-row" className="text-xl font-bold leading-tight [overflow-wrap:anywhere] sm:text-2xl">
          <h2 ref={titleRef} tabIndex={-1} className="inline text-xl font-bold leading-tight outline-none sm:text-2xl"
            data-slot="te-report-title">
            {resolve(tpl.title)}
          </h2>
          <CopyTextButton text={resolve(tpl.title)} />
        </div>
        {/* Ay geçişi + değişiklik geçmişi (2026-10-14, Faz B): aynı kanalın önceki / sonraki EN YAKIN raporu; geçiş
            kaydedilmemiş değişiklikte onay ister (leave) */}
        <div className="flex flex-wrap items-center justify-between gap-2" data-slot="te-report-nav">
          {onOpenReport && (data.neighbors?.prev || data.neighbors?.next) ? (
            <nav aria-label={t('te.nav.label')} className="flex flex-wrap items-center gap-2">
              {data.neighbors?.prev && (
                <Button type="button" variant="outline" size="sm" data-slot="te-nav-prev" disabled={busy != null}
                  className="h-auto min-h-8 whitespace-normal py-1.5 pointer-coarse:min-h-10"
                  aria-label={t('te.nav.prevAria', periodLabel(data.neighbors.prev, t))}
                  onClick={() => leave(() => onOpenReport(data.neighbors.prev.id, channel?.id))}>
                  <ChevronLeft aria-hidden="true" /> {periodLabel(data.neighbors.prev, t)}
                </Button>
              )}
              {data.neighbors?.next && (
                <Button type="button" variant="outline" size="sm" data-slot="te-nav-next" disabled={busy != null}
                  className="h-auto min-h-8 whitespace-normal py-1.5 pointer-coarse:min-h-10"
                  aria-label={t('te.nav.nextAria', periodLabel(data.neighbors.next, t))}
                  onClick={() => leave(() => onOpenReport(data.neighbors.next.id, channel?.id))}>
                  {periodLabel(data.neighbors.next, t)} <ChevronRight aria-hidden="true" />
                </Button>
              )}
            </nav>
          ) : <span />}
          <div className="flex flex-wrap items-center gap-2">
            {sectionIds.length > 0 && (
              <ExpandAllButton allOpen={allSectionsOpen(openState.state, sectionIds)} onToggle={openState.setAll} />
            )}
            <Button ref={historyBtnRef} type="button" variant="ghost" size="sm" className="pointer-coarse:min-h-10" data-slot="te-history-open"
              onClick={() => setHistoryOpen(true)}>
              <History aria-hidden="true" /> {t('te.hist.open')}
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <StatusBadge status={report.status} sendState={report.last_send_status} />
          {/* Yalnız paylaşımla görülen raporda rozet tıklanmaz: üye penceresi sahip takımın kişilerini gösterirdi */}
          <TeamBadge teamId={channel?.team_id} teamName={channel?.team_name} wrap
            static={!!(data?.shared_access || channel?.shared_access)} />
          {/* Raporun kopyaladığı kanal şablonu sürümü (D19); bilinmiyorsa çip yok ("v0" / "vnull" yazılmaz) */}
          {reportTplVersion != null && (
            <Badge variant="outline" className="rounded-md overflow-visible" data-slot="te-report-tpl-version" data-version={reportTplVersion}>
              {t('te.tplVersionChip', reportTplVersion)}
            </Badge>
          )}
          {report.created_at && (
            <span className="[overflow-wrap:anywhere]" data-slot="te-created">· {t('te.createdLine', fmtDateTime(report.created_at, uiLang), report.created_by_name || '—')}</span>
          )}
          <span>· {t('te.lastSaved', fmtDateTime(report.updated_at, uiLang), report.updated_by_name || '—')}</span>
          {report.last_sent_at && (
            <span className="[overflow-wrap:anywhere]">· {t('te.lastSent', fmtDateTime(report.last_sent_at, uiLang), report.last_sent_to || '—')}</span>
          )}
        </div>
        {/* Alıcı özeti (R13 / B6): takım adresi sayısı, onay akışında yönetici alıcı sayısı + onay durumu — yalnız sayılar */}
        <RecipientsSummary teamEmailCount={data.team_email_count} teamEmailDropped={data.team_email_dropped} approvalDetail={approvalDetail}
          approval={report.approval} />
      </header>

      <ApprovalCard reportId={reportId} detail={approvalDetail} approval={report.approval} uiLang={uiLang} busy={busy}
        onBusy={setBusy} onRequest={requestApproval} onChanged={() => load({ keepDraft: true })}
        sentAt={report.last_sent_at} sentTo={report.last_sent_to} reportStatus={report.status} />

      {state.error && (
        <AlertBanner tone="danger" title={t('te.reloadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => load({ keepDraft: true })}>{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}
      {readOnly && <AlertBanner tone="info" title={t('te.readOnlyTitle')}>{t('te.readOnlyBody')}</AlertBanner>}
      {conflict && (
        <AlertBanner tone="warning" title={t('te.conflictTitle')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => {
            // Değişiklik kaybolmasın: en son hâli yüklemeden önce taslak yedeğe yazılır; yükleme sonrası "geri yükle" önerilir.
            // Bu yedek bilerek bırakılır: taslak temizlenince silinmesin
            clearTimeout(backupTimer.current)
            writeBackup(reportId, { version: base.version, values: draft, at: Date.now() })
            wroteBackup.current = false
            load()
          }}>{t('te.loadLatest')}</Button>}>
          {t('te.conflictBody')}
        </AlertBanner>
      )}
      {backup && !readOnly && (
        <AlertBanner tone="info" icon={History} title={t('te.backupTitle')}
          actions={(
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" onClick={() => {
                // Eski sürümün yedeği: otomatik kayıt durur, karşılaştırıp Kaydet ile kullanıcı karar verir
                if (backup.version !== base.version) setAutoHold(true)
                replaceDraft(normalizeValues(backup.values))
                setBackup(null)
              }}>{t('te.backupRestore')}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => { dropBackup(reportId); setBackup(null) }}>{t('te.backupDiscard')}</Button>
            </div>
          )}>
          {backup.version === base.version ? t('te.backupBody') : t('te.backupBodyStale')}
        </AlertBanner>
      )}
      {!readOnly && canSend && data.team_email_set === false && (
        <AlertBanner tone="warning" icon={MailWarning} title={t('te.noTeamEmailTitle')}>{t('te.noTeamEmailBody')}</AlertBanner>
      )}
      {report.status === 'changed' && !readOnly && canSend && (
        <AlertBanner tone="info" title={t('te.changedTitle')}>{t('te.changedBody')}</AlertBanner>
      )}
      {upd?.differs && !readOnly && (
        <AlertBanner tone="info" icon={LayoutTemplate} title={t('te.templateChangedTitle')}
          actions={(
            <div className="flex flex-wrap items-center gap-2">
              {/* Kanal şablonu daha yeni sürümdeyse sürüm numarası (D19) — raporunki bilinmiyorsa da kanalınki söylenir */}
              {channelTplVersion != null && (reportTplVersion == null || channelTplVersion > reportTplVersion) && (
                <Badge variant="outline" className="rounded-md overflow-visible" data-slot="te-channel-tpl-version" data-version={channelTplVersion}>
                  {t('te.tplChannelVersion', channelTplVersion)}
                </Badge>
              )}
              <Button type="button" size="sm" variant="outline" disabled={busy != null} onClick={applyTemplate}>{t('te.applyTemplate')}</Button>
            </div>
          )}>
          {t('te.templateChangedBody')}
        </AlertBanner>
      )}

      <SectionOpenContext.Provider value={sectionCtx}>
      <PendingInputContext.Provider value={setFieldPending}>
      {sections.map((s) => {
        const key = `${s.id}:${gen}`
        // Özet kartına gömülü bölüm o kartın içinde çizilir (aşağıda SummaryBlock)
        if (hostOf(tpl, s)) return null
        if (s.kind === 'metrics') {
          return <MetricsBlock key={key} section={s} values={draft} resolve={resolve} uiLang={uiLang} previous={data.previous}
            readOnly={readOnly} errors={fieldErrors} onField={setField} onFieldError={setFieldError} templateFields={allFields(tpl)}
            calc={calc} onTarget={setTarget} />
        }
        if (s.kind === 'issues') {
          return <IssuesBlock key={key} section={s} template={tpl} list={draft.issues?.[s.id] || NO_ISSUES} units={draft.units}
            shownFields={shownFields} resolve={resolve} uiLang={uiLang} readOnly={readOnly} errors={fieldErrors}
            onIssuesUpdate={updateIssues} onFieldError={setFieldError} onUnit={setUnit} reportId={reportId} images={images}
            onImagesChange={onImagesChange} period={issuePeriod}
            beforeIssueUpload={beforeIssueUpload} />
        }
        if (s.kind === 'summary') {
          return <SummaryBlock key={key} section={s} template={tpl} values={shownValues} channelName={channel?.name} year={report?.year}
            month={report?.month} resolve={resolve} readOnly={readOnly} errors={fieldErrors} onText={setText} onClearText={clearText}
            embedded={embeddedSections(tpl, s.id)} fieldValues={draft} uiLang={uiLang} previous={data.previous}
            onField={setField} onFieldError={setFieldError} templateFields={allFields(tpl)} calc={calc} onTarget={setTarget} />
        }
        if (s.kind === 'table') {
          return <TableBlock key={key} section={s} values={draft} resolve={resolve} uiLang={uiLang} tplLang={tpl?.lang} readOnly={readOnly}
            errors={fieldErrors} onCell={setCell} onFieldError={setFieldError} />
        }
        if (s.kind === 'trend') {
          return <TrendBlock key={key} section={s} template={tpl} trend={data.trend} draftFields={shownFields} resolve={resolve}
            tplLang={tpl?.lang} />
        }
        if (s.kind === 'images') {
          return <ImagesBlock key={s.id} section={s} reportId={reportId} images={images} resolve={resolve} uiLang={uiLang}
            showTotals={s.id === firstImagesId} readOnly={readOnly} onImagesChange={onImagesChange} />
        }
        return <TextBlock key={key} section={s} values={draft} resolve={resolve} resolveText={resolveText} readOnly={readOnly}
          errors={fieldErrors} onText={setText} />
      })}
      </PendingInputContext.Provider>
      </SectionOpenContext.Provider>

      {mailPreviewOpen && (
        <MailPreviewDialog reportId={reportId} reportVersion={report.version ?? null} contentRev={previewRev} open
          onClose={() => setMailPreviewOpen(false)} uiLang={uiLang}
          onSend={!readOnly && canSend && busy == null && report.status !== 'sending'
            && (approvalRequired ? !!approvalDetail?.can_send_executive : data.team_email_set !== false) ? send : null}
          sendLabel={approvalRequired ? t('te.appr.sendExec') : (report.last_sent_at ? t('te.resend') : t('te.send'))} />
      )}
      {/* Gönderim penceresi (R18 / C13): eski onay sorusunun metni + bu gönderime ek Bilgi (CC); yalnız gönderebilen açar */}
      <SendDialog open={sendDialog != null} busy={busy === 'send'} serverError={sendDialog?.error || null}
        failure={sendDialog?.failure || null} onRecheck={busy == null ? checkUnsureSend : null}
        initialCc={sendDialog?.initialCc || ''}
        title={approvalRequired ? t('te.appr.sendExec') : t('te.sendTitle')}
        message={approvalRequired
          ? t('te.appr.sendExecBody', resolve(tpl?.title), (approvalDetail?.executive_emails || []).join(', ') || '—')
          : t('te.sendBody', channel?.team_name || '—', resolve(tpl?.title))}
        onConfirm={confirmSend} onCancel={() => { if (busy !== 'send') setSendDialog(null) }}
        onEdit={(v) => {
          if (typeof v === 'string') ccCarry.current = v
          if (sendDialog?.error) setSendDialog((d) => (d ? { ...d, error: null } : d))
        }} />
      <ReportHistorySheet reportId={reportId} open={historyOpen} onClose={() => setHistoryOpen(false)} uiLang={uiLang}
        returnFocusRef={historyBtnRef} />

      <div ref={actionsRef}
        className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-t-xl border-t bg-background/95 px-1 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur"
        data-slot="te-report-actions">
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]" aria-live="polite" data-slot="te-save-status"
          data-auto={autoInfo ? (autoInfo.ok ? 'ok' : 'failed') : undefined}>
          {readOnly ? t('te.readOnlyShort') : autoSaving ? t('te.autoSaving') : (
            <>
              <span>{unsaved ? t('te.unsaved') : t('te.allSaved')}</span>
              {/* Otomatik kayıt notu ayrı parça: durum metni aynı kalır, not onu açıklar; vaat edilmeyen kayıt yazılmaz */}
              {unsaved ? (
                <span data-slot="te-save-auto"> · {conflict ? t('te.autoPausedConflict')
                  : autoHold ? t('te.autoHeldRestore')
                    : autoInfo?.ok === false && autoInfo.blocked && autoInfo.message ? t('te.autoBlockedReason', autoInfo.message)
                  : autoInfo?.ok === false && autoInfo.blocked ? t('te.autoBlocked')
                    : autoInfo?.ok === false ? t('te.autoFailed', clockTime(autoInfo.at, uiLang))
                      : issueProblemNames.length ? t(otherInvalid ? 'te.unsavedInvalidListMore' : 'te.unsavedInvalidList', problemList)
                        : hasClientErrors || hasPending ? t('te.unsavedInvalid') : t('te.unsavedAuto')}</span>
              ) : autoInfo?.ok ? (
                <span data-slot="te-save-auto"> · {t('te.autoSaved', clockTime(autoInfo.at, uiLang))}</span>
              ) : null}
            </>
          )}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {/* İkincil işlemler menüde: telefonda çubuk tek-iki satırda kalsın, içerik örtülmesin (2026-10-13 e2e) */}
          <KebabMenu variant="labeled" label={t('te.moreActions')} rowLabel={resolve(tpl.title)} items={[
            { label: t('te.openTemplate'), icon: <LayoutTemplate aria-hidden="true" />, onClick: () => leave(() => onOpenTemplate(channel?.id)), hidden: readOnly },
            { label: t('te.discard'), icon: <Undo2 aria-hidden="true" />, hidden: readOnly || !dirty || busy != null || autoSaving,
              onClick: () => { clearTimeout(backupTimer.current); replaceDraft(normalizeValues(JSON.parse(base.json))); dropBackup(reportId); setAutoHold(false) } },
            { label: t('te.delete'), icon: <Trash2 aria-hidden="true" />, danger: true, hidden: !data.can_delete || busy != null, onClick: remove },
          ]} />
          <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy != null} onClick={downloadPdf}
            data-slot="te-pdf">
            {busy === 'pdf' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Download aria-hidden="true" />}
            {t('te.downloadPdf')}
          </Button>
          <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy != null} data-slot="te-mail-preview-btn"
            onClick={async () => { if (!(await ensureSaved())) return; setMailPreviewOpen(true) }}>
            <Eye aria-hidden="true" /> {t('te.mail.preview')}
          </Button>
          {!readOnly && canSend && !approvalRequired && (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-send"
              disabled={busy != null || data.team_email_set === false || report.status === 'sending'} onClick={send}>
              {busy === 'send' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Send aria-hidden="true" />}
              {report.last_sent_at ? t('te.resend') : t('te.send')}
            </Button>
          )}
          {/* Onay akışlı kanal (S6): onaylı rapor yöneticilere; onaysız / değişmiş rapor onaya */}
          {!readOnly && approvalRequired && approvalDetail?.can_send_executive && (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-send-exec"
              disabled={busy != null || report.status === 'sending'} onClick={send}>
              {busy === 'send' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <Send aria-hidden="true" />}
              {t('te.appr.sendExec')}
            </Button>
          )}
          {!readOnly && approvalRequired && approvalDetail?.can_request && (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-approval-request-bar"
              disabled={busy != null} onClick={requestApproval}>
              {busy === 'approval' ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" /> : <ShieldCheck aria-hidden="true" />}
              {approvalDetail?.resend ? t('te.appr.resend') : t('te.appr.request')}
            </Button>
          )}
          {!readOnly && (
            <Button type="button" className="pointer-coarse:min-h-10" disabled={!dirty || busy != null} onClick={() => save()} data-slot="te-save">
              <Save aria-hidden="true" /> {busy === 'save' ? t('te.saving') : t('te.save')}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
