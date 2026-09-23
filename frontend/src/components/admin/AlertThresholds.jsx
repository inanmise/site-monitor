import { useState, useEffect, useRef, useMemo } from 'react'
import { Plus, Trash2, Layers } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { Spinner } from '../ui/Progress.jsx'
import AdminChangeHistory from './AdminChangeHistory.jsx'

const TIERS = [1, 2, 3, 4]
const PREVIEW_DEBOUNCE_MS = 400

/** Sunucu tier'ı sayı ya da null döner; bozuk/eski değer (ör. metin) varsayılan sayılır. */
function tierOf(row) {
  const n = Number(row?.tier)
  return Number.isInteger(n) && n >= 1 && n <= 4 ? n : null
}

/**
 * Uyarı Eşik Değerleri — VARSAYILAN satır + tier satırları (2026-09-20).
 *
 * <p>Tier satırı o tier'daki alanlar için varsayılanın YERİNE geçer (Tier 1 müşteri yüzü → daha erken
 * uyarı). Düzenlerken canlı etki önizlemesi: "bu değerlerle BUGÜN kaç alan hangi seviyede olur" —
 * körlemesine eşik değişimi bir gecede onlarca KRİTİK alarm üretebilir. Önizleme kalıcı yazmaz.
 */
export default function AlertThresholds() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [thresholds, setThresholds] = useState([])
  const [editing, setEditing] = useState(null)      // {..row} | {tier, warning_days, …, _new: true}
  const [saving, setSaving] = useState(false)
  const [addTier, setAddTier] = useState('')
  // Yukleme hatasi GORUNUR olmali: eskiden ne else ne catch vardi; API 403/500 donunce de
  // ag koparken de liste bos kaliyor ve ekran "esik yok" diyordu. Esikler alarm siddetini
  // belirledigi icin yonetici bunu "esikler silinmis" diye okuyup elle yeniden giriyordu.
  const [loadError, setLoadError] = useState(null)
  const [preview, setPreview] = useState(null)      // { loading, data, error }
  const previewTimer = useRef(null)

  useEffect(() => { load() }, [])

  async function load() {
    try {
      const res = await api.admin.getThresholds()
      if (res?.success) { setThresholds(res.data || []); setLoadError(null) }
      else setLoadError(res?.error || t('settings.loadError'))
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  // Varsayılan önce, sonra tier 1..4.
  const rows = useMemo(() => [...thresholds].sort((a, b) => (tierOf(a) ?? 0) - (tierOf(b) ?? 0)), [thresholds])
  const usedTiers = useMemo(() => new Set(rows.map(tierOf).filter(Boolean)), [rows])
  const freeTiers = TIERS.filter(x => !usedTiers.has(x))

  function rowTitle(row) {
    const tier = tierOf(row)
    return tier ? t(`inv.tier${tier}`) : t('thr.defaultRow')
  }

  function startEdit(thr) { setEditing({ ...thr }); setPreview(null) }

  function startAdd() {
    const tier = Number(addTier)
    if (!tier) return
    const base = rows.find(r => tierOf(r) == null) || {}
    setEditing({
      _new: true, tier,
      warning_days: base.warning_days ?? 30, high_days: base.high_days ?? 15,
      critical_days: base.critical_days ?? 7, re_alert_interval_hours: base.re_alert_interval_hours ?? 24,
    })
    setPreview(null)
  }

  // Sıra: kritik ≤ yüksek ≤ uyarı — sunucu da reddeder; burada anında söylenir.
  const orderError = editing && !(Number(editing.critical_days) <= Number(editing.high_days)
    && Number(editing.high_days) <= Number(editing.warning_days))

  // Canlı etki önizlemesi: değerler değiştikçe (debounce) sunucudan sayım.
  useEffect(() => {
    if (!editing || orderError || typeof api.admin.previewThreshold !== 'function') { setPreview(null); return }
    const w = Number(editing.warning_days), h = Number(editing.high_days), c = Number(editing.critical_days)
    if (![w, h, c].every(n => Number.isInteger(n) && n >= 0)) { setPreview(null); return }
    if (previewTimer.current) clearTimeout(previewTimer.current)
    setPreview(p => ({ ...(p || {}), loading: true }))
    previewTimer.current = setTimeout(async () => {
      try {
        const res = await api.admin.previewThreshold({ tier: tierOf(editing), warning: w, high: h, critical: c })
        if (res?.success) setPreview({ loading: false, data: res.data })
        else setPreview({ loading: false, error: res?.error || t('thr.previewError') })
      } catch (e) {
        setPreview({ loading: false, error: e?.message || t('thr.previewError') })
      }
    }, PREVIEW_DEBOUNCE_MS)
    return () => { if (previewTimer.current) clearTimeout(previewTimer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing?.warning_days, editing?.high_days, editing?.critical_days, editing?.tier, editing?._new, orderError])

  async function save() {
    if (orderError) { toast.error(t('thr.orderError')); return }
    setSaving(true)
    try {
      const body = {
        tier: tierOf(editing),
        warning_days: Number(editing.warning_days), high_days: Number(editing.high_days),
        critical_days: Number(editing.critical_days), re_alert_interval_hours: Number(editing.re_alert_interval_hours),
      }
      const res = editing._new
        ? await api.admin.createThreshold(body)
        : await api.admin.updateThreshold(editing.id, { ...editing, ...body })
      if (res?.success) { setEditing(null); setAddTier(''); toast.success(editing._new ? t('thr.created') : t('thr.saved')); load() }
      else toast.error(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  async function remove(row) {
    const ok = await showConfirm({
      title: t('thr.deleteTitle'),
      message: t('thr.deleteMsg', rowTitle(row)),
      confirmText: t('thr.delete'),
      variant: 'danger',
    })
    if (!ok) return
    const res = await api.admin.deleteThreshold(row.id)
    if (res?.success) { toast.success(t('thr.deleted')); if (editing?.id === row.id) setEditing(null); load() }
    else toast.error(res?.error || 'Error')
  }

  function field(key, labelKey, hintKey) {
    return (
      <div className="threshold-field">
        <label>{t(labelKey)}</label>
        <input type="number" min={0} value={editing[key]} onChange={(e) => setEditing({ ...editing, [key]: e.target.value === '' ? '' : +e.target.value })} />
        <span className="hint">{t(hintKey)}</span>
      </div>
    )
  }

  function renderForm() {
    return (
      <div className="threshold-form">
        <div className="threshold-grid">
          {field('warning_days', 'thr.warnLabel', 'thr.warnHint')}
          {field('high_days', 'thr.highLabel', 'thr.highHint')}
          {field('critical_days', 'thr.critLabel', 'thr.critHint')}
          {field('re_alert_interval_hours', 'thr.intervalLabel', 'thr.intervalHint')}
        </div>
        {orderError && <AlertBanner tone="danger">{t('thr.orderError')}</AlertBanner>}
        {!orderError && <ThresholdPreview preview={preview} t={t} />}
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={() => { setEditing(null); setPreview(null) }}>{t('thr.cancel')}</button>
          <button className="btn btn-primary" onClick={save} disabled={saving || orderError} aria-busy={saving || undefined}>{t('thr.save')}</button>
        </div>
      </div>
    )
  }

  return (
    <div className="admin-section">
      <h3>{t('thr.title')}</h3>
      <p className="section-desc">{t('thr.desc')} {t('thr.tierDesc')}</p>
      {loadError && thresholds.length === 0 && (
        <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
      )}
      {rows.map((thr) => {
        const tier = tierOf(thr)
        return (
          <div key={thr.id} className={`threshold-card${tier ? ' threshold-card--tier' : ''}`}>
            <div className="threshold-card-head">
              <span className="threshold-card-title"><Layers size={14} /> {rowTitle(thr)}</span>
              {tier
                ? <span className="threshold-card-scope">{t('thr.scopeTier', tier)}</span>
                : <span className="threshold-card-scope">{t('thr.scopeDefault')}</span>}
            </div>
            {editing?.id === thr.id && !editing._new ? renderForm() : (
              <div className="threshold-display">
                <div className="threshold-levels">
                  <div className="level-badge warning">{t('thr.displayWarn', thr.warning_days)}</div>
                  <div className="level-badge high">{t('thr.displayHigh', thr.high_days)}</div>
                  <div className="level-badge critical">{t('thr.displayCrit', thr.critical_days)}</div>
                  <div className="level-badge info">{t('thr.displayInterval', thr.re_alert_interval_hours)}</div>
                </div>
                <div className="threshold-actions">
                  <button className="btn btn-secondary" onClick={() => startEdit(thr)}>{t('thr.edit')}</button>
                  {tier && (
                    <button className="btn btn-danger btn-sm-p" onClick={() => remove(thr)} title={t('thr.delete')}
                      aria-label={`${t('thr.scopeTier', tier)} — ${t('thr.delete')}`}>
                      <Trash2 size={14} />
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>
        )
      })}

      {/* Tier eşiği ekle — yalnız satırı olmayan tier'lar seçilebilir. */}
      {editing?._new ? (
        <div className="threshold-card threshold-card--tier">
          <div className="threshold-card-head">
            <span className="threshold-card-title"><Layers size={14} /> {t(`inv.tier${editing.tier}`)}</span>
            <span className="threshold-card-scope">{t('thr.scopeTier', editing.tier)}</span>
          </div>
          {renderForm()}
        </div>
      ) : freeTiers.length > 0 && (
        <div className="threshold-add">
          <SearchableSelect
            value={addTier}
            onChange={setAddTier}
            placeholder={t('thr.addTierPick')}
            ariaLabel={t('thr.addTierPick')}
            options={[{ value: '', label: t('thr.addTierPick') }, ...freeTiers.map(x => ({ value: String(x), label: t(`inv.tier${x}`) }))]}
          />
          <button className="btn btn-secondary" onClick={startAdd} disabled={!addTier}>
            <Plus size={14} /> {t('thr.addTier')}
          </button>
        </div>
      )}

      <AdminChangeHistory resource="ALERT_THRESHOLD" />
    </div>
  )
}

/** Etki önizleme paneli: mevcut → önerilen sayımlar + örnek alanlar. */
function ThresholdPreview({ preview, t }) {
  if (!preview) return null
  if (preview.loading && !preview.data) {
    return <div className="threshold-preview threshold-preview--loading"><Spinner size={12} inline decorative /> {t('thr.previewLoading')}</div>
  }
  if (preview.error) return <div className="threshold-preview threshold-preview--error">{preview.error}</div>
  const d = preview.data
  if (!d) return null
  const cur = d.current || {}, next = d.proposed || {}
  const cell = (key, cls) => {
    const a = Number(cur[key] ?? 0), b = Number(next[key] ?? 0)
    const delta = b - a
    return (
      <span className={`threshold-preview-cell level-badge ${cls}`}>
        {t(`thr.prev.${key}`)}: <b>{b}</b>
        {delta !== 0 && <span className="threshold-preview-delta">({delta > 0 ? '+' : ''}{delta})</span>}
      </span>
    )
  }
  const samples = d.samples || {}
  return (
    <div className="threshold-preview" data-testid="threshold-preview">
      <div className="threshold-preview-head">
        {t('thr.previewTitle', d.scope_total ?? 0)}
        {d.unchecked > 0 && <span className="hint"> · {t('thr.previewUnchecked', d.unchecked)}</span>}
        {preview.loading && <Spinner size={11} inline decorative />}
      </div>
      <div className="threshold-preview-row">
        {cell('critical', 'critical')}{cell('high', 'high')}{cell('warning', 'warning')}{cell('ok', 'info')}
      </div>
      {['CRITICAL', 'HIGH', 'WARNING'].map(lv => (samples[lv] || []).length > 0 && (
        <div key={lv} className="threshold-preview-samples">
          <span className="threshold-preview-lv">{t(`thr.prev.${lv.toLowerCase()}`)}:</span>
          {samples[lv].map(s => <span key={s} className="threshold-preview-chip">{s}</span>)}
        </div>
      ))}
      <div className="hint">{t('thr.previewHint')}</div>
    </div>
  )
}
