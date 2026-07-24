import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { RefreshCcw, Plus, ChevronLeft, ChevronRight, ChevronDown, Pencil, Trash2, ListChecks } from 'lucide-react'
import MarkdownEditor from './ui/MarkdownEditor.jsx'
import DateTimeField from './ui/DateTimeField.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip, Legend, Cell } from 'recharts'

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

// Günlük trend tooltip'i — temalı; gün + sıfır-olmayan önem kırılımı + toplam.
function TrendTooltip({ active, payload, label, t }) {
  if (!active || !payload || !payload.length) return null
  const p = payload[0]?.payload || {}
  const day = String(p.day || label || '')
  const dstr = day.length >= 10 ? `${day.slice(8, 10)}.${day.slice(5, 7)}.${day.slice(0, 4)}` : day
  const rows = [['CRITICAL', p.critical], ['HIGH', p.high], ['MEDIUM', p.medium], ['LOW', p.low]].filter(([, v]) => v > 0)
  return (
    <div style={{ background: 'var(--bg-card,#fff)', border: '1px solid var(--border)', borderRadius: 8,
      padding: '7px 10px', fontSize: '.8em', boxShadow: '0 4px 16px rgba(0,0,0,.14)' }}>
      <div style={{ fontWeight: 700, marginBottom: rows.length ? 3 : 0 }}>{dstr}</div>
      {rows.map(([k, v]) => (
        <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 6, lineHeight: 1.5 }}>
          <span style={{ width: 8, height: 8, borderRadius: 2, background: SEV_COLOR[k], flexShrink: 0 }} />
          <span>{t('inc.sev' + k)}: <b>{v}</b></span>
        </div>
      ))}
      <div style={{ marginTop: 3, color: 'var(--text-light)' }}>{t('inc.trendTotal')}: <b>{p.count || 0}</b></div>
    </div>
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
function TextInput({ label, value, onChange, disabled, type = 'text', req, full }) {
  return (
    <label className={full ? 'full-width' : undefined}>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <input type={type} value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)} />
    </label>
  )
}
function DateInput({ label, value, onChange, disabled, req, min }) {
  return (
    <label>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <DateTimeField value={value} onChange={onChange} disabled={disabled}
                     placeholder={label} clearable={!req} min={min} />
    </label>
  )
}
function SelectInput({ label, value, onChange, disabled, options, req }) {
  return (
    <label>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <select value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)}>
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  )
}
/** Yönetilen dropdown (kanal/domain) — sabit liste + yeni değer ekleme (creatable).
 *  onCreate yeni değeri kalıcılaştırır (api.incidents.addOption) ve listeyi yeniler. */
function CreatableSelect({ label, value, onChange, options, disabled, onCreate, onDelete }) {
  const opts = [{ value: '', label: '—' }, ...options.map(o => ({ value: o, label: o }))]
  return (
    <label>
      <span>{label}</span>
      <SearchableSelect value={value ?? ''} onChange={onChange} disabled={disabled}
        options={opts} creatable={!disabled} onCreate={onCreate}
        onDelete={disabled ? undefined : onDelete} placeholder="—" />
    </label>
  )
}
/** Çoklu seçim + creatable — değer CSV string ('a, b, c'). Seçilenler kaldırılabilir chip;
 *  "Ekle" için tekil SearchableSelect (seçilenler hariç). Picker seçim sonrası boş kalır.
 *  tagHue/.tag-chip yeniden kullanılır (yeni CSS yok). */
