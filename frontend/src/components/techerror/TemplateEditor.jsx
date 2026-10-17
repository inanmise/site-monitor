import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, Braces, ChartColumn, CircleAlert, FileSearch, FileText, Gauge, History, Import, ListPlus, Plus, RefreshCw, RotateCcw,
  Save, ScrollText, Table2, Trash2, TrendingUp, TriangleAlert, Undo2,
} from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import {
  allFields, allSectionsOpen, allSlots, calcFieldsUsing, calcInput, calcRefIssues, canonicalTemplate, COLUMN_TYPES,
  BETTER, calcNumber, pruneRemovedTiles, tileSectionsUsing,
  embeddedSections, errorCountsBySection, FIELD_TYPES, hostOf, imageRows, insertToken, LIMITS, move, newId, newSection,
  pruneRemovedTrendRefs, resolvePlaceholders, resolveTextPlaceholders, sanitizeHosts, sanitizeSkipRefs, stableJson,
  summaryLineIssues, summaryLinePreview, summaryLinesUsing, summaryRefs, templateErrors, templateErrorSection, trendableFields,
  trendRefs, trendSectionsUsing, withoutHost, withoutSkip,
} from './techErrorModel.js'
import { storageOwner } from '../../utils/personalStorage.js'
import TemplateVersionsDialog from './TemplateVersions.jsx'
import TemplateImportDialog from './TemplateImport.jsx'
import { NumberInput } from './ReportSections.jsx'
import {
  CollapsibleCard, ExpandAllButton, SectionOpenContext, sectionOpenKey, useSectionOpenState, useSectionOpenValue,
} from './SectionCollapse.jsx'

const KIND_ICON = { metrics: Gauge, issues: TriangleAlert, text: FileText, images: ChartColumn, summary: ScrollText, table: Table2, trend: TrendingUp }
// "Bölüm ekle" menüsünün sırası (raporun doğal akışı: özet → göstergeler → konular → tablo → metin → grafikler)
const ADD_KINDS = ['summary', 'metrics', 'issues', 'table', 'text', 'images', 'trend']

/**
 * Özet cümlesine eklenecek alan başvurusu. Evet/hayır alanı cevaba göre iki ifadeden birini yazar: ifadeler
 * kullanıcıdan iki ayrı kutuyla alınır (ör. "artış" / "azalış"); `|` ve `}` ifadede kullanılamaz (sözdizimini bozar).
 */

/**
 * Şablon listelerindeki öğe kartı — alan, görsel yuvası, özet cümlesi, tablo sütunu / satırı (kullanıcı isteği 2026-10-16:
 * "1. görsel, 2. görsel gibi alanların birbirinden ayrı bölümler olduğu keskin hatlarla belli olsun"). Koyu çerçeve +
 * gölge + kartın kenarına yaslanan başlık şeridi (numara ve taşıma / silme düğmeleri); öğeler arası boşluk 12 px.
 */
// 2026-10-17: "açılan bölümün çevresindeki çizgi" şikâyetinin kaynağı düzenleme kilidi fieldset'lerinin tarayıcı
// çerçevesiydi (artık `border-0`); öge kartları kullanıcının 2026-10-16 kararıyla belirgin kalır. Şeridin iç yarıçapı dış
// yarıçaptan kenar kalınlığı kadar küçük → köşeler örtüşür.
const TPL_ITEM = 'rounded-lg border border-foreground/25 bg-card p-3 shadow-sm'
const TPL_ITEM_HEAD = '-mx-3 -mt-3 mb-3 flex items-center justify-between gap-2 rounded-t-[calc(var(--radius-lg)-1px)] border-b border-foreground/20 bg-muted px-3 py-1.5'
const TPL_ITEM_TITLE = 'text-sm font-semibold text-foreground'

function fieldToken(f, yes = null, no = null) {
  if (f.type === 'yesno' && yes != null) return `{f:${f.id}?${cleanChoice(yes)}|${cleanChoice(no)}}`
  return `{f:${f.id}}`
}

function cleanChoice(s) {
  return String(s ?? '').replace(/[|}{]/g, ' ').replace(/ {2,}/g, ' ').trim()
}

/** Cümleden bir başvuruyu (şablondan silinmiş alanınkini) çıkarır; artan çift boşluk teke iner. */
function removeRef(line, ref) {
  return `${line.slice(0, ref.start)}${line.slice(ref.end)}`.replace(/ {2,}/g, ' ').trim()
}

// Kişisel kayıt: çıkışta ve kullanıcı değişince silinir (utils/personalStorage PERSONAL_PREFIXES)
export const TPL_BACKUP_PREFIX = 'sm.te.tpl.'
const tplJson = (tpl) => stableJson(canonicalTemplate(tpl))
/** Örnek PDF'in nesne adresi bu kadar sonra bırakılır (R19 inceleme: açık sekme yeniden yükleyebilsin / indirebilsin). */
export const PREVIEW_URL_TTL_MS = 10 * 60_000

function readTplBackup(id) {
  try {
    const raw = localStorage.getItem(TPL_BACKUP_PREFIX + id)
    if (!raw) return null
    const v = JSON.parse(raw)
    return v && typeof v === 'object' && v.template && Array.isArray(v.template.sections) ? v : null
  } catch {
    return null
  }
}

function writeTplBackup(id, payload) {
  try { localStorage.setItem(TPL_BACKUP_PREFIX + id, JSON.stringify(payload)) } catch { /* depolama yok / dolu */ }
}

function dropTplBackup(id) {
  try { localStorage.removeItem(TPL_BACKUP_PREFIX + id) } catch { /* depolama yok */ }
}

/**
 * KANAL ŞABLONU DÜZENLEYİCİSİ — yalnız bu kanalın şablonu. Bölümler sıralıdır (yukarı / aşağı düğmeleri — dokunmatikte
 * de çalışır); gösterge alanı, anomali etiketleri, metin ve grafik yuvaları (yarım = bir kolon, tam = iki kolon). Kayıt
 * sürüm denetimlidir: başkası arada kaydettiyse 409 → uyarı + "en son hâli yükle"; hiçbir değişiklik sessizce ezilmez.
 * Alan / yuva kimlikleri kalıcıdır — ad değişse de geçmiş raporların değerleri kaybolmaz; yeni kimlik hiçbir zaman
 * silinmiş bir alanınkini yeniden kullanmaz. Kaydedilmemiş düzenleme tarayıcıda yedeklenir (uygulamada başka sekmeye
 * geçmek ya da geri tuşu emeği silmez; dönüşte "geri yükle" önerilir).
 */
/**
 * Şablon düzenleyicinin İSTEMCİ hataları (inceleme 2026-10-17): sayı okunamayan bir hedef metni gibi taslağa hiç
 * yazılamayan girişler — `templateErrors` taslaktan türediği için onları göremez; kayıt kapısı ikisini birlikte okur.
 * Değer `(anahtar, i18n anahtarı | null)` bildiren bir işlevdir.
 */
export const TplClientErrorContext = createContext(null)

