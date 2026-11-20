import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, CircleAlert, Eye, EyeOff, ImageIcon, Import, Search, StickyNote } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useLanguage } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../../ui/modalClasses.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Switch } from '@/components/shadcn/switch'
import { Skeleton } from '@/components/shadcn/skeleton'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { SeverityBadge, StatusBadge } from '../../incidenthistory/HistoryBadges.jsx'
import { RichText } from '../RichText.jsx'
import { formatCount, issueDayLabel, monthName } from '../techErrorModel.js'
import {
  CANDIDATE_SCAN_MAX, CANDIDATE_TABLE_MIN_PX, DEFAULT_PARTS, DESCRIPTION_PARTS, MAX_IMPORT, SEARCH_DEBOUNCE_MS, SEARCH_MAX, candidatesFrom,
  failureMessage, failureRetryable, groupLabelKey, imageSkipKey, importCapacity, importFailure, incidentTimeLabel, noteKey, orderParts,
  partLabelKey, proposalRows, proposalsFrom, selectionBlock,
} from './incidentImportModel.js'

/** Tekil / çoğul (projenin deseni: `<anahtar>.one` yalnız 1 için). */
const plural = (t, key, n, ...rest) => t(Number(n) === 1 ? `${key}.one` : key, ...rest)

/** Nötr tonlu (yeniden denemenin işe yaramadığı, "hata" olmayan) hata türleri. */
const NEUTRAL_KINDS = new Set(['unsupported', 'missing', 'forbidden'])
const NEUTRAL_TITLE = { unsupported: 'te.inc.unsupportedTitle', missing: 'te.inc.missingTitle', forbidden: 'te.inc.forbiddenTitle' }

/**
 * Okuma / önizleme hatasının görünümü (ASLA "olay yok"): tür (sunucu kodu → alan → durum), başlık, cümle (ham kod yok), ton ve
 * yeniden dene. `fallbackKey` — ağ hatası / türün kendi cümlesi olmayan beklenmedik hata için işlemin genel metni.
 */
function failureView(res, t, { title, fallbackKey }) {
  const f = importFailure(res)
  const msg = failureMessage(f)
  const neutral = NEUTRAL_KINDS.has(f.kind)
  const body = msg ? t(msg.key, ...msg.params)
    : f.kind === 'error' && typeof res?.error === 'string' && res.error.trim() ? res.error : t(fallbackKey)
  // Okumada 'sonucu bilinmiyor' = ağ hatası (yazma yok); data-kind bunu söyler
  return { kind: f.kind === 'unsure' ? 'network' : f.kind, title: neutral ? t(NEUTRAL_TITLE[f.kind]) : title, body,
    tone: neutral ? 'neutral' : 'danger', retry: failureRetryable(f.kind) }
}

/** Adayın zamanı: "Başladı: gg.aa.yyyy SS:dd" / "Bitti: …" ya da "sürüyor". */
function WhenText({ row, uiLang }) {
  const t = useT()
  const start = incidentTimeLabel(row.occurredAt, uiLang)
  const end = incidentTimeLabel(row.resolvedAt, uiLang)
  return (
    <span className="flex min-w-0 flex-col gap-0.5 text-sm tabular-nums">
      <span>{start ? t('te.inc.started', start) : t('te.inc.startUnknown')}</span>
      <span className="text-muted-foreground">{end ? t('te.inc.ended', end) : t('te.inc.ongoing')}</span>
    </span>
  )
}

/** Adayın sayısal bilgileri (hata adedi, kesinti, müşteri) — bilinmeyen değer yazılmaz. */
function FactsText({ row, uiLang }) {
  const t = useT()
  const parts = []
  if (row.techErrorCount != null) parts.push(plural(t, 'te.inc.fact.count', row.techErrorCount, formatCount(row.techErrorCount, uiLang)))
  if (row.outageRelated === true) parts.push(t('te.inc.fact.outageYes'))
  else if (row.outageRelated === false) parts.push(t('te.inc.fact.outageNo'))
  if (row.affectedCustomers != null) parts.push(plural(t, 'te.inc.fact.customers', row.affectedCustomers, formatCount(row.affectedCustomers, uiLang)))
  if (!parts.length) return <span className="text-sm text-muted-foreground">{t('te.inc.fact.none')}</span>
  return <span className="text-sm [overflow-wrap:anywhere]">{parts.join(' · ')}</span>
}

/**
 * Seçim sınırının GÖRÜNÜR açıklamasının kimliği (inceleme 2026-11-20: devre dışı onay kutusu nedenini yalnız `title` ile
 * söylüyordu — dokunmatikte ve ekran okuyucuda görünmüyordu). Aynı anda tek biri çizilir: bölüm dolu, seçim fazla ya da
 * seçim sınıra ulaştı notu; devre dışı kutular `aria-describedby` ile ona bağlanır. Zaten bu raporda olan satırın nedeni
 * kendi "Bu raporda" rozetidir.
 */
