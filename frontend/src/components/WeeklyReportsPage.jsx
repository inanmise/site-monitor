import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import MDEditor, { commands as mdCommands } from '@uiw/react-md-editor'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  Plus, Save, Send, CheckCircle, Undo2, Eye, Trash2, RefreshCcw, ArrowLeft, Menu, History, FilePenLine,
  HelpCircle, ChevronDown, Bell, Mail, ArrowRightLeft, Printer, ChevronLeft, ChevronRight, Download, Sparkles, MessageSquare, ListPlus,
} from 'lucide-react'
import UserBadge from './ui/UserBadge.jsx'
import WeeklyKpiStrip from './WeeklyKpiStrip.jsx'
import WeeklySummaryBrief from './WeeklySummaryBrief.jsx'
import WeeklyMonitoringStrip from './WeeklyMonitoringStrip.jsx'
import { api, formatDate } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import WeekDatePicker from './ui/WeekDatePicker.jsx'
import { clipboardToMarkdownTable } from '../utils/pasteTable'
import { downscaleImage } from '../utils/imageDownscale'
import { isoWeekInfo, isEditableWeek, formatWeekRange } from '../utils/isoWeek'
import { mailPreviewSrcDoc } from '../utils/mailPreview.js'
import { LoadingBlock } from './ui/Progress.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import WeeklyCompletionBoard from './WeeklyCompletionBoard.jsx'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import WeeklyThisWeekStrip from './weekly/WeeklyThisWeekStrip.jsx'
import WeeklyComments from './weekly/WeeklyComments.jsx'
import WeeklyReminderStatus from './weekly/WeeklyReminderStatus.jsx'
import { WeeklyStatusChips, SortTh, ScoreBadge, DeltaBadge, SuggestBadge, PrevNoteToggle } from './weekly/WeeklyListExtras.jsx'
import { statusFacets, approvalQueue, filterByStatus, sortReports, parsePrevContent, buildYearCsv, toUrlMapping } from './weekly/weeklyModel.js'
import { Button } from '@/components/shadcn/button'

/** Oturum kesintisi yedekleri için localStorage anahtar öneki. */
const DRAFT_BACKUP_PREFIX = 'wr.draft.'

/** Madde 1 durum seçenekleri — mail her zaman TR olduğundan kanonik değer
 *  TR string'idir; UI etiketi i18n'den gelir. */
const ITEM1_STATUS_CHOICES = [
  { value: 'Çalışılıyor', key: 'wr.statusWorking' },
  { value: 'Planlandı',   key: 'wr.statusPlanned' },
  { value: 'Beklemede',   key: 'wr.statusOnHold' },
  { value: 'Tamamlandı',  key: 'wr.statusDone' },
]

/** Toolbar'daki özel "görsel yükle" komutu ikonu (varsayılan image komutu
 *  yalnız şablon metni eklediği için kaldırıldı — bu, dosya seçiciyi açar). */
const IMAGE_UPLOAD_ICON = (
  <svg width="13" height="13" viewBox="0 0 20 20">
    <path fill="currentColor"
      d="M15 9c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm4-7H1c-.55 0-1 .45-1 1v14c0 .55.45 1 1 1h18c.55 0 1-.45 1-1V3c0-.55-.45-1-1-1zm-1 13l-6-5-2 2-4-5-4 8V4h16v11z" />
  </svg>
)

const INDENT_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M3 4h18v2H3V4zm8 5h10v2H11V9zm0 5h10v2H11v-2zm-8 5h18v2H3v-2zM3 9l4 3-4 3V9z" />
  </svg>
)

const OUTDENT_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M3 4h18v2H3V4zm8 5h10v2H11V9zm0 5h10v2H11v-2zm-8 5h18v2H3v-2zM7 9l-4 3 4 3V9z" />
  </svg>
)

/** Bayt → okunur boyut (≥1MB ise MB, aksi KB). */
// Oluşturan/Son Düzenleme/Onaylayan hücresi — kişi (yalnız ad-soyad) üstte, tarih HER ZAMAN alt satırda + koyu.
function actorCell(username, dateIso) {
  if (!username) return '—'
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, alignItems: 'flex-start' }}>
      <UserBadge username={username} inline size="sm" nameOnly />
      <span style={{ color: 'var(--text)', fontWeight: 600, fontSize: '.85em' }}>{formatDate(dateIso)}</span>
    </div>
  )
}

function fmtFileSize(bytes) {
  if (bytes == null) return '—'
  return bytes >= 1024 * 1024
    ? (bytes / 1024 / 1024).toFixed(1) + ' MB'
    : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}

/** Seçimi tam satırlara genişletip her satıra fn uygular (girinti komutları). */
function transformSelectedLines(state, api, fn) {
  const text = state.text ?? ''
  const start = text.lastIndexOf('\n', Math.max(0, state.selection.start - 1)) + 1
  let end = text.indexOf('\n', state.selection.end)
  if (end === -1) end = text.length
  const next = text.slice(start, end).split('\n').map(fn).join('\n')
  api.setSelectionRange({ start, end })
  api.replaceSelection(next)
  // Blok seçili kalsın — arka arkaya girintileme akıcı olsun
  api.setSelectionRange({ start, end: start + next.length })
}

/**
 * Markdown alanı: geniş tek yazma alanı (sağ üst ikonlarla kaynak/önizleme
 * geçişi), Excel yapıştırma (TSV/HTML→markdown tablo) ve toolbar üzerinden
 * açıklamalı görsel yükleme.
 * highlightEnable kapalı — şeffaf textarea + arkadaki highlight katmanı
 * kombinasyonu kayma/hayalet-metin sorunları üretiyordu.
 */
