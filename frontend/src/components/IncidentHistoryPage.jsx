import { useState, useEffect, useCallback, useRef, useMemo, useId } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import {
  RefreshCcw, Plus, Pencil, Trash2, ListChecks, BarChart3, Sigma, AlertOctagon, AlertTriangle, AlertCircle, ArrowDownCircle,
  CircleDot, Search as SearchIcon, Shield, CheckCircle2, ShieldCheck, CalendarDays, CalendarRange, Calendar, Lock, X,
} from 'lucide-react'
import MarkdownEditor from './ui/MarkdownEditor.jsx'
import DateTimeField from './ui/DateTimeField.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { autoDurationMinutes } from '../utils/incidentMeta.js'
import { mailPreviewSrcDoc, MAIL_PREVIEW_SANDBOX } from '../utils/mailPreview.js'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import ModalShell from './ui/ModalShell.jsx'
import Field from './ui/Field.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import {
  ChartContainer, ChartTooltip, ChartLegend, ChartLegendContent, BarChart, Bar, XAxis, YAxis, CartesianGrid, Cell,
} from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const STATUSES   = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED']
const CATEGORIES = ['DATABASE', 'NETWORK', 'CERTIFICATE', 'APPLICATION', 'INFRASTRUCTURE', 'OTHER']
const SEV_COLOR  = { CRITICAL: '#dc2626', HIGH: '#ea580c', MEDIUM: '#d97706', LOW: '#16a34a' }
// Günlük trend yığılmış çubukları — alttan üste LOW→CRITICAL (kritik en üstte, en belirgin).
const SEV_BARS = [
  { key: 'low', color: SEV_COLOR.LOW, label: 'inc.sevLOW' },
  { key: 'medium', color: SEV_COLOR.MEDIUM, label: 'inc.sevMEDIUM' },
  { key: 'high', color: SEV_COLOR.HIGH, label: 'inc.sevHIGH' },
  { key: 'critical', color: SEV_COLOR.CRITICAL, label: 'inc.sevCRITICAL' },
]

// Günlük trend tooltip'i — temalı (shadcn yüzey jetonları); gün + sıfır-olmayan önem kırılımı + toplam.
function TrendTooltip({ active, payload, label, t }) {
  if (!active || !payload || !payload.length) return null
  const p = payload[0]?.payload || {}
  const day = String(p.day || label || '')
  const dstr = day.length >= 10 ? `${day.slice(8, 10)}.${day.slice(5, 7)}.${day.slice(0, 4)}` : day
  const rows = [['CRITICAL', p.critical], ['HIGH', p.high], ['MEDIUM', p.medium], ['LOW', p.low]].filter(([, v]) => v > 0)
  return (
    <div className="rounded-lg border bg-popover px-2.5 py-1.5 text-xs text-popover-foreground shadow-lg">
      <div className={cn('font-bold', rows.length && 'mb-0.5')}>{dstr}</div>
      {rows.map(([k, v]) => (
        <div key={k} className="flex items-center gap-1.5 leading-normal">
          <span aria-hidden="true" className="size-2 shrink-0 rounded-[2px]" style={{ background: SEV_COLOR[k] }} />
          <span>{t('inc.sev' + k)}: <b>{v}</b></span>
        </div>
      ))}
      <div className="mt-0.5 text-muted-foreground">{t('inc.trendTotal')}: <b>{p.count || 0}</b></div>
    </div>
  )
}

/** Önem rozeti (tablo + kart) — önem renginin %12 zemini, kalıcı renk kimliği. */
function SevBadge({ s, t }) {
  const c = SEV_COLOR[s] || '#71717a'
  return (
    <Badge variant="outline" data-severity={s} className="border-transparent font-bold whitespace-nowrap"
      style={{ color: c, background: c + '1f' }}>
      {t('inc.sev' + s) || s}
    </Badge>
  )
}