const LIMIT_NOTE_ID = 'te-inc-limit-note'
const importedBadgeId = (id) => `te-inc-imp-${id}`

/**
 * OLAY KAYDINDAN KONU AL penceresi (2026-11-20). İki adım:
 * <ol>
 *   <li><b>Seç</b> — kanalın takımına ait, raporun ayında başlayan olay kayıtları (arama 300 ms gecikmeli, sıra korumalı;
 *   sunucu sayfalaması `modal` ön ayarı; "önceki aydan süren" anahtarı). En çok 10 olay (ve bölümde kalan yer kadar); bu
 *   raporda zaten bağlı olanlar rozetli ve seçilemez. Açıklamaya alınacak bölümler ve "Görselleri de al".</li>
 *   <li><b>Önizle</b> — her olay için neyin DOLACAĞI, neyin boş kalacağı ve sunucunun her notu düz cümleyle; içe alma
 *   düğmesi.</li>
 * </ol>
 * Okuma hatası "olay yok" demez (tehlike bloğu + yeniden dene). Yazmayı (önce kaydet, sürüm, yanıtı uygulama) düzenleyici
 * yapar: `onImport(body)` → `{ ok, closed?, kind?, message?, incidentId? }`.
 *
 * Props: `sections` — anomali bölümleri `[{ id, title, opts }]` (başlık çözülmüş, `opts` = issueOptions), `counts` — bölüm
 * başına konu sayısı, `importedIds` — bu raporda bağlı olay kimlikleri (sunucunun `imported` bayrağına ek), `period`.
 */