function MdField({ value, onChange, editable, reportId, height = 220 }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  const fileRef = useRef(null)
  const caretRef = useRef(null) // kullanıcı textarea'da imleç hareket ettirince dolar; görsel buraya eklenir
  const [pendingFile, setPendingFile] = useState(null) // { file } → açıklama modalı
  const [caption, setCaption] = useState('')
  const [uploading, setUploading] = useState(false)

  const editorCommands = useMemo(() => {
    const tt = (key) => ({ 'aria-label': t(key), title: t(key) })
    return [
      // Word "Stiller" benzeri başlık menüsü (H1 rapor içinde fazla büyük)
      mdCommands.group([mdCommands.title2, mdCommands.title3, mdCommands.title4], {
        name: 'title', groupName: 'title', buttonProps: tt('wr.cmdTitle'),
      }),
      mdCommands.divider,
      mdCommands.bold, mdCommands.italic, mdCommands.strikethrough,
      mdCommands.divider,
      mdCommands.link, mdCommands.quote,
      mdCommands.divider,
      { ...mdCommands.unorderedListCommand, buttonProps: tt('wr.cmdUl') },
      { ...mdCommands.orderedListCommand, buttonProps: tt('wr.cmdOl') },
      { ...mdCommands.checkedListCommand, buttonProps: tt('wr.cmdTaskList') },
      {
        name: 'indent',
        keyCommand: 'indent',
        buttonProps: tt('wr.cmdIndent'),
        icon: INDENT_ICON,
        execute: (state, api) => transformSelectedLines(state, api, (l) => '    ' + l),
      },
      {
        name: 'outdent',
        keyCommand: 'outdent',
        buttonProps: tt('wr.cmdOutdent'),
        icon: OUTDENT_ICON,
        execute: (state, api) => transformSelectedLines(state, api, (l) => l.replace(/^ {1,4}/, '')),
      },
      mdCommands.divider,
      { ...mdCommands.table, buttonProps: tt('wr.cmdTable') },
      { ...mdCommands.hr, buttonProps: tt('wr.cmdHr') },
      mdCommands.divider,
      {
        name: 'image-upload',
        keyCommand: 'image-upload',
        buttonProps: { 'aria-label': t('wr.uploadImage'), title: t('wr.uploadImage') },
        icon: IMAGE_UPLOAD_ICON,
        execute: () => fileRef.current?.click(),
      },
    ]
  }, [t])

  // Kullanıcının editördeki imleç konumunu izle — görsel buraya eklenecek
  function recordCaret(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    caretRef.current = { start: e.target.selectionStart, end: e.target.selectionEnd }
  }

  function handlePasteCapture(e) {
    if (e.target?.tagName !== 'TEXTAREA') return
    const md = clipboardToMarkdownTable(e.clipboardData)
    if (!md) return
    e.preventDefault()
    e.stopPropagation()
    const ta = e.target
    const start = ta.selectionStart ?? (value?.length ?? 0)
    const end = ta.selectionEnd ?? start
    onChange((value ?? '').slice(0, start) + md + (value ?? '').slice(end))
    toast.success(t('wr.pasteTableDone'))
  }

  // Görsel seçilince HEMEN optimize et (mail için agresif küçültme) — modalda
  // orijinal→optimize boyutu gösterilir, kullanıcı ne olduğunu görür.
  async function handleFileChosen(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setCaption('')
    setPendingFile({ processing: true, name: file.name, originalSize: file.size })
    const processed = await downscaleImage(file)
    setPendingFile({ file: processed, name: file.name, originalSize: file.size, processedSize: processed.size })
  }

  async function doUpload() {
    if (!pendingFile || pendingFile.processing || !pendingFile.file) return
    setUploading(true)
    try {
      const cap = caption.trim()
      const res = await api.weeklyReports.uploadImage(reportId, pendingFile.file, cap || null)
      if (res?.success) {
        const alt = cap || pendingFile.file.name
        const captionLine = cap ? `\n**${cap}**\n` : '\n'
        const insert = `${captionLine}\n![${alt}](/api/weekly-reports/images/${res.data.id})\n`
        const v = value ?? ''
        const c = caretRef.current
        if (c && typeof c.start === 'number' && c.start <= v.length) {
          onChange(v.slice(0, c.start) + insert + v.slice(c.end ?? c.start)) // imleç konumuna
        } else {
          onChange(v + insert) // imleç belirtilmemiş → sona
        }
        caretRef.current = null // kontrollü değer değişimi caret'i bozar; sonraki etkileşim tazeler
        if (pendingFile.processedSize < pendingFile.originalSize) {
          toast.success(t('wr.imageOptimized', fmtFileSize(pendingFile.originalSize), fmtFileSize(pendingFile.processedSize)))
        }
        setPendingFile(null)
        setCaption('')
      } else {
        toast.error(res?.error || t('wr.uploadFailed'))
      }
    } catch {
      toast.error(t('wr.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  if (!editable) {
    return (
      <div className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{value || '—'}</ReactMarkdown>
      </div>
    )
  }

  return (
    <div onPasteCapture={handlePasteCapture} onKeyUp={recordCaret} onMouseUp={recordCaret}>
      <div data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
        <MDEditor
          value={value ?? ''}
          onChange={(v) => onChange(v ?? '')}
          preview="edit"
          height={height}
          visibleDragbar={true}
          highlightEnable={false}
          commands={editorCommands}
          extraCommands={[mdCommands.codeEdit, mdCommands.codePreview,
            mdCommands.divider, mdCommands.fullscreen]}
        />
      </div>
      <div className="wr-hint">{t('wr.pasteHint')}</div>
      <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/bmp"
        style={{ display: 'none' }} onChange={handleFileChosen} />

      {pendingFile && (
        <div className="modal-overlay" onClick={() => !uploading && setPendingFile(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.uploadImage')}</h3>
            <div style={{ fontSize: '.8em', color: 'var(--text-light)', marginBottom: 6 }}>{t('wr.imageFormats')}</div>
            <div className="alert-msg" style={{ fontSize: '.8em', marginBottom: 10 }}>{t('wr.imageLimitNote')}</div>
            <p style={{ fontSize: '.85em', marginBottom: 10 }}>
              <strong>{pendingFile.name}</strong>{' · '}
              {pendingFile.processing ? (
                <span style={{ color: 'var(--text-light)' }}>{t('wr.imageProcessing')}</span>
              ) : (
                <span>
                  {t('wr.imageOriginal')}: {fmtFileSize(pendingFile.originalSize)}
                  {pendingFile.processedSize < pendingFile.originalSize && (
                    <> {' → '}<strong style={{ color: '#16a34a' }}>
                      {t('wr.imageOptimizedLabel')}: {fmtFileSize(pendingFile.processedSize)}
                    </strong></>
                  )}
                </span>
              )}
            </p>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              {t('wr.imageCaption')}
              <textarea className="wr-caption" autoFocus rows={3}
                value={caption} placeholder={t('wr.imageCaptionPlaceholder')}
                onChange={(e) => setCaption(e.target.value)} />
            </label>
            <div className="modal-actions">
              <Button variant="secondary" disabled={uploading}
                onClick={() => setPendingFile(null)}>{t('wr.cancel')}</Button>
              <Button disabled={uploading || pendingFile.processing} onClick={doUpload}>
                {uploading ? t('wr.uploading') : t('wr.insertImage')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/** Serbest elle yazılabilen sayı alanı — yazarken boş bırakılabilir,
 *  yalnız rakam kabul eder; state'e anlık sayı yazılır. */
function NumInput({ label, value, onChange, editable }) {
  const [text, setText] = useState(value != null ? String(value) : '0')

  useEffect(() => {
    const parsed = text === '' ? 0 : parseInt(text, 10)
    if (value !== parsed) setText(value != null ? String(value) : '')
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  function handleChange(e) {
    const cleaned = e.target.value.replace(/[^0-9]/g, '')
    setText(cleaned)
    onChange(cleaned === '' ? 0 : parseInt(cleaned, 10))
  }

  return (
    <label className="wr-field wr-num">
      <span>{label}</span>
      <input type="text" inputMode="numeric" pattern="[0-9]*" value={text} disabled={!editable}
        onChange={handleChange}
        onBlur={() => { if (text === '') setText('0') }} />
    </label>
  )
}

export default function WeeklyReportsPage({ systemRole, teamId, teamName, resetNonce }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isAudit = systemRole === 'AUDIT'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'

  const [teams, setTeams] = useState([])
  // URL derin bağlantı (2026-09-13): w_team / team (bildirim kutusu), w_year, w_week, w_id, w_st, w_sort
  const [selTeamId, setSelTeamId] = useState(() => readUrlParam('w_team', null) || readUrlParam('team', null) || '')   // varsayılan: Tüm Takımlar (admin takım combobox'ı)
  const [year, setYear] = useState(() => readUrlInt('w_year', isoWeekInfo().year))
  const [years, setYears] = useState([])
  const [jumpDate, setJumpDate] = useState('')
  const [weekFilter, setWeekFilter] = useState(() => readUrlInt('w_week', null)) // "Tarihe Git" → tabloyu o haftaya daraltır
  const [openMenuId, setOpenMenuId] = useState(null)  // İşlemler kebab menüsü açık satır
  const [menuPos, setMenuPos] = useState({ top: 0, left: 0 }) // fixed konum (overflow'dan kaçış)
  const [reports, setReports] = useState([])
  const [loadingList, setLoadingList] = useState(true)
  const [selectedId, setSelectedId] = useState(() => readUrlInt('w_id', null))
  const [statusChip, setStatusChip] = useState(() => readUrlParam('w_st', '') || '')   // durum çipi / MINE (2026-09-13)
  const [listSort, setListSort] = useState(() => readUrlParam('w_sort', 'week|desc') || 'week|desc')
  const [thisWeek, setThisWeek] = useState(null)        // "bu hafta" şeridi (2026-09-13)
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
  const [summaryOpen, setSummaryOpen] = useState(false)  // 01·Özet akordeonu — varsayılan KAPALI
  const [monStats, setMonStats] = useState(null)         // İzleme göstergeleri (lazy, akordeon açılınca)
  const [monLoading, setMonLoading] = useState(false)
  const [managerMissing, setManagerMissing] = useState(false)
  const [teamChannels, setTeamChannels] = useState([])     // takım kanal şablonu (2026-09-13, ikinci tur)
  const [reminderNonce, setReminderNonce] = useState(0)    // hatırlatma durumu satırını elle gönderim sonrası tazele
  const [channelTab, setChannelTab] = useState(0)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [newModal, setNewModal] = useState(null)  // { year, week }
  const [rejectModal, setRejectModal] = useState(null) // { note }
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

  // İşlemler kebab menüsü — dışarı tıkla / kaydırma / yeniden boyutlandırmada kapan
  // (menü position:fixed olduğundan scroll'u takip etmez → kapatmak en doğrusu)
  useEffect(() => {
    const close = () => setOpenMenuId(null)
    const onDown = (e) => { if (!e.target.closest('.wr-menu-wrap')) setOpenMenuId(null) }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [])

  // Menüye tekrar tıklanınca (App.jsx wrResetNonce artar) açık rapor varsa listeye dön.
  // backToList yeniden kullanılır → kaydedilmemiş değişiklik onayı + kilit bırakma korunur.
  useEffect(() => {
    if (resetNonce > 0 && selectedId != null) backToList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetNonce])

  // Yıl dropdown'ı: rapor bulunan yıllar (geriye dönük erişim) — takım değişince yenilenir
  const loadYears = useCallback(async () => {
    const res = await api.weeklyReports.years(effTeamId ?? undefined)
    if (res?.success) setYears(res.data ?? [])
  }, [effTeamId])

  useEffect(() => { loadYears() }, [loadYears])

  // Son giriş zamanı canlı ayardan (2026-09-12, kullanıcı: "Deadline: every Friday at 15:00 konfigüratif
  // olmalı"). Yüklenene/başarısız olana kadar varsayılan (Cuma 15:00) gösterilir — sayfa kırılmaz.
  const [deadline, setDeadline] = useState(null)
  useEffect(() => {
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.deadline(); if (alive && r?.success && r.data) setDeadline(r.data) }
      catch { /* varsayılan kalır */ }
    })()
    return () => { alive = false }
  }, [])

  // Takvimde raporlu haftaların işaretlenmesi — yıl başına Set cache'i
  const [weekMarks, setWeekMarks] = useState({})

  useEffect(() => { setWeekMarks({}) }, [effTeamId])

  const ensureMarks = useCallback(async (y) => {
    if (weekMarks[y]) return
    const res = await api.weeklyReports.list({ teamId: effTeamId ?? undefined, year: y })
    const s = new Set((res?.success ? res.data ?? [] : []).map((r) => r.week_no))
    setWeekMarks((p) => ({ ...p, [y]: s }))
  }, [effTeamId, weekMarks])

  /** Tarihe Git: seçilen tarihin ISO haftası bulunur; kapsamda o haftanın
   *  raporu varsa doğrudan açılır, yoksa yıl filtresi yine o yıla çekilir.
   *  Seçilen tarih alanda görünür kalır. */
  /** Tarihe Git: seçilen tarihin ISO haftasını tabloya FİLTRE olarak uygular —
   *  raporu otomatik açmaz; yükleme göstergesiyle o haftanın kayıtlarını
   *  tabloda listeler (yoksa boş). Filtre chip'iyle temizlenebilir. */
  function goToDate(dateStr) {
    setJumpDate(dateStr)
    if (!dateStr) { setWeekFilter(null); return }
    const picked = new Date(dateStr + 'T12:00:00')
    // Elle yazım sırasında oluşan ara değerler (örn. yıl "0002") tetiklemesin
    if (picked.getFullYear() < 2000 || picked.getFullYear() > 2100) return
    const { year: y, week } = isoWeekInfo(picked)
    setWeekFilter(week)
    if (y !== year) setYear(y)  // yıl efekti loadList'i tetikler (loading + fetch)
    else loadList()             // aynı yıl: yükleme göstergesiyle tazele, sonra filtrele
  }

  const loadThisWeek = useCallback(async () => {
    try { const r = await api.weeklyReports.thisWeek(); if (r?.success) setThisWeek(r.data ?? null) } catch { /* şerit yoksa sayfa yine çalışır */ }
  }, [])

  const loadList = useCallback(async () => {
    setLoadingList(true)
    loadThisWeek()
    try {
      const res = await api.weeklyReports.list({ teamId: effTeamId ?? undefined, year })
      if (res?.success) {
        setReports(res.data ?? [])
        // Açık rapor (filtre değişimiyle) listeden kaybolduysa listeye dön
        if (selectedId && !res.data?.some((r) => r.id === selectedId)) {
          setSelectedId(null)
        }
      }
    } finally {
      setLoadingList(false)
    }
  }, [effTeamId, year, selectedId, loadThisWeek])

  useEffect(() => { loadList() }, [effTeamId, year]) // eslint-disable-line react-hooks/exhaustive-deps

  // URL eşitleme (2026-09-13): süzgeç/açık rapor paylaşılabilir, F5 açık raporu korur
  useUrlQuerySync(toUrlMapping({ selectedId, selTeamId: isAdmin ? selTeamId : '', year, weekFilter, statusChip, sort: listSort, currentYear: isoWeekInfo().year }))

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
      try { setContent(JSON.parse(r.content_json)) } catch { setContent(null) }
      setDirty(false)
      setChannelTab(0)
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
    setBusy(true)
    try {
      const res = await api.weeklyReports.save(report.id, JSON.stringify(content), report.version)
      if (res?.success) {
        if (showToast) toast.success(t('wr.saved'))
        setDirty(false)
        try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + report.id) } catch { /* */ }
        setReport((p) => ({ ...p, status: res.data.status, version: res.data.version }))
        loadList()
        return true
      }
      if (res?.error?.includes('VERSION_CONFLICT')) {
        // Başka kullanıcı araya kaydetmiş — yazılanlar yerel yedekte; banner çözüm sunar
        writeBackupNow()
        setConflict(res.error)
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
    if (s === 'SENT') return { label: t('wr.mailStatusSent'), color: '#16a34a' }
    if (s.startsWith('QUEUED_RETRY')) return { label: t('wr.mailStatusQueued'), color: '#d97706' }
    if (s === 'SKIPPED_NO_CONTACT') return { label: t('wr.mailStatusNoContact'), color: '#dc2626' }
    if (s === 'SKIPPED_DISABLED') return { label: t('wr.mailStatusDisabled'), color: '#71717a' }
    return { label: t('wr.mailStatusFailed'), color: '#dc2626' } // FAILED:*
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
      setContent(JSON.parse(pendingBackup.content_json))
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

  async function submit() {
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
  /** Komşu hafta (2026-09-13): listede varsa aç; yoksa oluşturma modalını o haftayla aç. */
  function gotoWeek(dir) {
    if (!report) return
    let y = report.report_year, w = report.week_no + dir
    if (w < 1) { y -= 1; w = 53 } else if (w > 53) { y += 1; w = 1 }
    if (dir < 0 && prevReport) { setSelectedId(prevReport.id); return }
    const hit = reports.find((r) => r.team_id === report.team_id && r.report_year === y && r.week_no === w)
    if (hit) { setSelectedId(hit.id); return }
    if (!isAudit) setNewModal({ year: y, week: w, teamId: String(report.team_id), carry: false })
  }
  function downloadYearCsv() {
    const name = (tid) => isAdmin ? (teams.find((tm) => tm.id === tid)?.name ?? '') : (teamName ?? '')
    const csv = buildYearCsv(displayedReports, name, t)
    try {
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = `haftalik-raporlar-${year}.csv`; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
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

  // Admin-only: Cuma hatırlatma maillerini cron beklemeden anında gönder (test kolaylığı)
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

  async function createReport() {
    const res = await api.weeklyReports.create({
      team_id: newModal.teamId ? Number(newModal.teamId) : undefined,
      year: newModal.year,
      week_no: newModal.week,
      carry_notes: !!newModal.carry,   // geçen haftadan devam (2026-09-13)
    })
    if (res?.success) {
      setNewModal(null)
      toast.success(t('wr.created'))
      setYear(newModal.year)
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

  async function backToList() {
    if (dirty) {
      const ok = await showConfirm({
        title: t('wr.unsavedLeaveTitle'),
        message: t('wr.unsavedLeaveMsg'),
        confirmText: t('wr.backToList'),
        cancelText: t('wr.cancel'),
      })
      if (!ok) return
      // Bilinçli vazgeçiş — yerel yedek de düşer
      try { localStorage.removeItem(DRAFT_BACKUP_PREFIX + (report?.id ?? '')) } catch { /* */ }
    }
    if (report && lockHeld) api.weeklyReports.unlock(report.id) // best-effort
    setSelectedId(null)
    loadList()
  }

  function addChannel() {
    const channels = content?.item4?.channels ?? []
    patch(['item4', 'channels'],
      [...channels, { id: 'c-' + Date.now(), name: t('wr.newChannelName'), notes_md: '' }])
    setChannelTab(channels.length)
  }

  /** Takım kanal şablonundaki eksik kanalları ekler (2026-09-13); mevcut adlar korunur. */
  function fillChannelsFromTemplate() {
    const channels = content?.item4?.channels ?? []
    const have = new Set(channels.map((c) => String(c.name || '').trim().toLocaleLowerCase('tr')))
    const missing = teamChannels.filter((n) => !have.has(String(n).trim().toLocaleLowerCase('tr')))
    if (!missing.length) return
    const stamp = Date.now()
    patch(['item4', 'channels'], [...channels, ...missing.map((n, i) => ({ id: `c-${stamp}-${i}`, name: n, notes_md: '' }))])
    setChannelTab(channels.length)
  }

  async function removeChannel(idx) {
    const ch = content.item4.channels[idx]
    const ok = await showConfirm({
      title: t('wr.deleteChannel'),
      message: t('wr.confirmDeleteChannel', ch.name),
      variant: 'danger',
      confirmText: t('wr.deleteChannel'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    const channels = content.item4.channels.filter((_, i) => i !== idx)
    patch(['item4', 'channels'], channels)
    setChannelTab(Math.max(0, channelTab - (idx <= channelTab ? 1 : 0)))
  }

  /** Durum rozeti — en son gerçek statü: APPROVED + gönderildi → "Gönderildi",
   *  APPROVED + gönderilmedi → "Onaylandı"; diğerleri durum eşlemesi. */
  const statusBadge = (status, sentAt) => {
    const label = status === 'APPROVED'
      ? (sentAt ? t('wr.statusSent') : t('wr.statusApproved'))
      : t(`wr.status${status === 'PENDING_APPROVAL' ? 'Pending' : status.charAt(0) + status.slice(1).toLowerCase()}`)
    return <span className={`wr-status-badge wr-status--${status}`}>{label}</span>
  }

  const i1 = content?.item1 ?? {}
  const i2 = content?.item2 ?? {}
  const channels = content?.item4?.channels ?? []
  const rawChannels = content?.item4?.channels
  const missingTemplateChannels = useMemo(() => {
    const have = new Set((rawChannels ?? []).map((c) => String(c.name || '').trim().toLocaleLowerCase('tr')))
    return teamChannels.filter((n) => !have.has(String(n).trim().toLocaleLowerCase('tr')))
  }, [rawChannels, teamChannels])
  // "Tarihe Git" hafta filtresi etkinse tablo o haftaya daraltılır
  const teamNameOf = (tid) => isAdmin ? (teams.find((tm) => tm.id === tid)?.name ?? '') : (teamName ?? '')
  const queueCtx = { isAdmin, isAudit, teamId }
  const facets = useMemo(() => statusFacets(weekFilter != null ? reports.filter((r) => r.week_no === weekFilter) : reports), [reports, weekFilter])
  const mineCount = useMemo(() => approvalQueue(reports, queueCtx).length, [reports, isAdmin, isAudit, teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  const displayedReports = useMemo(() => sortReports(
    filterByStatus(weekFilter != null ? reports.filter((r) => r.week_no === weekFilter) : reports, statusChip, queueCtx),
    listSort, teamNameOf), [reports, weekFilter, statusChip, listSort, teams, isAdmin, teamName, isAudit, teamId]) // eslint-disable-line react-hooks/exhaustive-deps
  // Sayfalama (2026-09-12, kullanıcı: "Weekly Reports tarafında paging yapılmamış"): liste yıl+takım ile
  // sınırlı (≤53 hafta × takım) ve takvim işaretleri (weekMarks) tüm listeyi istiyor → sayfalama YALNIZ
  // render'ı böler (InventoryManager deseni). "Tümünü seç" filtrelenmiş tüm liste üzerinde kalır.
  const pager = usePagination(displayedReports, { listKey: 'weekly-reports', resetDeps: [effTeamId, year, weekFilter, statusChip, listSort] })

  // Üstte ve altta aynı aksiyon barı — kaydırmada ikisi de sticky görünür
  const actionButtons = report && (
    <>
      {canDeleteRow(report) && (
        <Button variant="destructive" onClick={() => deleteReport(report)} disabled={busy}>
          <Trash2 size={14} /> {t('wr.deleteReport')}
        </Button>
      )}
      <Button variant="secondary" onClick={openPreview} disabled={busy}>
        <Eye size={14} /> {t('wr.preview')}
      </Button>
      <Button variant="secondary" className="wr-print-btn" onClick={() => window.print()} disabled={busy}>
        <Printer size={14} /> {t('wr.print')}
      </Button>
      {editable && !conflict && (
        <Button onClick={() => save()} disabled={busy || !dirty}>
          <Save size={14} /> {busy ? t('wr.saving') : t('wr.save')}
        </Button>
      )}
      {canSubmit && !conflict && (
        <Button variant="success" onClick={submit} disabled={busy}>
          <Send size={14} /> {t('wr.submit')}
        </Button>
      )}
      {showApproval && (
        <>
          <Button variant="success" onClick={approve} disabled={busy || managerMissing}>
            <CheckCircle size={14} /> {t('wr.approve')}
          </Button>
          <Button variant="secondary" onClick={() => setRejectModal({ note: '' })} disabled={busy}>
            <Undo2 size={14} /> {t('wr.reject')}
          </Button>
        </>
      )}
      {canResend && (
        <Button variant="success" onClick={resendReport} disabled={busy || managerMissing}>
          <RefreshCcw size={14} /> {t('wr.resend')}
        </Button>
      )}
      {canReopen && (
        <Button variant="secondary" onClick={reopenReport} disabled={busy}>
          <FilePenLine size={14} /> {t('wr.reopen')}
        </Button>
      )}
    </>
  )

  return (
    <div className="admin-section wr-editor">
      {/* ── Üst bar — listede filtreler, editörde Listeye Dön ── */}
      <div className="admin-section-header">
        <h3>{t('wr.title')}</h3>
        <div className="wr-filters">
          {!selectedId ? (
            <>
              {isAdmin && (
                <div className="wr-flt">
                  <span>{t('wr.team')}</span>
                  <SearchableSelect value={selTeamId} onChange={(v) => { setSelTeamId(v); setLoadingList(true) }}
                    placeholder={t('wr.allTeams')} searchThreshold={2} ariaLabel={t('wr.team')}
                    options={[{ value: '', label: t('wr.allTeams') },
                      ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))]} />
                </div>
              )}
              <div className="wr-flt">
                <span>{t('wr.year')}</span>
                <SearchableSelect value={year} onChange={(v) => { setYear(Number(v)); setLoadingList(true) }}
                  ariaLabel={t('wr.year')}
                  options={[...new Set([...years, year])].sort((a, b) => b - a)
                    .map((y) => ({ value: y, label: String(y) }))} />
              </div>
              <div className="wr-flt">
                <span>{t('wr.goToDate')}</span>
                <WeekDatePicker value={jumpDate} onChange={goToDate}
                  placeholder={t('wr.pickDate')} hint={t('wr.goToDateHint')}
                  isMarked={(y, w) => weekMarks[y]?.has(w) ?? false}
                  onViewYearChange={ensureMarks} />
              </div>
              <Button variant="secondary" size="sm" onClick={loadList}
                title={t('app.refresh')} aria-label={t('app.refresh')}>
                <RefreshCcw size={13} />
              </Button>
              {!isAudit && (
                <Button variant="success" data-tour="wr-new"
                  onClick={() => setNewModal({ ...isoWeekInfo(), teamId: isAdmin ? selTeamId : String(teamId ?? ''), carry: false })}>
                  <Plus size={14} /> {t('wr.newReport')}
                </Button>
              )}
            </>
          ) : (
            <Button variant="secondary" onClick={backToList}>
              <ArrowLeft size={14} /> {t('wr.backToList')}
            </Button>
          )}
        </div>
      </div>

      {/* ── Admin-only: hatırlatma maillerini cron beklemeden gönder (test kolaylığı) ── */}
      {!selectedId && isAdmin && (
        <div className="wr-reminder-trigger">
          <Button type="button" variant="secondary" size="sm"
            onClick={sendReminders} disabled={sendingReminder}>
            <Bell size={14} /> {sendingReminder ? t('wr.reminderSending') : t('wr.sendReminderNow')}
          </Button>
          <WeeklyReminderStatus nonce={reminderNonce} />
        </div>
      )}

      {/* Takım tamamlama panosu (2026-09-12, #21): takım × hafta — yalnız global admin/AUDIT'e veri gelir */}
      {!selectedId && (isAdmin || isAudit) && (
        <WeeklyCompletionBoard year={year} onPick={(tid, week, reportId) => {
          if (reportId) { setSelectedId(reportId); return }
          if (isAdmin) setSelTeamId(String(tid))
          setWeekFilter(week); setJumpDate('')
        }} />
      )}

      {/* ── "Bu hafta" şeridi (2026-09-13): takımın bu haftaki rapor durumu + son giriş geri sayımı ── */}
      {!selectedId && (
        <WeeklyThisWeekStrip data={thisWeek} loading={loadingList && !thisWeek} canCreate={!isAudit}
          onOpen={(id) => setSelectedId(id)}
          onCreate={(tid, y, w) => setNewModal({ year: y, week: w, teamId: String(tid), carry: false })} />
      )}

      {/* ── "Nasıl girilir?" yardım kartı — kısa, açılır-kapanır (liste görünümünde) ── */}
      {!selectedId && (
        <div className={`wr-help${helpOpen ? ' is-open' : ''}`} data-tour="wr-help">
          <button type="button" className="wr-help-toggle"
            onClick={() => { const n = !helpOpen; setHelpOpen(n); try { localStorage.setItem('wr-help-open', String(n)) } catch {} }}
            aria-expanded={helpOpen}>
            <HelpCircle size={15} />
            <span className="wr-help-title">{t('wr.helpTitle')}</span>
            <ChevronDown size={14} className="wr-help-chevron" />
          </button>
          {helpOpen && (
            <div className="wr-help-body">
              <ol className="wr-help-steps">
                <li>{t('wr.helpStep1')}</li>
                <li>{t('wr.helpStep2')}</li>
                <li>{t('wr.helpStep3')}</li>
                <li>{t('wr.helpStep4')}</li>
                <li>{t('wr.helpStep5')}</li>
              </ol>
              <div className="wr-help-deadline">⏰ {t('wr.helpDeadline',
                deadline ? (lang === 'tr' ? deadline.day_tr : deadline.day_en) : (lang === 'tr' ? 'Cuma' : 'Friday'),
                deadline?.time || '15:00')}</div>
            </div>
          )}
        </div>
      )}

      {/* ── Liste görünümü — hafta bazlı sıralı (backend hafta desc döner) ── */}
      {!selectedId && (
        loadingList ? (
          <LoadingBlock label={t('app.loading')} fullWidth />
        ) : (
          <>
            {weekFilter != null && (
              <div style={{
                display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: 12,
                padding: '5px 12px', borderRadius: 16, border: '1px solid var(--border)',
                background: 'var(--bg-card)', fontSize: '.85em', fontWeight: 600,
              }}>
                📅 {formatWeekRange(year, weekFilter, lang)}
                <button type="button" title={t('wr.clearFilter')} aria-label={t('wr.clearFilter')}
                  onClick={() => { setWeekFilter(null); setJumpDate('') }}
                  style={{ border: 'none', background: 'none', cursor: 'pointer', color: 'var(--text-light)', fontSize: '1.1em', lineHeight: 1 }}>✕</button>
              </div>
            )}
            <div className="wr-listbar">
              <WeeklyStatusChips facets={facets} value={statusChip} onChange={(v) => { setStatusChip(v); setSelectedIds(new Set()) }}
                mineCount={mineCount} showMine={!isAudit && (isAdmin || isTeamAdmin || mineCount > 0)} />
              <span className="wr-spacer" />
              <Button type="button" variant="secondary" size="sm" onClick={downloadYearCsv} title={t('wr.csvTitle')} data-tour="wr-csv" disabled={!displayedReports.length}>
                <Download size={13} /> CSV
              </Button>
            </div>
            {isAdmin && selectedIds.size > 0 && (
              <div className="wr-bulk-bar">
                <span className="wr-bulk-count">{t('wr.selectedCount', selectedIds.size)}</span>
                <Button type="button" size="sm" onClick={() => openTransfer([...selectedIds])}>
                  <ArrowRightLeft size={14} /> {t('wr.transferSelected')}
                </Button>
                <Button type="button" variant="success" size="sm" onClick={approveSelected} disabled={busy}>
                  <CheckCircle size={14} /> {t('wr.bulkApprove')}
                </Button>
                <Button type="button" variant="secondary" size="sm" onClick={() => setSelectedIds(new Set())}>
                  {t('wr.clearSelection')}
                </Button>
              </div>
            )}
            {displayedReports.length ? (
          <div className="admin-table-wrap">
            <table className="admin-table wr-table" data-tour="wr-table">
              <thead>
                <tr>
                  {isAdmin && (
                    <th style={{ width: 32 }}>
                      <input type="checkbox" title={t('wr.selectAll')}
                        checked={displayedReports.length > 0 && displayedReports.every((r) => selectedIds.has(r.id))}
                        onChange={() => toggleSelectAll(displayedReports)} />
                    </th>
                  )}
                  <SortTh col="week" label={t('wr.colWeek')} sort={listSort} onSort={setListSort} />
                  <SortTh col="team" label={t('wr.team')} sort={listSort} onSort={setListSort} />
                  <SortTh col="status" label={t('wr.statusCol')} sort={listSort} onSort={setListSort} />
                  <SortTh col="score" label={t('wr.colScore')} sort={listSort} onSort={setListSort} />
                  <th>{t('wr.colCreated')}</th>
                  <SortTh col="updated" label={t('wr.colUpdated')} sort={listSort} onSort={setListSort} />
                  <th>{t('wr.colApproved')}</th>
                  <th>{t('wr.colSent')}</th>
                  <th>{t('wr.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {pager.pageItems.map((r) => (
                  <tr key={r.id} onClick={() => setSelectedId(r.id)} style={{ cursor: 'pointer' }} tabIndex={0}
                    onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setSelectedId(r.id) } }}>
                    {isAdmin && (
                      <td onClick={(e) => e.stopPropagation()} style={{ width: 32 }}>
                        {/* Ad = HAFTA + TAKIM: toplu "onayla ve gönder" onayı yalnız ADET söylüyor; adsız
                            kutuyla ekran okuyucu kullanıcısı yanlış haftayı gönderebilirdi (2026-09-25, R5). */}
                        <input type="checkbox" checked={selectedIds.has(r.id)} onChange={() => toggleSelect(r.id)}
                          aria-label={t('bulk.selectOneFor', `${formatWeekRange(r.report_year, r.week_no, lang)} · ${teams.find((tm) => tm.id === r.team_id)?.name ?? r.team_id}`)} />
                      </td>
                    )}
                    <td data-label={t('wr.colWeek')}><strong>{formatWeekRange(r.report_year, r.week_no, lang)}</strong></td>
                    <td data-label={t('wr.team')}>{isAdmin ? (teams.find((tm) => tm.id === r.team_id)?.name ?? r.team_id) : (teamName ?? r.team_id)}</td>
                    <td data-label={t('wr.statusCol')}>
                      {statusBadge(r.status, r.sent_at)}
                      {r.reject_note && r.status !== 'APPROVED' && <span className="wr-reject-flag" title={t('wr.rejectFlagTitle')}>↩</span>}
                      {r.comment_count > 0 && <span className="wr-cm-badge" title={t('wr.cm.badge', r.comment_count)}><MessageSquare size={11} aria-hidden="true" />{r.comment_count}</span>}
                      {r.editing_by && (
                        <div style={{ fontSize: '.72em', color: 'var(--text-light)', marginTop: 3 }}>
                          ✏️ <UserBadge username={r.editing_by} inline size="sm" /> {t('wr.editingNow')}
                        </div>
                      )}
                      {mailProblem(r.last_mail_status) && (
                        <div title={r.last_mail_status}
                          style={{ fontSize: '.72em', color: '#dc2626', fontWeight: 700, marginTop: 3 }}>
                          ⚠ {t('wr.sendError')}
                        </div>
                      )}
                    </td>
                    <td data-label={t('wr.colScore')}><ScoreBadge score={r.score} title={t('wr.scoreTitle')} /></td>
                    <td data-label={t('wr.colCreated')}>{actorCell(r.created_by, r.created_at)}</td>
                    <td data-label={t('wr.colUpdated')}>{actorCell(r.updated_by, r.updated_at)}</td>
                    <td data-label={t('wr.colApproved')}>{actorCell(r.approved_by, r.approved_at)}</td>
                    <td data-label={t('wr.colSent')}>{r.sent_at ? formatDate(r.sent_at) : '—'}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <div className="wr-menu-wrap">
                        <Button variant="outline" size="sm" title={t('wr.actions')}
                          aria-label={`${formatWeekRange(r.report_year, r.week_no, lang)} — ${t('wr.actions')}`}
                          style={{ background: '#eef2f7', color: '#3f3f46' }}
                          onClick={(e) => {
                            if (openMenuId === r.id) { setOpenMenuId(null); return }
                            const rect = e.currentTarget.getBoundingClientRect()
                            setMenuPos({ top: rect.bottom + 4, left: Math.max(8, rect.right - 168) })
                            setOpenMenuId(r.id)
                          }}>
                          <Menu size={15} />
                        </Button>
                        {openMenuId === r.id && (
                          <div className="wr-menu-pop"
                            style={{ position: 'fixed', top: menuPos.top, left: menuPos.left, right: 'auto' }}>
                            <button onClick={() => { setOpenMenuId(null); setSelectedId(r.id) }}>
                              <Eye size={14} /> {t('wr.open')}
                            </button>
                            <button onClick={() => { setOpenMenuId(null); openPreviewById(r.id) }}>
                              <Mail size={14} /> {t('wr.preview')}
                            </button>
                            <button onClick={() => { setOpenMenuId(null); openMailHistory(r) }}>
                              <History size={14} /> {t('wr.history')}
                            </button>
                            {r.status === 'PENDING_APPROVAL' && !isAudit && (
                              <>
                                <button onClick={() => { setOpenMenuId(null); approveById(r.id) }}>
                                  <CheckCircle size={14} /> {t('wr.approve')}
                                </button>
                                <button onClick={() => { setOpenMenuId(null); setRejectModal({ note: '', id: r.id }) }}>
                                  <Undo2 size={14} /> {t('wr.reject')}
                                </button>
                              </>
                            )}
                            {isAdmin && (
                              <button onClick={() => { setOpenMenuId(null); openTransfer([r.id]) }}>
                                <ArrowRightLeft size={14} /> {t('wr.transfer')}
                              </button>
                            )}
                            {canDeleteRow(r) && (
                              <button className="danger" onClick={() => { setOpenMenuId(null); deleteReport(r) }}>
                                <Trash2 size={14} /> {t('wr.deleteReport')}
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <PaginationBar {...pager} />
          </div>
            ) : (
              <div className="empty-state">
                {weekFilter != null ? t('wr.noReportForWeek') : t('wr.noReportSelected')}
              </div>
            )}
          </>
        )
      )}

      {/* ── Rapor yükleniyor — boş ekran yerine gösterge ── */}
      {selectedId && !report && loadingReport && (
        <LoadingBlock label={t('app.loading')} fullWidth />
      )}

      {report && content && (
        <>
          {/* ── Executive brief başlığı — takım + ISO hafta / durum + onaylayan ── */}
          <div className="wr-brief">
            <div className="wr-brief-left">
              <div className="wr-brief-team">
                {isAdmin ? (teams.find((tm) => tm.id === report.team_id)?.name ?? teamName ?? report.team_id) : (teamName ?? report.team_id)}
              </div>
              <div className="wr-brief-week">
                <button type="button" className="wr-nav" onClick={() => gotoWeek(-1)} title={t('wr.prevWeek')} aria-label={t('wr.prevWeek')}><ChevronLeft size={14} /></button>
                {report.week_label}
                <button type="button" className="wr-nav" onClick={() => gotoWeek(1)} title={t('wr.nextWeek')} aria-label={t('wr.nextWeek')}><ChevronRight size={14} /></button>
              </div>
            </div>
            <div className="wr-brief-right">
              {statusBadge(report.status, report.sent_at)}
              {report.approved_by && (
                <span className="wr-brief-meta">{t('wr.approvedByAt', report.approved_by, formatDate(report.approved_at))}</span>
              )}
              {report.sent_at && <span className="wr-brief-meta">{t('wr.sentAt')} {formatDate(report.sent_at)}</span>}
              <span className="wr-brief-meta">{t('wr.lastEdit')} {report.updated_by} · {formatDate(report.updated_at)}</span>
              {lastAutoSave && (
                <span className="wr-autosave">✓ {t('wr.autoSaved')} {lastAutoSave.toLocaleTimeString(
                  lang === 'en' ? 'en-GB' : 'tr-TR', { hour: '2-digit', minute: '2-digit' })}</span>
              )}
            </div>
          </div>

          {/* ── Üst aksiyon barı (sticky) — özet akordeonunun ÜSTÜNDE ── */}
          <div className="modal-actions wr-actions wr-actions-top">{actionButtons}</div>

          {/* ── 01 · Özet — executive brief + KPI şeridi, akordeon (varsayılan kapalı; yazdırırken açık) ── */}
          <div className="wr-accordion">
            <button type="button" className="wr-accordion-hdr" onClick={() => setSummaryOpen((o) => !o)}
              aria-expanded={summaryOpen}>
              <span className="wr-accordion-title">{t('wr.sumSection')}</span>
              <ChevronDown size={16} className={`wr-accordion-chev${summaryOpen ? ' open' : ''}`} />
            </button>
            <div className={`wr-accordion-body${summaryOpen ? ' open' : ''}`}>
              <WeeklySummaryBrief kpis={kpis} t={t} lang={lang} />
              <WeeklyKpiStrip kpis={kpis} loading={kpisLoading} t={t} />
              <WeeklyMonitoringStrip stats={monStats} loading={monLoading} t={t} />
            </div>
          </div>

          {/* ── İade notu / müdür uyarısı ── */}
          {report.reject_note && report.status !== 'APPROVED' && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              <strong>{t('wr.rejectNoteBanner')}</strong> {report.reject_note}
            </div>
          )}
          {managerMissing && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              {t('wr.managerContactMissing')}
            </div>
          )}
          {weekLocked && (
            <div className="alert-msg" style={{ marginBottom: 12 }}>
              {t('wr.weekLockedBanner')}
            </div>
          )}
          {!lockHeld && lockHolder && canModifyRow(report) && (
            <div className="alert-msg" style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <span>{t('wr.lockedBy', lockHolder.name ?? '?')}</span>
              <Button type="button" variant="secondary" size="sm" onClick={retryLock}>
                {t('wr.lockRetry')}
              </Button>
              {isAdmin && (
                <Button type="button" variant="destructive" size="sm" onClick={takeoverLock}>
                  {t('wr.lockTakeover')}
                </Button>
              )}
            </div>
          )}
          {conflict && (
            <div className="alert-msg" style={{
              marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
              background: '#fde8e8', color: '#9b1c1c',
            }}>
              <span>⚠ {t('wr.conflictBanner', conflict.replace('VERSION_CONFLICT: ', ''))}</span>
              <Button type="button" variant="secondary" size="sm" onClick={() => loadReport(report.id)}>
                {t('wr.loadLatest')}
              </Button>
            </div>
          )}
          {pendingBackup && (
            <div className="alert-msg" style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <span>💾 {t('wr.backupFound',
                new Date(pendingBackup.saved_at).toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}</span>
              <Button type="button" variant="secondary" size="sm" onClick={restoreBackup}>
                {t('wr.restore')}
              </Button>
              <Button type="button" variant="outline" size="sm" onClick={discardBackup}>
                {t('wr.discardBackup')}
              </Button>
            </div>
          )}

          {/* ── Madde 1 ── */}
          <div className="show-section-header">{t('wr.item1Title')}</div>
          <div className="wr-fields">
            <NumInput label={t('wr.urgent')} value={i1.urgent} editable={editable} onChange={(v) => patchSeverity('urgent', v)} />
            <NumInput label={t('wr.high')}   value={i1.high}   editable={editable} onChange={(v) => patchSeverity('high', v)} />
            <NumInput label={t('wr.medium')} value={i1.medium} editable={editable} onChange={(v) => patchSeverity('medium', v)} />
            <NumInput label={t('wr.low')}    value={i1.low}    editable={editable} onChange={(v) => patchSeverity('low', v)} />
            {/* Toplam türetilir — elle girilmez */}
            <NumInput label={t('wr.total')}  value={i1.total}  editable={false} onChange={() => {}} />
          </div>
          {prevContent && (
            <div className="wr-deltas" title={t('wr.deltaTitle', prevReport?.week_label || '')}>
              <span>{t('wr.deltaVsPrev')}:</span>
              <DeltaBadge cur={i1.urgent} prev={prevContent.item1.urgent} /> <DeltaBadge cur={i1.high} prev={prevContent.item1.high} />
              <DeltaBadge cur={i1.medium} prev={prevContent.item1.medium} /> <DeltaBadge cur={i1.low} prev={prevContent.item1.low} />
              <DeltaBadge cur={i1.total} prev={prevContent.item1.total} />
            </div>
          )}
          {suggest && editable && (
            <div className="wr-suggest-line" data-tour="wr-suggest">
              <Sparkles size={13} /> {t('wr.sugLine', suggest.alarms_opened ?? '—', suggest.critical_certs ?? '—', suggest.open_incidents ?? '—')}
            </div>
          )}
          <PrevNoteToggle open={!!prevOpen.item1} onToggle={() => setPrevOpen((o) => ({ ...o, item1: !o.item1 }))} note={prevContent?.item1?.notes_md} weekLabel={prevReport?.week_label} />
          <div className="wr-fields">
            <label className="wr-field wr-grow1">
              <span>{t('wr.statusText')}</span>
              <SearchableSelect value={i1.status_text ?? ''} disabled={!editable}
                onChange={(v) => patch(['item1', 'status_text'], v)}
                placeholder={t('wr.statusWorking')}
                options={[
                  // Eski raporlardaki serbest metin değeri listede yoksa seçenek olarak korunur
                  ...(i1.status_text && !ITEM1_STATUS_CHOICES.some((s) => s.value === i1.status_text)
                    ? [{ value: i1.status_text, label: i1.status_text }] : []),
                  ...ITEM1_STATUS_CHOICES.map((s) => ({ value: s.value, label: t(s.key) })),
                ]} />
            </label>
            <label className="wr-field wr-grow2">
              <span>{t('wr.trackingUrl')}</span>
              <input value={i1.tracking_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item1', 'tracking_url'], e.target.value)} />
            </label>
          </div>
          <MdField value={i1.notes_md} editable={editable} reportId={report.id}
            onChange={(v) => patch(['item1', 'notes_md'], v)} height={180} />

          {/* ── Madde 2 ── */}
          <div className="show-section-header">{t('wr.item2Title')}</div>
          <div className="wr-fields">
            <NumInput label={t('wr.openIncidents')}  value={i2.open_incidents}  editable={editable} onChange={(v) => patch(['item2', 'open_incidents'], v)} />
            <NumInput label={t('wr.problemRecords')} value={i2.problem_records} editable={editable} onChange={(v) => patch(['item2', 'problem_records'], v)} />
            <NumInput label={t('wr.postmortems')}    value={i2.postmortems}     editable={editable} onChange={(v) => patch(['item2', 'postmortems'], v)} />
          </div>
          {(prevContent || (suggest && editable)) && (
            <div className="wr-deltas">
              {prevContent && <><span>{t('wr.deltaVsPrev')}:</span>
                <DeltaBadge cur={i2.open_incidents} prev={prevContent.item2.open_incidents} /> <DeltaBadge cur={i2.problem_records} prev={prevContent.item2.problem_records} />
                <DeltaBadge cur={i2.postmortems} prev={prevContent.item2.postmortems} /></>}
              {suggest && editable && <SuggestBadge value={suggest.open_incidents} current={i2.open_incidents} label={t('wr.openIncidents')} onApply={(v) => patch(['item2', 'open_incidents'], v)} />}
            </div>
          )}
          <PrevNoteToggle open={!!prevOpen.item2} onToggle={() => setPrevOpen((o) => ({ ...o, item2: !o.item2 }))} note={prevContent?.item2?.notes_md} weekLabel={prevReport?.week_label} />
          {/* Her kayıt türü için ayrı takip linki; eski raporlardaki genel link doluysa o da gösterilir */}
          <div className="wr-fields">
            <label className="wr-field wr-grow1">
              <span>{t('wr.incidentsUrl')}</span>
              <input value={i2.incidents_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item2', 'incidents_url'], e.target.value)} />
            </label>
            <label className="wr-field wr-grow1">
              <span>{t('wr.problemsUrl')}</span>
              <input value={i2.problems_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item2', 'problems_url'], e.target.value)} />
            </label>
            <label className="wr-field wr-grow1">
              <span>{t('wr.postmortemsUrl')}</span>
              <input value={i2.postmortems_url ?? ''} disabled={!editable} placeholder="https://..."
                onChange={(e) => patch(['item2', 'postmortems_url'], e.target.value)} />
            </label>
            {i2.tracking_url ? (
              <label className="wr-field wr-grow1">
                <span>{t('wr.trackingUrl')}</span>
                <input value={i2.tracking_url} disabled={!editable} placeholder="https://..."
                  onChange={(e) => patch(['item2', 'tracking_url'], e.target.value)} />
              </label>
            ) : null}
          </div>
          <MdField value={i2.notes_md} editable={editable} reportId={report.id}
            onChange={(v) => patch(['item2', 'notes_md'], v)} height={180} />

          {/* ── Madde 3 ── */}
          <div className="show-section-header">{t('wr.item3Title')}</div>
          <div style={{ marginTop: 10 }}>
            <MdField value={content?.item3?.notes_md} editable={editable} reportId={report.id}
              onChange={(v) => patch(['item3', 'notes_md'], v)} height={240} />
          </div>
          <PrevNoteToggle open={!!prevOpen.item3} onToggle={() => setPrevOpen((o) => ({ ...o, item3: !o.item3 }))} note={prevContent?.item3?.notes_md} weekLabel={prevReport?.week_label} />

          {/* ── Madde 4 — kanallar ── */}
          <div className="show-section-header">{t('wr.item4Title')}</div>
          <div className="admin-tabs" style={{ marginTop: 10 }}>
            {channels.map((ch, idx) => (
              <button key={ch.id} type="button"
                className={`admin-tab-btn${channelTab === idx ? ' active' : ''}`}
                onClick={() => setChannelTab(idx)}>
                {ch.name}
              </button>
            ))}
            {editable && (
              <button type="button" className="admin-tab-btn" onClick={addChannel}>
                <Plus size={12} /> {t('wr.addChannel')}
              </button>
            )}
            {editable && missingTemplateChannels.length > 0 && (
              <button type="button" className="admin-tab-btn wr-tpl-btn" onClick={fillChannelsFromTemplate}
                title={t('wr.fillChannelsTitle', missingTemplateChannels.join(', '))}>
                <ListPlus size={12} /> {t('wr.fillChannels', missingTemplateChannels.length)}
              </button>
            )}
          </div>
          {channels[channelTab] && (
            <div style={{ marginTop: 10 }}>
              {editable && (
                <div className="wr-fields" style={{ marginBottom: 8 }}>
                  <label className="wr-field">
                    <span>{t('wr.channelName')}</span>
                    <input value={channels[channelTab].name}
                      onChange={(e) => patch(['item4', 'channels', channelTab, 'name'], e.target.value)} />
                  </label>
                  <Button type="button" variant="destructive" size="sm" onClick={() => removeChannel(channelTab)}>
                    <Trash2 size={12} /> {t('wr.deleteChannel')}
                  </Button>
                </div>
              )}
              <MdField value={channels[channelTab].notes_md} editable={editable} reportId={report.id}
                onChange={(v) => patch(['item4', 'channels', channelTab, 'notes_md'], v)} height={220} />
            </div>
          )}

          {/* ── Yorum dizisi (2026-09-13, ikinci tur): PO ↔ takım gidiş-gelişi; durum geçişinde tazelenir ── */}
          <WeeklyComments reportId={report.id} canWrite={!isAudit} nonce={report.status} />

          {/* ── Alt aksiyon barı (sticky) ── */}
          <div className="modal-actions wr-actions">{actionButtons}</div>
        </>
      )}

      {/* ── Yeni rapor modalı ── */}
      {newModal && (
        <div className="modal-overlay" onClick={() => setNewModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.newReport')}</h3>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em', marginBottom: 10 }}>
              <span>{t('wr.team')} {isAdmin && <span className="req-star">*</span>}</span>
              {isAdmin ? (
                <SearchableSelect value={newModal.teamId}
                  onChange={(v) => setNewModal({ ...newModal, teamId: v })}
                  placeholder={t('wr.selectTeam')} searchThreshold={2}
                  options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
              ) : (
                <input value={teamName ?? ''} disabled />
              )}
            </label>
            <div className="form-grid">
              <label>
                {t('wr.year')}
                <input type="number" value={newModal.year}
                  onChange={(e) => setNewModal({ ...newModal, year: parseInt(e.target.value || '0', 10) })} />
              </label>
              <label>
                {t('wr.week')}
                <input type="number" min="1" max="53" value={newModal.week}
                  onChange={(e) => setNewModal({ ...newModal, week: parseInt(e.target.value || '0', 10) })} />
              </label>
            </div>
            <div style={{
              marginTop: 10, padding: '9px 12px', borderRadius: 8,
              border: '1px solid var(--border)', fontSize: '.9em', fontWeight: 700,
            }}>
              📅 {formatWeekRange(newModal.year, newModal.week, lang)}
            </div>
            <div className="wr-carry" role="radiogroup" aria-label={t('wr.carryTitle')}>
              <label><input type="radio" name="wr-carry" checked={!newModal.carry} onChange={() => setNewModal({ ...newModal, carry: false })} /> {t('wr.carryBlank')}</label>
              <label><input type="radio" name="wr-carry" checked={!!newModal.carry} onChange={() => setNewModal({ ...newModal, carry: true })} /> {t('wr.carryPrev')}</label>
            </div>
            <p style={{ fontSize: '.82em', color: 'var(--text-light)', marginTop: 8 }}>{newModal.carry ? t('wr.carryHint') : t('wr.templateHint')}</p>
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setNewModal(null)}>{t('wr.cancel')}</Button>
              <Button onClick={createReport}
                disabled={isAdmin && !newModal.teamId}>{t('wr.create')}</Button>
            </div>
          </div>
        </div>
      )}

      {/* ── İade modalı ── */}
      {rejectModal && (
        <div className="modal-overlay" onClick={() => setRejectModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.rejectModalTitle')}</h3>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              {t('wr.rejectNote')} <span className="req-star">*</span>
              <textarea rows={5} value={rejectModal.note}
                onChange={(e) => setRejectModal((m) => ({ ...m, note: e.target.value }))} />
            </label>
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setRejectModal(null)}>{t('wr.cancel')}</Button>
              <Button onClick={doReject} disabled={busy}>{t('wr.reject')}</Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Takım transferi (toplu / tekil) ── */}
      {transferModal && (
        <div className="modal-overlay" onClick={() => setTransferModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('wr.transferTitle')}</h3>
            <p style={{ fontSize: '.88em', color: 'var(--text-light)', marginTop: 0 }}>
              {t('wr.transferDesc', transferModal.ids.length)}
            </p>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '.9em' }}>
              <span>{t('wr.transferTarget')} <span className="req-star">*</span></span>
              <SearchableSelect
                value={transferTeamId}
                onChange={(v) => setTransferTeamId(v)}
                placeholder={t('wr.transferTargetPh')}
                searchThreshold={2}
                options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))}
              />
            </label>
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setTransferModal(null)}>{t('wr.cancel')}</Button>
              <Button onClick={doTransfer} disabled={transferring || !transferTeamId}>
                {transferring ? t('wr.transferring') : t('wr.transferConfirm')}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Gönderim geçmişi ── */}
      {mailHistory && (
        <div className="modal-overlay" onClick={() => { setMailHistory(null); setOpenMailBody(null) }}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 860, width: '100%', maxHeight: '88vh', display: 'flex', flexDirection: 'column' }}>
            <h3>{t('wr.mailHistoryTitle', mailHistory.report.week_label)}</h3>
            <div style={{ overflowY: 'auto', flex: 1, paddingRight: 4 }}>
              {!mailHistory.items.length && (
                <div className="empty-state">{t('wr.mailHistoryEmpty')}</div>
              )}
              {mailHistory.items.map((m) => {
                const st = mailStatusInfo(m.status)
                return (
                  <div key={m.id} style={{
                    border: '1px solid var(--border)', borderRadius: 8,
                    marginBottom: 12, overflow: 'hidden',
                  }}>
                    <div style={{
                      display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap',
                      padding: '8px 12px', borderBottom: '1px solid var(--border)',
                    }}>
                      <strong style={{ fontSize: '.9em' }}>{t(`wr.mailType${m.mail_type}`)}</strong>
                      {st && (
                        <span title={m.status} style={{
                          background: st.color, color: '#fff', padding: '2px 9px',
                          borderRadius: 10, fontSize: '.74em', fontWeight: 700,
                        }}>{st.label}</span>
                      )}
                      <span style={{ marginLeft: 'auto', fontSize: '.78em', color: 'var(--text-light)' }}>
                        {formatDate(m.created_at)}
                      </span>
                    </div>
                    <div style={{ padding: '10px 12px', fontSize: '.85em', display: 'grid', gap: 4 }}>
                      <div><strong>{t('wr.mailFrom')}:</strong> {m.from_address || '—'}</div>
                      <div><strong>{t('wr.mailTo')}:</strong> {m.to_addresses || '—'}</div>
                      {m.cc_addresses && <div><strong>CC:</strong> {m.cc_addresses}</div>}
                      <div><strong>{t('wr.mailSubject')}:</strong> {m.subject}</div>
                      <div><strong>{t('wr.mailBy')}:</strong> {m.created_by}</div>
                      {mailProblem(m.status) && (
                        <div style={{ color: '#dc2626', fontWeight: 600 }}>⚠ {m.status}</div>
                      )}
                    </div>
                    <div style={{ padding: '0 12px 10px' }}>
                      <Button type="button" variant="secondary" size="sm"
                        onClick={() => setOpenMailBody(openMailBody === m.id ? null : m.id)}>
                        {openMailBody === m.id ? t('wr.mailHideBody') : t('wr.mailShowBody')}
                      </Button>
                      {openMailBody === m.id && (
                        // allow-same-origin: görseller oturum çerezi ile yüklenir; script yok
                        <iframe title={`mail-${m.id}`} srcDoc={mailPreviewSrcDoc(m.body_html)} sandbox="allow-same-origin"
                          style={{
                            width: '100%', height: 420, marginTop: 8,
                            border: '1px solid var(--border)', borderRadius: 8, background: '#f4f6f8',
                          }} />
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
            <div className="modal-actions">
              <Button variant="secondary"
                onClick={() => { setMailHistory(null); setOpenMailBody(null) }}>{t('wr.close')}</Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Mail önizleme ── */}
      {previewHtml != null && (
        <div className="modal-overlay" onClick={() => setPreviewHtml(null)}>
          <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 820, height: '85vh', display: 'flex', flexDirection: 'column' }}>
            <h3>{t('wr.previewTitle')}</h3>
            {/* allow-same-origin: görsellerin oturum çerezi ile yüklenebilmesi için; script yok */}
            <iframe title="preview" srcDoc={mailPreviewSrcDoc(previewHtml)} sandbox="allow-same-origin"
              style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 8, background: '#f4f6f8' }} />
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setPreviewHtml(null)}>{t('wr.close')}</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