function CreatableMultiSelect({ label, value, onChange, options, disabled, onCreate, onDelete, placeholder }) {
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
    <label>
      <span>{label}</span>
      {!disabled && (
        <SearchableSelect value="" onChange={add} options={opts} creatable
          onCreate={v => { onCreate?.(v); add(v) }} onDelete={onDelete} placeholder={placeholder || '—'} />
      )}
      {selected.length > 0 ? (
        <div className="tag-chips">
          {selected.map(val => {
            const h = tagHue(val)
            return (
              <span key={val} className="tag-chip"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {val}
                {!disabled && <button type="button" className="tag-chip-x" aria-label="remove"
                  style={{ color: `hsl(${h},60%,38%)` }} onClick={() => remove(val)}>×</button>}
              </span>
            )
          })}
        </div>
      ) : (disabled && <span className="show-field-value">—</span>)}
    </label>
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
    <div className="full-width" style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <span style={{ fontSize: '.88em', fontWeight: 600 }}>{label}</span>
      <MarkdownEditor value={value} onChange={onChange} editable={editable} height={240}
                      uploadImage={uploadImage} makeUniqueCaption={makeUniqueCaption} />
    </div>
  )
}
function CheckInput({ label, checked, onChange, disabled }) {
  return (
    <label className="checkbox-label">
      <input type="checkbox" checked={!!checked} disabled={disabled} onChange={e => onChange(e.target.checked)} />
      {label}
    </label>
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
    <label className="full-width">
      <span>{label}</span>
      {!disabled && (
        <input type="text" value={text} placeholder={t('inc.tagsHint')}
          onChange={e => setText(e.target.value)} onBlur={add}
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
      )}
      {tags.length > 0 && (
        <div className="tag-chips">
          {tags.map(tag => {
            const h = hues[tag] ?? tagHue(tag)
            return (
              <span key={tag} className="tag-chip"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {tag}
                {!disabled && <button type="button" className="tag-chip-x" aria-label="remove"
                  style={{ color: `hsl(${h},60%,38%)` }} onClick={() => remove(tag)}>×</button>}
              </span>
            )
          })}
        </div>
      )}
    </label>
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

  const [rows, setRows]   = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage]   = useState(0)
  const [size, setSize]   = useState(20)
  const [loading, setLoading] = useState(false)
  const [trends, setTrends]   = useState(null)
  const [filters, setFilters] = useState({ q: '', severity: '', category: '', status: '', channel: '', team_id: '', since: '', until: '' })
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
    setLoading(true)
    try {
      const res = await api.incidents.list({ ...filters,
        since: localDayToUtcIso(filters.since, false),
        until: localDayToUtcIso(filters.until, true), page, size })
      if (res?.success) { setRows(res.data ?? []); setTotal(res.total ?? 0) }
      else toast.error(res?.error || t('inc.loadError'))
    } catch { toast.error(t('inc.loadError')) }
    setLoading(false)
  }, [filters, page, size, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadTrends = useCallback(async () => {
    if (!allowView) return
    const res = await api.incidents.trends(localDayToUtcIso(filters.since, false), localDayToUtcIso(filters.until, true))
    if (res?.success) setTrends(res.data)
  }, [filters.since, filters.until, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

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
    const res = await api.incidents.trends(localDayToUtcIso(since, false), localDayToUtcIso(ymd(today), true))
    if (res?.success) setTrendDaily(res.data?.daily ?? [])
  }, [trendDays, allowView]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { loadTrendDaily() }, [loadTrendDaily])
  useEffect(() => { loadOptions() }, [loadOptions])
  useEffect(() => { loadTeams() }, [loadTeams])
  useEffect(() => { setPage(0) }, [filters, size])
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
  }, [allowView]) // eslint-disable-line react-hooks/exhaustive-deps

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

  if (!allowView) return <div className="empty-state">{t('inc.noAccess')}</div>

  const totalPages = Math.max(1, Math.ceil(total / size))
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
      const res = modal.mode === 'create'
        ? await api.incidents.create(payload)
        : await api.incidents.update(modal.form.id, payload)
      setSaving(false)
      if (res?.success) { toast.success(t('inc.saved')); setModal(null); load(); loadTrends(); loadTrendDaily() }
      else toast.error(res?.error || t('inc.saveError'))
    } catch { setSaving(false); toast.error(t('inc.saveError')) }
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

  const sevBadge = (s) => <span style={{ color: SEV_COLOR[s] || '#64748b', fontWeight: 700 }}>{t('inc.sev' + s) || s}</span>
  const sum = trends?.summary || {}
  const bySev = trends?.by_severity || {}
  const byStatus = trends?.by_status || {}

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('inc.title')}</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-secondary btn-sm-p" onClick={() => { load(); loadTrends(); loadTrendDaily() }} disabled={loading}>
            <RefreshCcw size={13} /> {t('inc.refresh')}
          </button>
          {allowManage && (
            <button className="btn btn-primary btn-sm-p" onClick={() => setModal({ mode: 'create', form: { ...EMPTY } })}>
              <Plus size={14} /> {t('inc.new')}
            </button>
          )}
        </div>
      </div>

      {/* Özet (executive kartlar + günlük trend) — akordiyon: varsayılan kapalı, "Göster" ile açılır */}
      <button type="button" className="inc-summary-acc" aria-expanded={showSummary}
              onClick={() => setShowSummary(s => !s)}>
        {showSummary ? <ChevronDown size={16} className="inc-summary-acc-chev" />
                     : <ChevronRight size={16} className="inc-summary-acc-chev" />}
        <span className="inc-summary-acc-title">{t('inc.summary')}</span>
        <span className="inc-summary-acc-action">{showSummary ? t('inc.hide') : t('inc.show')}</span>
      </button>

      {showSummary && (<>
      {/* Executive özet kartları — tıklanınca filtre uygular (proje stats-panel deseni) */}
      <div className="stats-panel" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' }}>
        {[['sumTotal', sum.total, 'total', 'total'],
          ['sumCritical', sum.critical, 'critical', 'critical'],
          ['sumHigh', bySev.HIGH, 'high', 'high'],
          ['sumMedium', bySev.MEDIUM, 'medium', 'medium'],
          ['sumLow', bySev.LOW, 'low', 'low'],
          ['sumOpen', sum.open, 'warning', 'open'],
          ['sumInvestigating', byStatus.INVESTIGATING, 'investigating', 'investigating'],
          ['sumMitigated', byStatus.MITIGATED, 'mitigated', 'mitigated'],
          ['sumResolved', sum.resolved, 'valid', 'resolved'],
          ['sumSla', sum.sla_breached, 'alert', 'sla'],
          ['sumResolvedSla', sum.resolved_within_sla, 'resolvedsla', 'resolved_sla'],
          ['sumToday', sum.today, 'today', 'today'],
          ['sumLast7d', sum.last_7d, 'last7d', 'last7d'],
          ['sumLast30d', sum.last_30d, 'last30d', 'last30d']].map(([k, v, variant, kind]) => {
          const active = (kind === 'critical' && filters.severity === 'CRITICAL')
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
          return (
            <div key={k} className={`stat-item stat-item-${variant}`} role="button" tabIndex={0}
                 title={t('inc.filterByCard')} onClick={() => applyCardFilter(kind)}
                 onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); applyCardFilter(kind) } }}
                 style={{ cursor: 'pointer', ...(active ? { outline: '2px solid var(--primary)', outlineOffset: '-2px' } : {}) }}>
              <div className={`stat-value stat-value-${variant}`}>{v ?? 0}</div>
              <span className="stat-label">{t('inc.' + k)}</span>
            </div>
          )
        })}
      </div>

      {/* Günlük trend — gün başına olay sayısı (olaysız günler dahil; tarih + adet etiketli) */}
      {dailyChart.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <div className="show-section-header inc-trend-head">
            <span>{t('inc.trend')}</span>
            <span className="inc-trend-range">
              {[30, 60, 90].map(dd => (
                <button key={dd} type="button"
                        className={`inc-trend-btn${trendDays === dd ? ' active' : ''}`}
                        onClick={() => setTrendDays(dd)}>{dd}{t('inc.trendDayUnit')}</button>
              ))}
            </span>
          </div>
          <div style={{ marginTop: 4 }}>
            <ResponsiveContainer width="100%" height={170}>
              <BarChart data={dailyChart} margin={{ top: 12, right: 8, bottom: 0, left: -18 }} barCategoryGap="16%">
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                <XAxis dataKey="day" tickFormatter={(d) => d.slice(8, 10) + '.' + d.slice(5, 7)}
                  tick={{ fill: 'var(--text-light)', fontSize: 10 }} tickLine={false}
                  axisLine={{ stroke: 'var(--border)' }} minTickGap={10}
                  interval={Math.max(0, Math.floor(dailyChart.length / 10))} />
                <YAxis allowDecimals={false} tick={{ fill: 'var(--text-light)', fontSize: 10 }}
                  width={26} tickLine={false} axisLine={false} />
                <RTooltip content={<TrendTooltip t={t} />} cursor={{ fill: 'var(--primary)', fillOpacity: 0.08 }} />
                <Legend wrapperStyle={{ fontSize: '.78em' }} />
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
            </ResponsiveContainer>
            <div style={{ fontSize: '.72em', color: 'var(--text-muted)', marginTop: 2 }}>{t('inc.trendClickHint')}</div>
          </div>
        </div>
      )}
      </>)}

      {/* Filtreler */}
      <div className="inv-stats-pills" style={{ marginBottom: 12, gap: 8, alignItems: 'center' }}>
        <input className="filter-input" placeholder={t('inc.search')} value={filters.q}
               onChange={e => setF('q', e.target.value)} style={{ minWidth: 200 }} />
        <select className="filter-select" value={filters.severity} onChange={e => setF('severity', e.target.value)}>
          <option value="">{t('inc.filterSeverity')}</option>
          {SEVERITIES.map(s => <option key={s} value={s}>{t('inc.sev' + s)}</option>)}
        </select>
        <select className="filter-select" value={filters.category} onChange={e => setF('category', e.target.value)}>
          <option value="">{t('inc.filterCategory')}</option>
          {CATEGORIES.map(c => <option key={c} value={c}>{t('inc.cat' + c)}</option>)}
        </select>
        <select className="filter-select" value={filters.status} onChange={e => setF('status', e.target.value)}>
          <option value="">{t('inc.filterStatus')}</option>
          {STATUSES.map(s => <option key={s} value={s}>{t('inc.st' + s)}</option>)}
        </select>
        <select className="filter-select" value={filters.channel} onChange={e => setF('channel', e.target.value)}>
          <option value="">{t('inc.filterChannel')}</option>
          {channelOpts.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <select className="filter-select" value={filters.team_id} onChange={e => setF('team_id', e.target.value)}>
          <option value="">{t('inc.filterTeam')}</option>
          {teams.map(tm => <option key={tm.id} value={String(tm.id)}>{tm.name}</option>)}
        </select>
        <span className="inc-date-pair">
          <span className="inc-date-lbl">{t('inc.since')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.since')}
            value={filters.since} onChange={v => setF('since', v || '')} />
        </span>
        <span className="inc-date-pair">
          <span className="inc-date-lbl">{t('inc.until')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.until')}
            value={filters.until} onChange={v => setF('until', v || '')} />
        </span>
      </div>

      {/* Toplu transfer çubuğu — seçim varken */}
      {allowManage && selected.size > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#f1f5f9', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inc.selectedN', selected.size)}</span>
          <select className="filter-select" value={transferTeam} onChange={e => setTransferTeam(e.target.value)}>
            <option value="">{t('inc.transferTo')}</option>
            {teams.map(tm => <option key={tm.id} value={String(tm.id)}>{tm.name}</option>)}
          </select>
          <button className="btn btn-primary btn-sm-p" disabled={!transferTeam} onClick={doTransfer}>{t('inc.transferBtn')}</button>
          <button className="btn btn-secondary btn-sm-p" onClick={() => setSelected(new Set())}>{t('inc.clearSel')}</button>
        </div>
      )}

      {/* Tablo */}
      {loading && <div className="loading">{t('inc.loading')}</div>}
      {!loading && rows.length === 0 && <div className="empty-state">{t('inc.noResults')}</div>}
      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr>
              {allowManage && <th style={{ width: 28 }}>
                <input type="checkbox" checked={allOnPage} onChange={toggleAll} title={t('inc.selectAll')} />
              </th>}
              <th>{t('inc.colTime')}</th><th>{t('inc.colTitle')}</th><th>{t('inc.colTeam')}</th><th>{t('inc.colChannel')}</th><th>{t('inc.colService')}</th>
              <th>{t('inc.colCategory')}</th><th>{t('inc.colSeverity')}</th><th>{t('inc.colStatus')}</th>
              <th>{t('inc.colSla')}</th><th></th>
            </tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => setModal({ mode: 'view', form: { ...EMPTY, ...r } })}>
                  {allowManage && <td onClick={e => e.stopPropagation()}>
                    <input type="checkbox" checked={selected.has(r.id)} onChange={() => toggleSel(r.id)} />
                  </td>}
                  <td style={{ whiteSpace: 'nowrap' }}>{formatDate(r.occurred_at)}</td>
                  <td>{r.title}</td>
                  <td>{r.team_name || '—'}</td>
                  <td>{r.channel || '—'}</td>
                  <td>{r.service || '—'}</td>
                  <td>{t('inc.cat' + r.category) || r.category}</td>
                  <td>{sevBadge(r.severity)}</td>
                  <td>{t('inc.st' + r.status) || r.status}</td>
                  <td>{r.sla_breached ? <span style={{ color: '#dc2626', fontWeight: 700 }}>✓</span> : '—'}</td>
                  <td onClick={e => e.stopPropagation()}>
                    {allowManage && (
                      <button className="btn-sm btn-show" title={t('inc.edit')}
                              onClick={() => setModal({ mode: 'edit', form: { ...EMPTY, ...r } })}>
                        <Pencil size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Sayfalama */}
      {!loading && total > 0 && (
        <div className="alh-pagination" style={{ marginTop: 10 }}>
          <div className="alh-page-size">
            <span>{t('inc.perPage')}</span>
            {[10, 20, 50].map(n => (
              <button key={n} className={`alh-size-btn${size === n ? ' is-active' : ''}`} onClick={() => setSize(n)}>{n}</button>
            ))}
          </div>
          <div className="alh-page-info">{t('inc.pageOf', page + 1, totalPages)} · {total} {t('inc.records')}</div>
          <div className="alh-page-nav">
            <button disabled={page === 0} onClick={() => setPage(p => p - 1)}><ChevronLeft size={13} /> {t('inc.prev')}</button>
            <button disabled={page + 1 >= totalPages} onClick={() => setPage(p => p + 1)}>{t('inc.next')} <ChevronRight size={13} /></button>
          </div>
        </div>
      )}

      {modal && <IncidentModal modal={modal} setModal={setModal} save={save} remove={remove}
                               saving={saving} allowManage={allowManage} allowDelete={allowDelete} t={t} teams={teams}
                               channelOpts={channelOpts} domainOpts={domainOpts} errorCodeOpts={errorCodeOpts}
                               functionCodeOpts={functionCodeOpts} channelCodeOpts={channelCodeOpts}
                               onAddOption={addOption} onDeleteOption={deleteOption} />}
    </div>
  )
}