export default function IncidentImportDialog({ open, reportId, sections, initialSectionId, counts, importedIds, period, onClose, onImport,
  onReportStale = null }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [step, setStep] = useState('pick')
  const [sectionId, setSectionId] = useState(initialSectionId || sections?.[0]?.id || null)
  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
  const [overlap, setOverlap] = useState(false)
  const [list, setList] = useState({ loading: true, rows: null, failure: null, res: null, period: null, truncated: false })
  const [reloadKey, setReloadKey] = useState(0)
  const [selected, setSelected] = useState(() => new Map())   // id → aday satırı (sayfalar arası korunur)
  const [localImported, setLocalImported] = useState(() => new Set())
  const [parts, setParts] = useState(() => new Set(DEFAULT_PARTS))
  const [images, setImages] = useState(true)
  const [preview, setPreview] = useState({ loading: false, proposals: null, failure: null, res: null })
  const [busy, setBusy] = useState(false)
  const [importError, setImportError] = useState(null)
  // Görselli içe alma meşgul (429 IMPORT_BUSY): nötr "N sn sonra yeniden deneyin"; düğme sunucunun söylediği süre kadar
  // bekler, sonra kendiliğinden açılır (otomatik yeniden deneme YOK). Tek zamanlayıcı, süre ≤ 600 sn, kapanışta temizlenir.
  const [busyWait, setBusyWait] = useState(null)   // { seconds, cooling }
  const busyTimer = useRef(null)
  const [listRef, listWidth] = useElementWidth()
  const seq = useRef(0)
  const previewSeq = useRef(0)
  const titleId = 'te-inc-dialog-desc'

  const clearBusyWait = useCallback(() => {
    clearTimeout(busyTimer.current)
    busyTimer.current = null
    setBusyWait(null)
  }, [])
  useEffect(() => () => clearTimeout(busyTimer.current), [])

  const section = (sections || []).find((s) => s.id === sectionId) || sections?.[0] || null
  const capacity = importCapacity(counts?.[section?.id] ?? 0)
  const allowImages = !!section?.opts?.allowImages && !section?.opts?.legacy
  const selectedIds = useMemo(() => new Set(selected.keys()), [selected])
  const overCapacity = selected.size > capacity
  // Seçim sınıra ULAŞTI (fazla değil): bilgi satırı — "seçimi azaltın" değil, "başkasını seçmek için birini bırakın"
  const limit = Math.min(MAX_IMPORT, capacity)
  const atLimit = capacity > 0 && !overCapacity && selected.size > 0 && selected.size >= limit
  const limitKind = capacity < MAX_IMPORT ? 'capacity' : 'max'

  // Arama gecikmeli (her tuşta istek gitmez); sınır sunucununkiyle aynı
  useEffect(() => {
    const h = setTimeout(() => setQ(qInput.trim().slice(0, SEARCH_MAX)), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(h)
  }, [qInput])

  const sp = useServerPagination({ listKey: 'te-inc-candidates', preset: 'modal', resetDeps: [q, overlap], apiBase: 0 })
  const { bind } = sp
  // Süzgeç değişince eski liste hemen kalkar (yeni sorgunun yanıtı gelene dek iskelet); sayfa değişince eski sayfa kalır
  const filterKey = `${q}\u0001${overlap}`
  const lastFilter = useRef(filterKey)

  useEffect(() => {
    if (!open || !reportId) return undefined
    const my = ++seq.current
    const ac = new AbortController()
    const filterChanged = lastFilter.current !== filterKey
    lastFilter.current = filterKey
    setList((s) => ({ ...s, loading: true, failure: null, rows: filterChanged ? null : s.rows }))
    api.techErrors.incidentCandidates(reportId, { q, page: sp.apiPage, size: sp.pageSize, overlap }, { signal: ac.signal })
      .then((res) => {
        if (my !== seq.current) return
        const parsed = candidatesFrom(res)
        if (parsed) {
          bind(res)
          setList({ loading: false, rows: parsed.items, failure: null, res: null, period: parsed.period, truncated: parsed.truncated })
        } else {
          setList((s) => ({ ...s, loading: false, failure: true, res: res?.success ? { status: 500 } : res }))
        }
      })
      .catch(() => {
        if (my !== seq.current) return
        setList((s) => ({ ...s, loading: false, failure: true, res: null }))
      })
    return () => ac.abort()
  }, [open, reportId, q, overlap, sp.apiPage, sp.pageSize, reloadKey, bind, filterKey])

  const isImported = useCallback((row) => row.imported || localImported.has(row.id) || !!importedIds?.has?.(row.id),
    [localImported, importedIds])

  // Arada bu rapora alınmış olaylar seçimden düşer (yeniden okunan listenin `imported` bayrağı, güncellenen bağ listesi) —
  // seçili kalsaydı devre dışı ama işaretli görünür ve istek gövdesine girerdi
  useEffect(() => {
    setSelected((m) => {
      if (!m.size) return m
      const rowsById = new Map((list.rows || []).map((r) => [r.id, r]))
      let next = m
      for (const id of m.keys()) {
        const row = rowsById.get(id) || m.get(id)
        if (row?.imported || localImported.has(id) || importedIds?.has?.(id)) {
          if (next === m) next = new Map(m)
          next.delete(id)
        }
      }
      return next
    })
  }, [list.rows, localImported, importedIds])

  function toggle(row) {
    setImportError(null)
    clearBusyWait()
    setSelected((m) => {
      const next = new Map(m)
      if (next.has(row.id)) next.delete(row.id)
      else next.set(row.id, row)
      return next
    })
  }

  function togglePart(p, on) {
    setParts((s) => {
      const next = new Set(s)
      if (on) next.add(p)
      else next.delete(p)
      return next
    })
  }

  /** Olay bu rapora (başka bir yerde) alınmış: yerel "zaten bu raporda" işareti + seçimden düşürme. */
  const markImported = useCallback((id) => {
    setLocalImported((s) => new Set(s).add(id))
    setSelected((m) => {
      if (!m.has(id)) return m
      const next = new Map(m)
      next.delete(id)
      return next
    })
  }, [])

  /**
   * Olay arada bu rapora alındı (409 INCIDENT_ALREADY_IMPORTED — raporun sürümü ilerledi): seçime dönülür; numarası biliniyorsa
   * satır hemen "bu raporda" olur, bilinmiyorsa (eşzamanlı yarış) aday listesi yeniden okunur — sunucunun `imported` bayrağı
   * gösterir ve seçimden düşürür; düzenleyici raporu yeniden okur (bağ listesi ve sürüm). Aynı seçimle önizlemede kalınmaz.
   */
  const afterAlready = useCallback((incidentId, message) => {
    if (incidentId != null) markImported(incidentId)
    setImportError(message || (incidentId != null ? t('te.inc.err.already', incidentId) : t('te.inc.err.alreadyRace')))
    setStep('pick')
    setReloadKey((k) => k + 1)
    onReportStale?.()
  }, [markImported, t, onReportStale])

  const body = useMemo(() => ({
    section_id: section?.id || null,
    incident_ids: [...selected.keys()],
    parts: orderParts([...parts]),
    images: allowImages && images,
  }), [section?.id, selected, parts, allowImages, images])

  const loadPreview = useCallback(async () => {
    const my = ++previewSeq.current
    setPreview({ loading: true, proposals: null, failure: null, res: null })
    let res
    try {
      res = await api.techErrors.incidentImportPreview(reportId, body)
    } catch {
      res = null
    }
    if (my !== previewSeq.current) return
    const proposals = proposalsFrom(res)
    if (proposals) { setPreview({ loading: false, proposals, failure: null, res: null }); return }
    const bad = res?.success ? { status: 500 } : res
    const f = importFailure(bad)
    if (f.kind === 'already') {
      // Önizleme içe almanın doğrulamasını koşar: olay arada bu rapora alınmış (rapor sürümü ilerledi) — seçime dönülür
      setPreview({ loading: false, proposals: null, failure: null, res: null })
      afterAlready(f.incidentId)
      return
    }
    setPreview({ loading: false, proposals: null, failure: true, res: bad })
  }, [reportId, body, afterAlready])

  function goPreview() {
    if (!selected.size || overCapacity) return
    setImportError(null)
    clearBusyWait()
    setStep('preview')
    loadPreview()
  }

  function goBack() {
    previewSeq.current++
    setImportError(null)
    clearBusyWait()
    setStep('pick')
  }

  async function submit() {
    if (busy || busyWait?.cooling || !selected.size) return
    setBusy(true)
    setImportError(null)
    clearBusyWait()
    let out
    try {
      out = await onImport(body)
    } catch {
      out = { ok: false, message: t('te.inc.importFailed') }
    } finally {
      setBusy(false)
    }
    if (!out || out.ok || out.closed) return
    if (out.kind === 'busy') {
      // Meşgul: seçim ve önizleme kalır; nötr bilgi + düğme sunucunun süresi kadar bekler (en çok 600 sn), sonra açılır
      const seconds = Math.min(600, Math.max(1, Math.ceil(Number(out.retryAfter) || 5)))
      setBusyWait({ seconds, message: out.message || t('te.inc.err.busy', seconds), cooling: true })
      clearTimeout(busyTimer.current)
      busyTimer.current = setTimeout(() => {
        busyTimer.current = null
        setBusyWait((w) => (w ? { ...w, cooling: false } : w))
      }, seconds * 1000)
      return
    }
    if (out.kind === 'already') {
      // Olay arada başka bir pencerede bu rapora alındı: seçime dönülür, liste ve rapor yeniden okunur
      afterAlready(out.incidentId ?? null, out.message)
      return
    }
    // Seçimle ilgili ret (bölüm dolu, olay başka takımın / bulunamadı, içerik sınırı): seçimi değiştirmek için seçime dönülür
    if (out.back) setStep('pick')
    setImportError(out.message || t('te.inc.importFailed'))
  }

  const periodText = period ? `${monthName(period.month, uiLang)} ${period.year}` : ''
  const tableMode = listWidth === 0 || listWidth >= CANDIDATE_TABLE_MIN_PX
  const failure = list.failure ? failureView(list.res, t, { title: t('te.inc.listDownTitle'), fallbackKey: 'te.inc.loadFailed' }) : null
  const rows = list.rows || []

  const footer = step === 'pick' ? (
    <div className="flex w-full flex-wrap items-center justify-between gap-2">
      <p className="m-0 text-sm text-muted-foreground" role="status" aria-live="polite" data-slot="te-inc-selected">
        {selected.size ? plural(t, 'te.inc.selected', selected.size, selected.size) : t('te.inc.selectedNone')}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onClose}>{t('te.cancel')}</Button>
        <Button type="button" className="pointer-coarse:min-h-10" disabled={!selected.size || overCapacity || !section}
          onClick={goPreview} data-slot="te-inc-next">
          <Eye aria-hidden="true" /> {t('te.inc.preview')}
        </Button>
      </div>
    </div>
  ) : (
    <div className="flex w-full flex-wrap items-center justify-between gap-2">
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" disabled={busy} onClick={goBack} data-slot="te-inc-back">
        <ArrowLeft aria-hidden="true" /> {t('te.inc.back')}
      </Button>
      <Button type="button" className="pointer-coarse:min-h-10"
        disabled={busy || !!busyWait?.cooling || preview.loading || !preview.proposals || !selected.size}
        aria-describedby={busyWait ? 'te-inc-busy-note' : undefined} onClick={submit} data-slot="te-inc-import-confirm">
        <Import aria-hidden="true" /> {busy ? t('te.inc.importing') : plural(t, 'te.inc.import', selected.size, selected.size)}
      </Button>
    </div>
  )

  return (
    <ModalShell open={open} onClose={onClose} title={t('te.inc.title')} icon={Import} size="lg" scrollBody busy={busy}
      className={PHONE_FULLSCREEN} footer={footer}>
      <div className="flex min-w-0 flex-col gap-4" data-slot="te-inc-dialog" data-step={step}>
        <p id={titleId} className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">
          {step === 'pick' ? t('te.inc.desc', periodText) : t('te.inc.previewDesc')}
        </p>
        <p className="m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-xs text-muted-foreground [overflow-wrap:anywhere]"
          data-slot="te-inc-live-note">
          <StickyNote aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span>{t('te.inc.liveNote')}</span>
        </p>
        {importError && (
          <AlertBanner tone="danger" title={t('te.inc.importFailedTitle')} role="alert">
            <span data-slot="te-inc-import-error">{importError}</span>
          </AlertBanner>
        )}
        {busyWait && (
          // Hata değil: sunucu şu an görselli içe almayı sıraya alamıyor — seçim ve önizleme kalır, düğme süre dolunca açılır
          <AlertBanner tone="info" title={t('te.inc.busyTitle')} role="status">
            <span id="te-inc-busy-note" data-slot="te-inc-busy" data-cooling={busyWait.cooling ? 'true' : 'false'}>
              {busyWait.message}
              {!busyWait.cooling && <> {t('te.inc.busyReady')}</>}
            </span>
          </AlertBanner>
        )}

        {step === 'pick' ? (
          <>
            <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <label htmlFor="te-inc-search" className="text-sm font-medium">{t('te.inc.search')}</label>
                <InputGroup className="w-full pointer-coarse:h-10">
                  <InputGroupInput id="te-inc-search" type="search" value={qInput} maxLength={SEARCH_MAX}
                    onChange={(e) => setQInput(e.target.value)} placeholder={t('te.inc.searchPh')}
                    className="pointer-coarse:h-10" />
                  <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                </InputGroup>
              </div>
              {(sections || []).length > 1 && (
                <div className="flex min-w-0 flex-col gap-1.5 sm:w-72">
                  <label htmlFor="te-inc-section" className="text-sm font-medium">{t('te.inc.section')}</label>
                  <NativeSelect id="te-inc-section" value={section?.id || ''} className="w-full"
                    onChange={(e) => { setSectionId(e.target.value); setImportError(null) }}>
                    {sections.map((s) => <NativeSelectOption key={s.id} value={s.id}>{s.title || s.id}</NativeSelectOption>)}
                  </NativeSelect>
                </div>
              )}
            </div>
            <div className="flex items-start gap-3">
              <Switch id="te-inc-overlap" checked={overlap} onCheckedChange={(c) => setOverlap(c === true)}
                aria-describedby="te-inc-overlap-h" className="mt-0.5" />
              <div className="flex min-w-0 flex-col gap-0.5">
                <label htmlFor="te-inc-overlap" className="text-sm font-medium leading-snug">{t('te.inc.overlap')}</label>
                <p id="te-inc-overlap-h" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.inc.overlapHint')}</p>
              </div>
            </div>
            {capacity <= 0 && (
              <AlertBanner tone="warning" title={t('te.inc.fullTitle')}>
                <span id={LIMIT_NOTE_ID} data-slot="te-inc-limit" data-kind="full">{t('te.inc.full')}</span>
              </AlertBanner>
            )}
            {overCapacity && capacity > 0 && (
              <AlertBanner tone="warning" title={t('te.inc.fullTitle')} role="alert">
                <span id={LIMIT_NOTE_ID} data-slot="te-inc-limit" data-kind="over">{plural(t, 'te.inc.capacityReached', capacity, capacity)}</span>
              </AlertBanner>
            )}
            {atLimit && (
              <p className="m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-sm text-muted-foreground [overflow-wrap:anywhere]"
                role="status" data-slot="te-inc-limit" data-kind={limitKind}>
                <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span id={LIMIT_NOTE_ID}>
                  {limitKind === 'max' ? t('te.inc.limitMax', MAX_IMPORT) : plural(t, 'te.inc.limitCapacity', capacity, capacity)}
                </span>
              </p>
            )}

            <div ref={listRef} className="flex min-w-0 flex-col gap-3" data-slot="te-inc-list" data-layout={tableMode ? 'table' : 'cards'}
              aria-busy={list.loading || undefined}>
              {failure ? (
                <div data-slot="te-inc-list-failed" data-kind={failure.kind}>
                  <StatusBlock tone={failure.tone} icon={CircleAlert} title={failure.title} description={failure.body}
                    role={failure.tone === 'danger' ? 'alert' : undefined}
                    actions={failure.retry ? (
                      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={() => setReloadKey((k) => k + 1)}
                        data-slot="te-inc-retry">{t('te.retry')}</Button>
                    ) : null} />
                </div>
              ) : list.rows == null ? (
                <div className="flex flex-col gap-2" role="status" aria-live="polite">
                  <span className="sr-only">{t('te.inc.loading')}</span>
                  <Skeleton className="h-14 w-full rounded-lg motion-reduce:animate-none" />
                  <Skeleton className="h-14 w-full rounded-lg motion-reduce:animate-none" />
                  <Skeleton className="h-14 w-full rounded-lg motion-reduce:animate-none" />
                </div>
              ) : rows.length === 0 ? (
                <p className="m-0 rounded-md border border-dashed p-3 text-sm text-muted-foreground [overflow-wrap:anywhere]"
                  data-slot="te-inc-empty" role="status">
                  {q ? t('te.inc.emptySearch') : overlap ? t('te.inc.emptyOverlap') : t('te.inc.empty')}
                </p>
              ) : tableMode ? (
                <CandidateTable rows={rows} selected={selectedIds} capacity={capacity} isImported={isImported} onToggle={toggle} uiLang={uiLang} />
              ) : (
                <CandidateCards rows={rows} selected={selectedIds} capacity={capacity} isImported={isImported} onToggle={toggle} uiLang={uiLang} />
              )}
              {list.rows != null && !failure && list.truncated && (
                <p className="m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-xs text-muted-foreground [overflow-wrap:anywhere]"
                  data-slot="te-inc-truncated">
                  <CircleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                  <span>{t('te.inc.truncated', formatCount(CANDIDATE_SCAN_MAX, uiLang))}</span>
                </p>
              )}
              {list.rows != null && !failure && <PaginationBar {...sp.bar} />}
              <p className="sr-only" role="status" aria-live="polite">
                {list.rows != null && !list.loading && !failure ? plural(t, 'te.inc.found', sp.total ?? rows.length, sp.total ?? rows.length) : ''}
              </p>
            </div>

            <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0" data-slot="te-inc-parts">
              <legend className="mb-1 p-0 text-sm font-medium">{t('te.inc.parts')}</legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {DESCRIPTION_PARTS.map((p) => (
                  <div key={p} className="flex min-h-10 items-center gap-2.5">
                    <Checkbox id={`te-inc-part-${p}`} checked={parts.has(p)} onCheckedChange={(c) => togglePart(p, c === true)} />
                    <label htmlFor={`te-inc-part-${p}`} className="text-sm leading-snug">{t(partLabelKey(p))}</label>
                  </div>
                ))}
              </div>
              {parts.size === 0 && <p className="m-0 text-xs text-muted-foreground">{t('te.inc.partsNone')}</p>}
            </fieldset>
            {allowImages && (
              <div className="flex items-start gap-2.5" data-slot="te-inc-images">
                <Checkbox id="te-inc-images" className="mt-0.5" checked={images} onCheckedChange={(c) => setImages(c === true)}
                  aria-describedby="te-inc-images-h" />
                <div className="flex min-w-0 flex-col gap-0.5">
                  <label htmlFor="te-inc-images" className="text-sm font-medium leading-snug">{t('te.inc.images')}</label>
                  <p id="te-inc-images-h" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.inc.imagesNote')}</p>
                </div>
              </div>
            )}
          </>
        ) : (
          <PreviewStep state={preview} section={section} uiLang={uiLang} imagesWanted={body.images} onRetry={loadPreview} />
        )}
      </div>
    </ModalShell>
  )
}