// Filtre tarih sınırını YEREL gün → UTC ISO'ya çevirir. Kayıtlar UTC saklanır, formatDate
// tarayıcı yerel saatine göre gösterir; bu yüzden "17 Haz" seçimi yerel 17 Haz 00:00–23:59:59'a,
// yani UTC karşılığına çevrilmeli. Aksi halde 18 Haz 01:00 (yerel) = 17 Haz 22:00 (UTC) kaydı
// "17 Haz" filtresine sızar. endOfDay=true → günün sonu (23:59:59).
function localDayToUtcIso(dateStr, endOfDay) {
  if (!dateStr) return undefined
  const [y, m, d] = dateStr.slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return undefined
  const dt = new Date(y, m - 1, d, endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${dt.getUTCFullYear()}-${p(dt.getUTCMonth() + 1)}-${p(dt.getUTCDate())}` +
         `T${p(dt.getUTCHours())}:${p(dt.getUTCMinutes())}:${p(dt.getUTCSeconds())}`
}

const EMPTY = {
  title: '', occurred_at: '', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION',
  error_code: '', function_code: '', channel_code: '', service: '', channel: '', team_id: '', team_name: '', detected_at: '', resolved_at: '',
  rca_summary: '', description: '', resolution_steps: '', business_impact: '',
  affected_services: '', problem_types: '', affected_app: '', affected_systems: '',
  affected_customers: '', affected_transactions: '',
  sla_breached: false, error_budget_burn_pct: '', duration_minutes: '', tags: '',
}

// Problem tipi — sabit ana maddeler (combobox'ta çoklu seçilir; gerekirse serbest ekleme de yapılabilir).
const PROBLEM_TYPES = [
  'Performans/Kodlama', 'Test/Kontrol Eksikliği', 'Operasyonel Hata', 'Analiz Eksikliği',
  'Konfigürasyon', 'Dış Firma Kaynaklı', 'Donanım Arızası', 'Plansız Değişiklik', 'Diğer',
]

// ── Modül seviyesi alan bileşenleri (stabil kimlik → input remount/odak kaybı OLMAZ) ──
// shadcn (2026-09-26, D2): ui/Field (etiket ↔ kontrol bağı) + Input / NativeSelect / Checkbox / Badge; eski
// `.form-grid label` / `.tag-chip` / `.checkbox-label` legacy sınıfları yerine. `full` → ızgarada tam satır.
const FULL = 'col-span-full'
function TextInput({ label, value, onChange, disabled, type = 'text', req, full }) {
  return (
    <Field label={label} required={req} className={full ? FULL : undefined}>
      {({ id }) => <Input id={id} type={type} value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)}
        inputMode={type === 'number' ? 'decimal' : undefined} />}
    </Field>
  )
}
function DateInput({ label, value, onChange, disabled, req, min }) {
  return (
    <Field label={label} required={req}>
      {() => <DateTimeField value={value} onChange={onChange} disabled={disabled}
                            placeholder={label} clearable={!req} min={min} />}
    </Field>
  )
}
function SelectInput({ label, value, onChange, disabled, options, req }) {
  return (
    <Field label={label} required={req}>
      {({ id }) => (
        // NativeSelect sarmalayıcısı `w-fit` — alan genişliğini doldursun diye doğrudan çocuğa w-full
        <div className="*:w-full">
          <NativeSelect id={id} value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)}>
            {options.map(o => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      )}
    </Field>
  )
}
/** Yönetilen dropdown (kanal/domain) — sabit liste + yeni değer ekleme (creatable).
 *  onCreate yeni değeri kalıcılaştırır (api.incidents.addOption) ve listeyi yeniler. */
function CreatableSelect({ label, value, onChange, options, disabled, onCreate, onDelete }) {
  const opts = [{ value: '', label: '—' }, ...options.map(o => ({ value: o, label: o }))]
  return (
    <Field label={label}>
      {({ id }) => (
        <SearchableSelect id={id} value={value ?? ''} onChange={onChange} disabled={disabled}
          options={opts} creatable={!disabled} onCreate={onCreate}
          onDelete={disabled ? undefined : onDelete} placeholder="—" />
      )}
    </Field>
  )
}

/** Renkli etiket çipi (shadcn Badge) + kaldırma düğmesi — ton `h` (0-360). */
function HueChip({ value, hue, disabled, onRemove, removeLabel }) {
  return (
    <Badge variant="outline" data-tag={value} className="gap-1 rounded-full py-0.5 pr-1 pl-2.5 font-semibold"
      style={{ background: `hsl(${hue},70%,93%)`, color: `hsl(${hue},65%,30%)`, borderColor: `hsl(${hue},70%,78%)` }}>
      {value}
      {!disabled && (
        <Button type="button" variant="ghost" size="icon-xs" aria-label={removeLabel}
          className="size-5 rounded-full hover:bg-black/10" style={{ color: `hsl(${hue},60%,38%)` }} onClick={onRemove}>
          <X aria-hidden="true" className="size-3" />
        </Button>
      )}
    </Badge>
  )
}

/** Çoklu seçim + creatable — değer CSV string ('a, b, c'). Seçilenler kaldırılabilir chip;
 *  "Ekle" için tekil SearchableSelect (seçilenler hariç). Picker seçim sonrası boş kalır. */
function CreatableMultiSelect({ label, value, onChange, options, disabled, onCreate, onDelete, placeholder }) {
  const t = useT()
  const selected = (value || '').split(',').map(s => s.trim()).filter(Boolean)
  const add = (v) => {
    const x = (v ?? '').trim()
    if (x && !selected.some(s => s.toLowerCase() === x.toLowerCase())) onChange([...selected, x].join(', '))
  }
  const remove = (val) => onChange(selected.filter(x => x !== val).join(', '))
  const opts = [
    { value: '', label: placeholder || '—' },
    ...options.filter(o => !selected.some(s => s.toLowerCase() === String(o).toLowerCase()))
              .map(o => ({ value: o, label: o })),
  ]
  return (
    <Field label={label}>
      {({ id }) => (
        <div className="flex min-w-0 flex-col gap-1.5">
          {!disabled && (
            <SearchableSelect id={id} value="" onChange={add} options={opts} creatable
              onCreate={v => { onCreate?.(v); add(v) }} onDelete={onDelete} placeholder={placeholder || '—'} />
          )}
          {selected.length > 0 ? (
            <div className="flex flex-wrap gap-1.5">
              {selected.map(val => (
                <HueChip key={val} value={val} hue={tagHue(val)} disabled={disabled}
                  removeLabel={t('tag.removeTag', val)} onRemove={() => remove(val)} />
              ))}
            </div>
          ) : (disabled && <span className="text-sm">—</span>)}
        </div>
      )}
    </Field>
  )
}
/** Zengin metin alanı — Weekly Reports ile aynı markdown editör (full-width).
 *  editable iken görsel yükleme aktif; incidentId yoksa (create modu) taslak yüklenir,
 *  kaydedince backend görseli olaya bağlar. makeUniqueCaption = aynı incident içinde
 *  görsel isimlerini tekilleştirir (karışmasın). */
function MdArea({ label, value, onChange, editable, incidentId, makeUniqueCaption }) {
  const uploadImage = editable
    ? async (file, caption) => {
        const res = await api.incidents.uploadImage(incidentId, file, caption)
        return res?.success ? `/api/incidents/images/${res.data.id}` : null
      }
    : undefined
  return (
    <div className={cn(FULL, 'mb-3.5 flex min-w-0 flex-col gap-1.5')}>
      <span className="text-[0.88em] font-semibold">{label}</span>
      <MarkdownEditor value={value} onChange={onChange} editable={editable} height={240}
                      uploadImage={uploadImage} makeUniqueCaption={makeUniqueCaption} />
    </div>
  )
}
function CheckInput({ label, checked, onChange, disabled }) {
  const id = useId()
  return (
    <div className="mb-3.5 flex min-h-9 items-center gap-2 self-end">
      <Checkbox id={id} checked={!!checked} disabled={disabled} onCheckedChange={v => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </div>
  )
}
/** İlk render karesi için deterministik yedek ton (effect rastgele atayana dek). */
function tagHue(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}
const randomHue = () => Math.floor(Math.random() * 360)

/** Etiket chip input — text yazıp Enter (veya virgül) → altına RASTGELE renkli etiket.
 *  Renk eklenince atanır ve o oturumda sabit kalır (CSV'ye yazılmaz, her render'da titremez). */
function TagInput({ label, value, onChange, disabled, t }) {
  const [text, setText] = useState('')
  const [hues, setHues] = useState({})
  const tags = (value || '').split(',').map(s => s.trim()).filter(Boolean)
  // Görünen ama rengi olmayan etiketlere (örn. düzenlemede yüklenen) rastgele ton ata, stabil kalsın.
  useEffect(() => {
    setHues(prev => {
      let changed = false
      const next = { ...prev }
      for (const tag of tags) if (next[tag] == null) { next[tag] = randomHue(); changed = true }
      return changed ? next : prev
    })
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  const add = () => {
    const v = text.trim()
    if (v && !tags.some(x => x.toLowerCase() === v.toLowerCase())) {
      setHues(prev => ({ ...prev, [v]: randomHue() })) // her Enter → yeni rastgele renk
      onChange([...tags, v].join(', '))
    }
    setText('')
  }
  const remove = (tag) => onChange(tags.filter(x => x !== tag).join(', '))
  return (
    <Field label={label} className={FULL}>
      {({ id }) => (
        <div className="flex min-w-0 flex-col gap-1.5">
          {!disabled && (
            <Input id={id} type="text" value={text} placeholder={t('inc.tagsHint')}
              onChange={e => setText(e.target.value)} onBlur={add}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
          )}
          {tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {tags.map(tag => (
                <HueChip key={tag} value={tag} hue={hues[tag] ?? tagHue(tag)} disabled={disabled}
                  removeLabel={t('tag.removeTag', tag)} onRemove={() => remove(tag)} />
              ))}
            </div>
          )}
        </div>
      )}
    </Field>
  )
}

/**
 * SRE Olay & Hata Geçmişi — Raporlar menüsü altında, manuel ledger.
 * Aranabilir tablo + executive özet kartları + günlük trend + detay/düzenle modalı.
 * Yetki: incidents.view (görüntüleme), incidents.manage (yaz/sil). Sayfa+API enforce eder.
 */
export default function IncidentHistoryPage() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canView, canEdit, canExecute } = usePermissions()
  const allowView = canView('incidents.view')
  const allowManage = canEdit('incidents.manage')
  const allowDelete = canExecute('incidents.delete') // silme yalnız TEAM_ADMIN/ADMIN
  // Telefonda tablo yerine kart listesi (yapı farkı → useIsMobile). allowView erken-dönüşünün ÜSTÜNDE (hook sırası).
  const phone = useIsMobile()

  const [rows, setRows]   = useState([])
  const [loading, setLoading] = useState(false)
  const [trends, setTrends]   = useState(null)
  const [filters, setFilters] = useState({ q: '', severity: '', category: '', status: '', channel: '', team_id: '', since: '', until: '' })
  // Arama 300 ms debounce; diğer filtreler anında. effFilters yalnız yerleşen terimle değişir ki
  // load her tuşta yeniden kurulmasın.
  const [qTerm, setQTerm] = useState('')
  useEffect(() => { const id = setTimeout(() => setQTerm(filters.q), 300); return () => clearTimeout(id) }, [filters.q])
  const effFilters = useMemo(() => ({ ...filters, q: qTerm }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [filters.severity, filters.category, filters.status, filters.channel, filters.team_id, filters.since, filters.until, qTerm])
  // Sayfalama standardı (2026-09-26). Eskiden `useEffect(() => setPage(0), [effFilters, size])` MOUNT'ta
  // da koşuyor, `?page=3` derin bağlantısını ilk render'da 1'e düşürüyordu; kanca sıfırlamayı yalnız
  // süzgecin DEĞERİ değişince yapar. URL page/ps (1-tabanlı, ps ön ayar listesine karşı doğrulanır).
  // DİKKAT: aşağıdaki `allowView` erken-return'ünün ÜSTÜNDE kalmalı (hook sırası).
  const sp = useServerPagination({ listKey: 'incident-history', preset: 'page', resetDeps: [effFilters],
    url: { pageKey: 'page', sizeKey: 'ps' }, apiBase: 0 })
  const { apiPage, pageSize: size } = sp
  const page = sp.page
  // Fetch yarışı: (eski sayfa) + (sayfa 0) çift istekte eski yanıt sonra dönerse listeyi ezerdi.
  const loadSeq = useRef(0)
  // Trend çekimleri de yarışır: özet tarih aralığı / günlük pencere (30→60→90) hızlı değişince geç dönen ESKİ yanıt
  // yeni pencerenin grafiğini ezerdi. Yalnız EN SON isteğin yanıtı uygulanır.
  const trendsSeq = useRef(0)
  const trendDailySeq = useRef(0)
  const [modal, setModal] = useState(null) // { mode:'view'|'edit'|'create', form }
  const [saving, setSaving] = useState(false)
  const [channelOpts, setChannelOpts] = useState([])
  const [domainOpts, setDomainOpts]   = useState([])
  const [errorCodeOpts, setErrorCodeOpts] = useState([])
  const [functionCodeOpts, setFunctionCodeOpts] = useState([])
  const [channelCodeOpts, setChannelCodeOpts]   = useState([])
  const [teams, setTeams]             = useState([])
  const [selected, setSelected]       = useState(() => new Set()) // toplu transfer seçimi (id'ler)
  const [transferTeam, setTransferTeam] = useState('')
  const [showSummary, setShowSummary]   = useState(false) // özet kartları + trend akordiyonu — varsayılan kapalı
  const [trendDays, setTrendDays]       = useState(30)    // günlük trend penceresi (30/60/90), tablo filtresinden bağımsız
  const [trendDaily, setTrendDaily]     = useState([])

  const load = useCallback(async () => {
    if (!allowView) return
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      try {
        const res = await api.incidents.list({ ...effFilters,
          since: localDayToUtcIso(effFilters.since, false),
          until: localDayToUtcIso(effFilters.until, true), page: apiPage, size })
        if (seq !== loadSeq.current) return   // bayat yanıt
        if (res?.success) { setRows(res.data ?? []); sp.bind(res) }
        else toast.error(res?.error || t('inc.loadError'))
      } catch { if (seq === loadSeq.current) toast.error(t('inc.loadError')) }
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [effFilters, apiPage, size, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadTrends = useCallback(async () => {
    if (!allowView) return
    const my = ++trendsSeq.current
    try {
      const res = await api.incidents.trends(localDayToUtcIso(filters.since, false), localDayToUtcIso(filters.until, true))
      if (my !== trendsSeq.current) return   // bayat yanıt — daha yeni bir aralık istendi
      if (res?.success) setTrends(res.data)
    } catch { /* sessiz — özet paneli boş kalır, liste yine çizilir (efektten fire-and-forget çağrılıyor) */ }
  }, [filters.since, filters.until, allowView])

  const loadOptions = useCallback(async () => {
    if (!allowView) return
    try {
      const [ch, dm, ec, fc, cc] = await Promise.all([
        api.incidents.options('CHANNEL'), api.incidents.options('DOMAIN'), api.incidents.options('ERROR_CODE'),
        api.incidents.options('FUNCTION_CODE'), api.incidents.options('CHANNEL_CODE')])
      if (ch?.success) setChannelOpts(ch.data ?? [])
      if (dm?.success) setDomainOpts(dm.data ?? [])
      if (ec?.success) setErrorCodeOpts(ec.data ?? [])
      if (fc?.success) setFunctionCodeOpts(fc.data ?? [])
      if (cc?.success) setChannelCodeOpts(cc.data ?? [])
    } catch { /* sessiz — dropdown boş kalır, yine de yeni değer eklenebilir */ }
  }, [allowView])

  const loadTeams = useCallback(async () => {
    if (!allowView) return // takım listesi hem filtre (görüntüleyici) hem modal (yazma) için lazım
    try {
      const res = await api.admin.getTeams()
      if (res?.success) setTeams(res.data ?? [])
    } catch { /* sessiz — yetkisizse dropdown boş kalır, "tüm takımlar" gibi davranır */ }
  }, [allowView])

  const addOption = useCallback(async (type, value) => {
    const res = await api.incidents.addOption(type, value)
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }, [loadOptions]) // eslint-disable-line react-hooks/exhaustive-deps

  const deleteOption = useCallback(async (type, value) => {
    const ok = await showConfirm({
      title: t('inc.optDeleteTitle'), message: t('inc.optDeleteConfirm', value),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.deleteOption(type, value)
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }, [loadOptions]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load() }, [load])
  useEffect(() => { loadTrends() }, [loadTrends])

  // Günlük trend: SON trendDays gün için tablo filtresinden BAĞIMSIZ ayrı çekim (bugünle biten kayan pencere).
  const loadTrendDaily = useCallback(async () => {
    if (!allowView) return
    const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const today = new Date()
    const since = ymd(new Date(today.getTime() - (trendDays - 1) * 86400000))
    const my = ++trendDailySeq.current
    try {
      const res = await api.incidents.trends(localDayToUtcIso(since, false), localDayToUtcIso(ymd(today), true))
      if (my !== trendDailySeq.current) return   // bayat yanıt — daha yeni bir pencere (30/60/90) istendi
      if (res?.success) setTrendDaily(res.data?.daily ?? [])
    } catch { /* sessiz — günlük trend grafiği boş kalır, sayfa ayakta (efektten fire-and-forget çağrılıyor) */ }
  }, [trendDays, allowView])
  useEffect(() => { loadTrendDaily() }, [loadTrendDaily])
  useEffect(() => { loadOptions() }, [loadOptions])
  useEffect(() => { loadTeams() }, [loadTeams])
  useEffect(() => { setSelected(new Set()) }, [filters, page, size]) // sayfa/filtre değişince seçim sıfırlanır

  // E-posta deep-link: ?incident=<id> → o olayı çekip detay modalını aç, sonra paramı temizle
  // (yenilemede tekrar açılmasın; ?tab gibi diğer paramlar korunur).
  useEffect(() => {
    if (!allowView) return
    let id
    try { id = new URLSearchParams(window.location.search).get('incident') } catch { return }
    if (!id) return
    let cancelled = false
    api.incidents.get(id).then(res => {
      if (!cancelled && res?.success && res.data) setModal({ mode: 'view', form: { ...EMPTY, ...res.data } })
    }).catch(() => {})
    try {
      const url = new URL(window.location.href)
      url.searchParams.delete('incident')
      const qs = url.searchParams.toString()
      window.history.replaceState({}, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
    } catch { /* yoksay */ }
    return () => { cancelled = true }
  }, [allowView])  

  // Günlük trend: aralığı SÜREKLİ günlere doldur (olaysız gün = 0) → gerçek takvim trendi (2-3 blok yerine).
  // Çok geniş/garip aralıkta (>120 gün) doldurma yapma; yalnız veri günlerini sırala (devasa grafik olmasın).
  // NOT: useMemo bir HOOK → koşullu erken dönüşün (allowView) ÜSTÜNDE, tüm render'larda koşulsuz çağrılmalı.
  const dailyChart = useMemo(() => {
    const byDay = new Map((trendDaily || []).map(d => [String(d.day).slice(0, 10), d]))
    const out = []
    const today = new Date()
    for (let i = trendDays - 1; i >= 0; i--) {
      const dt = new Date(today.getTime() - i * 86400000)
      const key = `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`
      const d = byDay.get(key)
      out.push({
        day: key,
        count: Number(d?.count) || 0,
        critical: Number(d?.critical) || 0,
        high: Number(d?.high) || 0,
        medium: Number(d?.medium) || 0,
        low: Number(d?.low) || 0,
      })
    }
    return out
  }, [trendDaily, trendDays])

  // Paylaşılabilir URL (page/ps) artık useServerPagination'da (yukarıda, erken-return'ün ÜSTÜNDE).
  // Yetkiler asenkron yüklendiği için `allowView` false→true döner; hook'lar erken-return'ün altına
  // konursa hook sayısı render'lar arasında değişir. Bu bileşene yeni hook eklerken de aynı kurala uyun.

  if (!allowView) return <StatusBlock tone="neutral" icon={ListChecks} title={t('inc.noAccess')} />

  const setF = (k, v) => setFilters(f => ({ ...f, [k]: v }))
  // Trend çubuğuna tıkla → o günü listede filtrele (since=until=gün); aynı güne tekrar tıkla → temizle.
  const toggleDay = (day) => {
    if (!day) return
    setFilters(f => (f.since === day && f.until === day)
      ? { ...f, since: '', until: '' }
      : { ...f, since: day, until: day })
  }

  // Özet kartları filtre görevi görür — tarih penceresini (since/until) korur, diğer
  // boyut filtrelerini sıfırlar, kartın boyutunu uygular. Aktif kart tekrar tıklanırsa kalkar.
  function applyCardFilter(kind) {
    setFilters(prev => {
      const active = (kind === 'critical' && prev.severity === 'CRITICAL')
        || (kind === 'high' && prev.severity === 'HIGH')
        || (kind === 'medium' && prev.severity === 'MEDIUM')
        || (kind === 'low' && prev.severity === 'LOW')
        || (kind === 'sla' && prev.slaBreached === true)
        || (kind === 'open' && prev.open === true)
        || (kind === 'investigating' && prev.status === 'INVESTIGATING')
        || (kind === 'mitigated' && prev.status === 'MITIGATED')
        || (kind === 'resolved' && prev.status === 'RESOLVED' && prev.slaBreached !== false)
        || (kind === 'resolved_sla' && prev.status === 'RESOLVED' && prev.slaBreached === false)
      const base = { ...prev, q: '', severity: '', category: '', status: '', channel: '',
        slaBreached: undefined, open: undefined, _preset: undefined }
      if (kind === 'total' || active) return base
      if (kind === 'critical') return { ...base, severity: 'CRITICAL' }
      if (kind === 'high')     return { ...base, severity: 'HIGH' }
      if (kind === 'medium')   return { ...base, severity: 'MEDIUM' }
      if (kind === 'low')      return { ...base, severity: 'LOW' }
      if (kind === 'sla')      return { ...base, slaBreached: true }
      if (kind === 'open')     return { ...base, open: true }
      if (kind === 'investigating') return { ...base, status: 'INVESTIGATING' }
      if (kind === 'mitigated')     return { ...base, status: 'MITIGATED' }
      if (kind === 'resolved') return { ...base, status: 'RESOLVED' }
      if (kind === 'resolved_sla') return { ...base, status: 'RESOLVED', slaBreached: false }
      if (kind === 'today' || kind === 'last7d' || kind === 'last30d') {
        const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
        const today = new Date()
        const backDays = kind === 'today' ? 0 : kind === 'last7d' ? 7 : 30
        return { ...base, since: ymd(new Date(today.getTime() - backDays * 86400000)), until: ymd(today), _preset: kind }
      }
      return base
    })
  }

  async function save() {
    const f = modal.form
    if (!f.title?.trim() || !f.occurred_at?.trim() || !f.team_id) { toast.error(t('inc.required')); return }
    if (f.detected_at && f.resolved_at && new Date(f.resolved_at) < new Date(f.detected_at)) {
      toast.error(t('inc.resolvedBeforeDetected')); return
    }
    const payload = { ...f }
    if (payload.error_budget_burn_pct === '') delete payload.error_budget_burn_pct
    if (payload.duration_minutes === '') delete payload.duration_minutes
    setSaving(true)
    try {
      try {
        const res = modal.mode === 'create'
          ? await api.incidents.create(payload)
          : await api.incidents.update(modal.form.id, payload)
        if (res?.success) { toast.success(t('inc.saved')); setModal(null); load(); loadTrends(); loadTrendDaily() }
        else toast.error(res?.error || t('inc.saveError'))
      } catch { setSaving(false); toast.error(t('inc.saveError')) }
    } finally {
      setSaving(false)
    }
  }

  async function remove(rec) {
    const ok = await showConfirm({
      title: t('inc.deleteTitle'), message: t('inc.deleteConfirm', rec.title),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.remove(rec.id)
    if (res?.success) { toast.success(t('inc.deleted')); setModal(null); load(); loadTrends(); loadTrendDaily() }
    else toast.error(res?.error || t('inc.saveError'))
  }

  const allOnPage = rows.length > 0 && rows.every(r => selected.has(r.id))
  const toggleSel = (id) => setSelected(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n })
  const toggleAll = () => setSelected(s => {
    const n = new Set(s); if (allOnPage) rows.forEach(r => n.delete(r.id)); else rows.forEach(r => n.add(r.id)); return n
  })

  async function doTransfer() {
    const tm = teams.find(x => String(x.id) === String(transferTeam))
    if (!tm || selected.size === 0) return
    const ok = await showConfirm({
      title: t('inc.transferTitle'), message: t('inc.transferConfirm', selected.size, tm.name),
      confirmText: t('inc.transferBtn'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.transfer([...selected], tm.id, tm.name)
    if (res?.success) {
      toast.success(t('inc.transferred', res.data?.transferred ?? selected.size))
      setSelected(new Set()); setTransferTeam(''); load(); loadTrends(); loadTrendDaily()
    } else toast.error(res?.error || t('inc.saveError'))
  }

  const sum = trends?.summary || {}
  const bySev = trends?.by_severity || {}
  const byStatus = trends?.by_status || {}

  // Özet kartları — MonitorStatsBar (tıklanınca süzgeç; etkin kart tekrar tıklanınca kalkar). Etkin kart,
  // eski satır-içi `outline` koşuluyla AYNI kuraldan hesaplanır (tek etkin anahtar).
  const isActiveKind = (kind) => (kind === 'critical' && filters.severity === 'CRITICAL')
    || (kind === 'high' && filters.severity === 'HIGH')
    || (kind === 'medium' && filters.severity === 'MEDIUM')
    || (kind === 'low' && filters.severity === 'LOW')
    || (kind === 'sla' && filters.slaBreached === true)
    || (kind === 'open' && filters.open === true)
    || (kind === 'investigating' && filters.status === 'INVESTIGATING')
    || (kind === 'mitigated' && filters.status === 'MITIGATED')
    || (kind === 'resolved' && filters.status === 'RESOLVED' && filters.slaBreached !== false)
    || (kind === 'resolved_sla' && filters.status === 'RESOLVED' && filters.slaBreached === false)
    || (kind === 'today' && filters._preset === 'today')
    || (kind === 'last7d' && filters._preset === 'last7d')
    || (kind === 'last30d' && filters._preset === 'last30d')
    || (kind === 'total' && !filters.severity && !filters.status && filters.slaBreached === undefined
        && filters.open === undefined && !filters.category && !filters.channel && !filters.q && !filters._preset)
  const statItems = [
    ['sumTotal', sum.total, 'total', 'total', Sigma],
    ['sumCritical', sum.critical, 'critical', 'critical', AlertOctagon],
    ['sumHigh', bySev.HIGH, 'high', 'high', AlertTriangle],
    ['sumMedium', bySev.MEDIUM, 'warning', 'medium', AlertCircle],
    ['sumLow', bySev.LOW, 'valid', 'low', ArrowDownCircle],
    ['sumOpen', sum.open, 'alert', 'open', CircleDot],
    ['sumInvestigating', byStatus.INVESTIGATING, 'total', 'investigating', SearchIcon],
    ['sumMitigated', byStatus.MITIGATED, 'total', 'mitigated', Shield],
    ['sumResolved', sum.resolved, 'valid', 'resolved', CheckCircle2],
    ['sumSla', sum.sla_breached, 'expired', 'sla', Lock],
    ['sumResolvedSla', sum.resolved_within_sla, 'valid', 'resolved_sla', ShieldCheck],
    ['sumToday', sum.today, 'total', 'today', Calendar],
    ['sumLast7d', sum.last_7d, 'total', 'last7d', CalendarRange],
    ['sumLast30d', sum.last_30d, 'total', 'last30d', CalendarDays],
  ].map(([k, v, cls, kind, Icon]) => ({ key: kind, label: t('inc.' + k), value: v ?? 0, cls, Icon, hint: t('inc.filterByCard') }))
  const activeKind = statItems.find(it => isActiveKind(it.key))?.key ?? null

  const chartConfig = Object.fromEntries(SEV_BARS.map(sv => [sv.key, { label: t(sv.label), color: sv.color }]))
  const openRecord = (r) => setModal({ mode: 'view', form: { ...EMPTY, ...r } })
  const rowKey = (e, fn) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); fn() } }
  const TH = 'h-9 px-3 text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase'

  const selectBox = (r) => (
    // Ad satırı ayırır: toplu takım aktarımı onayı yalnız ADET söylüyor (2026-09-25, R5).
    <Checkbox checked={selected.has(r.id)} onCheckedChange={() => toggleSel(r.id)}
      aria-label={t('bulk.selectOneFor', r.title || r.id)} />
  )
  const editButton = (r, big = false) => (
    <Button type="button" variant="outline" size={big ? 'icon' : 'icon-sm'} title={t('inc.edit')}
      aria-label={t('a11y.rowAction', r.title || r.id, t('inc.edit'))}
      onClick={e => { e.stopPropagation(); setModal({ mode: 'edit', form: { ...EMPTY, ...r } }) }}>
      <Pencil aria-hidden="true" />
    </Button>
  )

  // Masaüstü: shadcn Table (satır tıklaması ayrıntıyı açar; klavye Enter/Space). Düşük öncelikli sütunlar dar ekranda gizli.
  const desktop = (
    <div className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            {allowManage && (
              <TableHead className={cn(TH, 'w-9')}>
                <Checkbox checked={allOnPage} onCheckedChange={toggleAll} aria-label={t('inc.selectAll')} title={t('inc.selectAll')} />
              </TableHead>
            )}
            <TableHead className={TH}>{t('inc.colTime')}</TableHead>
            <TableHead className={TH}>{t('inc.colTitle')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('inc.colTeam')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('inc.colChannel')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('inc.colService')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inc.colCategory')}</TableHead>
            <TableHead className={TH}>{t('inc.colSeverity')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('inc.colStatus')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inc.colSla')}</TableHead>
            <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('inc.edit')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map(r => (
            <TableRow key={r.id} tabIndex={0} data-state={selected.has(r.id) ? 'selected' : undefined}
              aria-label={t('a11y.openRow', r.title || r.id)}
              className="cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
              onClick={() => openRecord(r)} onKeyDown={(e) => rowKey(e, () => openRecord(r))}>
              {allowManage && <TableCell onClick={e => e.stopPropagation()}>{selectBox(r)}</TableCell>}
              <TableCell className="whitespace-nowrap tabular-nums">{formatDate(r.occurred_at)}</TableCell>
              <TableCell className="max-w-[22rem] min-w-[12rem] font-medium whitespace-normal [overflow-wrap:anywhere]">{r.title}</TableCell>
              <TableCell className="hidden md:table-cell">{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : '—'}</TableCell>
              <TableCell className="hidden whitespace-normal xl:table-cell">{r.channel || '—'}</TableCell>
              <TableCell className="hidden whitespace-normal xl:table-cell">{r.service || '—'}</TableCell>
              <TableCell className="hidden lg:table-cell">{t('inc.cat' + r.category) || r.category}</TableCell>
              <TableCell><SevBadge s={r.severity} t={t} /></TableCell>
              <TableCell className="hidden md:table-cell">{t('inc.st' + r.status) || r.status}</TableCell>
              <TableCell className="hidden lg:table-cell">{r.sla_breached ? <span className="font-bold text-destructive">✓</span> : '—'}</TableCell>
              <TableCell className="text-right" onClick={e => e.stopPropagation()}>{allowManage && editButton(r)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )

  // Telefon: kart listesi — başlık (sarar) + önem, zaman · durum · kategori, takım; seçim kutusu + düzenle ≥ 40 px.
  const mobile = (
    <ul className="flex list-none flex-col gap-2 p-0">
      {rows.map(r => (
        <li key={r.id} className={cn('min-w-0 overflow-hidden rounded-lg border bg-card', selected.has(r.id) && 'border-primary bg-primary/5')}>
          <Button type="button" variant="ghost" onClick={() => openRecord(r)}
            className="h-auto w-full flex-col items-stretch gap-1.5 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal">
            <span className="flex min-w-0 items-start justify-between gap-2">
              <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{r.title}</span>
              <SevBadge s={r.severity} t={t} />
            </span>
            <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[0.84em] text-muted-foreground">
              <span className="tabular-nums">{formatDate(r.occurred_at)}</span>
              <span aria-hidden="true">·</span><span>{t('inc.st' + r.status) || r.status}</span>
              <span aria-hidden="true">·</span><span>{t('inc.cat' + r.category) || r.category}</span>
              {r.sla_breached && <><span aria-hidden="true">·</span><span className="font-bold text-destructive">{t('inc.colSla')} ✓</span></>}
            </span>
          </Button>
          <div className="flex min-w-0 items-center justify-between gap-2 border-t px-3 py-1.5">
            <span className="flex min-w-0 items-center gap-3">
              {allowManage && <span className="inline-flex size-10 items-center justify-center">{selectBox(r)}</span>}
              <span className="min-w-0">{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">—</span>}</span>
            </span>
            {allowManage && editButton(r, true)}
          </div>
        </li>
      ))}
    </ul>
  )

  return (
    <section data-slot="incident-history" className="mb-8 flex min-w-0 flex-col gap-3">
      {/* Başlık + eylemler — telefonda alt alta */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <h3 className="text-lg leading-tight font-semibold">{t('inc.title')}</h3>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" onClick={() => { load(); loadTrends(); loadTrendDaily() }} disabled={loading}>
            <RefreshCcw aria-hidden="true" /> {t('inc.refresh')}
          </Button>
          {allowManage && (
            <Button size="sm" onClick={() => setModal({ mode: 'create', form: { ...EMPTY } })}>
              <Plus aria-hidden="true" /> {t('inc.new')}
            </Button>
          )}
        </div>
      </div>

      {/* Özet (executive kartlar + günlük trend) — projenin tek katlanır şeridi (ui/CollapsibleSection); varsayılan kapalı */}
      <CollapsibleSection open={showSummary} onOpenChange={setShowSummary} icon={BarChart3} label={t('inc.summary')}
        hint={t('inc.show')} toggleLabel={showSummary ? t('inc.hide') : t('inc.show')} contentClassName="mt-3">
        <MonitorStatsBar items={statItems} activeFilter={activeKind} onStatClick={applyCardFilter} />

        {/* Günlük trend — gün başına olay sayısı (olaysız günler dahil; tarih + adet etiketli). shadcn Chart. */}
        {dailyChart.length > 0 && (
          <div data-slot="incident-trend" className="mb-2 flex min-w-0 flex-col gap-1">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-semibold">{t('inc.trend')}</span>
              <SegmentedControl value={String(trendDays)} onChange={v => setTrendDays(Number(v))} ariaLabel={t('inc.trend')}
                options={[30, 60, 90].map(dd => ({ value: String(dd), label: `${dd}${t('inc.trendDayUnit')}` }))} />
            </div>
            <ChartContainer config={chartConfig} className="aspect-auto h-[190px] w-full">
              <BarChart data={dailyChart} margin={{ top: 12, right: 8, bottom: 0, left: -18 }} barCategoryGap="16%">
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="day" tickFormatter={(d) => d.slice(8, 10) + '.' + d.slice(5, 7)}
                  tick={{ fontSize: 10 }} tickLine={false} minTickGap={10}
                  interval={Math.max(0, Math.floor(dailyChart.length / 10))} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} width={26} tickLine={false} axisLine={false} />
                <ChartTooltip content={<TrendTooltip t={t} />} cursor={{ fill: 'var(--primary)', fillOpacity: 0.08 }} />
                <ChartLegend content={<ChartLegendContent />} />
                {SEV_BARS.map((sv, si) => (
                  <Bar key={sv.key} dataKey={sv.key} stackId="s" name={t(sv.label)} fill={sv.color}
                    isAnimationActive={false} cursor="pointer"
                    radius={si === SEV_BARS.length - 1 ? [3, 3, 0, 0] : 0}
                    onClick={(bar) => toggleDay(bar?.day ?? bar?.payload?.day)}>
                    {dailyChart.map((d) => {
                      const isSel = filters.since === d.day && filters.until === d.day
                      const anySel = filters.since && filters.since === filters.until
                      return <Cell key={d.day} fillOpacity={anySel && !isSel ? 0.28 : 1} />
                    })}
                  </Bar>
                ))}
              </BarChart>
            </ChartContainer>
            <p className="text-[0.72em] text-muted-foreground">{t('inc.trendClickHint')}</p>
          </div>
        )}
      </CollapsibleSection>

      {/* Süzgeçler — mobil-önce: telefonda tam genişlik, alt alta; sm+ sarar */}
      <div data-slot="incident-filters" className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <InputGroup className="w-full sm:w-64">
          <InputGroupInput type="search" placeholder={t('inc.search')} aria-label={t('inc.search')} value={filters.q}
            onChange={e => setF('q', e.target.value)} />
          <InputGroupAddon><SearchIcon aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
        {/* Seçiciler: telefonda 2 sütunlu ızgara (NativeSelect sarmalayıcısı `w-fit` → *:w-full), sm+ tek satırda sarar */}
        <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center sm:*:min-w-[150px]">
          <div className="*:w-full"><NativeSelect aria-label={t('inc.colSeverity')} value={filters.severity} onChange={e => setF('severity', e.target.value)}>
            <NativeSelectOption value="">{t('inc.filterSeverity')}</NativeSelectOption>
            {SEVERITIES.map(s => <NativeSelectOption key={s} value={s}>{t('inc.sev' + s)}</NativeSelectOption>)}
          </NativeSelect></div>
          <div className="*:w-full"><NativeSelect aria-label={t('inc.colCategory')} value={filters.category} onChange={e => setF('category', e.target.value)}>
            <NativeSelectOption value="">{t('inc.filterCategory')}</NativeSelectOption>
            {CATEGORIES.map(c => <NativeSelectOption key={c} value={c}>{t('inc.cat' + c)}</NativeSelectOption>)}
          </NativeSelect></div>
          <div className="*:w-full"><NativeSelect aria-label={t('inc.colStatus')} value={filters.status} onChange={e => setF('status', e.target.value)}>
            <NativeSelectOption value="">{t('inc.filterStatus')}</NativeSelectOption>
            {STATUSES.map(s => <NativeSelectOption key={s} value={s}>{t('inc.st' + s)}</NativeSelectOption>)}
          </NativeSelect></div>
          <div className="*:w-full"><NativeSelect aria-label={t('inc.colChannel')} value={filters.channel} onChange={e => setF('channel', e.target.value)}>
            <NativeSelectOption value="">{t('inc.filterChannel')}</NativeSelectOption>
            {channelOpts.map(c => <NativeSelectOption key={c} value={c}>{c}</NativeSelectOption>)}
          </NativeSelect></div>
          <div className="*:w-full"><NativeSelect aria-label={t('inc.colTeam')} value={filters.team_id} onChange={e => setF('team_id', e.target.value)}>
            <NativeSelectOption value="">{t('inc.filterTeam')}</NativeSelectOption>
            {teams.map(tm => <NativeSelectOption key={tm.id} value={String(tm.id)}>{tm.name}</NativeSelectOption>)}
          </NativeSelect></div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold text-muted-foreground">{t('inc.since')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.since')}
            value={filters.since} onChange={v => setF('since', v || '')} />
          <span className="text-xs font-semibold text-muted-foreground">{t('inc.until')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.until')}
            value={filters.until} onChange={v => setF('until', v || '')} />
        </div>
      </div>

      {/* Toplu transfer çubuğu — seçim varken */}
      {allowManage && selected.size > 0 && (
        <div data-slot="incident-bulk" role="group" aria-label={t('inc.selectedN', selected.size)}
          className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/50 px-3 py-2">
          <span className="text-[0.9em] font-bold">{t('inc.selectedN', selected.size)}</span>
          <NativeSelect size="sm" aria-label={t('inc.transferTo')} value={transferTeam} onChange={e => setTransferTeam(e.target.value)}>
            <NativeSelectOption value="">{t('inc.transferTo')}</NativeSelectOption>
            {teams.map(tm => <NativeSelectOption key={tm.id} value={String(tm.id)}>{tm.name}</NativeSelectOption>)}
          </NativeSelect>
          <Button size="sm" disabled={!transferTeam} onClick={doTransfer}>{t('inc.transferBtn')}</Button>
          <Button variant="secondary" size="sm" onClick={() => setSelected(new Set())}>{t('inc.clearSel')}</Button>
        </div>
      )}

      {/* Liste — masaüstünde tablo, telefonda kart */}
      {loading && <LoadingBlock label={t('inc.loading')} fullWidth />}
      {!loading && rows.length === 0 && <StatusBlock tone="neutral" icon={ListChecks} title={t('inc.noResults')} />}
      {!loading && rows.length > 0 && (phone ? mobile : desktop)}

      {/* Sayfalama — standart çubuk; yüklenirken de yerinde kalır (sayfa değişiminde zıplamasın) */}
      <PaginationBar {...sp.bar} />

      {modal && <IncidentModal modal={modal} setModal={setModal} save={save} remove={remove}
                               saving={saving} allowManage={allowManage} allowDelete={allowDelete} t={t} teams={teams}
                               channelOpts={channelOpts} domainOpts={domainOpts} errorCodeOpts={errorCodeOpts}
                               functionCodeOpts={functionCodeOpts} channelCodeOpts={channelCodeOpts}
                               onAddOption={addOption} onDeleteOption={deleteOption} />}
    </section>
  )
}

/** Detay (read-only) / düzenle / oluştur modalı — ui/ModalShell + ui/Field ızgarası (shadcn, D2 2026-09-26). */
function IncidentModal({ modal, setModal, save, remove, saving, allowManage, allowDelete, t, teams, channelOpts, domainOpts, errorCodeOpts, functionCodeOpts, channelCodeOpts, onAddOption, onDeleteOption }) {
  const editing = modal.mode !== 'view'
  const f = modal.form
  const set = (k, v) => setModal(m => ({ ...m, form: { ...m.form, [k]: v } }))
  const titleKey = modal.mode === 'create' ? 'inc.newTitle' : modal.mode === 'edit' ? 'inc.editTitle' : 'inc.detailTitle'
  const opts = (arr, pfx) => arr.map(x => ({ value: x, label: t(pfx + x) }))
  const toast = useToast()
  const [previewHtml, setPreviewHtml] = useState(null)
  const [previewing, setPreviewing] = useState(false)
  // Gidecek mailin önizlemesi — kaydetmez/göndermez (backend forEmail=false → görseller /api URL'siyle render).
  async function openPreview() {
    const payload = { ...f }
    if (payload.error_budget_burn_pct === '') delete payload.error_budget_burn_pct
    if (payload.duration_minutes === '') delete payload.duration_minutes
    payload.kind = modal.mode === 'create' ? 'NEW' : (f.status === 'RESOLVED' ? 'RESOLVED' : 'UPDATED')
    setPreviewing(true)
    try {
      try {
        const res = await api.incidents.previewNotification(payload)
        if (res?.success) setPreviewHtml(res.html ?? '')
        else toast.error(res?.error || t('inc.saveError'))
      } catch { toast.error(t('inc.saveError')) }
    } finally {
      setPreviewing(false)
    }
  }

  // Takım seçenekleri — seçili takım yüklenen listede yoksa (kapsam dışı/eski kayıt) yine de göster
  const teamOptions = [{ value: '', label: '—' }, ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]
  if (f.team_id != null && f.team_id !== '' && !teams.some(tm => String(tm.id) === String(f.team_id)))
    teamOptions.push({ value: String(f.team_id), label: f.team_name || ('#' + f.team_id) })

  // Süre (dk) otomatik: olayın gerçek toplam süresi = OLUŞ ↔ çözülme farkı (tespit DEĞİL —
  // tespit gecikmesi süreden düşmemeli). autoDurationMinutes UTC-güvenli parse + negatif/eksik → null.
  useEffect(() => {
    if (!editing || !f.occurred_at || !f.resolved_at) return
    const diff = autoDurationMinutes(f.occurred_at, f.resolved_at)
    if (diff == null) return
    if (String(f.duration_minutes ?? '') !== String(diff)) {
      setModal(m => ({ ...m, form: { ...m.form, duration_minutes: diff } }))
    }
  }, [f.occurred_at, f.resolved_at, editing]) // eslint-disable-line react-hooks/exhaustive-deps

  // Görsel isim tekilleştirme — aynı incident içinde (4 markdown alanı genelinde) aynı görsel
  // adı tekrar ederse "ad (2).uzantı" üretir. İçerik farklı iki "capture.jpg" ikisi de yüklenir,
  // ayrı URL alır, isimleri ayrışır → karışmaz. formRef en güncel alanları okur (async upload).
  const formRef = useRef(f); formRef.current = f
  const reservedRef = useRef(new Set())
  useEffect(() => { reservedRef.current = new Set() }, [f.id, modal.mode])
  const makeUniqueCaption = useCallback((desired) => {
    const base = (desired || 'image').trim() || 'image'
    const used = new Set(reservedRef.current)
    for (const k of ['rca_summary', 'description', 'resolution_steps', 'business_impact']) {
      const txt = formRef.current[k] || ''
      const re = /!\[([^\]]*)\]\([^)]*\)/g
      let m
      while ((m = re.exec(txt))) used.add(m[1])
    }
    if (!used.has(base)) { reservedRef.current.add(base); return base }
    const dot = base.lastIndexOf('.')
    const stem = dot > 0 ? base.slice(0, dot) : base
    const ext = dot > 0 ? base.slice(dot) : ''
    let n = 2, cand
    do { cand = `${stem} (${n})${ext}`; n++ } while (used.has(cand))
    reservedRef.current.add(cand)
    return cand
  }, [])

  // ui/ModalShell (shadcn Dialog). Dış tıklamada KAPANMAZ — giriş kaybını önlemek için (Escape/X/İptal kapatır);
  // uzun form gövdesi kayar, eylem altlığı sabit. Mail önizlemesi iç içe ikinci kabuk (derinlik z'si üstte).
  return (
    <>
    <ModalShell open onClose={() => setModal(null)} title={t(titleKey)} icon={ListChecks} size="xl" scrollBody
      dismissOnBackdrop={false} busy={saving}
      footer={(
        <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-end">
          {editing && <SendMailCheckbox checked={!!f.send_notification} label={t('inc.sendMail')} onChange={v => set('send_notification', v)} />}
          <div className="flex flex-wrap justify-end gap-2">
            {editing && <Button variant="secondary" onClick={openPreview} disabled={previewing}>{previewing ? t('inc.previewing') : t('inc.previewMail')}</Button>}
            {editing && <Button onClick={save} disabled={saving}>{t('inc.save')}</Button>}
            {modal.mode === 'view' && (
              <>
                {allowManage && <Button variant="secondary" onClick={() => setModal(m => ({ ...m, mode: 'edit' }))}><Pencil aria-hidden="true" />{t('inc.edit')}</Button>}
                {allowDelete && <Button variant="destructive" onClick={() => remove(f)}><Trash2 aria-hidden="true" /> {t('inc.delete')}</Button>}
              </>
            )}
            <Button variant="secondary" onClick={() => setModal(null)}>{t('inc.cancel')}</Button>
          </div>
        </div>
      )}>
        <div data-slot="incident-form" className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
          <TextInput label={t('inc.fTitle')} req full value={f.title} disabled={!editing} onChange={v => set('title', v)} />
          <DateInput label={t('inc.fOccurredAt')} req value={f.occurred_at} disabled={!editing} onChange={v => set('occurred_at', v)} />
          <SelectInput label={t('inc.fTeam')} req value={f.team_id != null ? String(f.team_id) : ''} disabled={!editing}
            onChange={v => setModal(m => ({ ...m, form: { ...m.form, team_id: v,
              team_name: teams.find(tm => String(tm.id) === String(v))?.name || '' } }))}
            options={teamOptions} />
          <CreatableMultiSelect label={t('inc.fChannel')} value={f.channel} disabled={!editing}
            options={channelOpts} onChange={v => set('channel', v)} placeholder={t('inc.selectChannel')}
            onCreate={v => onAddOption('CHANNEL', v)} onDelete={v => onDeleteOption('CHANNEL', v)} />
          <CreatableMultiSelect label={t('inc.fService')} value={f.service} disabled={!editing}
            options={domainOpts} onChange={v => set('service', v)} placeholder={t('inc.selectService')}
            onCreate={v => onAddOption('DOMAIN', v)} onDelete={v => onDeleteOption('DOMAIN', v)} />
          <CreatableMultiSelect label={t('inc.fProblemType')} value={f.problem_types} disabled={!editing}
            options={PROBLEM_TYPES} onChange={v => set('problem_types', v)} placeholder={t('inc.selectProblemType')} />
          <SelectInput label={t('inc.fSeverity')} value={f.severity} disabled={!editing} onChange={v => set('severity', v)} options={opts(SEVERITIES, 'inc.sev')} />
          <SelectInput label={t('inc.fStatus')} value={f.status} disabled={!editing} onChange={v => set('status', v)} options={opts(STATUSES, 'inc.st')} />
          <SelectInput label={t('inc.fCategory')} value={f.category} disabled={!editing} onChange={v => set('category', v)} options={opts(CATEGORIES, 'inc.cat')} />
          <CreatableSelect label={t('inc.fErrorCode')} value={f.error_code} disabled={!editing}
            options={errorCodeOpts} onChange={v => set('error_code', v)}
            onCreate={v => onAddOption('ERROR_CODE', v)} onDelete={v => onDeleteOption('ERROR_CODE', v)} />
          <CreatableSelect label={t('inc.fFunctionCode')} value={f.function_code} disabled={!editing}
            options={functionCodeOpts} onChange={v => set('function_code', v)}
            onCreate={v => onAddOption('FUNCTION_CODE', v)} onDelete={v => onDeleteOption('FUNCTION_CODE', v)} />
          <CreatableSelect label={t('inc.fChannelCode')} value={f.channel_code} disabled={!editing}
            options={channelCodeOpts} onChange={v => set('channel_code', v)}
            onCreate={v => onAddOption('CHANNEL_CODE', v)} onDelete={v => onDeleteOption('CHANNEL_CODE', v)} />
          <DateInput label={t('inc.fDetectedAt')} value={f.detected_at} disabled={!editing} onChange={v => set('detected_at', v)} />
          <DateInput label={t('inc.fResolvedAt')} value={f.resolved_at} disabled={!editing} min={f.detected_at || f.occurred_at} onChange={v => set('resolved_at', v)} />
          <TextInput label={t('inc.fErrorBudget')} type="number" value={f.error_budget_burn_pct} disabled={!editing} onChange={v => set('error_budget_burn_pct', v)} />
          <TextInput label={t('inc.fDuration')} type="number" value={f.duration_minutes}
            disabled={!editing || (!!f.occurred_at && !!f.resolved_at)}
            onChange={v => set('duration_minutes', v)} />
          <CheckInput label={t('inc.fSla')} checked={f.sla_breached} disabled={!editing} onChange={v => set('sla_breached', v)} />
          <TextInput label={t('inc.fAffected')} full value={f.affected_services} disabled={!editing} onChange={v => set('affected_services', v)} />
          <TextInput label={t('inc.fAffectedApp')} full value={f.affected_app} disabled={!editing} onChange={v => set('affected_app', v)} />
          <TextInput label={t('inc.fAffectedSystems')} full value={f.affected_systems} disabled={!editing} onChange={v => set('affected_systems', v)} />
          <TextInput label={t('inc.fAffectedCustomers')} type="number" value={f.affected_customers} disabled={!editing} onChange={v => set('affected_customers', v)} />
          <TextInput label={t('inc.fAffectedTransactions')} type="number" value={f.affected_transactions} disabled={!editing} onChange={v => set('affected_transactions', v)} />
          <TagInput label={t('inc.fTags')} value={f.tags} disabled={!editing} t={t} onChange={v => set('tags', v)} />
          <MdArea label={t('inc.fRca')} value={f.rca_summary} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('rca_summary', v)} />
          <MdArea label={t('inc.fDescription')} value={f.description} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('description', v)} />
          <MdArea label={t('inc.fResolution')} value={f.resolution_steps} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('resolution_steps', v)} />
          <MdArea label={t('inc.fBusinessImpact')} value={f.business_impact} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('business_impact', v)} />
        </div>
    </ModalShell>
    {/* Mail önizleme — kaydetmeden gidecek mailin görünümü (haftalık rapor deseni) */}
    <ModalShell open={previewHtml != null} onClose={() => setPreviewHtml(null)} title={t('inc.previewTitle')} size="lg"
      footer={<Button variant="secondary" onClick={() => setPreviewHtml(null)}>{t('inc.cancel')}</Button>}>
      <iframe title="mail-preview" srcDoc={mailPreviewSrcDoc(previewHtml ?? '')} sandbox={MAIL_PREVIEW_SANDBOX}
        className="h-[70dvh] w-full rounded-lg border bg-[#f4f6f8]" />
    </ModalShell>
    </>
  )
}

/** "Mail gönder" (kaydetle birlikte) — shadcn Checkbox + bağlı etiket; telefonda altlığın üstünde tam satır. */
function SendMailCheckbox({ checked, onChange, label }) {
  const id = useId()
  return (
    <div className="flex items-center gap-2 sm:mr-auto">
      <Checkbox id={id} checked={checked} onCheckedChange={v => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer text-[0.85em] font-semibold">{label}</Label>
    </div>
  )
}