/** Detay (read-only) / düzenle / oluştur modalı — proje form deseni (modal-box + form-grid). */
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
      const res = await api.incidents.previewNotification(payload)
      if (res?.success) setPreviewHtml(res.html ?? '')
      else toast.error(res?.error || t('inc.saveError'))
    } catch { toast.error(t('inc.saveError')) }
    setPreviewing(false)
  }

  // Takım seçenekleri — seçili takım yüklenen listede yoksa (kapsam dışı/eski kayıt) yine de göster
  const teamOptions = [{ value: '', label: '—' }, ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]
  if (f.team_id != null && f.team_id !== '' && !teams.some(tm => String(tm.id) === String(f.team_id)))
    teamOptions.push({ value: String(f.team_id), label: f.team_name || ('#' + f.team_id) })

  // Süre (dk) otomatik: tespit ↔ çözülme farkı (ikisi de geçerli ve resolved >= detected ise).
  // İkisi de aynı UTC string formatında olduğundan yerel parse'ta offset sadeleşir → fark doğru.
  useEffect(() => {
    if (!editing || !f.detected_at || !f.resolved_at) return
    const diff = Math.round((new Date(f.resolved_at).getTime() - new Date(f.detected_at).getTime()) / 60000)
    if (!Number.isFinite(diff) || diff < 0) return
    if (String(f.duration_minutes ?? '') !== String(diff)) {
      setModal(m => ({ ...m, form: { ...m.form, duration_minutes: diff } }))
    }
  }, [f.detected_at, f.resolved_at, editing]) // eslint-disable-line react-hooks/exhaustive-deps

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

  return (
    <>
    <div className="modal-overlay">
      {/* Dış tıklamada KAPANMAZ — giriş kaybını önlemek için yalnız İptal/Kaydet ile kapanır */}
      <div className="modal-box modal-wide" onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr">
          <div className="modal-icon-hdr-badge" style={{ background: 'linear-gradient(135deg,#0f172a,#334155)', color: '#fff' }}>
            <ListChecks size={20} />
          </div>
          <h3>{t(titleKey)}</h3>
        </div>

        <div className="form-grid form-grid--top">
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
          <DateInput label={t('inc.fResolvedAt')} value={f.resolved_at} disabled={!editing} min={f.detected_at} onChange={v => set('resolved_at', v)} />
          <TextInput label={t('inc.fErrorBudget')} type="number" value={f.error_budget_burn_pct} disabled={!editing} onChange={v => set('error_budget_burn_pct', v)} />
          <TextInput label={t('inc.fDuration')} type="number" value={f.duration_minutes}
            disabled={!editing || (!!f.detected_at && !!f.resolved_at)}
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

        <div className="modal-actions">
          {editing && (
            <label className="inc-sendmail" style={{ display: 'flex', alignItems: 'center', gap: 6, marginRight: 'auto', fontSize: '.85em', fontWeight: 600, cursor: 'pointer' }}>
              <input type="checkbox" checked={!!f.send_notification} onChange={e => set('send_notification', e.target.checked)} />
              {t('inc.sendMail')}
            </label>
          )}
          {editing && <button className="btn btn-secondary" onClick={openPreview} disabled={previewing}>{previewing ? t('inc.previewing') : t('inc.previewMail')}</button>}
          {editing && <button className="btn btn-primary" onClick={save} disabled={saving}>{t('inc.save')}</button>}
          {modal.mode === 'view' && (
            <>
              {allowManage && <button className="btn btn-secondary" onClick={() => setModal(m => ({ ...m, mode: 'edit' }))}>{t('inc.edit')}</button>}
              {allowDelete && <button className="btn btn-danger" onClick={() => remove(f)}><Trash2 size={13} /> {t('inc.delete')}</button>}
            </>
          )}
          <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('inc.cancel')}</button>
        </div>
      </div>
    </div>
    {/* Mail önizleme — kaydetmeden gidecek mailin görünümü (haftalık rapor deseni) */}
    {previewHtml != null && (
      <div className="modal-overlay" onClick={() => setPreviewHtml(null)}>
        <div className="modal-box modal-wide" onClick={e => e.stopPropagation()}
          style={{ maxWidth: 820, height: '85vh', display: 'flex', flexDirection: 'column' }}>
          <h3>{t('inc.previewTitle')}</h3>
          <iframe title="mail-preview" srcDoc={previewHtml} sandbox="allow-same-origin"
            style={{ flex: 1, border: '1px solid var(--border)', borderRadius: 8, background: '#f4f6f8' }} />
          <div className="modal-actions">
            <button className="btn btn-secondary" onClick={() => setPreviewHtml(null)}>{t('inc.cancel')}</button>
          </div>
        </div>
      </div>
    )}
    </>
  )
}