function SelectCell({ row, selected, capacity, isImported, onToggle }) {
  const t = useT()
  const imported = isImported(row)
  const block = imported ? 'imported' : selectionBlock(row, selected, { capacity })
  // Neden GÖRÜNÜR metinde: bu raporda olan satırın rozeti ya da sınır notu (title yalnız fareyle görünürdü)
  const reasonId = block === 'imported' ? importedBadgeId(row.id) : block ? LIMIT_NOTE_ID : undefined
  return (
    <Checkbox checked={selected.has(row.id)} disabled={!!block} onCheckedChange={() => onToggle(row)}
      aria-label={t('te.inc.selectRow', row.title || `#${row.id}`)} aria-describedby={reasonId}
      data-inc-check={row.id} data-block={block || undefined} />
  )
}

function TitleCell({ row, isImported }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <span className="font-medium [overflow-wrap:anywhere]">{row.title || t('te.inc.untitled')}</span>
      <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <span className="tabular-nums">#{row.id}</span>
        {isImported(row) && (
          <Badge id={importedBadgeId(row.id)} variant="secondary" className="rounded-md overflow-visible whitespace-normal" data-slot="te-inc-imported">
            {t('te.inc.imported')}
          </Badge>
        )}
      </span>
    </span>
  )
}

/** Aday tablosu (kap ≥ 640 px): yüzde kolonlar, adlar sarar. */
function CandidateTable({ rows, selected, capacity, isImported, onToggle, uiLang }) {
  const t = useT()
  return (
    <Table className="table-fixed" data-slot="te-inc-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[8%]"><span className="sr-only">{t('te.inc.colSelect')}</span></TableHead>
          <TableHead className="w-[38%] whitespace-normal">{t('te.inc.colTitle')}</TableHead>
          <TableHead className="w-[24%] whitespace-normal">{t('te.inc.colWhen')}</TableHead>
          <TableHead className="w-[30%] whitespace-normal">{t('te.inc.colFacts')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.id} data-slot="te-inc-row" data-incident={row.id} data-imported={isImported(row) ? 'true' : undefined}
            data-selected={selected.has(row.id) ? 'true' : undefined}>
            <TableCell className="align-top">
              <SelectCell row={row} selected={selected} capacity={capacity} isImported={isImported} onToggle={onToggle} />
            </TableCell>
            <TableCell className="whitespace-normal align-top"><TitleCell row={row} isImported={isImported} /></TableCell>
            <TableCell className="whitespace-normal align-top"><WhenText row={row} uiLang={uiLang} /></TableCell>
            <TableCell className="whitespace-normal align-top">
              <span className="flex min-w-0 flex-col gap-1.5">
                <span className="flex flex-wrap gap-1"><StatusBadge status={row.status} /><SeverityBadge severity={row.severity} /></span>
                <FactsText row={row} uiLang={uiLang} />
              </span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** Aday kartları (dar kap — telefon): onay kutusu + başlık satırın tamamı. */
function CandidateCards({ rows, selected, capacity, isImported, onToggle, uiLang }) {
  return (
    <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="te-inc-cards">
      {rows.map((row) => (
        <li key={row.id} className={cn('flex gap-3 rounded-lg border p-3', selected.has(row.id) && 'border-primary bg-primary/5')}
          data-slot="te-inc-row" data-incident={row.id} data-imported={isImported(row) ? 'true' : undefined}
          data-selected={selected.has(row.id) ? 'true' : undefined}>
          <div className="pt-0.5">
            <SelectCell row={row} selected={selected} capacity={capacity} isImported={isImported} onToggle={onToggle} />
          </div>
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <TitleCell row={row} isImported={isImported} />
            <span className="flex flex-wrap gap-1"><StatusBadge status={row.status} /><SeverityBadge severity={row.severity} /></span>
            <WhenText row={row} uiLang={uiLang} />
            <FactsText row={row} uiLang={uiLang} />
          </div>
        </li>
      ))}
    </ul>
  )
}

/** Önizleme adımı: olay başına dolacak / boş kalacak alanlar, açıklama önizlemesi, notlar, görseller. */
function PreviewStep({ state, section, uiLang, imagesWanted, onRetry }) {
  const t = useT()
  if (state.loading || (!state.proposals && !state.failure)) {
    return (
      <div className="flex flex-col gap-2" role="status" aria-live="polite" data-slot="te-inc-preview-loading">
        <span className="sr-only">{t('te.inc.previewLoading')}</span>
        <Skeleton className="h-40 w-full rounded-lg motion-reduce:animate-none" />
        <Skeleton className="h-40 w-full rounded-lg motion-reduce:animate-none" />
      </div>
    )
  }
  if (state.failure) {
    const v = failureView(state.res, t, { title: t('te.inc.previewFailedTitle'), fallbackKey: 'te.inc.previewFailed' })
    return (
      <div data-slot="te-inc-preview-failed" data-kind={v.kind}>
        <StatusBlock tone={v.tone} icon={CircleAlert} role={v.tone === 'danger' ? 'alert' : undefined} title={v.title}
          description={v.retry || v.tone === 'neutral' ? v.body : `${v.body} ${t('te.inc.previewBackHint')}`}
          actions={v.retry ? (
            <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onRetry} data-slot="te-inc-preview-retry">
              {t('te.retry')}
            </Button>
          ) : null} />
      </div>
    )
  }
  const opts = section?.opts || {}
  return (
    <ol className="m-0 flex list-none flex-col gap-3 p-0" data-slot="te-inc-preview">
      {state.proposals.map((p) => (
        <ProposalCard key={p.incidentId} proposal={p} opts={opts} uiLang={uiLang} imagesWanted={imagesWanted} />
      ))}
    </ol>
  )
}

function ProposalCard({ proposal: p, opts, uiLang, imagesWanted }) {
  const t = useT()
  const legacy = !!opts.legacy
  // Bölüm sormuyorsa grup hiç gösterilmez (sunucu da yazmaz — conform); etki sorulmuyorsa yalnız müşteri sayısı
  const asked = (g) => {
    if (g === 'date' || g === 'time') return !legacy && !!opts.askTime
    if (g === 'outage') return !legacy && !!opts.askOutage
    return true
  }
  const rows = proposalRows(p).filter((r) => asked(r.group))
  const valueText = (r) => {
    switch (r.group) {
      case 'topic': return String(r.value)
      case 'date': return issueDayLabel(r.value, uiLang) || String(r.value)
      case 'time': return `${r.value.start}–${r.value.end}${r.value.nextDay ? ` (${t('te.inc.pv.nextDay')})` : ''}`
      case 'errors': return formatCount(r.value, uiLang)
      case 'outage': return r.value.outage ? (r.value.id ? t('te.inc.pv.outageYes', r.value.id) : t('te.inc.pv.outageYesNoId')) : t('te.inc.pv.outageNo')
      case 'impact': {
        const n = r.value.customers
        if (!legacy && opts.askImpact) {
          if (r.value.impact === true) return n != null ? plural(t, 'te.inc.pv.impactYes', n, formatCount(n, uiLang)) : t('te.inc.pv.impactYesNoCount')
          if (r.value.impact === false) return t('te.inc.pv.impactNo')
        }
        return n != null ? plural(t, 'te.inc.pv.customers', n, formatCount(n, uiLang)) : t('te.inc.pv.notFilled')
      }
      default: return ''
    }
  }
  return (
    <li className={cn('flex min-w-0 flex-col gap-3 rounded-lg border p-3 sm:p-4', p.hidden && 'border-dashed')}
      data-slot="te-inc-proposal" data-incident={p.incidentId} data-hidden={p.hidden ? 'true' : undefined}>
      <div className="flex min-w-0 flex-col gap-0.5">
        <h3 className="m-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere] sm:text-base">{p.title || t('te.inc.untitled')}</h3>
        <span className="text-xs text-muted-foreground tabular-nums">{t('te.inc.incidentNo', p.incidentId)}</span>
      </div>
      {p.hidden && (
        <p className="m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-sm text-amber-900 dark:text-amber-200"
          data-slot="te-inc-pv-hidden">
          <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('te.inc.pv.hidden')}</span>
        </p>
      )}
      <dl className="m-0 grid min-w-0 gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,10rem)_minmax(0,1fr)]">
        {rows.filter((r) => r.group !== 'description').map((r) => (
          <div key={r.group} className="contents" data-slot="te-inc-pv-row" data-group={r.group} data-filled={r.filled ? 'true' : 'false'}>
            <dt className="text-sm font-medium text-muted-foreground">
              {r.group === 'impact' && (legacy || !opts.askImpact) ? t('te.inc.grp.customers') : t(groupLabelKey(r.group))}
            </dt>
            <dd className={cn('m-0 min-w-0 text-sm [overflow-wrap:anywhere]', !r.filled && 'text-muted-foreground')}>
              {r.filled ? valueText(r) : t('te.inc.pv.notFilled')}
            </dd>
          </div>
        ))}
      </dl>
      <div className="flex min-w-0 flex-col gap-1.5" data-slot="te-inc-pv-desc">
        <p className="m-0 text-sm font-medium text-muted-foreground">{t('te.inc.grp.description')}</p>
        {p.description
          ? <RichText text={p.description} className="rounded-md border bg-muted/30 p-2.5 text-sm" slot="te-inc-pv-desc-text" />
          : <p className="m-0 text-sm text-muted-foreground">{t('te.inc.pv.descEmpty')}</p>}
      </div>
      {(imagesWanted || p.images.allowed === false) && (
        <div className="flex min-w-0 flex-col gap-1" data-slot="te-inc-pv-images" data-allowed={p.images.allowed ? 'true' : 'false'}>
          {p.images.allowed ? (
            <>
              <p className="m-0 flex items-start gap-1.5 text-sm">
                <ImageIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <span className="[overflow-wrap:anywhere]">{plural(t, 'te.inc.pv.images', p.images.copy, p.images.copy)}</span>
              </p>
              {p.images.copy > 0 && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.inc.pv.imagesEstimate')}</p>}
              {(p.images.skipped.length > 0 || p.images.remaining > 0) && (
                <ul className="m-0 flex list-disc flex-col gap-0.5 ps-5 text-xs text-muted-foreground">
                  {p.images.skipped.map((r, i) => (
                    <li key={`${r}-${i}`} className="[overflow-wrap:anywhere]" data-slot="te-inc-pv-img-skip" data-reason={r}>
                      {p.images.skippedIds[i] != null ? t('te.inc.pv.imageSkippedNo', p.images.skippedIds[i], t(imageSkipKey(r))) : t(imageSkipKey(r))}
                    </li>
                  ))}
                  {p.images.remaining > 0 && (
                    <li className="[overflow-wrap:anywhere]" data-slot="te-inc-pv-img-remaining">
                      {plural(t, 'te.inc.pv.imagesRemaining', p.images.remaining, p.images.remaining)}
                    </li>
                  )}
                </ul>
              )}
            </>
          ) : (
            <p className="m-0 flex items-start gap-1.5 text-sm text-muted-foreground">
              <ImageIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className="[overflow-wrap:anywhere]">{t('te.inc.pv.imagesNotAllowed')}</span>
            </p>
          )}
        </div>
      )}
      {p.notes.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1" data-slot="te-inc-pv-notes">
          <p className="m-0 text-sm font-medium">{t('te.inc.notesLabel')}</p>
          <ul className="m-0 flex list-disc flex-col gap-1 ps-5 text-sm">
            {p.notes.map((code) => <li key={code} data-note={code} className="[overflow-wrap:anywhere]">{t(noteKey(code))}</li>)}
          </ul>
        </div>
      )}
    </li>
  )
}