export default function TemplateEditor({ channelId, onDirtyChange, onSaved }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null })
  const [channel, setChannel] = useState(null)
  const [draft, setDraft] = useState(null)
  // Taslak DIŞARIDAN değiştiğinde (yükleme, vazgeç, kayıt yanıtı, varsayılana dönüş, yedekten geri yükleme) artar:
  // bölüm kartları yeniden kurulur — cümle kimlikleri, hatırlanan imleç ve açık evet/hayır paneli eski şablondan
  // yenisine taşınmaz (inceleme 2026-10-14)
  const [gen, setGen] = useState(0)
  const replaceDraft = (tpl) => { setDraft(tpl); setGen((g) => g + 1) }
  const [versionsOpen, setVersionsOpen] = useState(false)
  const versionsBtnRef = useRef(null)
  const [importOpen, setImportOpen] = useState(false)
  const importBtnRef = useRef(null)
  const [base, setBase] = useState({ json: null, version: null })
  const [serverError, setServerError] = useState(null)
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  // Örnek PDF (R19) hazırlanıyor — düğme kilitli, adı "hazırlanıyor"
  const [previewing, setPreviewing] = useState(false)
  // Açılan önizleme PDF'lerinin nesne adresleri → bırakma zamanlayıcısı; düzenleyici kapanınca hepsi bırakılır (sızıntı yok)
  const previewUrls = useRef(new Map())
  useEffect(() => {
    const urls = previewUrls.current
    return () => {
      for (const [url, h] of urls) {
        clearTimeout(h)
        try { URL.revokeObjectURL(url) } catch { /* bırakılmış */ }
      }
      urls.clear()
    }
  }, [])
  const [showErrors, setShowErrors] = useState(false)
  const [backup, setBackup] = useState(null)
  const seq = useRef(0)
  // Dil değişimi düzenlemeyi yeniden yüklemesin: yükleyici t'ye bağlı değil (yalnız hata metni için okunur)
  const tRef = useRef(t)
  tRef.current = t
  const backupTimer = useRef(null)
  const wroteBackup = useRef(false)
  const skipFlush = useRef(false)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.techErrors.template(channelId)
      if (my !== seq.current) return
      if (res?.success && res.data?.template) {
        const json = tplJson(res.data.template)
        setChannel(res.data.channel)
        setDraft(res.data.template)
        setGen((g) => g + 1)
        setBase({ json, version: res.data.version })
        const b = res.data.channel?.can_edit ? readTplBackup(channelId) : null
        const same = b && tplJson(b.template) === json
        setBackup(b && !same ? b : null)
        if (same) dropTplBackup(channelId)
        setConflict(false)
        setServerError(null)
        setShowErrors(false)
        skipFlush.current = false
        setState({ loading: false, error: null, status: null })
      } else setState({ loading: false, error: res?.error || tRef.current('te.loadError'), status: res?.status ?? null })
    } catch (e) {
      if (my !== seq.current) return
      setState({ loading: false, error: e?.message || tRef.current('te.loadError'), status: 0 })
    }
  }, [channelId])
  useEffect(() => { load() }, [load])

  const dirty = draft != null && base.json != null && tplJson(draft) !== base.json
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange])
  useEffect(() => {
    if (!dirty) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty])

  const modelErrors = useMemo(() => (draft ? templateErrors(draft) : {}), [draft])
  // Taslağa yazılamayan geçersiz girişler (ör. "Hedef (%)" alanında sayı olmayan metin) — kayıt kapısı bunları da okur
  const [clientErrors, setClientErrors] = useState({})
  const setClientError = useCallback((key, msg) => setClientErrors((e) => {
    if ((e[key] || null) === (msg || null)) return e
    const out = { ...e }
    if (msg) out[key] = msg
    else delete out[key]
    return out
  }), [])
  const errors = useMemo(() => (Object.keys(clientErrors).length ? { ...modelErrors, ...clientErrors } : modelErrors),
    [modelErrors, clientErrors])
  const canEdit = !!channel?.can_edit
  // Alanlar salt okunurda ve kayıt sürerken kilitli (bölüm aç / kapat düğmeleri hariç — aşağıdaki fieldset notu)
  const locked = !canEdit || saving

  // Yazarken yerel yedek (1 sn sonra) — uygulama içinde başka yere geçmek düzenlemeyi silmesin. Taslak yeniden
  // temizlenince bu sayfanın yazdığı yedek silinir; sayfadan çıkarken son saniye de yedeklenir.
  useEffect(() => {
    if (!canEdit) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropTplBackup(channelId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
      wroteBackup.current = true
    }, 1000)
    backupTimer.current = h
    return () => clearTimeout(h)
  }, [draft, dirty, canEdit, channelId, base.version])

  const flushBackup = useRef(null)
  flushBackup.current = () => {
    // Vazgeçilmiş düzenleme ve çıkış (logout) sonrası yeniden yazılmaz
    if (skipFlush.current || !canEdit || !dirty || !draft || !storageOwner()) return
    writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
  }
  useEffect(() => () => flushBackup.current?.(), [])
  const shownErrors = showErrors ? errors : {}

  // KATLANIR BÖLÜMLER (2026-10-16, kullanıcı kararı): bölümler kapalı başlar; açılanlar bu kanalın şablonu için bu
  // tarayıcıda hatırlanır (`sm.te.open.t.<kanal>`). Kimlik listesi yalnız bölüm kümesi / sırası değişince yeni dizi
  const sectionIdsKey = JSON.stringify((draft?.sections || []).map((s) => s.id))
  const sectionIds = useMemo(() => JSON.parse(sectionIdsKey), [sectionIdsKey])
  const openState = useSectionOpenState(sectionOpenKey('t', channelId), sectionIds)
  const { openMany, setAll } = openState
  // Bölüm başına hatalı alan sayısı (yalnız kayıt denemesinden sonra — shownErrors ile aynı kural). Map yalnız sayılar
  // değişince yenilenir: her tuşta yeni nesne olsa bağlam tüketen her kart yeniden çizilirdi
  const invalidKey = useMemo(() => {
    if (!showErrors) return ''
    return JSON.stringify([...errorCountsBySection(errors, (k) => templateErrorSection(k, draft))])
  }, [showErrors, errors, draft])
  const invalidCounts = useMemo(() => new Map(invalidKey ? JSON.parse(invalidKey) : []), [invalidKey])
  const openValue = useSectionOpenValue(openState, invalidCounts)
  // YENİ hata alan bölüm kendiliğinden açılır (önceden hatalı olup kullanıcının kapattığı bölüm, hata sürdükçe kapalı
  // kalır — başlıkta sayı görünür; kaydet denemesi onu yine açar)
  const erroredRef = useRef(new Set())
  useEffect(() => {
    const now = new Set(invalidCounts.keys())
    const fresh = [...now].filter((id) => !erroredRef.current.has(id))
    erroredRef.current = now
    if (fresh.length) openMany(fresh)
  }, [invalidCounts, openMany])
  // Kullanıcının EKLEDİĞİ bölüm açık gelir; yükleme / vazgeç / kayıt yanıtı / varsayılana dönüş / yedekten ya da
  // sürümden yükleme (hepsi gen'i artırır) ile gelen bölümler kapalı kalır
  const seenSections = useRef({ gen: null, ids: [] })
  useEffect(() => {
    const prev = seenSections.current
    seenSections.current = { gen, ids: sectionIds }
    if (prev.gen !== gen) return
    const added = sectionIds.filter((id) => !prev.ids.includes(id))
    if (added.length) openMany(added)
  }, [gen, sectionIds, openMany])
  const allOpen = allSectionsOpen(openState.state, sectionIds)

  // Her değişiklikten sonra geçersiz kalan "Hayır cevabında sorma" bağları kalkar (soru silindi / türü değişti); silinen
  // alan otomatik trend seçimlerinden de düşer (silme onayında söylenir); silinen özetin kartında gösterilen bölümler
  // ayrı bölüm olur (2026-10-17, silme onayında söylenir)
  // Silinen alan özet kutucuklarından da düşer (2026-10-17, 4b; silme onayında söylenir)
  const update = (fn) => setDraft((d) => (d ? sanitizeSkipRefs(sanitizeHosts(pruneRemovedTiles(d, pruneRemovedTrendRefs(d, fn(d))), d)) : d))
  const updateSection = (id, fn) => update((d) => ({ ...d, sections: d.sections.map((s) => (s.id === id ? fn(s) : s)) }))

  async function save() {
    if (!draft || saving) return
    if (Object.keys(errors).length) {
      setShowErrors(true)
      // Hatalı alanı olan her bölüm açılır — kullanıcı arada kapatmış olsa da (başlık kartı zaten hep açık)
      openMany([...errorCountsBySection(errors, (k) => templateErrorSection(k, draft)).keys()])
      toast.error(t('te.tpl.fixErrors'))
      return
    }
    setSaving(true)
    setServerError(null)
    clearTimeout(backupTimer.current)
    try {
      const res = await api.techErrors.saveTemplate(channelId, draft, base.version)
      if (res?.success && res.data?.template) {
        replaceDraft(res.data.template)
        setBase({ json: tplJson(res.data.template), version: res.data.version })
        dropTplBackup(channelId)
        setBackup(null)
        setShowErrors(false)
        toast.success(t('te.tpl.saved'))
        onSaved?.()
      } else if (res?.code === 'CONCURRENT_MODIFICATION' || res?.status === 409) {
        setConflict(true)
      } else if (res?.code === 'VALIDATION_FAILED') {
        setServerError(res.error)
      } else {
        toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
      }
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setSaving(false)
    }
  }

  /**
   * ÖRNEK PDF (2026-10-17, R19 — kullanıcı onayı): düzenleyicideki GÜNCEL taslak (kaydedilmemiş değişiklikler dahil) sunucuda
   * örnek değerlerle PDF'e basılır ve yeni sekmede açılır; hiçbir şey kaydedilmez ya da gönderilmez. Salt okuyan da önizler.
   * Kayıtla aynı kapı: işaretli (hatalı) alan varsa istek gitmez, alanlar gösterilir — yalnız DÜZENLEYEN için: salt okuyan
   * düzeltemeyeceği alanlar yüzünden durdurulmaz (R19 inceleme), istek gider; sunucu katı doğrular, reddederse metni
   * gösterilir. Sekme TIKLAMA ANINDA açılır (PDF birkaç saniye sürer; istek bittikten sonra açılan pencereyi tarayıcı
   * engeller); tarayıcı yine engellerse PDF indirilir.
   */
  async function previewPdf() {
    if (!draft || previewing || saving) return
    if (canEdit && Object.keys(errors).length) {
      setShowErrors(true)
      openMany([...errorCountsBySection(errors, (k) => templateErrorSection(k, draft)).keys()])
      toast.error(t('te.tpl.previewFixErrors'))
      return
    }
    let win = null
    try { win = window.open('', '_blank') } catch { win = null }
    if (win) {
      try {
        win.opener = null
        win.document.title = t('te.tpl.previewPdfTab')
        win.document.body.textContent = t('te.tpl.previewPdfTab')
      } catch { /* sekmeye yazılamadı — boş kalır, PDF gelince dolar */ }
    }
    setPreviewing(true)
    try {
      const res = await api.techErrors.templatePreviewPdf(channelId, draft)
      if (!res?.success || !res.blob) {
        try { win?.close() } catch { /* kapanmış */ }
        toast.error(res?.error || t('te.tpl.previewPdfError'))
        return
      }
      const url = URL.createObjectURL(res.blob)
      let shown = false
      if (win && !win.closed) {
        try { win.location.href = url; shown = true } catch { shown = false }
      }
      if (!shown) {
        const a = document.createElement('a')
        a.href = url
        a.download = res.name || 'sablon-onizleme.pdf'
        a.click()
        toast.info(t('te.tpl.previewPdfDownloaded'))
      }
      // Adres 10 dk sonra bırakılır (hemen bırakılırsa sekme boş kalır; 60 sn sonra bırakılınca açık sekmede yeniden
      // yükleme / indirme bozuluyordu — R19 inceleme). Düzenleyici kapanırsa bekleyen adresler o anda bırakılır.
      const h = setTimeout(() => {
        previewUrls.current.delete(url)
        try { URL.revokeObjectURL(url) } catch { /* bırakılmış */ }
      }, PREVIEW_URL_TTL_MS)
      previewUrls.current.set(url, h)
    } catch (e) {
      try { win?.close() } catch { /* kapanmış */ }
      toast.error(e?.message || t('te.tpl.previewPdfError'))
    } finally {
      setPreviewing(false)
    }
  }

  /**
   * Sürüm geçmişinden bir sürümü TASLAĞA yükler (2026-10-14): sunucuya bir şey yazılmaz; kaydedince yeni sürüm olur.
   * Kaydedilmemiş değişiklik varsa onay sorulur. Pencerenin kapanması için true döner.
   */
  async function loadVersion(tpl, version) {
    return loadIntoDraft(tpl, {
      confirm: { title: t('te.ver.loadDirtyTitle'), message: t('te.ver.loadDirtyBody', version), confirmText: t('te.ver.loadConfirm') },
      loaded: t('te.ver.loaded', version),
    })
  }

  /**
   * Başka bir kanalın şablonunu TASLAĞA alır (2026-10-17, D16) — sürüm yüklemesiyle AYNI yol: taban sürüm bu kanalın şu
   * anki sürümü kalır (araya kayıt girerse kayıt 409 alır), şablon kendi dilini korur, kaydedilmemiş değişiklik varsa sorulur.
   */
  async function importTemplate(tpl, sourceName) {
    return loadIntoDraft(tpl, {
      confirm: { title: t('te.ver.loadDirtyTitle'), message: t('te.imp.dirtyBody', sourceName || '—'), confirmText: t('te.imp.confirm') },
      loaded: t('te.imp.loaded', sourceName || '—'),
    })
  }

  /**
   * Dışarıdan gelen şablonu taslağa koyar: sunucuya bir şey yazılmaz, `base` (sürüm) DEĞİŞMEZ → taslak kirli, Kaydet yeni
   * sürüm olur; nesil sayacı artar (bölüm kartları yeniden kurulur), açık bölüm kaydı olduğu gibi kalır. Pencere kapansın
   * diye true döner; kullanıcı vazgeçerse false.
   */
  async function loadIntoDraft(tpl, { confirm, loaded }) {
    if (!tpl || !Array.isArray(tpl.sections)) return false
    if (dirty) {
      const ok = await showConfirm({ title: confirm.title, message: confirm.message, variant: 'warning',
        confirmText: confirm.confirmText, cancelText: t('te.cancel') })
      if (!ok) return false
    }
    replaceDraft(tpl)
    setShowErrors(false)
    setServerError(null)
    toast.success(loaded)
    return true
  }

  async function resetToDefault() {
    const ok = await showConfirm({ title: t('te.tpl.resetTitle'), message: t('te.tpl.resetBody'), variant: 'warning',
      confirmText: t('te.tpl.resetConfirm'), cancelText: t('te.cancel') })
    if (!ok) return
    try {
      const res = await api.techErrors.defaultTemplate(draft?.lang || 'tr')
      if (res?.success && res.data?.template) replaceDraft(res.data.template)
      else toast.error(res?.error || t('te.loadError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.loadError'))
    }
  }

  if (state.loading && !draft) {
    return (
      <div role="status" aria-live="polite" className="flex flex-col gap-3">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-28 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-64 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.error && !draft) {
    return <StatusBlock tone="danger" icon={CircleAlert} title={t('te.loadErrorTitle')} description={state.error}
      actions={<Button type="button" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>} />
  }
  if (!draft) return null

  // Önizleme dönemi: önceki ay (Ocak'ta önceki yılın Aralık'ı) — yalnız yer tutucuları göstermek için
  const nowDate = new Date()
  const previewPeriod = nowDate.getMonth() === 0
    ? { year: nowDate.getFullYear() - 1, month: 12 }
    : { year: nowDate.getFullYear(), month: nowDate.getMonth() }
  const resolve = (p) => resolvePlaceholders(p, channel?.name, previewPeriod.year, previewPeriod.month, draft.lang)
  // Uzun metin (cümle önizlemesi) başlık sınırıyla (300) kesilmesin
  const resolveLong = (p) => resolveTextPlaceholders(p, channel?.name, previewPeriod.year, previewPeriod.month, draft.lang)
  const slotCount = allSlots(draft).length

  return (
    <div className="flex flex-col gap-4" data-slot="te-template-editor">
      {state.error && (
        <AlertBanner tone="danger" title={t('te.reloadFailed')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => load()}>{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}
      {!canEdit && <AlertBanner tone="info" title={t('te.tpl.readOnlyTitle')}>{t('te.tpl.readOnlyBody')}</AlertBanner>}
      {conflict && (
        <AlertBanner tone="warning" title={t('te.tpl.conflictTitle')}
          actions={<Button type="button" size="sm" variant="outline" onClick={() => {
            // Düzenleme kaybolmasın: en son hâli yüklemeden önce yedeğe yazılır; yükleme sonrası "geri yükle" önerilir.
            // Bu yedek bilerek bırakılır: taslak temizlenince silinmesin
            clearTimeout(backupTimer.current)
            writeTplBackup(channelId, { version: base.version, template: draft, at: Date.now() })
            wroteBackup.current = false
            load()
          }}>{t('te.loadLatest')}</Button>}>
          {t('te.tpl.conflictBody')}
        </AlertBanner>
      )}
      {backup && canEdit && (
        <AlertBanner tone="info" icon={History} title={t('te.tpl.backupTitle')}
          actions={(
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" onClick={() => { replaceDraft(backup.template); setBackup(null) }}>{t('te.backupRestore')}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => { dropTplBackup(channelId); setBackup(null) }}>{t('te.backupDiscard')}</Button>
            </div>
          )}>
          {backup.version === base.version ? t('te.tpl.backupBody') : t('te.tpl.backupBodyStale')}
        </AlertBanner>
      )}
      {serverError && <AlertBanner tone="danger" title={t('te.tpl.serverInvalid')}>{serverError}</AlertBanner>}
      {showErrors && errors.sections && <AlertBanner tone="danger">{t(errors.sections)}</AlertBanner>}

      {/* Sürüm geçmişi ve "tümünü aç / kapat" salt okunurda da çalışır: düğmeler düzenleme kilidinin (fieldset disabled) DIŞINDA */}
      <div className="flex flex-wrap items-center justify-between gap-2" data-slot="te-tpl-toolbar">
        <p className="m-0 text-sm text-muted-foreground">
          {t('te.ver.currentInfo', base.version ?? '—')}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {sectionIds.length > 0 && <ExpandAllButton allOpen={allOpen} onToggle={setAll} />}
          <Button ref={versionsBtnRef} type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-versions-open"
            onClick={() => setVersionsOpen(true)}>
            <History aria-hidden="true" /> {t('te.ver.open')}
          </Button>
          {/* Örnek PDF (R19): salt okuyan da önizler; kayıt sürerken ve PDF hazırlanırken kilitli */}
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-tpl-preview-pdf"
            disabled={previewing || saving} aria-busy={previewing || undefined} title={t('te.tpl.previewPdfHint')} onClick={previewPdf}>
            {previewing
              ? <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />
              : <FileSearch aria-hidden="true" />}
            {previewing ? t('te.tpl.previewPdfBusy') : t('te.tpl.previewPdf')}
          </Button>
          {/* Başka kanaldan şablon al (2026-10-17): yalnız düzenleyebilen; kayıt sürerken kapalı */}
          {canEdit && (
            <Button ref={importBtnRef} type="button" variant="outline" size="sm" className="pointer-coarse:min-h-10" data-slot="te-tpl-import"
              disabled={saving} onClick={() => setImportOpen(true)}>
              <Import aria-hidden="true" /> {t('te.imp.open')}
            </Button>
          )}
        </div>
      </div>
      <TemplateVersionsDialog channelId={channelId} channelName={channel?.name} period={previewPeriod} open={versionsOpen}
        onClose={() => setVersionsOpen(false)} canEdit={canEdit && !saving} onLoad={loadVersion} returnFocusRef={versionsBtnRef} />
      {canEdit && (
        <TemplateImportDialog channelId={channelId} open={importOpen} onClose={() => setImportOpen(false)}
          onLoad={importTemplate} returnFocusRef={importBtnRef} />
      )}

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_20rem]">
        {/* Düzenleme kilidi (salt okuma / kayıt sürüyor) tek bir dış fieldset değil, parça parça: disabled fieldset
            içindeki HER düğmeyi kapatır — bölüm aç / kapat düğmeleri salt okunurda ve kayıt sırasında da çalışmalı
            (2026-10-16). Başlık kartı ve bölüm içerikleri kendi fieldset'inde, bölüm başlığındaki taşı / sil düğmeleri
            `disabled` ile, "Bölüm ekle" (yalnız yazabilene) kayıt sürerken kapalı — alanlar eskisi gibi kilitli. */}
        <div className="flex min-w-0 flex-col gap-4" data-slot="te-tpl-form">
          <fieldset disabled={locked} className="m-0 min-w-0 border-0 p-0">
            <Card className="gap-3" data-slot="te-tpl-head">
              <CardHeader>
                <h3 className="text-base font-semibold">{t('te.tpl.headTitle')}</h3>
                <p className="text-sm text-muted-foreground">{t('te.tpl.headHint')}</p>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <div className="flex flex-col gap-1.5" data-field="title">
                  <label htmlFor="te-tpl-title" className="text-sm font-semibold">{t('te.tpl.reportTitle')}</label>
                  <Input id="te-tpl-title" className="pointer-coarse:h-10" value={draft.title || ''} maxLength={LIMITS.title + 20}
                    aria-invalid={shownErrors.title ? true : undefined}
                    onChange={(e) => update((d) => ({ ...d, title: e.target.value }))} />
                  <PlaceholderButtons lang={draft.lang}
                    onInsert={(ph) => update((d) => ({ ...d, title: `${d.title || ''}${d.title?.endsWith(' ') || !d.title ? '' : ' '}${ph}` }))} />
                  {shownErrors.title && <p className="text-sm text-destructive" role="alert">{t(shownErrors.title)}</p>}
                  <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-title-preview">
                    {t('te.tpl.titlePreview')} <span className="font-medium text-foreground">{resolve(draft.title)}</span>
                  </p>
                </div>
                <div className="flex flex-col gap-1.5 sm:w-72">
                  <label htmlFor="te-tpl-lang" className="text-sm font-semibold">{t('te.tpl.lang')}</label>
                  <NativeSelect id="te-tpl-lang" className="pointer-coarse:h-10" value={draft.lang || 'tr'} onChange={(e) => update((d) => ({ ...d, lang: e.target.value }))}>
                    <NativeSelectOption value="tr">{t('te.tpl.langTr')}</NativeSelectOption>
                    <NativeSelectOption value="en">{t('te.tpl.langEn')}</NativeSelectOption>
                  </NativeSelect>
                  <p className="text-xs text-muted-foreground">{t('te.tpl.langHint')}</p>
                </div>
              </CardContent>
            </Card>
          </fieldset>

          <SectionOpenContext.Provider value={openValue}>
            {draft.sections.map((s, i) => (
              <TplClientErrorContext.Provider key={`${s.id}:${gen}`} value={setClientError}>
              <SectionCard section={s} index={i} count={draft.sections.length} errors={shownErrors} slotCount={slotCount}
                draft={draft} resolve={resolve} resolveLong={resolveLong} locked={locked}
                onChange={(fn) => updateSection(s.id, fn)}
                onMove={(delta) => update((d) => ({ ...d, sections: move(d.sections, i, delta) }))}
                onRemove={async () => {
                  // Bölümün alanları yönetici özetinde ya da otomatik trendde kullanılıyorsa söylenir (alan tek tek
                  // silinirken olduğu gibi; trend seçimi update içinde düşer)
                  const used = new Set()
                  for (const f of s.fields || []) for (const l of summaryLinesUsing(draft, f.id)) used.add(l)
                  const charted = (s.fields || []).filter((f) => trendSectionsUsing(draft, f.id).length).length
                  // Bölümün alanlarını formülünde kullanan BAŞKA bölümlerdeki hesaplanan alanlar (raporda elle girilir)
                  const own = new Set((s.fields || []).map((f) => f.id))
                  const calcs = new Set()
                  for (const f of s.fields || []) for (const c of calcFieldsUsing(draft, f.id)) if (!own.has(c.id)) calcs.add(c.label || c.id)
                  // Bu özetin kartında gösterilen bölümler ayrı bölüm olur (2026-10-17)
                  const hosted = s.kind === 'summary' ? embeddedSections(draft, s.id).length : 0
                  // Bölümün renkli kutucuk olan alanları (4b): kutucuklar da kalkar (pruneRemovedTiles)
                  const tiled = (s.fields || []).filter((f) => tileSectionsUsing(draft, f.id).length).length
                  const message = t('te.tpl.removeSectionBody', s.title || '')
                    + (hosted ? `\n\n${t('te.tpl.removeSectionHosted', hosted)}` : '')
                    + (used.size ? `\n\n${t('te.tpl.removeSectionUsed', used.size)}` : '')
                    + (charted ? `\n\n${t('te.tpl.removeSectionTrend', charted)}` : '')
                    + (calcs.size ? `\n\n${t('te.tpl.removeFieldCalcPart', [...calcs].join(', '))}` : '')
                    + (tiled ? `\n\n${t('te.tpl.removeSectionTiles', tiled)}` : '')
                  const ok = await showConfirm({ title: t('te.tpl.removeSectionTitle'), message,
                    variant: 'danger', confirmText: t('te.delete'), cancelText: t('te.cancel') })
                  if (ok) update((d) => ({ ...d, sections: d.sections.filter((x) => x.id !== s.id) }))
                }} />
              </TplClientErrorContext.Provider>
            ))}
          </SectionOpenContext.Provider>

          {canEdit && (
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button type="button" variant="outline" className="w-fit pointer-coarse:min-h-10"
                  disabled={saving || draft.sections.length >= LIMITS.sections} data-slot="te-tpl-add-section">
                  <ListPlus aria-hidden="true" /> {t('te.tpl.addSection')}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="z-(--z-menu)">
                {ADD_KINDS.map((kind) => {
                  const Icon = KIND_ICON[kind]
                  return (
                    <DropdownMenuItem key={kind} onSelect={() => update((d) => ({
                      ...d, sections: [...d.sections, newSection(kind, d, sectionLabels(kind, t))],
                    }))}>
                      <Icon aria-hidden="true" /> {t(`te.kind.${kind}`)}
                    </DropdownMenuItem>
                  )
                })}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>

        <aside className="flex min-w-0 flex-col gap-3 xl:sticky xl:top-4 xl:self-start" aria-label={t('te.tpl.previewTitle')}>
          <LayoutPreview draft={draft} resolve={resolve} />
        </aside>
      </div>

      {canEdit && (
        <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 rounded-t-xl border-t bg-background/95 px-1 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur"
          data-slot="te-tpl-actions">
          <p className="text-sm text-muted-foreground" aria-live="polite">{dirty ? t('te.unsaved') : t('te.allSaved')}</p>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="ghost" onClick={resetToDefault} disabled={saving} className="pointer-coarse:min-h-10">
              <RotateCcw aria-hidden="true" /> {t('te.tpl.reset')}
            </Button>
            <Button type="button" variant="outline" disabled={!dirty || saving} className="pointer-coarse:min-h-10"
              onClick={() => { skipFlush.current = true; clearTimeout(backupTimer.current); dropTplBackup(channelId); setBackup(null); load() }}>
              <Undo2 aria-hidden="true" /> {t('te.discard')}
            </Button>
            <Button type="button" onClick={save} disabled={!dirty || saving} data-slot="te-tpl-save" className="pointer-coarse:min-h-10">
              <Save aria-hidden="true" /> {saving ? t('te.saving') : t('te.tpl.save')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * Otomatik trend bölümü (2026-10-15, Faz C): grafiğe dökülecek sayı / yüzde alanları. Her alan raporda bir çubuk grafik
 * (son 12 ay, girilen rakamlardan). Şablonda olmayan ya da sayı / yüzde olmayan başvuru kaydı engellemez — burada uyarılır
 * ve kaldırılabilir; raporda o grafik çizilmez.
 */
function TrendEditor({ section, draft, resolve, onChange }) {
  const t = useT()
  const options = trendableFields(draft)
  const series = section.series || []
  const selected = new Set(series)
  const problems = trendRefs(draft, section).filter((r) => r.status !== 'ok')
  const full = series.length >= LIMITS.trendSeries
  const set = (id, on) => onChange((x) => {
    const cur = x.series || []
    return { ...x, series: on ? (cur.includes(id) ? cur : [...cur, id]) : cur.filter((v) => v !== id) }
  })
  return (
    <div className="flex flex-col gap-2" data-slot="te-tpl-trend">
      <p className="text-sm text-muted-foreground">{t('te.tpl.trendHint')}</p>
      {options.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-slot="te-tpl-trend-none">{t('te.tpl.trendNoFields')}</p>
      ) : (
        <ul className="m-0 grid list-none gap-1.5 p-0 sm:grid-cols-2">
          {options.map((f) => {
            const cid = `te-trend-${section.id}-${f.id}`
            const on = selected.has(f.id)
            return (
              <li key={f.id}>
                <div className="flex min-h-10 items-start gap-2 rounded-md border px-3 py-2">
                  <Checkbox id={cid} checked={on} disabled={!on && full} className="mt-0.5" data-slot="te-tpl-trend-field"
                    data-field-id={f.id} onCheckedChange={(v) => set(f.id, v === true)} />
                  <label htmlFor={cid} className="min-w-0 text-sm leading-snug [overflow-wrap:anywhere]">
                    {resolve(f.label)}
                    <span className="block text-xs text-muted-foreground">{resolve(f.section)} · {t(`te.type.${f.type}`)}</span>
                  </label>
                </div>
              </li>
            )
          })}
        </ul>
      )}
      {full && <p className="text-xs text-muted-foreground">{t('te.tpl.trendFull', LIMITS.trendSeries)}</p>}
      {problems.map((r) => (
        <div key={r.id} data-slot="te-tpl-trend-problem"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm">
          <span className="min-w-0 [overflow-wrap:anywhere]">
            {r.status === 'missing' ? t('te.tpl.trendMissing', r.id) : t('te.tpl.trendNotNumeric', resolve(r.field?.label || r.id))}
          </span>
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={() => set(r.id, false)}>
            {t('te.tpl.trendRemoveRef')}
          </Button>
        </div>
      ))}
    </div>
  )
}

function sectionLabels(kind, t) {
  return {
    title: t(`te.tpl.newTitle.${kind}`),
    field: t('te.tpl.newField'),
    slot: t('te.tpl.newSlot'),
    itemTitle: t('te.tpl.defaultItemTitle'),
    descriptionLabel: t('te.tpl.defaultDescriptionLabel'),
    customersLabel: t('te.tpl.defaultCustomersLabel'),
    errorsLabel: t('te.tpl.defaultErrorsLabel'),
    summaryLine: t('te.tpl.newSummaryLine'),
    column: t('te.tpl.newColumn'),
    row: t('te.tpl.newRow'),
  }
}

function PlaceholderButtons({ lang, onInsert }) {
  const t = useT()
  const list = lang === 'en' ? ['{channel}', '{month}', '{year}'] : ['{kanal}', '{ay}', '{yil}']
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
      <span>{t('te.tpl.insert')}</span>
      {list.map((ph) => (
        <Button key={ph} type="button" size="sm" variant="outline" className="h-7 px-2 font-mono text-xs pointer-coarse:h-10"
          onClick={() => onInsert(ph)}>{ph}</Button>
      ))}
    </div>
  )
}

/** `disabled`: kilit dışındaki yerde (bölüm başlığı — düzenleme fieldset'inin dışında) salt okuma / kayıt kilidi. */
function MoveButtons({ index, count, onMove, label, onRemove, removeLabel, disabled = false }) {
  const t = useT()
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" aria-label={t('te.moveUp', label)}
        disabled={disabled || index === 0} onClick={() => onMove(-1)} data-move="up"><ArrowUp aria-hidden="true" /></Button>
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" aria-label={t('te.moveDown', label)}
        disabled={disabled || index === count - 1} onClick={() => onMove(1)} data-move="down"><ArrowDown aria-hidden="true" /></Button>
      {onRemove && (
        <Button type="button" size="icon" variant="ghost" className="size-8 text-destructive pointer-coarse:size-10"
          aria-label={removeLabel} disabled={disabled} onClick={onRemove}><Trash2 aria-hidden="true" /></Button>
      )}
    </div>
  )
}

/**
 * Şablon bölümü — katlanır kart (2026-10-16). Başlık satırı: aç / kapat düğmesi (bölümün çözümlenmiş başlığı + tür
 * rozeti; başlık boşsa "Başlıksız bölüm") ve sağda taşı / sil — kapalı bölüm de sıralanır / silinir. Başlık kutusu,
 * önizlemesi, bölüm hatası ve türe özgü düzenleyiciler içerikte (kapalıyken de kurulu kalır). İçerik kendi fieldset'iyle
 * kilitlenir (`locked`); aç / kapat düğmesi kilidin dışında — salt okuyan da bölümleri açıp kapatabilir.
 */
function SectionCard({ section: s, index, count, errors, slotCount, draft, resolve, resolveLong, locked, onChange, onMove, onRemove }) {
  const t = useT()
  const Icon = KIND_ICON[s.kind] || FileText
  const err = errors[`section:${s.id}`]
  const titleId = `te-sec-${s.id}`
  const kindName = t(`te.kind.${s.kind}`)
  const heading = String(resolve(s.title || '') ?? '').trim() || t('te.tpl.untitledSection')
  const host = hostOf(draft, s)
  return (
    <CollapsibleCard sectionId={s.id} slot="te-tpl-section" title={heading} copyText=""
      toggleLabel={(
        <>
          {heading}{' '}
          <Badge variant="secondary" className="ms-1 rounded-md overflow-visible align-middle" data-slot="te-tpl-section-kind">
            <Icon aria-hidden="true" className="size-3" /> {kindName}
          </Badge>
          {host && (
            <Badge variant="outline" className="ms-1 rounded-md overflow-visible align-middle" data-slot="te-tpl-section-hosted">
              <ScrollText aria-hidden="true" className="size-3" /> {t('te.tpl.hostBadge')}
            </Badge>
          )}
        </>
      )}
      headerEnd={(
        <MoveButtons index={index} count={count} onMove={onMove} label={s.title || kindName} disabled={locked}
          onRemove={onRemove} removeLabel={t('te.tpl.removeSectionAria', s.title || kindName)} />
      )}
      cardProps={{ 'data-kind': s.kind, 'data-field': `section:${s.id}` }}>
      <fieldset disabled={locked} className="m-0 flex min-w-0 flex-col gap-3 border-0 p-0">
        <div className="flex flex-col gap-1.5">
          <label htmlFor={titleId} className="text-sm font-semibold">{t('te.tpl.sectionTitle')}</label>
          <Input id={titleId} className="pointer-coarse:h-10" value={s.title || ''} maxLength={LIMITS.label + 20}
            onChange={(e) => onChange((x) => ({ ...x, title: e.target.value }))} />
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{resolve(s.title)}</p>
        </div>
        {err && <p className="text-sm text-destructive" role="alert">{t(err)}</p>}
        {s.kind === 'metrics' && <HostSelect section={s} draft={draft} resolve={resolve} onChange={onChange} />}
        {s.kind === 'metrics' && <FieldsEditor section={s} errors={errors} draft={draft} onChange={onChange} />}
        {s.kind === 'images' && <SlotsEditor section={s} errors={errors} draft={draft} slotCount={slotCount} resolve={resolve} onChange={onChange} />}
        {s.kind === 'issues' && <IssueLabelsEditor section={s} onChange={onChange} />}
        {s.kind === 'summary' && <SummaryLinesEditor section={s} errors={errors} draft={draft} resolve={resolve} resolveLong={resolveLong} onChange={onChange} />}
        {s.kind === 'table' && <TableEditor section={s} errors={errors} draft={draft} resolve={resolve} onChange={onChange} />}
        {s.kind === 'trend' && <TrendEditor section={s} draft={draft} resolve={resolve} onChange={onChange} />}
        {s.kind === 'text' && (
          <div className="flex flex-col gap-1.5">
            <label htmlFor={`${titleId}-text`} className="text-sm font-semibold">{t('te.tpl.defaultText')}</label>
            <Textarea id={`${titleId}-text`} rows={3} value={s.text || ''} maxLength={LIMITS.sectionText}
              onChange={(e) => onChange((x) => ({ ...x, text: e.target.value }))} />
            <p className="text-xs text-muted-foreground">{t('te.tpl.defaultTextHint')}</p>
          </div>
        )}
      </fieldset>
    </CollapsibleCard>
  )
}

/**
 * "Gösterim yeri" (2026-10-17, kullanıcı kararı): gösterge bölümü ayrı bölüm olarak mı, yoksa bir yönetici özetinin
 * kartında mı gösterilir. Özet kartında gösterilen bölümün alanları raporda o kartın içinde doldurulur; PDF / e-postada
 * ayrı bölüm basılmaz, açıklaması ilgili özet cümlesinin altına yazılır. Şablonda özet bölümü yoksa seçici gizlenir
 * (bağ geçersiz kaldıysa düzeltilebilsin diye görünür kalır).
 */
function HostSelect({ section: s, draft, resolve, onChange }) {
  const t = useT()
  const summaries = (draft.sections || []).filter((x) => x.kind === 'summary')
  const value = typeof s.host === 'string' ? s.host : ''
  const dangling = value !== '' && !summaries.some((x) => x.id === value)
  if (summaries.length === 0 && !dangling) return null
  const id = `te-host-${s.id}`
  const name = (x) => String(resolve(x.title || '') ?? '').trim() || t('te.tpl.untitledSection')
  return (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot="te-tpl-host">
      <label htmlFor={id} className="text-sm font-semibold">{t('te.tpl.hostLabel')}</label>
      <NativeSelect id={id} value={value} className="w-full pointer-coarse:h-10" aria-describedby={`${id}-h`}
        onChange={(e) => {
          const v = e.target.value
          onChange((x) => (v ? { ...x, host: v } : withoutHost(x)))
        }}>
        <NativeSelectOption value="">{t('te.tpl.hostSeparate')}</NativeSelectOption>
        {dangling && <NativeSelectOption value={value}>{t('te.tpl.hostIn', '—')}</NativeSelectOption>}
        {summaries.map((x) => <NativeSelectOption key={x.id} value={x.id}>{t('te.tpl.hostIn', name(x))}</NativeSelectOption>)}
      </NativeSelect>
      <p id={`${id}-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.tpl.hostHint')}</p>
    </div>
  )
}

function FieldsEditor({ section: s, errors, draft, onChange }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const fields = s.fields || []
  // Özet cümlesinde ya da otomatik trendde kullanılan alan silinirken uyarılır: o cümleler raporda artık görünmez, alanın
  // grafiği trendden düşer (kayıt engellenmez)
  const removeField = async (f) => {
    const used = summaryLinesUsing(draft, f.id)
    const charts = trendSectionsUsing(draft, f.id)
    // Otomatik hesabın formülünde kullanılan alan (2026-10-16): silinirse o hesap kullanılamaz, alan raporda elle girilir
    const calcs = calcFieldsUsing(draft, f.id)
    // Renkli kutucuk olarak gösterilen alan (2026-10-17, 4b): silinirse kutucuğu da kalkar (pruneRemovedTiles)
    const tiles = tileSectionsUsing(draft, f.id)
    if (used.length || charts.length || calcs.length || tiles.length) {
      const calcPart = calcs.length ? t('te.tpl.removeFieldCalcPart', calcs.map((x) => x.label || t('te.tpl.field')).join(', ')) : ''
      const tilePart = tiles.length ? t('te.tpl.removeFieldTilePart') : ''
      const [title, message] = used.length && charts.length
        ? [t('te.tpl.removeFieldUsedAnyTitle'), t('te.tpl.removeFieldBothBody', f.label || '', used.length)]
        : used.length
          ? [t('te.tpl.removeFieldUsedTitle'), t('te.tpl.removeFieldUsedBody', f.label || '', used.length)]
          : charts.length
            ? [t('te.tpl.removeFieldTrendTitle'), t('te.tpl.removeFieldTrendBody', f.label || '')]
            : calcs.length
              ? [t('te.tpl.removeFieldCalcTitle'), '']
              : [t('te.tpl.removeFieldTileTitle'), '']
      const ok = await showConfirm({ title, message: [message, calcPart, tilePart].filter(Boolean).join(' '), variant: 'warning',
        confirmText: t('te.tpl.removeAnyway'), cancelText: t('te.cancel') })
      if (!ok) return
    }
    onChange((x) => ({ ...x, fields: x.fields.filter((y) => y.id !== f.id) }))
  }
  return (
    <div className="flex flex-col gap-2">
      <ol className="flex flex-col gap-3">
        {fields.map((f, i) => (
          <li key={f.id} className={TPL_ITEM} data-slot="te-tpl-field" data-field={`field:${f.id}`}>
            {/* Taşıma düğmeleri kendi satırında: telefonda alanların üstüne binmesin (2026-10-13 e2e) */}
            <div className={TPL_ITEM_HEAD}>
              <span className={TPL_ITEM_TITLE}>{t('te.tpl.fieldN', i + 1)}</span>
              <MoveButtons index={i} count={fields.length} label={f.label || t('te.tpl.field')}
                onMove={(d) => onChange((x) => ({ ...x, fields: move(x.fields, i, d) }))}
                onRemove={fields.length > 1 ? () => removeField(f) : null}
                removeLabel={t('te.tpl.removeFieldAria', f.label || '')} />
            </div>
            <div>
              <div className="grid min-w-0 gap-2 md:grid-cols-[minmax(0,1fr)_11rem]">
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-f-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldLabel')}</label>
                  <Input id={`te-f-${f.id}`} className="pointer-coarse:h-10" value={f.label || ''} maxLength={LIMITS.label + 20}
                    aria-invalid={errors[`field:${f.id}`] ? true : undefined}
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? { ...y, label: e.target.value } : y)) }))} />
                </div>
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-ft-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldType')}</label>
                  <NativeSelect id={`te-ft-${f.id}`} value={f.type} className="w-full pointer-coarse:h-10"
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? withType(y, e.target.value) : y)) }))}>
                    {FIELD_TYPES.map((ty) => <NativeSelectOption key={ty} value={ty}>{t(`te.type.${ty}`)}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
                {(f.type === 'number' || f.type === 'percent') && (
                  <TargetEditor field={f} error={errors[`target:${f.id}`]}
                    onChange={(patch) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? withTarget(y, patch) : y)) }))} />
                )}
                <SkipIfNoSelect field={f} draft={draft} resolveLabel={(x) => x.label || t('te.tpl.field')}
                  onChange={(v) => onChange((x) => ({ ...x, fields: x.fields.map((y) => {
                    if (y.id !== f.id) return y
                    if (!v) return withoutSkip(y)
                    return { ...y, skip_if_no: v }
                  }) }))} />
                <CalcEditor field={f} draft={draft} error={errors[`calc:${f.id}`]}
                  onChange={(calc) => onChange((x) => ({ ...x, fields: x.fields.map((y) => {
                    if (y.id !== f.id) return y
                    if (!calc) return withoutCalc(y)
                    return { ...y, calc }
                  }) }))} />
                <div className="flex flex-col gap-1 md:col-span-2">
                  <label htmlFor={`te-fh-${f.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.fieldHint')}</label>
                  <Input id={`te-fh-${f.id}`} className="pointer-coarse:h-10" value={f.hint || ''} maxLength={LIMITS.hint}
                    onChange={(e) => onChange((x) => ({ ...x, fields: x.fields.map((y) => (y.id === f.id ? { ...y, hint: e.target.value } : y)) }))} />
                </div>
              </div>
            </div>
            {errors[`field:${f.id}`] && <p className="mt-1 text-sm text-destructive" role="alert">{t(errors[`field:${f.id}`])}</p>}
          </li>
        ))}
      </ol>
      <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10"
        disabled={fields.length >= LIMITS.fieldsPerSection}
        onClick={() => onChange((x) => ({ ...x, fields: [...(x.fields || []), { id: newId('f', draft), label: t('te.tpl.newField'), type: 'number' }] }))}>
        <Plus aria-hidden="true" /> {t('te.tpl.addField')}
      </Button>
    </div>
  )
}

/** Alanın hesap tanımı olmadan kopyası. */
function withoutCalc(field) {
  return Object.fromEntries(Object.entries(field || {}).filter(([k]) => k !== 'calc'))
}

/**
 * Tür değişince yeni türle uyumsuz otomatik hesap düşer (oran / değişim yüzde, artış ve hedef karşılaştırması evet/hayır
 * alanında); hedef yalnız yüzdede, "daha iyi" yönü yalnız sayı / yüzdede kalır (4b).
 */
function withType(field, type) {
  let next = { ...field, type }
  const k = field?.calc?.kind
  const yesNoKind = k === 'increase' || k === 'compare'
  if (k && (yesNoKind ? type !== 'yesno' : type !== 'percent')) next = withoutCalc(next)
  if (type !== 'percent') delete next.target
  if (type !== 'percent' && type !== 'number') delete next.better
  return next
}

/** Hedef / yön yaması: boş hedef ve boş yön anahtarı silinir (sunucu yalnız dolu değeri yazar). */
function withTarget(field, patch) {
  const next = { ...field, ...patch }
  if (next.target == null || next.target === '') delete next.target
  if (!next.better) delete next.better
  // Hedef yazılırken "yön yok" düşer: seçici gerçekten "Yön seçin" durumuna geçer (gizli bir "none" taslakta kalmaz)
  if (next.target != null && next.better === 'none') delete next.better
  return next
}

/**
 * Hedef ve "daha iyi" yönü (2026-10-17, 4b; kullanıcı kararı "alanda hedef + otomatik hesap", "hedef + yön"): yüzde alanına
 * hedef (%0–100) verilebilir — raporda hedefiyle karşılaştırılır, kutucuğu hedefi tutturdu mu diye renklenir, rapor o ay
 * için farklı hedef yazabilir. Yön sayı / yüzde alanında: hedefsiz kutucuk önceki aya göre iyileşti mi diye renklenir
 * (varsayılan: yüzde düşük iyi, adet yönsüz). Hedefi olan alan yön seçmek zorundadır (sunucu da reddeder).
 */
function TargetEditor({ field, error, onChange }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [typeErr, setTypeErr] = useState(null)
  const id = `te-fg-${field.id}`
  const percent = field.type === 'percent'
  const hasTarget = percent && calcNumber(field.target) != null
  // Sayı okunamayan hedef metni taslağa yazılamaz → kayıt kapısı onu istemci hatası olarak görür (inceleme 2026-10-17:
  // eski hedef sessizce kaydediliyordu); tür yüzdeden çıkınca ve bileşen kalkınca hata silinir
  const report = useContext(TplClientErrorContext)
  const errKey = `target:${field.id}`
  const setErr = (k) => { setTypeErr(k); report?.(errKey, k) }
  useEffect(() => { if (!percent) setErr(null) }, [percent])     // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => report?.(errKey, null), [report, errKey])
  return (
    <div className={cn('grid min-w-0 gap-2 md:col-span-2', percent && 'sm:grid-cols-2')} data-slot="te-tpl-target" data-field={`target:${field.id}`}>
      {percent && (
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={id} className="text-xs font-medium text-muted-foreground">{t('te.tpl.target')}</label>
          <NumberInput id={id} value={field.target ?? null} lang={uiLang} percent invalid={error || typeErr ? true : undefined}
            describedBy={`${id}-h`} onChange={(n) => onChange({ target: n })} onError={setErr} />
        </div>
      )}
      <div className="flex min-w-0 flex-col gap-1">
        <label htmlFor={`${id}-b`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.better')}</label>
        <NativeSelect id={`${id}-b`} value={field.better || ''} className="w-full pointer-coarse:h-10" data-slot="te-tpl-better"
          aria-invalid={error ? true : undefined} aria-describedby={`${id}-h`}
          onChange={(e) => onChange({ better: e.target.value || null })}>
          <NativeSelectOption value="">{hasTarget ? t('te.tpl.betterPick') : t('te.tpl.betterDefault')}</NativeSelectOption>
          {/* Hedefi olan alanda "yön yok" seçilemez (sunucu reddeder): seçenek gizlenir; kayıtlı "none" hata olarak görünür */}
          {BETTER.filter((b) => !(hasTarget && b === 'none')).map((b) => (
            <NativeSelectOption key={b} value={b}>{t(`te.tpl.better.${b}`)}</NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      <p id={`${id}-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere] sm:col-span-2">
        {percent ? t('te.tpl.targetHint') : t('te.tpl.betterHint')}
      </p>
      {(typeErr || error) && <p className="m-0 text-sm text-destructive sm:col-span-2" role="alert">{t(typeErr || error)}</p>}
    </div>
  )
}

/**
 * Otomatik hesap (2026-10-16, kullanıcı isteği: "yüzdeleri kullanıcı değil sistem hesaplasın"): yüzde alanında oran
 * ((pay − düşülen) ÷ payda × 100) ya da önceki aya göre değişim; evet/hayır alanında önceki aya göre artış. Seçilen
 * alan raporda sorulmaz, girilen adetlerden hesaplanır. Formüldeki alanlar şablonun hesaplanmayan SAYI alanları;
 * şablonda olmayan / uygun olmayan başvuru kaydı engellemez (raporda alan elle girilir) — burada uyarılır.
 */
function CalcEditor({ field, draft, error, onChange }) {
  const t = useT()
  if (field.type !== 'percent' && field.type !== 'yesno') return null
  const c = field.calc || {}
  const kinds = field.type === 'yesno' ? ['increase', 'compare'] : ['ratio', 'change']
  const inputs = allFields(draft).filter((x) => calcInput(x) && x.id !== field.id)
  // Hedef karşılaştırması (4b): şablonda hedefi olan yüzde alanları (hesaplanan da olabilir)
  const targetsPct = allFields(draft).filter((x) => x.type === 'percent' && x.id !== field.id && calcNumber(x.target) != null)
  const name = (x) => x.label || t('te.tpl.field')
  const id = `te-fc-${field.id}`
  const set = (patch) => onChange({ ...c, ...patch })
  const picker = (key, label, optional = false, options = inputs) => {
    const pid = `${id}-${key}`
    const value = c[key] || ''
    const known = !value || options.some((x) => x.id === value)
    return (
      <div className="flex min-w-0 flex-col gap-1">
        <label htmlFor={pid} className="text-xs font-medium text-muted-foreground">{label}</label>
        <NativeSelect id={pid} value={value} className="w-full pointer-coarse:h-10" data-slot="te-calc-pick" data-key={key}
          onChange={(e) => set({ [key]: e.target.value || undefined })}>
          <NativeSelectOption value="">{optional ? t('te.tpl.calcMinusNone') : t('te.tpl.calcPick')}</NativeSelectOption>
          {!known && <NativeSelectOption value={value}>{value}</NativeSelectOption>}
          {options.map((x) => <NativeSelectOption key={x.id} value={x.id}>{name(x)}</NativeSelectOption>)}
        </NativeSelect>
      </div>
    )
  }
  const issues = calcRefIssues(draft, field)
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-md border border-dashed p-2.5 md:col-span-2" data-slot="te-tpl-calc"
      data-field={`calc:${field.id}`}>
      <div className="flex min-w-0 flex-col gap-1">
        <label htmlFor={id} className="text-xs font-medium text-muted-foreground">{t('te.tpl.calc')}</label>
        <NativeSelect id={id} value={c.kind || ''} className="w-full pointer-coarse:h-10"
          aria-invalid={error ? true : undefined}
          onChange={(e) => {
            const kind = e.target.value
            if (!kind) { onChange(null); return }
            // Karşılaştırmanın 'of'u hedefli yüzde, artış / değişiminki adet: tür değişince seçim sıfırlanır
            onChange(kind === 'ratio' ? { kind, num: c.num, minus: c.minus, den: c.den }
              : { kind, of: (kind === 'compare') === (c.kind === 'compare') ? c.of : undefined })
          }}>
          <NativeSelectOption value="">{t('te.tpl.calcNone')}</NativeSelectOption>
          {kinds.map((k) => <NativeSelectOption key={k} value={k}>{t(`te.tpl.calcKind.${k}`)}</NativeSelectOption>)}
        </NativeSelect>
      </div>
      {c.kind === 'ratio' && (
        <div className="grid min-w-0 gap-2 sm:grid-cols-3">
          {picker('num', t('te.tpl.calcNum'))}
          {picker('minus', t('te.tpl.calcMinus'), true)}
          {picker('den', t('te.tpl.calcDen'))}
        </div>
      )}
      {(c.kind === 'change' || c.kind === 'increase') && picker('of', t('te.tpl.calcOf'))}
      {c.kind === 'compare' && picker('of', t('te.tpl.calcOfTarget'), false, targetsPct)}
      {c.kind && <p className="text-xs text-muted-foreground">{t(c.kind === 'compare' ? 'te.tpl.calcCompareHint' : 'te.tpl.calcHint')}</p>}
      {issues.map((x) => (
        <p key={x.id} className="text-xs text-amber-800 dark:text-amber-300 [overflow-wrap:anywhere]" data-slot="te-tpl-calc-issue">
          {t(`te.tpl.calcRef.${x.status}`, x.field?.label || x.id)}
        </p>
      ))}
      {error && <p className="text-sm text-destructive" role="alert">{t(error)}</p>}
    </div>
  )
}

/**
 * "Bu soru Hayır ise alanı sorma": şablondaki evet/hayır sorularından biri (alanın kendisi ve kendisi bağlı sorular
 * hariç — sunucu zincire izin vermez). Seçilen soru "Hayır" işaretlenirse alan raporda muaf olur.
 */
function SkipIfNoSelect({ field, draft, resolveLabel, onChange }) {
  const t = useT()
  const options = allFields(draft).filter((q) => q.type === 'yesno' && q.id !== field.id && !q.skip_if_no && !q.calc?.kind)
  // Başka alanların bağlandığı bir soru kendisi bağlanamaz (zincir) — seçenek gösterilmez
  const isTarget = allFields(draft).some((x) => x.skip_if_no === field.id)
  if (options.length === 0 || isTarget) return null
  const id = `te-fs-${field.id}`
  return (
    <div className="flex flex-col gap-1 md:col-span-2">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">{t('te.tpl.skipIfNo')}</label>
      <NativeSelect id={id} value={field.skip_if_no || ''} className="w-full pointer-coarse:h-10"
        onChange={(e) => onChange(e.target.value || null)}>
        <NativeSelectOption value="">{t('te.tpl.skipIfNoNone')}</NativeSelectOption>
        {options.map((q) => <NativeSelectOption key={q.id} value={q.id}>{resolveLabel(q)}</NativeSelectOption>)}
      </NativeSelect>
      <p className="text-xs text-muted-foreground">{t('te.tpl.skipIfNoHint')}</p>
    </div>
  )
}

function SlotsEditor({ section: s, errors, draft, slotCount, resolve = (x) => x, onChange }) {
  const t = useT()
  const slots = s.slots || []
  return (
    <div className="flex flex-col gap-2">
      <ol className="flex flex-col gap-3">
        {slots.map((sl, i) => (
          <li key={sl.id} className={TPL_ITEM} data-slot="te-tpl-slot" data-field={`slot:${sl.id}`}>
            <div className={TPL_ITEM_HEAD}>
              <span className={TPL_ITEM_TITLE}>{t('te.tpl.slotN', i + 1)}</span>
              <MoveButtons index={i} count={slots.length} label={sl.label || t('te.tpl.slot')}
                onMove={(d) => onChange((x) => ({ ...x, slots: move(x.slots, i, d) }))}
                onRemove={slots.length > 1 ? () => onChange((x) => ({ ...x, slots: x.slots.filter((y) => y.id !== sl.id) })) : null}
                removeLabel={t('te.tpl.removeSlotAria', sl.label || '')} />
            </div>
            <div>
              <div className="grid min-w-0 gap-2 md:grid-cols-[minmax(0,1fr)_auto]">
                <div className="flex flex-col gap-1">
                  <label htmlFor={`te-s-${sl.id}`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.slotLabel')}</label>
                  <Input id={`te-s-${sl.id}`} className="pointer-coarse:h-10" value={sl.label || ''} maxLength={LIMITS.label + 20}
                    aria-invalid={errors[`slot:${sl.id}`] ? true : undefined} aria-describedby={`te-sp-${sl.id}`}
                    onChange={(e) => onChange((x) => ({ ...x, slots: x.slots.map((y) => (y.id === sl.id ? { ...y, label: e.target.value } : y)) }))} />
                  {/* Değişkenler (kanal / ay / yıl) doldurulmuş hâli (2026-10-16, kullanıcı isteği) — raporda bu ad görünür */}
                  <p id={`te-sp-${sl.id}`} className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-slot-preview">
                    {t('te.tpl.titlePreview')} <span className="font-medium text-foreground">{resolve(sl.label || '') || '—'}</span>
                  </p>
                </div>
                <div className="flex flex-col gap-1">
                  <span className="text-xs font-medium text-muted-foreground" id={`te-sw-${sl.id}`}>{t('te.tpl.slotWidth')}</span>
                  <ToggleGroup type="single" variant="outline" value={sl.width} aria-labelledby={`te-sw-${sl.id}`}
                    onValueChange={(v) => { if (v) onChange((x) => ({ ...x, slots: x.slots.map((y) => (y.id === sl.id ? { ...y, width: v } : y)) })) }}>
                    <ToggleGroupItem value="half" className="px-3 pointer-coarse:min-h-10">{t('te.width.half')}</ToggleGroupItem>
                    <ToggleGroupItem value="full" className="px-3 pointer-coarse:min-h-10">{t('te.width.full')}</ToggleGroupItem>
                  </ToggleGroup>
                </div>
              </div>
            </div>
            {errors[`slot:${sl.id}`] && <p className="mt-1 text-sm text-destructive" role="alert">{t(errors[`slot:${sl.id}`])}</p>}
          </li>
        ))}
      </ol>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10" disabled={slotCount >= LIMITS.slots}
          onClick={() => onChange((x) => ({ ...x, slots: [...(x.slots || []), { id: newId('g', draft), label: t('te.tpl.newSlot'), width: 'full' }] }))}>
          <Plus aria-hidden="true" /> {t('te.tpl.addSlot')}
        </Button>
        <span className="text-xs text-muted-foreground">{t('te.tpl.slotCount', slotCount, LIMITS.slots)}</span>
      </div>
    </div>
  )
}

function IssueLabelsEditor({ section: s, onChange }) {
  const t = useT()
  const rows = [
    ['item_title', 'te.tpl.itemTitle', 'te.tpl.itemTitleHint'],
    ['description_label', 'te.tpl.descriptionLabel', null],
    ['customers_label', 'te.tpl.customersLabel', null],
    ['errors_label', 'te.tpl.errorsLabel', null],
  ]
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {rows.map(([key, label, hint]) => (
        <div key={key} className={cn('flex flex-col gap-1', key === 'item_title' && 'md:col-span-2')}>
          <label htmlFor={`te-i-${s.id}-${key}`} className="text-xs font-medium text-muted-foreground">{t(label)}</label>
          <Input id={`te-i-${s.id}-${key}`} className="pointer-coarse:h-10" value={s[key] || ''} maxLength={key === 'item_title' ? LIMITS.label : LIMITS.issueLabel}
            onChange={(e) => onChange((x) => ({ ...x, [key]: e.target.value }))} />
          {hint && <p className="text-xs text-muted-foreground">{t(hint)}</p>}
        </div>
      ))}
    </div>
  )
}

/** Yerleşim önizlemesi — PDF'in sırası ve iki kolonlu ızgarası (yarım yuvalar yan yana, tamlar tek satır). */
function LayoutPreview({ draft, resolve }) {
  const t = useT()
  return (
    <Card className="gap-3" data-slot="te-tpl-preview">
      <CardHeader>
        <h3 className="text-sm font-semibold">{t('te.tpl.previewTitle')}</h3>
        <p className="text-xs text-muted-foreground">{t('te.tpl.previewHint')}</p>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-xs">
        <p className="rounded-md bg-muted px-2 py-1.5 font-semibold [overflow-wrap:anywhere]">{resolve(draft.title)}</p>
        {/* Özet kartına gömülü bölüm kendi yerinde değil, özetinin altında (raporda kartın içinde, PDF'te açıklaması
            cümlenin altında — 2026-10-17) */}
        {draft.sections.filter((s) => !hostOf(draft, s)).map((s) => (
          <div key={s.id} className="flex flex-col gap-1" data-slot="te-tpl-preview-section" data-section={s.id}>
            <p className="font-medium [overflow-wrap:anywhere]">{resolve(s.title) || '—'}</p>
            {s.kind === 'summary' && embeddedSections(draft, s.id).map((e) => (
              <p key={e.id} className="m-0 ps-3 text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-preview-embedded" data-section={e.id}>
                <span className="font-medium text-foreground">{resolve(e.title) || '—'}</span> · {t('te.tpl.hostBadge')}
              </p>
            ))}
            {s.kind === 'images' ? (
              <div className="flex flex-col gap-1">
                {imageRows(s.slots).map((row) => (
                  <div key={row.map((r) => r.id).join('|')} className={cn('grid gap-1', row.length === 2 || row[0].width === 'half' ? 'grid-cols-2' : 'grid-cols-1')}>
                    {row.map((sl) => (
                      <div key={sl.id} className={cn('rounded border border-dashed px-1.5 py-2 text-[11px] text-muted-foreground [overflow-wrap:anywhere]',
                        sl.width === 'full' && 'col-span-2')}>
                        {resolve(sl.label)}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-muted-foreground">
                {s.kind === 'metrics' ? t('te.tpl.previewFields', (s.fields || []).length)
                  : s.kind === 'issues' ? t('te.tpl.previewIssues')
                    : s.kind === 'summary' ? t('te.tpl.previewSummary', (s.lines || []).length)
                    : s.kind === 'trend' ? t('te.tpl.previewTrend', (s.series || []).length)
                      : s.kind === 'table' ? t('te.tpl.previewTable', (s.columns || []).length, (s.rows || []).length)
                        : t('te.tpl.previewText')}
              </p>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

/**
 * Yönetici özeti cümleleri (2026-10-14): her satır bir cümle kalıbı. "Alan ekle" imlecin olduğu yere `{f:…}` koyar
 * (evet/hayır alanı için iki ifade iki ayrı kutudan alınır); altında okunur önizleme ("[Teknik hata oranı]") ve
 * uyarılar: silinmiş alan ("Başvuruyu kaldır" ile temizlenir) / evet-hayır olmayan alanda seçim (cümle raporda
 * görünmez — kayıt engellenmez), okunamayan başvuru (kayıt engellenir). Satırların istemci tarafında KARARLI
 * kimliği vardır: taşıma / silme sonrası odak ve imleç doğru cümlede kalır.
 */
/**
 * Renkli kutucuklar (2026-10-17, 4b; kullanıcı kararı "6 karışık, her takım şablonunda değiştirebilir"): özetin başında
 * gösterilecek en çok 6 sayı / yüzde alanı, seçim sırasıyla; taşıma ve kaldırma düğmeleri. Alanın varlığı kayıtta
 * denetlenmez (silinen alanın kutucuğu çizilmez) — burada uyarılır.
 */
function TilesEditor({ section: s, draft, resolve, onChange }) {
  const t = useT()
  const tiles = s.tiles || []
  const byId = new Map(allFields(draft).map((f) => [f.id, f]))
  const candidates = allFields(draft).filter((f) => (f.type === 'number' || f.type === 'percent') && !tiles.includes(f.id))
  const [pick, setPick] = useState('')
  // Seçilen alan bu arada silinmiş / türü değişmişse eklenemez (bayat seçim — inceleme 2026-10-17)
  const pickOk = !!pick && candidates.some((f) => f.id === pick)
  const setTiles = (next) => onChange((x) => {
    const out = { ...x }
    if (next.length) out.tiles = next
    else delete out.tiles
    return out
  })
  const headId = `te-tiles-${s.id}`
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-dashed border-border/60 p-2.5" data-slot="te-tpl-tiles">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p id={headId} className="m-0 text-xs font-medium text-muted-foreground">{t('te.tpl.tiles')}</p>
        <span className="text-xs text-muted-foreground" data-slot="te-tpl-tiles-count">{t('te.tpl.tilesCount', tiles.length, LIMITS.tiles)}</span>
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t('te.tpl.tilesHint')}</p>
      {tiles.length === 0 ? (
        <p className="m-0 text-xs text-muted-foreground" data-slot="te-tpl-tiles-empty">{t('te.tpl.tilesEmpty')}</p>
      ) : (
        <ol className="m-0 flex list-none flex-col gap-1.5 p-0" aria-labelledby={headId}>
          {tiles.map((tid, i) => {
            const f = byId.get(tid)
            // Şablonda olmayan alanın ham kimliği gösterilmez (inceleme 2026-10-17)
            const name = f ? resolve(f.label) : t('te.sum.removedField')
            const usable = !!f && (f.type === 'number' || f.type === 'percent')
            return (
              <li key={tid} className="flex min-w-0 items-center justify-between gap-2 rounded-md border border-border/60 bg-muted/30 px-2 py-1"
                data-slot="te-tpl-tile" data-tile={tid}>
                <span className="min-w-0 text-sm [overflow-wrap:anywhere]">
                  <span className="me-1 text-xs text-muted-foreground">{i + 1}.</span>{name}
                  {!usable && <span className="ms-1 text-xs text-amber-800 dark:text-amber-300">{t('te.tpl.tileUnusable')}</span>}
                </span>
                <MoveButtons index={i} count={tiles.length} label={name} onMove={(d) => setTiles(move(tiles, i, d))}
                  onRemove={() => setTiles(tiles.filter((x) => x !== tid))} removeLabel={t('te.tpl.removeTileAria', name)} />
              </li>
            )
          })}
        </ol>
      )}
      {tiles.length < LIMITS.tiles ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex min-w-0 grow flex-col gap-1 sm:max-w-xs">
            <label htmlFor={`${headId}-pick`} className="text-xs font-medium text-muted-foreground">{t('te.tpl.tileAdd')}</label>
            <NativeSelect id={`${headId}-pick`} value={pick} className="w-full pointer-coarse:h-10" data-slot="te-tpl-tile-pick"
              onChange={(e) => setPick(e.target.value)}>
              <NativeSelectOption value="">{t('te.tpl.tilePick')}</NativeSelectOption>
              {candidates.map((f) => <NativeSelectOption key={f.id} value={f.id}>{resolve(f.label)}</NativeSelectOption>)}
            </NativeSelect>
          </div>
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" disabled={!pickOk} data-slot="te-tpl-tile-add"
            onClick={() => { if (pickOk && !tiles.includes(pick)) setTiles([...tiles, pick]); setPick('') }}>
            <Plus aria-hidden="true" /> {t('te.tpl.tileAdd')}
          </Button>
        </div>
      ) : (
        <p className="m-0 text-xs text-muted-foreground">{t('te.tpl.tilesFull', LIMITS.tiles)}</p>
      )}
    </div>
  )
}

function SummaryLinesEditor({ section: s, errors, draft, resolve, resolveLong = resolve, onChange }) {
  const t = useT()
  const lines = s.lines || []
  const ids = useRef([])
  const seq = useRef(0)
  // Kimlik listesi satır sayısını izler (dışarıdan yükleme / sıfırlama sayıyı değiştirebilir)
  while (ids.current.length < lines.length) ids.current.push(`l${++seq.current}`)
  if (ids.current.length > lines.length) ids.current.length = lines.length
  const caret = useRef({})
  const root = useRef(null)
  // Menüden alan seçildiyse odak cümleye / seçim kutusuna gider; Escape ile kapanışta Radix odağı tetiğe döndürür
  const picked = useRef(false)
  const [choice, setChoice] = useState(null)        // { lineId, field, yes, no }
  const tokenFields = allFields(draft).filter((f) => f.type !== 'textarea')
  // Karşılaştırma başvuruları (4b): sayı / yüzde alanı için önceki aya göre fark; hedefli yüzde için hedef ve hedefe uzaklık
  const compareItems = tokenFields.filter((f) => f.type === 'number' || f.type === 'percent').flatMap((f) => {
    const items = [{ key: `d:${f.id}`, token: `{d:${f.id}}`, field: f, kind: 'd' }]
    if (f.type === 'percent' && calcNumber(f.target) != null) {
      items.push({ key: `t:${f.id}`, token: `{t:${f.id}}`, field: f, kind: 't' }, { key: `dt:${f.id}`, token: `{dt:${f.id}}`, field: f, kind: 'dt' })
    }
    return items
  })
  const decorateRef = (kind, label) => t(`te.tpl.refKind.${kind}`, label)
  const indexOf = (lineId) => ids.current.indexOf(lineId)
  // Kimlikler (l1, l2 …) bölüm başınadır: arama bu bölümün kabıyla sınırlı (iki özet bölümü birbirine karışmaz)
  const focusLine = (lineId, pos = null, selector = 'textarea') => requestAnimationFrame(() => {
    const item = root.current?.querySelector(`[data-line-id="${lineId}"]`)
    if (!item) return
    let el = item.querySelector(selector)
    // Taşınan cümle en üste / en alta vardıysa o düğme kapanır: odak cümlenin açık taşıma düğmesine, yoksa metne
    if (!el || el.disabled) el = item.querySelector('[data-move]:not(:disabled)') || item.querySelector('textarea')
    if (!el) return
    el.focus()
    if (pos != null && el.setSelectionRange) { try { el.setSelectionRange(pos, pos) } catch { /* gizli alan */ } }
  })
  const setLine = (lineId, v) => {
    const i = indexOf(lineId)
    onChange((x) => ({ ...x, lines: (x.lines || []).map((l, j) => (j === i ? v : l)) }))
  }
  const insert = (lineId, token) => {
    const i = indexOf(lineId)
    if (i < 0) return
    const [next, pos] = insertToken(lines[i], token, caret.current[lineId])
    caret.current[lineId] = pos
    onChange((x) => ({ ...x, lines: (x.lines || []).map((l, j) => (j === i ? next : l)) }))
    focusLine(lineId, pos)
  }
  const moveLine = (lineId, delta) => {
    const i = indexOf(lineId)
    ids.current = move(ids.current, i, delta)
    onChange((x) => ({ ...x, lines: move(x.lines, i, delta) }))
    // Odak taşınan cümlenin aynı düğmesinde kalır (ikinci basış geri almaz)
    focusLine(lineId, null, `[data-move="${delta < 0 ? 'up' : 'down'}"]`)
  }
  const removeLine = (lineId) => {
    const i = indexOf(lineId)
    const neighbour = ids.current[i + 1] ?? ids.current[i - 1]
    ids.current = ids.current.filter((x) => x !== lineId)
    delete caret.current[lineId]
    if (choice?.lineId === lineId) setChoice(null)
    onChange((x) => ({ ...x, lines: x.lines.filter((_, j) => j !== i) }))
    // Silinen cümlenin düğmesi kayboldu: odak komşu cümleye (yoksa "Cümle ekle"ye) — <body>'ye düşmesin
    if (neighbour) focusLine(neighbour)
    else requestAnimationFrame(() => root.current?.querySelector('[data-slot="te-tpl-add-line"]')?.focus())
  }
  const addLine = () => {
    const lineId = `l${++seq.current}`
    ids.current = [...ids.current, lineId]
    onChange((x) => ({ ...x, lines: [...(x.lines || []), ''] }))
    focusLine(lineId)
  }
  const remember = (lineId) => (e) => { caret.current[lineId] = e.target.selectionStart }
  const placeholders = draft.lang === 'en'
    ? [['{channel}', 'te.tpl.insertChannel'], ['{month}', 'te.tpl.insertMonth'], ['{year}', 'te.tpl.insertYear']]
    : [['{kanal}', 'te.tpl.insertChannel'], ['{ay}', 'te.tpl.insertMonth'], ['{yil}', 'te.tpl.insertYear']]
  return (
    <div ref={root} className="flex flex-col gap-2" data-slot="te-tpl-summary">
      <p className="m-0 text-xs text-muted-foreground">{t('te.tpl.summaryHint')}</p>
      <TilesEditor section={s} draft={draft} resolve={resolve} onChange={onChange} />
      <ol className="m-0 flex list-none flex-col gap-2 p-0">
        {lines.map((line, i) => {
          const lineId = ids.current[i]
          const id = `te-l-${s.id}-${lineId}`
          const err = errors[`line:${s.id}:${i}`]
          const refs = summaryRefs(line)
          const issues = summaryLineIssues(line, draft).filter((x) => x.kind !== 'malformed')
          const warnId = issues.length ? `${id}-w` : undefined
          const errId = err ? `${id}-e` : undefined
          const open = choice?.lineId === lineId
          return (
            <li key={lineId} className={TPL_ITEM} data-slot="te-tpl-line" data-line-id={lineId} data-field={`line:${s.id}:${i}`}>
              <div className={TPL_ITEM_HEAD}>
                <label htmlFor={id} className={TPL_ITEM_TITLE}>{t('te.tpl.lineN', i + 1)}</label>
                <MoveButtons index={i} count={lines.length} label={t('te.tpl.lineN', i + 1)}
                  onMove={(d) => moveLine(lineId, d)}
                  onRemove={lines.length > 1 ? () => removeLine(lineId) : null}
                  removeLabel={t('te.tpl.removeLineAria', i + 1)} />
              </div>
              <Textarea id={id} rows={2} value={line} maxLength={LIMITS.summaryLine + 20} aria-invalid={err ? true : undefined}
                aria-describedby={[warnId, errId].filter(Boolean).join(' ') || undefined}
                onSelect={remember(lineId)} onKeyUp={remember(lineId)} onClick={remember(lineId)}
                // Cümle tek satırdır (sunucu satır sonunu boşluğa çevirir): Enter satır açmaz, yapıştırılan satır sonu boşluk
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent?.isComposing) e.preventDefault() }}
                onChange={(e) => {
                  const raw = e.target.value
                  const v = raw.replace(/\r?\n/g, ' ')
                  // Satır sonu değişince React değeri yeniden yazar ve imleç sona kaçar: imleç değişmiş metne göre geri konur
                  const at = raw.slice(0, e.target.selectionStart).replace(/\r?\n/g, ' ').length
                  caret.current[lineId] = at
                  setLine(lineId, v)
                  if (v !== raw) focusLine(lineId, at)
                }} />
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                <DropdownMenu modal={false}>
                  <DropdownMenuTrigger asChild>
                    <Button type="button" size="sm" variant="outline" className="h-7 px-2 text-xs pointer-coarse:h-10"
                      disabled={tokenFields.length === 0} data-slot="te-tpl-insert-field">
                      <Braces aria-hidden="true" /> {t('te.tpl.insertField')}
                    </Button>
                  </DropdownMenuTrigger>
                  {/* Alan seçilince odak menü düğmesine değil cümleye (imleç eklenen alanın ardında) ya da seçim
                      kutusuna gider; seçimsiz kapanışta (Escape / dışarı) Radix odağı menü düğmesine döndürür */}
                  <DropdownMenuContent align="start" className="z-(--z-menu) max-h-72 max-w-[min(24rem,calc(100vw-2rem))] overflow-y-auto"
                    onCloseAutoFocus={(e) => { if (picked.current) { e.preventDefault(); picked.current = false } }}>
                    {tokenFields.map((f) => (
                      <DropdownMenuItem key={f.id} className="whitespace-normal" onSelect={() => {
                        picked.current = true
                        if (f.type === 'yesno') {
                          setChoice({ lineId, field: f, yes: '', no: '' })
                          requestAnimationFrame(() => document.getElementById(`${id}-yes`)?.focus())
                        } else insert(lineId, fieldToken(f))
                      }}>
                        <span className="min-w-0 [overflow-wrap:anywhere]">{resolve(f.label)}</span>
                        <span className="ms-auto shrink-0 text-xs text-muted-foreground">{t(`te.type.${f.type}`)}</span>
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
                <DropdownMenu modal={false}>
                  <DropdownMenuTrigger asChild>
                    <Button type="button" size="sm" variant="outline" className="h-7 px-2 text-xs pointer-coarse:h-10"
                      disabled={compareItems.length === 0} data-slot="te-tpl-insert-compare">
                      <Braces aria-hidden="true" /> {t('te.tpl.insertCompare')}
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start" className="z-(--z-menu) max-h-72 max-w-[min(24rem,calc(100vw-2rem))] overflow-y-auto"
                    onCloseAutoFocus={(e) => { if (picked.current) { e.preventDefault(); picked.current = false } }}>
                    {compareItems.map((x) => (
                      <DropdownMenuItem key={x.key} className="whitespace-normal" onSelect={() => { picked.current = true; insert(lineId, x.token) }}>
                        <span className="min-w-0 [overflow-wrap:anywhere]">{decorateRef(x.kind, resolve(x.field.label))}</span>
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
                {placeholders.map(([ph, key]) => (
                  <Button key={ph} type="button" size="sm" variant="outline" className="h-7 px-2 font-mono text-xs pointer-coarse:h-10"
                    aria-label={`${ph} — ${t(key)}`} title={t(key)} onClick={() => insert(lineId, ph)}>{ph}</Button>
                ))}
              </div>
              {open && (
                <div className="mt-2 flex flex-col gap-2 rounded-md border bg-muted/30 p-3" data-slot="te-tpl-choice"
                  role="group" aria-label={t('te.tpl.choiceTitle', resolve(choice.field.label))}
                  onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setChoice(null); focusLine(lineId) } }}>
                  <p className="m-0 text-xs font-medium [overflow-wrap:anywhere]">{t('te.tpl.choiceTitle', resolve(choice.field.label))}</p>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div className="flex min-w-0 flex-col gap-1">
                      <label htmlFor={`${id}-yes`} className="text-xs text-muted-foreground">{t('te.tpl.choiceYes')}</label>
                      <Input id={`${id}-yes`} className="pointer-coarse:h-10" value={choice.yes} maxLength={120}
                        placeholder={t('te.tpl.choiceYesPh')} onChange={(e) => setChoice((c) => ({ ...c, yes: e.target.value }))} />
                    </div>
                    <div className="flex min-w-0 flex-col gap-1">
                      <label htmlFor={`${id}-no`} className="text-xs text-muted-foreground">{t('te.tpl.choiceNo')}</label>
                      <Input id={`${id}-no`} className="pointer-coarse:h-10" value={choice.no} maxLength={120}
                        placeholder={t('te.tpl.choiceNoPh')} onChange={(e) => setChoice((c) => ({ ...c, no: e.target.value }))} />
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" className="pointer-coarse:min-h-10"
                      disabled={!cleanChoice(choice.yes) && !cleanChoice(choice.no)}
                      onClick={() => { const c = choice; setChoice(null); insert(lineId, fieldToken(c.field, c.yes, c.no)) }}>
                      <Plus aria-hidden="true" /> {t('te.tpl.choiceInsert')}
                    </Button>
                    <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10"
                      onClick={() => { setChoice(null); focusLine(lineId) }}>{t('te.cancel')}</Button>
                  </div>
                </div>
              )}
              <p className="m-0 mt-2 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-tpl-line-preview">
                {t('te.tpl.linePreview')} <span className="text-foreground">{summaryLinePreview(line, draft, resolve, t('te.sum.removedField'), resolveLong, decorateRef)}</span>
              </p>
              {issues.length > 0 && (
                <div id={warnId} className="mt-1 flex flex-col gap-1">
                  {issues.map((x) => {
                    const ref = refs.find((r) => r.id === x.id)
                    return (
                      <div key={`${x.kind}:${x.id}`} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-amber-800 dark:text-amber-300 [overflow-wrap:anywhere]"
                        data-slot="te-tpl-line-warn">
                        <span>{x.kind === 'missing' ? t('te.tpl.lineMissingField')
                          : x.kind === 'textarea' ? t('te.tpl.lineTextarea', resolve(x.label || ''))
                          : x.kind === 'notNumeric' ? t('te.tpl.lineNotNumeric', resolve(x.label || ''))
                          : x.kind === 'noTarget' ? t('te.tpl.lineNoTarget', resolve(x.label || ''))
                          : t('te.tpl.lineNotYesNo', resolve(x.label || ''))}</span>
                        {x.kind === 'missing' && ref && (
                          <Button type="button" size="sm" variant="link" className="h-auto p-0 text-xs pointer-coarse:min-h-10"
                            onClick={() => {
                              // Cümle kısaldı: hatırlanan imleç başvurunun yerine çekilir (eski konum yeni metnin
                              // ortasına alan sokuyordu); düğme kendi uyarısıyla kaybolur → odak cümleye
                              const next = removeRef(line, ref)
                              const at = Math.min(ref.start, next.length)
                              caret.current[lineId] = at
                              setLine(lineId, next)
                              focusLine(lineId, at)
                            }}>{t('te.tpl.removeRef')}</Button>
                        )}
                      </div>
                    )
                  })}
                </div>
              )}
              {err && <p id={errId} className="m-0 mt-1 text-sm text-destructive" role="alert">{t(err)}</p>}
            </li>
          )
        })}
      </ol>
      <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10" disabled={lines.length >= LIMITS.summaryLines}
        onClick={addLine} data-slot="te-tpl-add-line">
        <Plus aria-hidden="true" /> {t('te.tpl.addLine')}
      </Button>
    </div>
  )
}

/**
 * Tablo bölümü (2026-10-14): sütunlar (ad + tür: sayı / yüzde / metin) ve satırlar (ad + "grup satırı" — kalın,
 * zeminli; ör. kanal toplamı). Değerler raporda girilir. Kimlikler kalıcıdır: satır / sütun adı değişse de geçmiş
 * raporların hücreleri kaybolmaz.
 */
function TableEditor({ section: s, errors, draft, resolve, onChange }) {
  const t = useT()
  const cols = s.columns || []
  const rows = s.rows || []
  const root = useRef(null)
  // Kimlikler bölüm kimliğiyle: iki tablo aynı satır / sütun kimliğini taşısa da (API, kopyalanmış şablon) etiketler
  // yanlış kutuyu işaretlemez
  const cid = (kind, id) => `te-${kind}-${s.id}-${id}`
  // Silinen kartın düğmesi kayboldu: odak komşu kartın silme / taşıma düğmesine, yoksa ekleme düğmesine
  const focusAfterRemove = (kind, list, id) => {
    const i = list.findIndex((x) => x.id === id)
    const next = list[i + 1] ?? list[i - 1]
    requestAnimationFrame(() => {
      const scope = root.current
      if (!scope) return
      const card = next ? scope.querySelector(`[data-slot="te-tpl-${kind}"][data-item="${next.id}"]`) : null
      const target = card?.querySelector('input') || scope.querySelector(`[data-slot="te-tpl-add-${kind}"]`)
      target?.focus?.()
    })
  }
  // Taşınan kartın odağı: React düğümü taşıyınca odak düşer; uca varınca basılan düğme kapanır → aynı kartın açık
  // taşıma düğmesine, o da yoksa adına
  const focusItem = (kind, id, selector) => requestAnimationFrame(() => {
    const card = root.current?.querySelector(`[data-slot="te-tpl-${kind}"][data-item="${id}"]`)
    if (!card) return
    let el = selector ? card.querySelector(selector) : null
    if (!el || el.disabled) el = card.querySelector('[data-move]:not(:disabled)') || card.querySelector('input')
    el?.focus?.()
  })
  const add = (kind, item) => {
    onChange((x) => ({ ...x, [kind === 'column' ? 'columns' : 'rows']: [...(x[kind === 'column' ? 'columns' : 'rows'] || []), item] }))
    // Yeni kartın adına odak (sınırda "ekle" düğmesi kapanır; odak ona kalmasın)
    focusItem(kind, item.id, 'input')
  }
  const colName = (c, i) => resolve(c.label || '') || t('te.tpl.columnN', i + 1)
  const rowName = (r, i) => resolve(r.label || '') || t('te.tpl.rowN', i + 1)
  return (
    <div ref={root} className="flex flex-col gap-4">
    <TablePreview cols={cols} rows={rows} resolve={resolve} title={resolve(s.title || '')} />
    <div className="grid gap-4 lg:grid-cols-2" data-slot="te-tpl-table">
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-sm font-semibold">{t('te.tpl.columns')} <span className="font-normal text-muted-foreground">{t('te.tpl.columnCount', cols.length, LIMITS.columns)}</span></p>
        <ol className="flex flex-col gap-3">
          {cols.map((c, i) => (
            <li key={c.id} className={TPL_ITEM} data-slot="te-tpl-column" data-item={c.id} data-field={`col:${s.id}:${c.id}`}>
              <div className={TPL_ITEM_HEAD}>
                <span className={TPL_ITEM_TITLE}>{t('te.tpl.columnN', i + 1)}</span>
                <MoveButtons index={i} count={cols.length} label={colName(c, i)}
                  onMove={(d) => {
                    onChange((x) => ({ ...x, columns: move(x.columns, i, d) }))
                    focusItem('column', c.id, `[data-move="${d < 0 ? 'up' : 'down'}"]`)
                  }}
                  onRemove={cols.length > 1 ? () => {
                    focusAfterRemove('column', cols, c.id)
                    onChange((x) => ({ ...x, columns: x.columns.filter((y) => y.id !== c.id) }))
                  } : null}
                  removeLabel={t('te.tpl.removeColumnAria', colName(c, i))} />
              </div>
              {/* Ad tam genişlik, tür altında: yarım kolonluk kartta ad kutusu sığsın (2026-10-14 görsel kontrol) */}
              <div className="flex min-w-0 flex-col gap-2">
                <div className="flex min-w-0 flex-col gap-1">
                  <label htmlFor={cid('col', c.id)} className="text-xs font-medium text-muted-foreground">{t('te.tpl.columnLabel')}</label>
                  <Input id={cid('col', c.id)} className="pointer-coarse:h-10" value={c.label || ''} maxLength={LIMITS.columnLabel + 20}
                    aria-invalid={errors[`col:${s.id}:${c.id}`] ? true : undefined}
                    aria-describedby={errors[`col:${s.id}:${c.id}`] ? `${cid('col', c.id)}-e` : undefined}
                    onChange={(e) => onChange((x) => ({ ...x, columns: x.columns.map((y) => (y.id === c.id ? { ...y, label: e.target.value } : y)) }))} />
                </div>
                <div className="flex flex-col gap-1 sm:max-w-48">
                  <label htmlFor={cid('colt', c.id)} className="text-xs font-medium text-muted-foreground">{t('te.tpl.columnType')}</label>
                  <NativeSelect id={cid('colt', c.id)} value={c.type || 'number'} className="w-full pointer-coarse:h-10"
                    onChange={(e) => onChange((x) => ({ ...x, columns: x.columns.map((y) => (y.id === c.id ? { ...y, type: e.target.value } : y)) }))}>
                    {COLUMN_TYPES.map((ty) => <NativeSelectOption key={ty} value={ty}>{t(`te.type.${ty}`)}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
              </div>
              {errors[`col:${s.id}:${c.id}`] && <p id={`${cid('col', c.id)}-e`} className="mt-1 text-sm text-destructive" role="alert">{t(errors[`col:${s.id}:${c.id}`])}</p>}
            </li>
          ))}
        </ol>
        <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10" disabled={cols.length >= LIMITS.columns}
          onClick={() => add('column', { id: newId('c', draft), label: t('te.tpl.newColumn'), type: 'number' })}
          data-slot="te-tpl-add-column">
          <Plus aria-hidden="true" /> {t('te.tpl.addColumn')}
        </Button>
      </div>
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-sm font-semibold">{t('te.tpl.rows')} <span className="font-normal text-muted-foreground">{t('te.tpl.rowCount', rows.length, LIMITS.rows)}</span></p>
        <ol className="flex flex-col gap-3">
          {rows.map((r, i) => (
            <li key={r.id} className={cn(TPL_ITEM, r.group && 'bg-muted/60')} data-slot="te-tpl-row" data-item={r.id} data-field={`row:${s.id}:${r.id}`}>
              <div className={TPL_ITEM_HEAD}>
                <span className={TPL_ITEM_TITLE}>{t('te.tpl.rowN', i + 1)}</span>
                <MoveButtons index={i} count={rows.length} label={rowName(r, i)}
                  onMove={(d) => {
                    onChange((x) => ({ ...x, rows: move(x.rows, i, d) }))
                    focusItem('row', r.id, `[data-move="${d < 0 ? 'up' : 'down'}"]`)
                  }}
                  onRemove={rows.length > 1 ? () => {
                    focusAfterRemove('row', rows, r.id)
                    onChange((x) => ({ ...x, rows: x.rows.filter((y) => y.id !== r.id) }))
                  } : null}
                  removeLabel={t('te.tpl.removeRowAria', rowName(r, i))} />
              </div>
              <div className="flex min-w-0 flex-col gap-1">
                <label htmlFor={cid('row', r.id)} className="text-xs font-medium text-muted-foreground">{t('te.tpl.rowLabel')}</label>
                <Input id={cid('row', r.id)} className="pointer-coarse:h-10" value={r.label || ''} maxLength={LIMITS.label + 20}
                  aria-invalid={errors[`row:${s.id}:${r.id}`] ? true : undefined}
                  aria-describedby={errors[`row:${s.id}:${r.id}`] ? `${cid('row', r.id)}-e` : undefined}
                  onChange={(e) => onChange((x) => ({ ...x, rows: x.rows.map((y) => (y.id === r.id ? { ...y, label: e.target.value } : y)) }))} />
                {resolve(r.label) !== (r.label || '') && (
                  <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.tpl.linePreview')} {resolve(r.label)}</p>
                )}
              </div>
              <div className="mt-2 flex items-start gap-2">
                <Checkbox id={cid('rowg', r.id)} checked={!!r.group} className="mt-0.5"
                  onCheckedChange={(v) => onChange((x) => ({ ...x, rows: x.rows.map((y) => {
                    if (y.id !== r.id) return y
                    if (v === true) return { ...y, group: true }
                    return Object.fromEntries(Object.entries(y).filter(([k]) => k !== 'group'))
                  }) }))} />
                <label htmlFor={cid('rowg', r.id)} className="text-sm leading-snug">
                  {t('te.tpl.rowGroup')}
                  <span className="block text-xs text-muted-foreground">{t('te.tpl.rowGroupHint')}</span>
                </label>
              </div>
              {errors[`row:${s.id}:${r.id}`] && <p id={`${cid('row', r.id)}-e`} className="mt-1 text-sm text-destructive" role="alert">{t(errors[`row:${s.id}:${r.id}`])}</p>}
            </li>
          ))}
        </ol>
        <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:min-h-10" disabled={rows.length >= LIMITS.rows}
          onClick={() => add('row', { id: newId('r', draft), label: t('te.tpl.newRow') })}
          data-slot="te-tpl-add-row">
          <Plus aria-hidden="true" /> {t('te.tpl.addRow')}
        </Button>
      </div>
    </div>
    </div>
  )
}

/**
 * Tablonun okunur önizlemesi (2026-10-14, inceleme: "satır / sütun düzenlemesi tabloyu göstermiyor"): PDF'teki gibi
 * koyu başlık, grup satırı kalın ve zeminli, altındaki satırlar içeriden. Değer yok — yalnız yapı. Geniş tablo kendi
 * kabında kayar (sayfa kaymaz).
 */
function TablePreview({ cols, rows, resolve, title }) {
  const t = useT()
  // İçeriden başlama yalnız bir grup satırının ALTINDAKİ satırlar için (rapor ve PDF ile aynı)
  const afterGroup = new Set()
  let seenGroup = false
  for (const r of rows) {
    if (seenGroup && !r.group) afterGroup.add(r.id)
    if (r.group) seenGroup = true
  }
  return (
    <figure className="m-0 flex min-w-0 flex-col gap-1" data-slot="te-tpl-table-preview">
      <figcaption className="text-xs font-medium text-muted-foreground">{t('te.tpl.tablePreview')}</figcaption>
      {/* Geniş tablo shadcn Table'ın kendi kabında kayar (sayfa değil); tablo içeriği kadar geniş, en az kap kadar */}
      <div className="max-w-full rounded-md border" data-slot="te-tpl-table-scroll">
        <Table className="min-w-full text-xs" aria-label={title ? `${t('te.tpl.tablePreview')}: ${title}` : t('te.tpl.tablePreview')}>
          <TableHeader>
            <TableRow className="bg-foreground hover:bg-foreground">
              <TableHead scope="col" className="h-auto px-2 py-1.5 text-background"><span className="sr-only">{t('te.tbl.rowHeader')}</span></TableHead>
              {cols.map((c) => (
                <TableHead key={c.id} scope="col" className={cn('h-auto min-w-24 whitespace-normal px-2 py-1.5 font-semibold text-background [overflow-wrap:anywhere]',
                  c.type === 'text' ? 'text-left' : 'text-right')}>
                  {resolve(c.label) || '—'}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} className={cn(r.group && 'bg-muted font-semibold hover:bg-muted')}>
                <TableHead scope="row" className={cn('h-auto min-w-32 whitespace-normal px-2 py-1.5 text-left text-foreground [overflow-wrap:anywhere]',
                  r.group ? 'font-semibold' : 'font-normal', afterGroup.has(r.id) && 'ps-5')}>
                  {resolve(r.label) || '—'}
                </TableHead>
                {cols.map((c) => <TableCell key={c.id} className="px-2 py-1.5 text-right text-muted-foreground">—</TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </figure>
  )
}
