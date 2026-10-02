import { useState, useEffect, useCallback, useMemo, useRef, useId } from 'react'
import {
  AlertTriangle, ArrowLeft, ArrowRightLeft, Bell, CheckCircle, ChevronDown, ChevronLeft, ChevronRight, Clock,
  FileChartColumn, FilePenLine, HelpCircle, History, Lock, Mail, MoreHorizontal, Plus, Printer, RefreshCcw,
  Save, Send, Sparkles, Trash2, Undo2, Eye, CalendarDays, Hourglass,
} from 'lucide-react'
import WeeklyKpiStrip from './WeeklyKpiStrip.jsx'
import WeeklySummaryBrief from './WeeklySummaryBrief.jsx'
import WeeklyMonitoringStrip from './WeeklyMonitoringStrip.jsx'
import { api, formatDate } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import WeekDatePicker from './ui/WeekDatePicker.jsx'
import { isoWeekInfo, isEditableWeek, formatWeekRange } from '../utils/isoWeek'
import { mailPreviewSrcDoc } from '../utils/mailPreview.js'
import { LoadingBlock } from './ui/Progress.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import PageHeader from './ui/PageHeader.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import WeeklyCompletionBoard from './WeeklyCompletionBoard.jsx'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import WeeklyThisWeekStrip from './weekly/WeeklyThisWeekStrip.jsx'
import WeeklyComments from './weekly/WeeklyComments.jsx'
import WeeklyReminderStatus from './weekly/WeeklyReminderStatus.jsx'
import WeeklyReportList, { WeeklyListToolbar } from './weekly/WeeklyReportList.jsx'
import WeeklyReportCreate from './weekly/WeeklyReportCreate.jsx'
import WeeklyExportMenu from './weekly/WeeklyExportMenu.jsx'
import LinkField from './weekly/WeeklyLinkField.jsx'
import { MdField, NumInput } from './weekly/WeeklyMdField.jsx'
import { DomainWorkEditor, DomainWorkView } from './weekly/WeeklyDomainSection.jsx'
import {
  CompactProgress, EditorOutline, ReportDetailsCard, SaveState, SectionCard, ValidationSummary, sectionTitle,
} from './weekly/WeeklyEditorParts.jsx'
import {
  sectionOutline, reportIssues, normaliseContent, statusCounts, statusMismatch, statusSum, STATUS_COUNT_KEYS,
} from './weekly/editorModel.js'
import { downloadCsv } from './weekly/yearSummaryActions.js'
import {
  WeeklyStatusChips, WeeklyStatusBadge, statusLabel, SuggestBadge, DeltaBadge, PrevNoteToggle,
} from './weekly/WeeklyListExtras.jsx'
import {
  statusFacets, approvalQueue, filterByStatus, sortReports, parsePrevContent, buildYearCsv, toUrlMapping, isoWeekLabel,
  matchesSearch, thisWeekSummary,
} from './weekly/weeklyModel.js'
import { useElementWidthState } from '../hooks/useElementWidth.js'
import ModalShell from './ui/ModalShell.jsx'
import Field from './ui/Field.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Textarea } from '@/components/shadcn/textarea'
import { Collapsible, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

/** Oturum kesintisi yedekleri için localStorage anahtar öneki. */
const DRAFT_BACKUP_PREFIX = 'wr.draft.'


/**
 * Haftalık Raporlar (`?tab=weeklyreports`) — 2026-09-27 shadcn + mobil web yeniden tasarımı.
 *
 * Üç görünüm, tek sayfa durumu:
 *  - GİRİŞLER (liste): PageHeader (bu haftanın gönderim sayısı, geciken, son giriş geri sayımı, onayımı bekleyen;
 *    Yenile · Dışa aktar (liste CSV + yıl özeti yazdır/CSV) · Yeni Hafta Raporu) → "Bu hafta" (süzgeç kutucukları +
 *    takım satırları; yöneticide hatırlatma satırı) → takım tamamlama panosu (admin/AUDIT) → nasıl girilir →
 *    arama + takım/yıl/hafta süzgeçleri + durum çipleri + etkin süzgeç çipleri → tablo (dar kapta kart) + sayfalama.
 *  - YENİ RAPOR: yönlendirmeli ekran (weekly/WeeklyReportCreate) — hafta hızlı seçimleri, takım, başlangıç noktası.
 *  - DÜZENLEYİCİ: başlık (hafta aralığı, ISO etiket, takım, durum, kayıt durumu, önceki/sonraki hafta) → durum
 *    bantları → bölüm kartları (sayılar + fark rozetleri, takip bağlantıları ÇİP olarak, Markdown notlar) + sağ
 *    sütun (xl: ana hat, ayrıntılar, yorumlar; dar ekranda üstte ilerleme, altta ayrıntılar + yorumlar) → YAPIŞKAN
 *    eylem çubuğu (kayıt durumu + gönderim kontrol listesi · Diğer · Önizleme · Kaydet · Onaya Gönder / Onayla · İade Et).
 *
 * Sözleşmeler DEĞİŞMEDİ: tüm API çağrıları, yetki kuralları (canModifyRow / canDeleteRow — backend ile aynı), kilit +
 * kalp atışı + açılış yarışı koruması (R11), otomatik kayıt + yerel yedek, URL anahtarları (`w_*`, yeni `w_q`),
 * derin bağlantılar, yazdırma, yorumlar, hatırlatmalar, yıl özeti belgesi.
 */
export default function WeeklyReportsPage({ systemRole, teamId, teamName, resetNonce }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isAudit = systemRole === 'AUDIT'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const phone = useIsMobile()
  const summaryBodyId = useId()
  // Liste görünümü kaba göre: 768 px tablette kenar çubuğu açıkken içerik ~440 px → kart (ölçüm yoksa useIsMobile)
  const [listWidth, setListBox] = useElementWidthState()
  const narrow = listWidth > 0 ? listWidth < 640 : phone

  const [teams, setTeams] = useState([])
  // URL derin bağlantı (2026-09-13): w_team / team (bildirim kutusu), w_year, w_week, w_id, w_st, w_sort; w_q (2026-09-27)
  const [selTeamId, setSelTeamId] = useState(() => readUrlParam('w_team', null) || readUrlParam('team', null) || '')   // varsayılan: Tüm Takımlar
  const [year, setYear] = useState(() => readUrlInt('w_year', isoWeekInfo().year))
  const [years, setYears] = useState([])
  const [jumpDate, setJumpDate] = useState('')
  const [weekFilter, setWeekFilter] = useState(() => readUrlInt('w_week', null)) // "Haftaya git" → listeyi o haftaya daraltır
  const [q, setQ] = useState(() => readUrlParam('w_q', '') || '')
  const [reports, setReports] = useState([])
  const [loadingList, setLoadingList] = useState(true)
  const [selectedId, setSelectedId] = useState(() => readUrlInt('w_id', null))
  const [statusChip, setStatusChip] = useState(() => readUrlParam('w_st', '') || '')   // durum çipi / MINE (2026-09-13)
  const [listSort, setListSort] = useState(() => readUrlParam('w_sort', 'week|desc') || 'week|desc')
  const [thisWeek, setThisWeek] = useState(null)        // "bu hafta" bölümü (2026-09-13)
  const [completion, setCompletion] = useState(null)    // takım × hafta panosu + yıl özeti (admin/AUDIT)
  const [suggest, setSuggest] = useState(null)          // sistemden öneriler (detay, düzenlenebilirken)
  const [prevReport, setPrevReport] = useState(null)    // önceki hafta (Δ + not paneli)
  const [prevOpen, setPrevOpen] = useState({})          // bölüm → geçen haftanın notu açık mı
  const [helpOpen, setHelpOpen] = useState(() => {
    try { return localStorage.getItem('wr-help-open') === 'true' } catch { return false }
  })
  const [sendingReminder, setSendingReminder] = useState(false)
  const [report, setReport] = useState(null)      // full report (GET /{id})
  const [content, setContent] = useState(null)    // parsed content_json
  const [kpis, setKpis] = useState(null)          // executive KPI şeridi (GET /{id}/kpis) — canlı, read-only
  const [kpisLoading, setKpisLoading] = useState(false)
  const [summaryOpen, setSummaryOpen] = useState(false)  // Özet akordeonu — varsayılan KAPALI
  const [monStats, setMonStats] = useState(null)         // İzleme göstergeleri (lazy, akordeon açılınca)
  const [monLoading, setMonLoading] = useState(false)
  const [managerMissing, setManagerMissing] = useState(false)
  const [teamChannels, setTeamChannels] = useState([])     // takım kanal şablonu (2026-09-13, ikinci tur)
  const [reminderNonce, setReminderNonce] = useState(0)    // hatırlatma durumu satırını elle gönderim sonrası tazele
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [newReport, setNewReport] = useState(null)  // { year, week, teamId, carry } → "Yeni Hafta Raporu" ekranı
  const [rejectModal, setRejectModal] = useState(null) // { note, id? }
  const [previewHtml, setPreviewHtml] = useState(null)
  const [pendingBackup, setPendingBackup] = useState(null) // { content_json, saved_at }
  const [lastAutoSave, setLastAutoSave] = useState(null)
  const [lockHeld, setLockHeld] = useState(false)     // düzenleme kilidi bizde mi
  const [lockHolder, setLockHolder] = useState(null)  // { name } — başkasında ise
  const [conflict, setConflict] = useState(null)      // VERSION_CONFLICT mesajı
  const [loadingReport, setLoadingReport] = useState(false)
  const [mailHistory, setMailHistory] = useState(null) // { report, items } — gönderim geçmişi modal'ı
  const [openMailBody, setOpenMailBody] = useState(null) // içeriği açık olan kayıt id'si
  // Takım transferi (yalnız ADMIN): toplu seçim + tekil (kebab)
  const [selectedIds, setSelectedIds] = useState(() => new Set())
  const [transferModal, setTransferModal] = useState(null) // { ids: [...] } | null
  const [transferTeamId, setTransferTeamId] = useState('')
  const [transferring, setTransferring] = useState(false)

  const effTeamId = isAdmin ? (selTeamId ? Number(selTeamId) : null) : teamId
  // Düzenleme/silme: ADMIN her durum + her hafta; diğerleri kendi takımının
  // DRAFT/REJECTED raporunu yalnız mevcut + önceki ISO haftasında (backend kuralıyla aynı)
  const canModifyRow = (r) => isAdmin || (!isAudit
    && r.team_id === teamId
    && (r.status === 'DRAFT' || r.status === 'REJECTED')
    && isEditableWeek(r))
  // Silme: düzenleme yetkisine EK olarak yönetici rolü gerekir — salt USER (ve PO,
  // systemRole=USER) silemez; yalnız ADMIN ya da takımın TEAM_ADMIN'i (backend ile aynı).
  const canDeleteRow = (r) => (isAdmin || isTeamAdmin) && canModifyRow(r)
  // Düzenleme = yetki + kilit (kilit başkasındaysa salt-okunur)
  const editable = !!report && canModifyRow(report) && lockHeld
  const weekLocked = !!report && !isAdmin && !isAudit && report.team_id === teamId
    && (report.status === 'DRAFT' || report.status === 'REJECTED') && !isEditableWeek(report)
  const canSubmit = editable && report.status === 'DRAFT'
  const showApproval = !!report && report.status === 'PENDING_APPROVAL' && !isAudit
  // Gönderilmiş raporu tekrar işleme: Revize Et (taslağa döndür) / Tekrar Gönder
  const canReopen = !!report && report.status === 'APPROVED'
    && (isAdmin || (!isAudit && report.team_id === teamId && isEditableWeek(report)))
  const canResend = !!report && report.status === 'APPROVED' && !isAudit

  useEffect(() => {
    if (isAdmin) api.admin.getTeams().then((res) => { if (res?.success) setTeams(res.data ?? []) })
  }, [isAdmin])

  // Menüye tekrar tıklanınca (App.jsx wrResetNonce artar) açık rapor / yeni rapor ekranı varsa listeye dön.
  // backToList yeniden kullanılır → kaydedilmemiş değişiklik onayı + kilit bırakma korunur.
  useEffect(() => {
    if (resetNonce > 0 && selectedId != null) backToList()
    else if (resetNonce > 0) setNewReport(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetNonce])

  // Yıl seçici: rapor bulunan yıllar (geriye dönük erişim) — takım değişince yenilenir
  // Sıra korumaları (2026-09-27 regresyon taraması, DÜŞÜK): hızlı takım/yıl değişiminde geç gelen eski yanıt yeni
  // süzgecin üstüne yazılıyordu (B'nin süzgecinde A'nın raporları; yükleniyor bayrağı erken düşüyor; bayat seçim
  // kontrolü açık raporu kapatabiliyordu). Yalnız EN SON isteğin yanıtı uygulanır.
  const yearsSeq = useRef(0)
  const listSeq = useRef(0)
  const loadYears = useCallback(async () => {
    const seq = ++yearsSeq.current
    const res = await api.weeklyReports.years(effTeamId ?? undefined)
    if (seq !== yearsSeq.current) return
    if (res?.success) setYears(res.data ?? [])
  }, [effTeamId])

  useEffect(() => { loadYears() }, [loadYears])

  // Son giriş zamanı canlı ayardan (2026-09-12). Yüklenene/başarısız olana kadar varsayılan (Cuma 15:00).
  const [deadline, setDeadline] = useState(null)
  useEffect(() => {
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.deadline(); if (alive && r?.success && r.data) setDeadline(r.data) }
      catch { /* varsayılan kalır */ }
    })()
    return () => { alive = false }
  }, [])

  // Takım tamamlama panosu + yıl özeti (yalnız admin/AUDIT'e veri gelir) — sayfa çeker, pano ve Dışa aktar paylaşır.
  const [completionNonce, setCompletionNonce] = useState(0)
  useEffect(() => {
    if (!isAdmin && !isAudit) return undefined
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.completion(year); if (alive) setCompletion(r?.success && r.data ? r.data : null) }
      catch { /* pano süs */ }
    })()
    return () => { alive = false }
  }, [year, isAdmin, isAudit, completionNonce])

  // Takvimde raporlu haftaların işaretlenmesi — yıl başına Set cache'i
  const [weekMarks, setWeekMarks] = useState({})

  useEffect(() => { setWeekMarks({}) }, [effTeamId])

  const ensureMarks = useCallback(async (y) => {
    if (weekMarks[y]) return
    const res = await api.weeklyReports.list({ teamId: effTeamId ?? undefined, year: y })
    const s = new Set((res?.success ? res.data ?? [] : []).map((r) => r.week_no))
    setWeekMarks((p) => ({ ...p, [y]: s }))
  }, [effTeamId, weekMarks])

  /** Haftaya git: seçilen tarihin ISO haftasını listeye SÜZGEÇ olarak uygular — raporu otomatik açmaz; o haftanın
   *  kayıtlarını listeler (yoksa boş). Süzgeç çipiyle temizlenebilir. */
  function goToDate(dateStr) {
    setJumpDate(dateStr)
    if (!dateStr) { setWeekFilter(null); return }
    const picked = new Date(dateStr + 'T12:00:00')
    // Elle yazım sırasında oluşan ara değerler (örn. yıl "0002") tetiklemesin
    if (picked.getFullYear() < 2000 || picked.getFullYear() > 2100) return
    const { year: y, week } = isoWeekInfo(picked)
    setWeekFilter(week)
    if (y !== year) setYear(y)  // yıl efekti loadList'i tetikler (loading + fetch)
    else loadList()             // aynı yıl: yükleme göstergesiyle tazele, sonra süz
  }

  const loadThisWeek = useCallback(async () => {
    try { const r = await api.weeklyReports.thisWeek(); if (r?.success) setThisWeek(r.data ?? null) } catch { /* bölüm yoksa sayfa yine çalışır */ }
  }, [])

  const loadList = useCallback(async () => {
    const seq = ++listSeq.current
    const current = () => seq === listSeq.current
    setLoadingList(true)
    loadThisWeek()
    try {
      const res = await api.weeklyReports.list({ teamId: effTeamId ?? undefined, year })
      if (!current()) return   // daha yeni bir liste isteği var — bu yanıt bayat
      if (res?.success) {
        setReports(res.data ?? [])
        // Açık rapor (süzgeç değişimiyle) listeden kaybolduysa listeye dön
        if (selectedId && !res.data?.some((r) => r.id === selectedId)) {
          setSelectedId(null)
        }
      }
    } finally {
      if (current()) setLoadingList(false)
    }
  }, [effTeamId, year, selectedId, loadThisWeek])

  useEffect(() => { loadList() }, [effTeamId, year]) // eslint-disable-line react-hooks/exhaustive-deps

  // URL eşitleme (2026-09-13): süzgeç/açık rapor paylaşılabilir, F5 açık raporu korur
  useUrlQuerySync(toUrlMapping({ selectedId, selTeamId: isAdmin ? selTeamId : '', year, weekFilter, statusChip, sort: listSort, currentYear: isoWeekInfo().year, q }))

  // R11 (2026-09-25): açılış yarışı kilidi sızdırıyordu. Rapor yüklenirken listeye dönülünce lockHeld
  // henüz false olduğu için backToList kilidi bırakmıyor, geç gelen yanıt raporu yazıp kilidi alıyor ve
  // 45 sn'lik kalp atışı kullanıcı liste ekranındayken kilidi tazeliyordu ("X düzenliyor"). Artık her
  // açılış bir sıra numarası taşır; bayat yanıt hiçbir state'e yazmaz, bu arada alınmış kilidi BIRAKIR.
  const loadSeq = useRef(0)
  const loadTarget = useRef(null)   // şu an açılmak istenen rapor (liste/söküm → null)
  useEffect(() => () => { loadSeq.current++; loadTarget.current = null }, [])
  const loadReport = useCallback(async (id) => {
    const seq = ++loadSeq.current
    loadTarget.current = id || null
    if (!id) { setReport(null); setContent(null); setLockHeld(false); setLockHolder(null); setLoadingReport(false); return }
    setLoadingReport(true)
    try {
      await loadReportInner(id, seq)
    } finally {
      if (seq === loadSeq.current) setLoadingReport(false)
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function loadReportInner(id, seq) {
    const stale = () => seq !== loadSeq.current
    const res = await api.weeklyReports.get(id)
    if (stale()) return   // kullanıcı listeye döndü / başka rapora geçti / sayfa söküldü
    if (res?.success) {
      const r = res.data.report
      setReport({ ...r, images: res.data.images })
      setManagerMissing(!!res.data.manager_contact_missing)
      setTeamChannels(Array.isArray(res.data.team_channels) ? res.data.team_channels : [])
      // Durum dağılımı her zaman dört anahtarlı nesne (eski rapor: sıfırlar; status_text korunur) — 2026-09-27
      try { setContent(normaliseContent(JSON.parse(r.content_json))) } catch { setContent(null) }
      setDirty(false)
      setLastAutoSave(null)
      setConflict(null)
      // Düzenleme kilidi: yetkimiz varsa almayı dene; başkasındaysa salt-okunur bant
      setLockHeld(false)
      setLockHolder(res.data.lock_holder ?? null)
      if (canModifyRow(r)) {
        const lk = await api.weeklyReports.lock(r.id)
        if (stale()) {
          // Kilit bu arada alındıysa bırak — aynı raporu yeniden açan daha yeni bir yükleme yoksa
          // (o yükleme kilidi kendisi alır; burada bırakmak onun kilidini düşürürdü).
          if (lk?.success && lk.data?.acquired && String(loadTarget.current) !== String(r.id)) {
            api.weeklyReports.unlock(r.id)?.catch?.(() => { /* best-effort: kilit 3 dk'da bayatlar */ })
          }
          return
        }
        if (lk?.success && lk.data?.acquired) {
          setLockHeld(true)
          setLockHolder(null)
        } else if (lk?.success) {
          setLockHolder({ name: lk.data?.editing_by })
        }
      }
      // Oturum kesintisinden kalan yerel yedek var mı? (yalnız düzenlenebilirken anlamlı)
      try {
        const raw = localStorage.getItem(DRAFT_BACKUP_PREFIX + r.id)
        if (raw && canModifyRow(r)) {
          setPendingBackup(JSON.parse(raw))
        } else {
          if (raw) localStorage.removeItem(DRAFT_BACKUP_PREFIX + r.id)
          setPendingBackup(null)
        }
      } catch { setPendingBackup(null) }
    } else {
      // Rapor açılamadı (silinmiş/yetki/ağ) — boş ekranda bırakma, listeye dön
      toast.error(res?.error || t('wr.actionFailed'))
      setSelectedId(null)
    }
  }

  useEffect(() => { loadReport(selectedId) }, [selectedId, loadReport])

  // Executive KPI şeridi — rapor açıldığında canlı çekilir (durum makinesinden bağımsız, read-only).
  useEffect(() => {
    const rid = report?.id
    if (!rid) { setKpis(null); return }
    let alive = true
    setKpisLoading(true)
    api.weeklyReports.kpis(rid)
      .then((r) => { if (alive && r?.success) setKpis(r.data) })
      .catch(() => {})
      .finally(() => { if (alive) setKpisLoading(false) })
    return () => { alive = false }
  }, [report?.id])

  // Önceki hafta (Δ rozetleri + "geçen haftanın notu") ve sistemden öneriler (yalnız düzenlenebilirken) — 2026-09-13
  useEffect(() => {
    const rid = report?.id
    setPrevReport(null); setSuggest(null); setPrevOpen({})
    if (!rid) return
    let alive = true
    api.weeklyReports.previous(rid).then((r) => { if (alive && r?.success) setPrevReport(r.data ?? null) }).catch(() => {})
    if (canModifyRow(report)) {
      api.weeklyReports.suggestions(rid).then((r) => { if (alive && r?.success) setSuggest(r.data ?? null) }).catch(() => {})
    }
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report?.id])
  const prevContent = useMemo(() => parsePrevContent(prevReport?.content_json), [prevReport])

  // İzleme göstergeleri — AĞIR 7-tür toplama; yalnız akordeon İLK açıldığında çekilir (tek pod'u koru). Rapor değişince sıfırla.
  useEffect(() => { setMonStats(null) }, [report?.id])
  useEffect(() => {
    const rid = report?.id
    if (!summaryOpen || !rid || monStats || monLoading) return
    let alive = true
    setMonLoading(true)
    api.weeklyReports.monitoringStats(rid)
      .then((r) => { if (alive && r?.success) setMonStats(r.data) })
      .catch(() => {})
      .finally(() => { if (alive) setMonLoading(false) })
    return () => { alive = false }
  }, [summaryOpen, report?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  function patch(path, value) {
    setContent((prev) => {
      const next = structuredClone(prev)
      let obj = next
      for (let i = 0; i < path.length - 1; i++) obj = obj[path[i]]
      obj[path[path.length - 1]] = value
      return next
    })
    setDirty(true)
  }

  /** Madde 1 önem alanları — Toplam elle girilmez, dört alanın toplamından türetilir. */
  function patchSeverity(key, value) {
    setContent((prev) => {
      const next = structuredClone(prev)
      next.item1[key] = value
      next.item1.total = ['urgent', 'high', 'medium', 'low']
        .reduce((sum, k) => sum + (parseInt(next.item1[k], 10) || 0), 0)
      return next
    })
    setDirty(true)
  }

  async function save(showToast = true) {
    if (!report || !content) return false
    // Sürüm yarışı (2026-09-27 regresyon taraması, ORTA): kayıt uçuştayken ◀/▶ ya da otomatik kayıt başka rapora
    // geçerse, A'nın yeni sürümü/durumu B'ye yazılıyordu → B'nin sonraki kaydı sahte VERSION_CONFLICT. Yanıt yalnız
    // KAYDEDİLEN rapora uygulanır (rid); açık rapor değiştiyse kirli/çakışma bayrakları da ona dokunulmaz.
    const rid = report.id
    const stillOpen = () => liveRef.current.report?.id === rid
    setBusy(true)
    try {
      const res = await api.weeklyReports.save(rid, JSON.stringify(content), report.version)
      if (res?.success) {
        if (showToast) toast.success(t('wr.saved'))
        if (stillOpen()) setDirty(false)
        try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + rid) } catch { /* */ }
        setReport((p) => (p?.id === rid ? { ...p, status: res.data.status, version: res.data.version } : p))
        loadList()
        return true
      }
      if (res?.error?.includes('VERSION_CONFLICT')) {
        // Başka kullanıcı araya kaydetmiş — yazılanlar yerel yedekte; banner çözüm sunar
        if (stillOpen()) { writeBackupNow(); setConflict(res.error) }
        return false
      }
      toast.error(res?.error || t('wr.saveFailed'))
      return false
    } finally {
      setBusy(false)
    }
  }

  // ── Autosave + oturum kesintisi yedeği + kilit ────────────────────────────
  // Zamanlayıcılar/kapanış handler'ı güncel state'i bu ref üzerinden okur
  const liveRef = useRef({})
  liveRef.current = { report, content, dirty, editable, save, lockHeld, conflict }

  function writeBackupNow() {
    const { report: r, content: c, dirty: d, editable: e } = liveRef.current
    if (!r || !c || !d || !e) return
    try {
      localStorage.setItem(DRAFT_BACKUP_PREFIX + r.id, JSON.stringify({
        content_json: JSON.stringify(c),
        saved_at: Date.now(),
      }))
    } catch { /* localStorage dolu/kapalı — sessiz geç */ }
  }

  // İçerik değiştikçe 1.5 sn debounce ile yerel yedek — oturum düşse de yazılanlar kalır
  useEffect(() => {
    if (!dirty || !editable) return
    const id = setTimeout(writeBackupNow, 1500)
    return () => clearTimeout(id)
  }, [content, dirty, editable])

  // Sekme kapanırken / 401 oturum yönlendirmesinde: son hali yedekle + kilidi bırak
  useEffect(() => {
    const handleUnload = () => {
      writeBackupNow()
      const { report: r, lockHeld: held } = liveRef.current
      if (r && held) {
        try { navigator.sendBeacon(`/api/weekly-reports/${r.id}/unlock`) } catch { /* */ }
      }
    }
    window.addEventListener('beforeunload', handleUnload)
    return () => window.removeEventListener('beforeunload', handleUnload)
  }, [])

  // Component kapanırken kilidi bırak (terk edilen kilit 3 dk'da zaten bayatlar)
  useEffect(() => () => {
    const { report: r, lockHeld: held } = liveRef.current
    if (r && held) api.weeklyReports.unlock(r.id)
  }, [])

  // Sunucuya otomatik kayıt — kaydedilmemiş değişiklik varken 60 sn'de bir
  // (çakışma durumunda durur; kullanıcı banner'dan çözer)
  useEffect(() => {
    if (!editable || !dirty || conflict) return
    const id = setInterval(async () => {
      if (liveRef.current.conflict) return
      if (await liveRef.current.save?.(false)) setLastAutoSave(new Date())
    }, 60000)
    return () => clearInterval(id)
  }, [editable, dirty, conflict, report?.id])

  // Kilit kalp atışı — editör açık ve kilit bizdeyken 45 sn'de bir tazele;
  // kilit (ADMIN devralması ile) elden gittiyse salt-okunura düş
  useEffect(() => {
    if (!report?.id || !lockHeld) return
    const id = setInterval(async () => {
      const r = liveRef.current.report
      if (!r) return
      const res = await api.weeklyReports.lock(r.id)
      if (res?.success && !res.data?.acquired) {
        setLockHeld(false)
        setLockHolder({ name: res.data?.editing_by })
        toast.info(t('wr.lockLost'))
      }
    }, 45000)
    return () => clearInterval(id)
  }, [report?.id, lockHeld]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Mail gönderim geçmişi ────────────────────────────────────────────────

  /** sendHtml status string'i → rozet bilgisi. */
  function mailStatusInfo(s) {
    if (!s) return null
    if (s === 'SENT') return { label: t('wr.mailStatusSent'), cls: 'bg-success text-white' }
    if (s.startsWith('QUEUED_RETRY')) return { label: t('wr.mailStatusQueued'), cls: 'bg-amber-600 text-white' }
    if (s === 'SKIPPED_NO_CONTACT') return { label: t('wr.mailStatusNoContact'), cls: 'bg-destructive text-white' }
    if (s === 'SKIPPED_DISABLED') return { label: t('wr.mailStatusDisabled'), cls: 'bg-muted-foreground text-white' }
    return { label: t('wr.mailStatusFailed'), cls: 'bg-destructive text-white' } // FAILED:*
  }

  const mailProblem = (s) => !!s && (s.startsWith('FAILED') || s === 'SKIPPED_NO_CONTACT')

  async function openMailHistory(r) {
    const res = await api.weeklyReports.mails(r.id)
    if (res?.success) {
      setOpenMailBody(null)
      setMailHistory({ report: r, items: res.data ?? [] })
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  /** Kilit bandındaki "Yenile" — kilidi tekrar dene (rapor da tazelenir). */
  async function retryLock() {
    await loadReport(report.id)
  }

  /** ADMIN: tutulan kilidi zorla devral. */
  async function takeoverLock() {
    const ok = await showConfirm({
      title: t('wr.lockTakeover'),
      message: t('wr.lockTakeoverConfirm', lockHolder?.name ?? ''),
      variant: 'danger',
      confirmText: t('wr.lockTakeover'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    const res = await api.weeklyReports.lock(report.id, true)
    if (res?.success && res.data?.acquired) {
      setLockHeld(true)
      setLockHolder(null)
    } else {
      toast.error(res?.error || t('wr.actionFailed'))
    }
  }

  function restoreBackup() {
    try {
      setContent(normaliseContent(JSON.parse(pendingBackup.content_json)))
      setDirty(true)
      toast.success(t('wr.restored'))
    } catch {
      toast.error(t('wr.actionFailed'))
    }
    setPendingBackup(null)
  }

  function discardBackup() {
    try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + report.id) } catch { /* */ }
    setPendingBackup(null)
  }

  // Onaya gönder (2026-09-27): onay penceresi — kontrol listesinde madde varsa sayısını söyler (engellemez;
  // sunucu boş bölüme de izin verir). Kaydedilmemiş değişiklik önce kaydedilir.
  async function submit() {
    const pending = reportIssues(content).length
    const ok = await showConfirm({
      title: t('wr.submitConfirmTitle'),
      message: pending ? t('wr.submitConfirmWarn', pending) : t('wr.submitConfirmMsg'),
      confirmText: t('wr.submit'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    if (dirty && !(await save(false))) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.submit(report.id)
      if (res?.success) {
        toast.success(res.po_mail === 'SKIPPED_NO_CONTACT' ? t('wr.poMailSkipped') : t('wr.submitOk'))
        loadReport(report.id); loadList()
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  async function approve() {
    const ok = await showConfirm({
      title: t('wr.approveConfirmTitle'),
      message: t('wr.approveConfirmMsg'),
      confirmText: t('wr.approve'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.approve(report.id)
      if (res?.success) {
        toast.success(t('wr.approveOk'))
        loadReport(report.id); loadList()
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  // Listeden onay (2026-09-13): önizleme açmadan tek tık; sunucu yetkiyi doğrular
  async function approveById(id) {
    const ok = await showConfirm({ title: t('wr.approveConfirmTitle'), message: t('wr.approveConfirmMsg'), confirmText: t('wr.approve'), cancelText: t('wr.cancel') })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.approve(id)
      if (res?.success) { toast.success(t('wr.approveOk')); loadList() } else toast.error(res?.error || t('wr.actionFailed'))
    } finally { setBusy(false) }
  }
  async function approveSelected() {
    const ids = [...selectedIds].filter((id) => reports.find((r) => r.id === id)?.status === 'PENDING_APPROVAL')
    if (!ids.length) { toast.error(t('wr.bulkApproveNone')); return }
    const ok = await showConfirm({ title: t('wr.bulkApproveTitle'), message: t('wr.bulkApproveMsg', ids.length), confirmText: t('wr.approve'), cancelText: t('wr.cancel') })
    if (!ok) return
    setBusy(true)
    let okN = 0, fail = 0
    try {
      for (const id of ids) { try { const r = await api.weeklyReports.approve(id); if (r?.success) okN++; else fail++ } catch { fail++ } }
    } finally {
      setBusy(false)
    }
    if (fail === 0) toast.success(t('bulk.done', okN)); else toast.error(t('bulk.partial', okN, fail))
    setSelectedIds(new Set()); loadList()
  }
  /** Komşu hafta (2026-09-13): listede varsa aç; yoksa "Yeni Hafta Raporu" ekranını o haftayla aç. */
  // 2026-09-27 regresyon taraması (ORTA): komşu haftaya geçiş kaydedilmemiş değişiklik onayını ve kilit bırakmayı
  // ATLIYORDU (A'nın kilidi 3 dk tutuluyordu). Artık listeye dönüşle AYNI yoldan geçer (leaveReport); kayıt sürerken
  // oklar kapalı.
  async function gotoWeek(dir) {
    if (!report || busy) return
    let y = report.report_year, w = report.week_no + dir
    if (w < 1) { y -= 1; w = 53 } else if (w > 53) { y += 1; w = 1 }
    const target = dir < 0 && prevReport ? prevReport.id
      : reports.find((r) => r.team_id === report.team_id && r.report_year === y && r.week_no === w)?.id
    if (target) {
      if (await leaveReport()) setSelectedId(target)
      return
    }
    if (isAudit) return
    const init = { year: y, week: w, teamId: String(report.team_id), carry: false }
    if (await backToList()) setNewReport(init)
  }
  function downloadYearCsv() {
    const name = (tid) => isAdmin ? (teams.find((tm) => tm.id === tid)?.name ?? '') : (teamName ?? '')
    downloadCsv(buildYearCsv(displayedReports, name, t), `haftalik-raporlar-${year}.csv`)
  }

  // Onaylı raporu yeniden düzenlenebilir hale getirir (APPROVED → DRAFT)
  async function reopenReport() {
    const ok = await showConfirm({
      title: t('wr.reopen'),
      message: t('wr.reopenConfirm'),
      confirmText: t('wr.reopen'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.reopen(report.id)
      if (res?.success) {
        toast.success(t('wr.reopenOk'))
        loadReport(report.id); loadList()
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  // Onaylı raporu (yeniden onaysız) müdüre tekrar gönderir
  async function resendReport() {
    const ok = await showConfirm({
      title: t('wr.resend'),
      message: t('wr.resendConfirm'),
      confirmText: t('wr.resend'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.resend(report.id)
      if (res?.success) {
        toast.success(t('wr.resendOk'))
        loadReport(report.id); loadList()
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  async function doReject() {
    if (!rejectModal?.note?.trim()) { toast.error(t('wr.rejectNoteRequired')); return }
    setBusy(true)
    try {
      const rid = rejectModal.id ?? report?.id
      const res = await api.weeklyReports.reject(rid, rejectModal.note.trim())
      if (res?.success) {
        setRejectModal(null)
        toast.success(t('wr.rejectOk'))
        if (report?.id === rid) loadReport(rid)
        loadList()
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  async function openPreview() {
    if (dirty && !(await save(false))) return
    const res = await api.weeklyReports.preview(report.id)
    if (res?.success) setPreviewHtml(res.html)
    else toast.error(res?.error || t('wr.actionFailed'))
  }

  // Liste kebabından: raporu (editörü) açmadan doğrudan mail önizlemesi
  async function openPreviewById(id) {
    const res = await api.weeklyReports.preview(id)
    if (res?.success) setPreviewHtml(res.html)
    else toast.error(res?.error || t('wr.actionFailed'))
  }

  // Admin-only: Cuma hatırlatma maillerini cron beklemeden anında gönder
  async function sendReminders() {
    setSendingReminder(true)
    try {
      const res = await api.weeklyReports.triggerReminder()
      if (res?.success) {
        const sent = res.data?.sent ?? 0
        if (sent > 0) {
          toast.success(t('wr.reminderSent').replace('{0}', sent).replace('{1}', res.data?.candidates ?? 0))
        } else {
          toast.info ? toast.info(t('wr.reminderNone')) : toast.success(t('wr.reminderNone'))
        }
      } else {
        toast.error(res?.error || t('wr.reminderFailed'))
      }
    } catch {
      toast.error(t('wr.reminderFailed'))
    } finally {
      setSendingReminder(false)
      setReminderNonce((n) => n + 1)
    }
  }

  function openCreate(init = {}) {
    const now = isoWeekInfo()
    setNewReport({ year: now.year, week: now.week, teamId: isAdmin ? selTeamId : String(teamId ?? ''), carry: false, ...init })
  }

  async function createReport({ teamId: tid, year: y, week: w, carry }) {
    const res = await api.weeklyReports.create({
      team_id: tid ? Number(tid) : undefined,
      year: y,
      week_no: w,
      carry_notes: !!carry,   // geçen haftadan devam (2026-09-13)
    })
    if (res?.success) {
      setNewReport(null)
      toast.success(t('wr.created'))
      setYear(y)
      await loadList()
      loadYears()
      setWeekMarks({})
      setSelectedId(res.data.id)
    } else {
      toast.error(res?.error?.includes('DUPLICATE_WEEK') ? t('wr.duplicateWeek') : (res?.error || t('wr.actionFailed')))
    }
  }

  async function deleteReport(r) {
    const ok = await showConfirm({
      title: t('wr.deleteReport'),
      message: t('wr.confirmDeleteReport', r.week_label),
      variant: 'danger',
      confirmText: t('wr.deleteReport'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.weeklyReports.remove(r.id)
      if (res?.success) {
        toast.success(t('wr.deleted'))
        try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + r.id) } catch { /* */ }
        if (selectedId === r.id) setSelectedId(null)
        loadList()
        loadYears()
        setWeekMarks({})
      } else {
        toast.error(res?.error || t('wr.actionFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  // ── Takım transferi (yalnız ADMIN) ──────────────────────────────────────
  function toggleSelect(id) {
    setSelectedIds((prev) => {
      const n = new Set(prev)
      if (n.has(id)) n.delete(id); else n.add(id)
      return n
    })
  }
  function toggleSelectAll(rows) {
    setSelectedIds((prev) => {
      const allSelected = rows.length > 0 && rows.every((r) => prev.has(r.id))
      return allSelected ? new Set() : new Set(rows.map((r) => r.id))
    })
  }
  function openTransfer(ids) {
    setTransferTeamId('')
    setTransferModal({ ids })
  }
  async function doTransfer() {
    if (!transferModal || !transferTeamId) return
    setTransferring(true)
    try {
      const res = await api.weeklyReports.transfer(transferModal.ids, Number(transferTeamId))
      if (res?.success) {
        const n = res.transferred ?? 0
        const skipped = res.skipped ?? []
        if (n > 0) toast.success(t('wr.transferDone', n))
        if (skipped.length) toast.error(t('wr.transferSkipped', skipped.length))
        if (n === 0 && skipped.length === 0) toast.info?.(t('wr.transferNone'))
        setTransferModal(null)
        setSelectedIds(new Set())
        loadList()
      } else {
        toast.error(res?.error || t('wr.transferError'))
      }
    } finally {
      setTransferring(false)
    }
  }

  /** Listeye dön — kaydedilmemiş değişiklik onayı + kilit bırakma. Vazgeçilirse false. */
  /** Açık raporu bırak: kaydedilmemiş değişiklik varsa onay (vazgeçilirse false), yerel yedek düşer, kilit bırakılır. */
  async function leaveReport() {
    if (dirty) {
      const ok = await showConfirm({
        title: t('wr.unsavedLeaveTitle'),
        message: t('wr.unsavedLeaveMsg'),
        confirmText: t('wr.backToList'),
        cancelText: t('wr.cancel'),
      })
      if (!ok) return false
      // Bilinçli vazgeçiş — yerel yedek de düşer
      try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + (report?.id ?? '')) } catch { /* */ }
    }
    if (report && lockHeld) api.weeklyReports.unlock(report.id) // best-effort
    return true
  }

  /** Listeye dön — leaveReport + liste tazelenir. Vazgeçilirse false. */
  async function backToList() {
    if (!(await leaveReport())) return false
    setSelectedId(null)
    loadList()
    return true
  }

  function refreshAll() {
    loadList()
    setCompletionNonce((n) => n + 1)
    setReminderNonce((n) => n + 1)
  }

  const i1 = content?.item1 ?? {}
  const i2 = content?.item2 ?? {}
  const channels = content?.item4?.channels ?? []
  const outline = useMemo(() => sectionOutline(content), [content])
  // Madde 1 durum dağılımı (2026-09-27): güncel sayılar, toplam, önem toplamıyla tutarsızlık, geçen haftaya fark
  // (yalnız geçen haftanın raporunda dağılım varsa — eski raporda sıfırlara göre sahte fark çizilmez), eski tekil durum.
  const countsNow = statusCounts(i1)
  const statusSumNow = statusSum(countsNow)
  const statusMm = content ? statusMismatch(i1) : null
  const prevCounts = prevContent?.item1?.status_counts && typeof prevContent.item1.status_counts === 'object' ? statusCounts(prevContent.item1) : null
  const legacyStatus = statusSumNow === 0 && String(i1.status_text ?? '').trim()
    ? (STATUS_COUNT_KEYS.find((s) => s.legacy === String(i1.status_text).trim()) ? t(STATUS_COUNT_KEYS.find((s) => s.legacy === String(i1.status_text).trim()).label) : String(i1.status_text).trim())
    : ''
  const issues = useMemo(() => reportIssues(content), [content])

  const teamLabel = (tid) => (isAdmin ? (teams.find((tm) => tm.id === tid)?.name ?? tid) : (teamName ?? tid))
  const weekText = (r) => formatWeekRange(r.report_year, r.week_no, lang)
  const queueCtx = { isAdmin, isAudit, teamId }
  // Süzme hattı: hafta ("Haftaya git") → arama → durum çipi → sıralama. Çip sayaçları hafta + arama kapsamında.
  const scoped = useMemo(() => (weekFilter != null ? reports.filter((r) => r.week_no === weekFilter) : reports)
    .filter((r) => matchesSearch(r, q, { teamName: String(teamLabel(r.team_id) ?? ''), weekText: weekText(r) })),
  [reports, weekFilter, q, teams, isAdmin, teamName, lang]) // eslint-disable-line react-hooks/exhaustive-deps
  const facets = useMemo(() => statusFacets(scoped), [scoped])
  const mineCount = useMemo(() => approvalQueue(reports, queueCtx).length, [reports, isAdmin, isAudit, teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  const displayedReports = useMemo(() => sortReports(filterByStatus(scoped, statusChip, queueCtx), listSort, teamLabel),
    [scoped, statusChip, listSort, teams, isAdmin, teamName, isAudit, teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  // Sayfalama (2026-09-12): liste yıl+takım ile sınırlı ve takvim işaretleri tüm listeyi istiyor → sayfalama YALNIZ
  // çizimi böler (InventoryManager deseni). "Tümünü seç" süzülmüş tüm liste üzerinde kalır. URL: w_page / w_ps.
  const pager = usePagination(displayedReports, { listKey: 'weekly-reports', preset: 'page', resetDeps: [effTeamId, year, weekFilter, statusChip, listSort, q],
    url: { pageKey: 'w_page', sizeKey: 'w_ps' } })

  const menuItems = (r) => [
    { label: t('wr.open'), icon: <Eye aria-hidden="true" />, onClick: () => setSelectedId(r.id) },
    { label: t('wr.preview'), icon: <Mail aria-hidden="true" />, onClick: () => openPreviewById(r.id) },
    { label: t('wr.history'), icon: <History aria-hidden="true" />, onClick: () => openMailHistory(r) },
    ...(r.status === 'PENDING_APPROVAL' && !isAudit ? [
      { label: t('wr.approve'), icon: <CheckCircle aria-hidden="true" />, onClick: () => approveById(r.id) },
      { label: t('wr.reject'), icon: <Undo2 aria-hidden="true" />, onClick: () => setRejectModal({ note: '', id: r.id }) },
    ] : []),
    ...(isAdmin ? [{ label: t('wr.transfer'), icon: <ArrowRightLeft aria-hidden="true" />, onClick: () => openTransfer([r.id]) }] : []),
    ...(canDeleteRow(r) ? [{ label: t('wr.deleteReport'), icon: <Trash2 aria-hidden="true" />, danger: true, onClick: () => deleteReport(r) }] : []),
  ]

  const view = selectedId ? 'editor' : newReport ? 'create' : 'list'
  const tw = thisWeek ? thisWeekSummary(thisWeek) : null

  // ── GİRİŞLER: başlık çipleri + eylemler ──
  const headerMeta = (
    <>
      {tw && tw.total > 0 && (
        <Badge variant="outline" data-slot="wr-meta-submitted" className={cn('h-6 gap-1 rounded-full', tw.submitted === tw.total && 'border-success/40 text-success')}>
          <Send aria-hidden="true" /> {t('wr.meta.submitted', tw.submitted, tw.total)}
        </Badge>
      )}
      {tw && tw.overdue > 0 && (
        <Badge variant="destructive" data-slot="wr-meta-overdue" className="h-6 gap-1 rounded-full">
          <AlertTriangle aria-hidden="true" /> {t('wr.meta.overdue', tw.overdue)}
        </Badge>
      )}
      {tw?.cd && !tw.past && (
        <Badge variant="outline" data-slot="wr-meta-deadline" className={cn('h-6 gap-1 rounded-full', tw.cd.d === 0 && 'border-amber-500/60 text-amber-700 dark:text-amber-400')}>
          <Clock aria-hidden="true" /> {t('wr.meta.dueIn', tw.cd.d, tw.cd.h)}
        </Badge>
      )}
      {!isAudit && mineCount > 0 && (
        <Button type="button" variant="outline" size="xs" data-slot="wr-meta-mine"
          className="h-6 rounded-full border-amber-500/50 px-2 text-xs text-amber-700 dark:text-amber-400 pointer-coarse:h-8"
          aria-pressed={statusChip === 'MINE'} onClick={() => setStatusChip(statusChip === 'MINE' ? '' : 'MINE')}>
          <Hourglass aria-hidden="true" /> {t('wr.meta.awaitingMine', mineCount)}
        </Button>
      )}
    </>
  )
  const headerActions = (
    <>
      <Button type="button" variant="outline" size="icon" onClick={refreshAll} title={t('app.refresh')} aria-label={t('app.refresh')} className="sm:flex-none">
        <RefreshCcw aria-hidden="true" />
      </Button>
      <WeeklyExportMenu onListCsv={downloadYearCsv} listCount={displayedReports.length} yearData={completion} />
      {!isAudit && (
        <Button type="button" data-tour="wr-new" onClick={() => openCreate()}>
          <Plus aria-hidden="true" /> {t('wr.newReport')}
        </Button>
      )}
    </>
  )

  const filterChips = [
    ...(isAdmin && selTeamId ? [{ key: 'team', label: `${t('wr.team')}: ${teams.find((tm) => String(tm.id) === String(selTeamId))?.name ?? selTeamId}`, onRemove: () => { setSelTeamId(''); setLoadingList(true) } }] : []),
    ...(weekFilter != null ? [{ key: 'week', label: formatWeekRange(year, weekFilter, lang), onRemove: () => { setWeekFilter(null); setJumpDate('') } }] : []),
    ...(statusChip ? [{ key: 'status', label: `${t('wr.statusCol')}: ${statusChip === 'MINE' ? t('wr.chipMine') : statusChip === 'SENT' ? t('wr.statusSent') : statusLabel(t, statusChip)}`, onRemove: () => setStatusChip('') }] : []),
    ...(q.trim() ? [{ key: 'q', label: `“${q.trim()}”`, onRemove: () => setQ('') }] : []),
  ]
  const clearAllFilters = () => {
    if (isAdmin && selTeamId) { setSelTeamId(''); setLoadingList(true) }
    setWeekFilter(null); setJumpDate(''); setStatusChip(''); setQ('')
  }
  const listFilters = (
    <>
      {isAdmin && (
        <div className="w-full min-w-0 sm:w-48">
          <SearchableSelect value={selTeamId} onChange={(v) => { setSelTeamId(v); setLoadingList(true) }}
            placeholder={t('wr.allTeams')} searchThreshold={2} ariaLabel={t('wr.team')}
            options={[{ value: '', label: t('wr.allTeams') },
              ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]} />
        </div>
      )}
      <div className="w-[calc(50%-0.25rem)] min-w-0 sm:w-28">
        <SearchableSelect value={year} onChange={(v) => { setYear(Number(v)); setLoadingList(true) }}
          ariaLabel={t('wr.year')}
          options={[...new Set([...years, year])].sort((a, b) => b - a)
            .map((y) => ({ value: y, label: String(y) }))} />
      </div>
      <div className="w-[calc(50%-0.25rem)] min-w-0 sm:w-auto">
        <WeekDatePicker value={jumpDate} onChange={goToDate}
          placeholder={t('wr.goToWeek')} hint={t('wr.goToDateHint')}
          isMarked={(y, w) => weekMarks[y]?.has(w) ?? false}
          onViewYearChange={ensureMarks} />
      </div>
    </>
  )

  // ── DÜZENLEYİCİ parçaları ──
  const deltaFor = (cur, prev) => (prevContent ? <DeltaBadge cur={cur} prev={prev} /> : null)
  const prevIso = prevReport ? isoWeekLabel(prevReport.report_year, prevReport.week_no) : ''
  const deltaCaption = prevContent && (
    <p className="text-xs text-muted-foreground" title={t('wr.deltaTitle', prevReport?.week_label || '')}>{t('wr.ed.deltaCaption', prevIso)}</p>
  )
  const saveLabel = report && (report.status === 'DRAFT' || report.status === 'REJECTED') ? t('wr.saveDraft') : t('wr.save')

  const statusCallout = report && (
    report.status === 'PENDING_APPROVAL' ? (
      <AlertBanner tone="info" icon={Send} className="mb-0">
        {report.submitted_at ? t('wr.ed.pendingSince', formatDate(report.submitted_at)) : t('wr.ed.pending')}
      </AlertBanner>
    ) : report.status === 'APPROVED' ? (
      <AlertBanner tone="success" icon={CheckCircle} className="mb-0">
        {report.sent_at
          ? t('wr.ed.sentNote', report.approved_by || '—', formatDate(report.sent_at))
          : t('wr.approvedByAt', report.approved_by || '—', formatDate(report.approved_at))}
      </AlertBanner>
    ) : null
  )

  const moreMenu = report && (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="icon" aria-label={t('wr.ed.more')} title={t('wr.ed.more')} className="pointer-coarse:size-10">
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" side="top" className="z-(--z-menu) w-56">
        <DropdownMenuItem className="sm:hidden" disabled={busy} onSelect={() => openPreview()}><Eye aria-hidden="true" /> {t('wr.preview')}</DropdownMenuItem>
        <DropdownMenuItem disabled={busy} onSelect={() => window.print()}><Printer aria-hidden="true" /> {t('wr.print')}</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => openMailHistory(report)}><History aria-hidden="true" /> {t('wr.history')}</DropdownMenuItem>
        {canDeleteRow(report) && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem variant="destructive" disabled={busy} onSelect={() => deleteReport(report)}><Trash2 aria-hidden="true" /> {t('wr.deleteReport')}</DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )

  return (
    <div className="wr-editor mb-8 flex min-w-0 flex-col gap-4">
      {/* ══════════════ GİRİŞLER ══════════════ */}
      {view === 'list' && (
        <>
          <PageHeader icon={FileChartColumn} title={t('wr.title')} description={t('wr.pageDesc')}
            meta={headerMeta} actions={headerActions} className="mb-0" />

          {/* ── "Bu hafta": kutucuklar (süzgeç) + takım satırları ── */}
          <WeeklyThisWeekStrip data={thisWeek} loading={loadingList && !thisWeek} canCreate={!isAudit}
            onOpen={(id) => setSelectedId(id)}
            onCreate={(tid, y, w) => openCreate({ year: y, week: w, teamId: String(tid) })} />

          {/* ── Yönetici: hatırlatma durumu + cron beklemeden gönder ── */}
          {isAdmin && (
            <div data-slot="wr-reminders" className="@container rounded-lg border border-dashed px-3 py-2 print:hidden">
              <div className="flex min-w-0 flex-col gap-2 @xl:flex-row @xl:items-center">
                <WeeklyReminderStatus nonce={reminderNonce} className="min-w-0 flex-1" />
                <Button type="button" variant="outline" size="sm" className="self-start @xl:self-auto pointer-coarse:h-10"
                  onClick={sendReminders} disabled={sendingReminder}>
                  <Bell aria-hidden="true" /> {sendingReminder ? t('wr.reminderSending') : t('wr.sendReminderNow')}
                </Button>
              </div>
            </div>
          )}

          {/* ── Takım tamamlama panosu (admin/AUDIT): hücre → rapor ya da takım + hafta süzgeci ── */}
          {(isAdmin || isAudit) && (
            <WeeklyCompletionBoard year={year} data={completion} onPick={(tid, week, reportId) => {
              if (reportId) { setSelectedId(reportId); return }
              if (isAdmin) setSelTeamId(String(tid))
              setWeekFilter(week); setJumpDate('')
            }} />
          )}

          {/* ── "Nasıl girilir?" — projenin katlanır şeridi (ui/CollapsibleSection), tercih hatırlanır ── */}
          <CollapsibleSection open={helpOpen} data-tour="wr-help" className="print:hidden"
            onOpenChange={(n) => { setHelpOpen(n); try { localStorage.setItem('wr-help-open', String(n)) } catch { /* yoksay */ } }}
            icon={HelpCircle} label={t('wr.helpTitle')} contentClassName="mt-2">
            <div className="rounded-[10px] border bg-card px-4 py-3">
              <ol className="ml-5 list-decimal space-y-1 text-[0.9em]">
                <li>{t('wr.helpStep1')}</li>
                <li>{t('wr.helpStep2')}</li>
                <li>{t('wr.helpStep3')}</li>
                <li>{t('wr.helpStep4')}</li>
                <li>{t('wr.helpStep5')}</li>
              </ol>
              <p className="mt-2.5 flex items-center gap-1.5 text-[0.86em] font-semibold text-amber-700 dark:text-amber-300">
                <Clock aria-hidden="true" className="size-4 shrink-0" /> {t('wr.helpDeadline',
                  deadline ? (lang === 'tr' ? deadline.day_tr : deadline.day_en) : (lang === 'tr' ? 'Cuma' : 'Friday'),
                  deadline?.time || '15:00')}
              </p>
            </div>
          </CollapsibleSection>

          {/* ── Rapor girişleri listesi ── */}
          <section ref={setListBox} aria-labelledby="wr-list-title" className="flex min-w-0 flex-col gap-3">
            <h3 id="wr-list-title" className="text-base font-semibold">{t('wr.listTitle')}</h3>
            <WeeklyListToolbar q={q} onQ={setQ} filters={listFilters} chips={filterChips} onClearAll={clearAllFilters}
              shown={displayedReports.length} total={reports.length} sort={listSort} onSort={setListSort} showSort={narrow} />
            <WeeklyStatusChips facets={facets} value={statusChip} onChange={(v) => { setStatusChip(v); setSelectedIds(new Set()) }}
              mineCount={mineCount} showMine={!isAudit && (isAdmin || isTeamAdmin || mineCount > 0)} />
            {isAdmin && selectedIds.size > 0 && (
              <div role="group" aria-label={t('wr.selectedCount', selectedIds.size)}
                className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/50 px-3 py-2 print:hidden">
                <span className="text-[0.9em] font-bold">{t('wr.selectedCount', selectedIds.size)}</span>
                <Button type="button" size="sm" variant="outline" onClick={() => openTransfer([...selectedIds])}>
                  <ArrowRightLeft aria-hidden="true" /> {t('wr.transferSelected')}
                </Button>
                <Button type="button" variant="success" size="sm" onClick={approveSelected} disabled={busy}>
                  <CheckCircle aria-hidden="true" /> {t('wr.bulkApprove')}
                </Button>
                <Button type="button" variant="ghost" size="sm" onClick={() => setSelectedIds(new Set())}>
                  {t('wr.clearSelection')}
                </Button>
              </div>
            )}
            {loadingList ? (
              <LoadingBlock label={t('app.loading')} fullWidth />
            ) : displayedReports.length ? (
              <>
                <WeeklyReportList rows={pager.pageItems} allRows={displayedReports} narrow={narrow} isAdmin={isAdmin}
                  selectedIds={selectedIds} onToggle={toggleSelect} onToggleAll={toggleSelectAll}
                  teamLabel={teamLabel} weekText={weekText} sort={listSort} onSort={setListSort}
                  onOpen={(r) => setSelectedId(r.id)} menuItems={menuItems} mailProblem={mailProblem} />
                {/* Çubuk liste kabının DIŞINDA (telefonda tabloyla birlikte kayıp gitmesin) */}
                <PaginationBar {...pager} />
              </>
            ) : (
              <StatusBlock tone="neutral" icon={CalendarDays}
                title={filterChips.length ? t('wr.noMatch') : weekFilter != null ? t('wr.noReportForWeek') : t('wr.noReportSelected')}
                actions={filterChips.length
                  ? <Button type="button" variant="outline" size="sm" onClick={clearAllFilters}>{t('app.clearFilters')}</Button>
                  : (!isAudit ? <Button type="button" size="sm" onClick={() => openCreate()}><Plus aria-hidden="true" /> {t('wr.newReport')}</Button> : null)} />
            )}
          </section>
        </>
      )}

      {/* ══════════════ YENİ HAFTA RAPORU ══════════════ */}
      {view === 'create' && (
        <WeeklyReportCreate initial={newReport} isAdmin={isAdmin} teams={teams} teamId={teamId} teamName={teamName}
          onCancel={() => setNewReport(null)} onCreate={createReport}
          onOpenExisting={(id) => { setNewReport(null); setSelectedId(id) }} />
      )}

      {/* ══════════════ DÜZENLEYİCİ ══════════════ */}
      {view === 'editor' && (
        <div>
          <Button type="button" variant="ghost" size="sm" className="-ml-2 pointer-coarse:h-10" onClick={backToList}>
            <ArrowLeft aria-hidden="true" /> {t('wr.backToList')}
          </Button>
        </div>
      )}

      {/* ── Rapor yükleniyor — boş ekran yerine gösterge ── */}
      {view === 'editor' && !report && loadingReport && (
        <LoadingBlock label={t('app.loading')} fullWidth />
      )}

      {view === 'editor' && report && content && (
        <>
          <PageHeader data-brief="" icon={FileChartColumn} className="mb-0"
            title={formatWeekRange(report.report_year, report.week_no, lang)}
            description={t('wr.ed.headerDesc', String(teamLabel(report.team_id) ?? ''))}
            meta={(
              <>
                <Badge variant="secondary" className="h-6 font-mono">{isoWeekLabel(report.report_year, report.week_no)}</Badge>
                <TeamBadge teamId={report.team_id} teamName={String(teamLabel(report.team_id) ?? '')} />
                <WeeklyStatusBadge status={report.status} sentAt={report.sent_at} />
                <SaveState editable={editable} dirty={dirty} busy={busy} lastAutoSave={lastAutoSave} conflict={conflict} />
              </>
            )}
            actions={(
              <>
                <Button type="button" variant="outline" className="print:hidden" disabled={busy} onClick={() => gotoWeek(-1)} title={t('wr.prevWeek')} aria-label={t('wr.prevWeek')}>
                  <ChevronLeft aria-hidden="true" /> <span className="hidden sm:inline">{t('wr.prevWeekShort')}</span>
                </Button>
                <Button type="button" variant="outline" className="print:hidden" disabled={busy} onClick={() => gotoWeek(1)} title={t('wr.nextWeek')} aria-label={t('wr.nextWeek')}>
                  <span className="hidden sm:inline">{t('wr.nextWeekShort')}</span> <ChevronRight aria-hidden="true" />
                </Button>
              </>
            )} />

          {/* ── Durum / iade notu / müdür uyarısı / kilit / çakışma / yerel yedek bantları ── */}
          {statusCallout}
          {report.reject_note && report.status !== 'APPROVED' && (
            <AlertBanner tone="warning" title={t('wr.rejectNoteBanner')} className="mb-0">{report.reject_note}</AlertBanner>
          )}
          {managerMissing && (
            <AlertBanner tone="warning" className="mb-0">{t('wr.managerContactMissing')}</AlertBanner>
          )}
          {weekLocked && (
            <AlertBanner tone="info" icon={Lock} className="mb-0">{t('wr.weekLockedBanner')}</AlertBanner>
          )}
          {!lockHeld && lockHolder && canModifyRow(report) && (
            <AlertBanner tone="warning" icon={Lock} className="mb-0"
              actions={<>
                <Button type="button" variant="secondary" size="sm" onClick={retryLock}>{t('wr.lockRetry')}</Button>
                {isAdmin && <Button type="button" variant="destructive" size="sm" onClick={takeoverLock}>{t('wr.lockTakeover')}</Button>}
              </>}>
              {t('wr.lockedBy', lockHolder.name ?? '?')}
            </AlertBanner>
          )}
          {conflict && (
            <AlertBanner tone="danger" role="alert" className="mb-0"
              actions={<Button type="button" variant="secondary" size="sm" onClick={() => loadReport(report.id)}>{t('wr.loadLatest')}</Button>}>
              {t('wr.conflictBanner', conflict.replace('VERSION_CONFLICT: ', ''))}
            </AlertBanner>
          )}
          {pendingBackup && (
            <AlertBanner tone="info" icon={Save} className="mb-0"
              actions={<>
                <Button type="button" variant="secondary" size="sm" onClick={restoreBackup}>{t('wr.restore')}</Button>
                <Button type="button" variant="outline" size="sm" onClick={discardBackup}>{t('wr.discardBackup')}</Button>
              </>}>
              {t('wr.backupFound', new Date(pendingBackup.saved_at).toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}
            </AlertBanner>
          )}

          <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_19rem]">
            <div className="flex min-w-0 flex-col gap-4">
              <CompactProgress outline={outline} className="xl:hidden" />

              {/* ── Özet — executive brief + KPI şeridi, katlanır (varsayılan kapalı; yazdırırken DAİMA açık) ──
                  Collapsible (tetik + aria-expanded); gövde DOM'da kalır ki print:block onu kapalıyken de basabilsin. */}
              <Collapsible open={summaryOpen} onOpenChange={setSummaryOpen} className="overflow-hidden rounded-xl border bg-card shadow-xs">
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="ghost" aria-controls={summaryBodyId}
                    className="h-auto min-h-12 w-full justify-between rounded-none px-4 py-3 text-left font-semibold whitespace-normal">
                    <span className="flex items-center gap-2"><Sparkles aria-hidden="true" className="size-4 text-primary" />{t('wr.sumSection')}</span>
                    <ChevronDown aria-hidden="true" className={cn('size-4 text-muted-foreground transition-transform motion-reduce:transition-none print:hidden', summaryOpen && 'rotate-180')} />
                  </Button>
                </CollapsibleTrigger>
                <div id={summaryBodyId} className={cn('border-t px-4 pt-4 pb-1', !summaryOpen && 'hidden print:block')}>
                  <WeeklySummaryBrief kpis={kpis} t={t} lang={lang} />
                  <WeeklyKpiStrip kpis={kpis} loading={kpisLoading} t={t} />
                  <WeeklyMonitoringStrip stats={monStats} loading={monLoading} t={t} />
                </div>
              </Collapsible>

              {/* ── 1 · Proaktif iyileştirme kayıtları ── */}
              <SectionCard sectionKey="item1" n={1} filled={outline[0].filled} description={t('wr.ed.help1')}>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                  <NumInput label={t('wr.urgent')} tone="urgent" value={i1.urgent} editable={editable} onChange={(v) => patchSeverity('urgent', v)} extra={deltaFor(i1.urgent, prevContent?.item1.urgent)} />
                  <NumInput label={t('wr.high')} tone="high" value={i1.high} editable={editable} onChange={(v) => patchSeverity('high', v)} extra={deltaFor(i1.high, prevContent?.item1.high)} />
                  <NumInput label={t('wr.medium')} tone="medium" value={i1.medium} editable={editable} onChange={(v) => patchSeverity('medium', v)} extra={deltaFor(i1.medium, prevContent?.item1.medium)} />
                  <NumInput label={t('wr.low')} tone="low" value={i1.low} editable={editable} onChange={(v) => patchSeverity('low', v)} extra={deltaFor(i1.low, prevContent?.item1.low)} />
                  {/* Toplam türetilir — elle girilmez */}
                  <NumInput label={t('wr.total')} value={i1.total} editable={false} onChange={() => {}} readOnlyHint={t('wr.ed.totalHint')} extra={deltaFor(i1.total, prevContent?.item1.total)} />
                </div>
                {deltaCaption}
                {suggest && editable && (
                  <p className="flex items-center gap-1.5 rounded-md bg-primary/5 px-2.5 py-1.5 text-[0.84em] text-muted-foreground" data-tour="wr-suggest">
                    <Sparkles aria-hidden="true" className="size-3.5 shrink-0 text-primary" /> {t('wr.sugLine', suggest.alarms_opened ?? '—', suggest.critical_certs ?? '—', suggest.open_incidents ?? '—')}
                  </p>
                )}
                {/* Durum dağılımı (2026-09-27): tekil "Durum" seçimi yerine her durumda kaç kayıt — anlık görüntü.
                    Toplam önem toplamını tutmazsa yumuşak uyarı (kontrol listesinde de); eski raporda dağılım boşsa
                    tekil status_text okunur. */}
                <div data-slot="wr-status-breakdown" className="flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/20 p-3">
                  <div className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1">
                    <div className="min-w-0">
                      <h4 className="text-sm font-semibold">{t('wr.ed.statusBreakdown')}</h4>
                      <p className="text-xs text-muted-foreground">{t('wr.ed.statusBreakdownHint')}</p>
                    </div>
                    <Badge variant="outline" data-slot="wr-status-sum" className="h-6 rounded-full tabular-nums">{t('wr.ed.statusSum', statusSumNow)}</Badge>
                  </div>
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    {STATUS_COUNT_KEYS.map((s) => (
                      <NumInput key={s.key} label={t(s.label)} tone={s.tone} value={countsNow[s.key]} editable={editable}
                        onChange={(v) => patch(['item1', 'status_counts', s.key], v)}
                        extra={prevCounts ? <DeltaBadge neutral cur={countsNow[s.key]} prev={prevCounts[s.key]} /> : null} />
                    ))}
                  </div>
                  {statusMm && (editable || statusSumNow > 0) && (
                    <p data-slot="wr-status-mismatch" className="flex items-start gap-1.5 text-xs font-medium text-amber-700 dark:text-amber-400">
                      <AlertTriangle aria-hidden="true" className="mt-px size-3.5 shrink-0" /> {t('wr.ed.statusMismatch', statusMm.sum, statusMm.total)}
                    </p>
                  )}
                  {legacyStatus && (
                    <p data-slot="wr-legacy-status" className="text-xs text-muted-foreground">{t('wr.ed.legacyStatus', legacyStatus)}</p>
                  )}
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <LinkField key={`${report.id}-i1t`} label={t('wr.trackingUrl')} value={i1.tracking_url} editable={editable}
                    onChange={(v) => patch(['item1', 'tracking_url'], v)} />
                </div>
                <MdField value={i1.notes_md} editable={editable} reportId={report.id} label={t('wr.ed.notesFor', sectionTitle(t, 'item1'))}
                  onChange={(v) => patch(['item1', 'notes_md'], v)} height={180} />
                <PrevNoteToggle open={!!prevOpen.item1} onToggle={() => setPrevOpen((o) => ({ ...o, item1: !o.item1 }))} note={prevContent?.item1?.notes_md} weekLabel={prevReport?.week_label} />
              </SectionCard>

              {/* ── 2 · İhlal edilen olay / problem kayıtları, açık postmortem'ler ── */}
              <SectionCard sectionKey="item2" n={2} filled={outline[1].filled} description={t('wr.ed.help2')}>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <NumInput label={t('wr.openIncidents')} value={i2.open_incidents} editable={editable} onChange={(v) => patch(['item2', 'open_incidents'], v)}
                    extra={deltaFor(i2.open_incidents, prevContent?.item2.open_incidents)}
                    below={suggest && editable
                      ? <span className="flex"><SuggestBadge value={suggest.open_incidents} current={i2.open_incidents} label={t('wr.openIncidents')} onApply={(v) => patch(['item2', 'open_incidents'], v)} /></span>
                      : null} />
                  <NumInput label={t('wr.problemRecords')} value={i2.problem_records} editable={editable} onChange={(v) => patch(['item2', 'problem_records'], v)} extra={deltaFor(i2.problem_records, prevContent?.item2.problem_records)} />
                  <NumInput label={t('wr.postmortems')} value={i2.postmortems} editable={editable} onChange={(v) => patch(['item2', 'postmortems'], v)} extra={deltaFor(i2.postmortems, prevContent?.item2.postmortems)} />
                </div>
                {deltaCaption}
                {/* Her kayıt türü için ayrı takip bağlantısı (çip); eski raporlardaki genel bağlantı doluysa o da gösterilir */}
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 2xl:grid-cols-3">
                  {[['incidentsUrl', 'incidents_url'], ['problemsUrl', 'problems_url'], ['postmortemsUrl', 'postmortems_url']].map(([lbl, key]) => (
                    <LinkField key={`${report.id}-${key}`} label={t('wr.' + lbl)} value={i2[key]} editable={editable}
                      onChange={(v) => patch(['item2', key], v)} />
                  ))}
                  {i2.tracking_url ? (
                    <LinkField key={`${report.id}-i2t`} label={t('wr.trackingUrl')} value={i2.tracking_url} editable={editable}
                      onChange={(v) => patch(['item2', 'tracking_url'], v)} />
                  ) : null}
                </div>
                <MdField value={i2.notes_md} editable={editable} reportId={report.id} label={t('wr.ed.notesFor', sectionTitle(t, 'item2'))}
                  onChange={(v) => patch(['item2', 'notes_md'], v)} height={180} />
                <PrevNoteToggle open={!!prevOpen.item2} onToggle={() => setPrevOpen((o) => ({ ...o, item2: !o.item2 }))} note={prevContent?.item2?.notes_md} weekLabel={prevReport?.week_label} />
              </SectionCard>

              {/* ── 3 · Haftalık temaslar ── */}
              <SectionCard sectionKey="item3" n={3} filled={outline[2].filled} description={t('wr.ed.help3')}>
                <MdField value={content?.item3?.notes_md} editable={editable} reportId={report.id} label={t('wr.ed.notesFor', sectionTitle(t, 'item3'))}
                  onChange={(v) => patch(['item3', 'notes_md'], v)} height={240} />
                <PrevNoteToggle open={!!prevOpen.item3} onToggle={() => setPrevOpen((o) => ({ ...o, item3: !o.item3 }))} note={prevContent?.item3?.notes_md} weekLabel={prevReport?.week_label} />
              </SectionCard>

              {/* ── 4 · Alan bazlı kritik işler — alan listesi (2026-09-27: sekmeler yerine tablo / kart; veri aynı) ── */}
              <SectionCard sectionKey="item4" n={4} filled={outline[3].filled} description={t('wr.ed.help4')}>
                {editable ? (
                  <DomainWorkEditor key={report.id} channels={channels} reportId={report.id}
                    onChange={(next) => patch(['item4', 'channels'], next)}
                    templateNames={teamChannels} prevChannels={prevContent?.channels} prevWeekLabel={prevReport?.week_label} />
                ) : (
                  <DomainWorkView key={report.id} channels={channels} />
                )}
              </SectionCard>
            </div>

            {/* ── Sağ sütun (xl): ana hat + ayrıntılar + yorumlar; dar ekranda bölümlerin altına iner ── */}
            <aside aria-label={t('wr.ed.aside')}
              className="flex min-w-0 flex-col gap-4 xl:sticky xl:top-4 xl:max-h-[calc(100dvh-2rem)] xl:self-start xl:overflow-y-auto print:hidden">
              <EditorOutline outline={outline} className="hidden xl:flex" />
              <ReportDetailsCard report={report} content={content} teamName={String(teamLabel(report.team_id) ?? '')} />
              {/* Yorum dizisi (2026-09-13, ikinci tur): PO ↔ takım gidiş-gelişi; durum geçişinde tazelenir */}
              <WeeklyComments reportId={report.id} canWrite={!isAudit} nonce={report.status} />
            </aside>
          </div>

          {/* ── YAPIŞKAN eylem çubuğu: kayıt durumu + kontrol listesi · Diğer · Önizleme · Kaydet · birincil eylem ── */}
          {/* Sağda yardım düğmesi (sabit, sağ alt) için pay (pr-14): düğmeler onun altında kalmasın. Telefonda durum satırı
              üstte tam genişlik, eylemler altta. */}
          <div data-slot="wr-actions" role="toolbar" aria-label={t('wr.actions')}
            className="sticky bottom-0 z-20 flex min-w-0 flex-col gap-2 border-t bg-card/95 py-2.5 pr-14 pb-[max(0.625rem,env(safe-area-inset-bottom))] backdrop-blur supports-[backdrop-filter]:bg-card/85 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-3 print:hidden">
            <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 sm:flex-1">
              <SaveState editable={editable} dirty={dirty} busy={busy} lastAutoSave={lastAutoSave} conflict={conflict} compact={phone} />
              {editable && <ValidationSummary issues={issues} compact={phone} />}
            </div>
            <div className="flex flex-wrap items-center justify-end gap-2">
              {moreMenu}
              <Button type="button" variant="outline" className="hidden sm:inline-flex" onClick={openPreview} disabled={busy}>
                <Eye aria-hidden="true" /> {t('wr.preview')}
              </Button>
              {editable && !conflict && (
                <Button type="button" variant={canSubmit ? 'outline' : 'default'} onClick={() => save()} disabled={busy || !dirty} aria-label={saveLabel}>
                  <Save aria-hidden="true" /> <span className="hidden sm:inline">{busy ? t('wr.saving') : saveLabel}</span>
                </Button>
              )}
              {canSubmit && !conflict && (
                <Button type="button" onClick={submit} disabled={busy}>
                  <Send aria-hidden="true" /> {t('wr.submit')}
                </Button>
              )}
              {showApproval && (
                <>
                  <Button type="button" variant="outline" onClick={() => setRejectModal({ note: '' })} disabled={busy}>
                    <Undo2 aria-hidden="true" /> {t('wr.reject')}
                  </Button>
                  <Button type="button" variant="success" onClick={approve} disabled={busy || managerMissing}>
                    <CheckCircle aria-hidden="true" /> {t('wr.approve')}
                  </Button>
                </>
              )}
              {canReopen && (
                <Button type="button" variant="outline" onClick={reopenReport} disabled={busy}>
                  <FilePenLine aria-hidden="true" /> {t('wr.reopen')}
                </Button>
              )}
              {canResend && (
                <Button type="button" variant="success" onClick={resendReport} disabled={busy || managerMissing}>
                  <RefreshCcw aria-hidden="true" /> {t('wr.resend')}
                </Button>
              )}
            </div>
          </div>
        </>
      )}

      {/* ── İade penceresi ── */}
      <ModalShell open={!!rejectModal} onClose={() => setRejectModal(null)} title={t('wr.rejectModalTitle')} icon={Undo2} size="sm" busy={busy}
        footer={<>
          <Button variant="secondary" onClick={() => setRejectModal(null)}>{t('wr.cancel')}</Button>
          <Button onClick={doReject} disabled={busy}>{t('wr.reject')}</Button>
        </>}>
        {rejectModal && (
          <Field label={t('wr.rejectNote')} required className="mb-0">
            {({ id }) => <Textarea id={id} rows={5} value={rejectModal.note}
              onChange={(e) => setRejectModal((m) => ({ ...m, note: e.target.value }))} />}
          </Field>
        )}
      </ModalShell>

      {/* ── Takım transferi (toplu / tekil) ── */}
      <ModalShell open={!!transferModal} onClose={() => setTransferModal(null)} title={t('wr.transferTitle')} icon={ArrowRightLeft} size="sm" busy={transferring}
        footer={<>
          <Button variant="secondary" onClick={() => setTransferModal(null)}>{t('wr.cancel')}</Button>
          <Button onClick={doTransfer} disabled={transferring || !transferTeamId}>
            {transferring ? t('wr.transferring') : t('wr.transferConfirm')}
          </Button>
        </>}>
        {transferModal && (
          <>
            <p className="mb-3 text-[.88em] text-muted-foreground">{t('wr.transferDesc', transferModal.ids.length)}</p>
            <Field label={t('wr.transferTarget')} required className="mb-0">
              {({ id }) => (
                <SearchableSelect id={id}
                  value={transferTeamId}
                  onChange={(v) => setTransferTeamId(v)}
                  placeholder={t('wr.transferTargetPh')}
                  searchThreshold={2}
                  options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))}
                />
              )}
            </Field>
          </>
        )}
      </ModalShell>

      {/* ── Gönderim geçmişi ── */}
      <ModalShell open={!!mailHistory} onClose={() => { setMailHistory(null); setOpenMailBody(null) }} size="lg" scrollBody icon={History}
        title={mailHistory ? t('wr.mailHistoryTitle', mailHistory.report.week_label) : ''}
        footer={<Button variant="secondary" onClick={() => { setMailHistory(null); setOpenMailBody(null) }}>{t('wr.close')}</Button>}>
        {mailHistory && (
          <div className="flex flex-col gap-3">
            {!mailHistory.items.length && (
              <StatusBlock tone="neutral" icon={Mail} title={t('wr.mailHistoryEmpty')} className="py-6" />
            )}
            {mailHistory.items.map((m) => {
              const st = mailStatusInfo(m.status)
              return (
                <Card key={m.id} data-slot="wr-mail" className="gap-0 overflow-hidden py-0 shadow-none">
                  <div className="flex flex-wrap items-center gap-2.5 border-b px-3 py-2">
                    <strong className="text-[.9em]">{t(`wr.mailType${m.mail_type}`)}</strong>
                    {st && <Badge title={m.status} className={cn('rounded-full font-bold', st.cls)}>{st.label}</Badge>}
                    <span className="ml-auto text-[.78em] text-muted-foreground">{formatDate(m.created_at)}</span>
                  </div>
                  <dl className="grid gap-1 px-3 py-2.5 text-[.85em] [&_dd]:min-w-0 [&_dd]:[overflow-wrap:anywhere]">
                    <div className="flex flex-wrap gap-x-1.5"><dt className="font-semibold">{t('wr.mailFrom')}:</dt><dd>{m.from_address || '—'}</dd></div>
                    <div className="flex flex-wrap gap-x-1.5"><dt className="font-semibold">{t('wr.mailTo')}:</dt><dd>{m.to_addresses || '—'}</dd></div>
                    {m.cc_addresses && <div className="flex flex-wrap gap-x-1.5"><dt className="font-semibold">CC:</dt><dd>{m.cc_addresses}</dd></div>}
                    <div className="flex flex-wrap gap-x-1.5"><dt className="font-semibold">{t('wr.mailSubject')}:</dt><dd>{m.subject}</dd></div>
                    <div className="flex flex-wrap gap-x-1.5"><dt className="font-semibold">{t('wr.mailBy')}:</dt><dd>{m.created_by}</dd></div>
                    {mailProblem(m.status) && (
                      <div className="flex items-center gap-1 font-semibold text-destructive"><AlertTriangle aria-hidden="true" className="size-3.5" /> {m.status}</div>
                    )}
                  </dl>
                  <div className="px-3 pb-2.5">
                    <Button type="button" variant="secondary" size="sm" aria-expanded={openMailBody === m.id}
                      onClick={() => setOpenMailBody(openMailBody === m.id ? null : m.id)}>
                      {openMailBody === m.id ? t('wr.mailHideBody') : t('wr.mailShowBody')}
                    </Button>
                    {openMailBody === m.id && (
                      // allow-same-origin: görseller oturum çerezi ile yüklenir; script yok
                      <iframe title={`mail-${m.id}`} srcDoc={mailPreviewSrcDoc(m.body_html)} sandbox="allow-same-origin"
                        className="mt-2 h-[420px] w-full rounded-lg border bg-[#f4f6f8]" />
                    )}
                  </div>
                </Card>
              )
            })}
          </div>
        )}
      </ModalShell>

      {/* ── Mail önizleme ── */}
      <ModalShell open={previewHtml != null} onClose={() => setPreviewHtml(null)} size="lg" icon={Eye} title={t('wr.previewTitle')}
        footer={<Button variant="secondary" onClick={() => setPreviewHtml(null)}>{t('wr.close')}</Button>}>
        {/* allow-same-origin: görsellerin oturum çerezi ile yüklenebilmesi için; script yok */}
        {previewHtml != null && (
          <iframe title="preview" srcDoc={mailPreviewSrcDoc(previewHtml)} sandbox="allow-same-origin"
            className="h-[70dvh] w-full rounded-lg border bg-[#f4f6f8]" />
        )}
      </ModalShell>
    </div>
  )
}
