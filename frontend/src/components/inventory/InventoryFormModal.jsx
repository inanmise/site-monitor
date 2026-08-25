import { useState, useEffect, useRef } from 'react'
import { ChevronDown } from 'lucide-react'
import MDEditor from '@uiw/react-md-editor'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import NotificationGroupSelect from '../ui/NotificationGroupSelect.jsx'
import { INVENTORY_FLAGS, emptyFlags } from '../../utils/inventoryFlags.js'
import { LoadingBlock } from '../ui/Progress.jsx'

/**
 * Envanter (sertifika) kayıt formu — InventoryManager'dan ÇIKARILDI ki dashboard kartındaki
 * "Düzenle"/"Kopyala" butonları da aynı formu açabilsin. Aynı klasördeki InventoryDetails.jsx
 * deseni izleniyor: saf bileşen + aynı dosyada kendi verisini çeken sarmalayıcı.
 *
 * Form state'inin TAMAMI burada; çağıran yalnız hangi modda açılacağını söyler ve kaydedilince
 * kendi listesini tazeler.
 */

const EMPTY = {
  domain: '', port: 443, owner: '', description: '', active: true,
  team_id: '', group_name: '', notification_group_id: '', tier: null,
  ...emptyFlags(),          // 13 operasyonel bayrak — tek kaynak: utils/inventoryFlags.js
  tls_mode: '',
  purchased_by: '',
  change_description: '',
  expected_fingerprint: '', expected_subject: '',
}

function YesNo({ value, onChange }) {
  const isYes = value === true
  return (
    <div className="yn-group">
      <button type="button" className={`yn-btn${isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(true)}>Evet</button>
      <button type="button" className={`yn-btn${!isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(false)}>Hayır</button>
    </div>
  )
}

function SectionHeader({ label }) {
  return <div className="form-section-header">{label}</div>
}

/** Kayıt → form state eşlemesi. Düzenle ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
function formFrom(item) {
  return {
    ...EMPTY,
    ...item,
    team_id:            String(item.team_id ?? ''),
    notification_group_id: item.notification_group_id != null ? String(item.notification_group_id) : '',
    external_vendor:    item.external_vendor  ?? false,
    action_required:    item.action_required  ?? false,
    openshift:          item.openshift        ?? false,
    ssl_pinning:        item.ssl_pinning      ?? false,
    internal_cert:      item.internal_cert    ?? false,
    jks_keystore:       item.jks_keystore     ?? false,
    server_update:      item.server_update    ?? false,
    netscaler:          item.netscaler        ?? false,
    waf_enabled:        item.waf_enabled      ?? false,
    in_use:             item.in_use           ?? false,
    ev_certificate:     item.ev_certificate   ?? false,
    transferred_to_sy:  item.transferred_to_sy ?? false,
    use_proxy:          item.use_proxy        ?? false,
    tls_mode:           item.tls_mode         ?? '',
    purchased_by:       item.purchased_by     ?? '',
    change_description: item.change_description ?? '',
    expected_fingerprint: item.expected_fingerprint ?? '',
    expected_subject:   item.expected_subject ?? '',
    tier:               item.tier ?? null,
  }
}

/** Modun başlangıç form durumu. */
function initialForm(mode, record) {
  if (mode === 'add' || !record) return EMPTY
  const base = formFrom(record)
  if (mode !== 'duplicate') return base
  // Kopyada taşınMAyan üç alan:
  //  - expected_* : o domain'in BEKLENEN sertifika parmak izi/subject'i. Kopyaya taşınırsa yeni
  //    domain sürekli DEPLOYMENT_INCOMPLETE alarmı üretir (ScriptedMonitorPage'in gizli env'leri
  //    sıfırlamasıyla aynı mantık).
  //  - change_description : kaynak domain'in kendi değişiklik geçmişi; kopyada yanıltıcı olur.
  return { ...base, expected_fingerprint: '', expected_subject: '', change_description: '' }
}

/**
 * @param {'add'|'edit'|'duplicate'} mode
 * @param {object|null} record            edit/duplicate kaynağı
 * @param {Array}  [teams]                verilmezse bileşen kendisi çeker (dashboard yolu)
 * @param {boolean} [canManage=true]      takım/grup seçicilerinin disabled'ı
 * @param {Function} onClose
 * @param {Function} onSaved              (savedResponse) => void — çağıran kapatır + tazeler
 */
export default function InventoryFormModal({ mode = 'add', record = null, teams: teamsProp,
                                             canManage = true, onClose, onSaved }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  const { showConfirm } = useDialog()

  const [form, setForm]   = useState(() => initialForm(mode, record))
  const [teams, setTeams] = useState(() => teamsProp ?? [])
  const [teamGroups, setTeamGroups] = useState([])   // seçili takımın "cert" grupları (sızıntısız, server-scoped)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)
  const [showScrollHint, setShowScrollHint] = useState(false)
  const formGridRef = useRef(null)

  const isDuplicate = mode === 'duplicate'

  // Takım listesi: InventoryManager kendi listesini geçer (ekstra istek yok); dashboard geçmez.
  useEffect(() => {
    if (teamsProp) { setTeams(teamsProp); return }
    let alive = true
    api.admin.getTeams().then(res => { if (alive && res?.success) setTeams(res.data) })
    return () => { alive = false }
  }, [teamsProp])

  useEffect(() => {
    const el = formGridRef.current
    if (!el) return
    const check = () => {
      const hasOverflow = el.scrollHeight > el.clientHeight + 2
      const atBottom    = el.scrollTop + el.clientHeight >= el.scrollHeight - 6
      setShowScrollHint(hasOverflow && !atBottom)
    }
    check()
    el.addEventListener('scroll', check, { passive: true })
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => { el.removeEventListener('scroll', check); ro.disconnect() }
  }, [])

  // Seçili takımın "cert" gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!form.team_id) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.team_id, 'cert').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [form.team_id])

  function f(field, val) { setForm(prev => ({ ...prev, [field]: val })) }

  function validate() {
    if (!form.domain.trim()) return t('inv.formDomain') + ' zorunlu'
    if (!form.team_id) return t('inv.teamRequired')
    return null
  }

  async function save() {
    const err = validate()
    if (err) {
      setMsg(err)
      // Inline hata mesajı modal'ın üstünde — kullanıcı uzun form'da kaçırmasın
      formGridRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' })
      return
    }

    // Domain rename uyarısı — geçmiş veri taşıma onay isteği. YALNIZ edit'te: kopyada kaynak
    // domain zaten dolu geliyor ve kullanıcının onu değiştirmesi BEKLENEN akış.
    if (mode === 'edit' && record?.domain && form.domain.trim() !== record.domain) {
      const confirmed = await showConfirm({
        title: t('inv.renameTitle'),
        message: t('inv.renameMessage', record.domain, form.domain.trim()),
        confirmText: t('inv.renameConfirm'),
        cancelText: t('inv.cancel'),
      })
      if (!confirmed) return
    }

    setSaving(true)
    setMsg(null)
    const payload = {
      domain:             form.domain.trim(),
      port:               parseInt(form.port) || 443,
      owner:              form.owner,
      description:        form.description,
      active:             form.active,
      team_id:            form.team_id ? Number(form.team_id) : null,
      // Bos = takim varsayilani -> takim adresi (zincirin kalani).
      notificationGroupId: form.notification_group_id ? Number(form.notification_group_id) : null,
      group_name:         form.group_name?.trim() || null,
      ug_team_id:         null,   // tek takım modeli — UG ayrımı kaldırıldı
      external_vendor:    form.external_vendor,
      action_required:    form.action_required,
      openshift:          form.openshift,
      ssl_pinning:        form.ssl_pinning,
      internal_cert:      form.internal_cert,
      jks_keystore:       form.jks_keystore,
      server_update:      form.server_update,
      netscaler:          form.netscaler,
      waf_enabled:        form.waf_enabled,
      in_use:             form.in_use,
      ev_certificate:     form.ev_certificate,
      transferred_to_sy:  form.transferred_to_sy,
      use_proxy:          form.use_proxy,
      tls_mode:           form.tls_mode || null,
      purchased_by:       form.purchased_by || null,
      change_description: form.change_description || null,
      // DİKKAT: payload'ın tek camelCase çifti (entity Jackson adlarıyla eşleşsin diye).
      // snake_case'e "düzeltilirse" iki alan sessizce null gider.
      expectedFingerprint: form.expected_fingerprint || null,
      expectedSubject:    form.expected_subject || null,
      tier:               form.tier ? Number(form.tier) : null,
    }
    const res = mode === 'edit'
      ? await api.admin.updateInventory(record.id, payload)
      : await api.admin.addInventory(payload)
    setSaving(false)
    if (res?.success) {
      toast.success(t('inv.saved'))
      if ((res.alertsClosed ?? 0) > 0) {
        toast.success(t('inv.deactivatedAlerts', res.alertsClosed))
      }
      onSaved?.(res)
    } else {
      // Sunucu hatası → tek bildirim (toast); modal AÇIK kalır (mükerrer domain 409'u burada görünür).
      // Inline setMsg yalnız form validation için.
      toast.error(res?.error || t('inv.saveError'))
    }
  }

  return (
    // Dış (overlay) tıklamada KAPANMAZ — girilen veri kaybolmasın; yalnız İptal/Kaydet kapatır.
    <div className="modal-overlay">
      <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
        <h3>
          {mode === 'edit' ? t('inv.editTitle') : t('inv.addTitle')}
          {isDuplicate && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}
        </h3>
        {isDuplicate && <div className="mon-dup-hint">{t('inv.duplicateHint')}</div>}

        <div className="form-grid" ref={formGridRef}>

          {/* ── Temel Bilgiler ── */}
          <SectionHeader label={t('inv.sectionBasic')} />

          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.active} onChange={e => f('active', e.target.checked)} />
            {t('inv.formActive')}
          </label>

          <label>
            <span>{t('inv.formDomain')} <span className="req-star">*</span></span>
            <input value={form.domain} onChange={e => f('domain', e.target.value)}
              placeholder={t('inv.formDomainPh')} autoFocus={isDuplicate} />
          </label>
          <label>
            {t('inv.formPort')}
            <input type="number" value={form.port} onChange={e => f('port', e.target.value)} />
          </label>

          <label>
            <span>{t('inv.formTeam')} <span className="req-star">*</span></span>
            <SearchableSelect
              value={form.team_id}
              onChange={v => f('team_id', v)}
              placeholder={t('inv.selectTeam')}
              disabled={!canManage}
              searchThreshold={2}
              options={[
                { value: '', label: t('inv.selectTeam') },
                ...teams.map(team => ({ value: team.id, label: team.name })),
              ]}
            />
          </label>

          <label>
            {t('inv.formGroup')}
            <SearchableSelect
              value={form.group_name}
              onChange={v => f('group_name', v)}
              placeholder={t('inv.noGroup')}
              disabled={!canManage || !form.team_id}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              options={[
                { value: '', label: t('inv.noGroup') },
                ...teamGroups.map(g => ({ value: g.name, label: g.name })),
              ]}
            />
          </label>

          <NotificationGroupSelect
            teamId={form.team_id}
            value={form.notification_group_id}
            onChange={v => f('notification_group_id', v)}
            disabled={!canManage}
          />

          <label>
            {t('inv.formTier')}
            <SearchableSelect
              value={form.tier ?? ''}
              onChange={v => f('tier', v ? Number(v) : null)}
              options={[
                { value: '', label: t('inv.tierNone') },
                { value: '1', label: t('inv.tier1') },
                { value: '2', label: t('inv.tier2') },
                { value: '3', label: t('inv.tier3') },
                { value: '4', label: t('inv.tier4') },
              ]}
            />
          </label>

          <label>
            {t('inv.formTlsMode')}
            <SearchableSelect
              value={form.tls_mode}
              onChange={v => f('tls_mode', v)}
              options={[
                { value: '',        label: t('inv.tlsModeInherit') },
                { value: 'browser', label: t('inv.tlsModeBrowser') },
                { value: 'default', label: t('inv.tlsModeDefault') },
              ]}
            />
          </label>

          <label>
            {t('inv.formPurchasedBy')}
            <input value={form.purchased_by} onChange={e => f('purchased_by', e.target.value)} />
          </label>

          {/* ── Operasyonel Bilgiler ── */}
          <SectionHeader label={t('inv.sectionOps')} />

          <div className="yn-grid">
            {INVENTORY_FLAGS.map(({ key, labelKey }) => (
              <div key={key} className="yn-field-row">
                <span className="yn-field-label">{t(labelKey)}</span>
                <YesNo value={form[key]} onChange={v => f(key, v)} />
              </div>
            ))}
          </div>

          <label className="full-width">
            {t('inv.formChangeDesc')}
            <div data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
              <MDEditor
                value={form.change_description}
                onChange={(v) => f('change_description', v ?? '')}
                preview="edit"
                height={260}
                visibleDragbar={false}
              />
            </div>
          </label>

        </div>

        {msg && <div className="alert-msg" style={{ marginTop: 10 }}>{msg}</div>}

        {showScrollHint && (
          <button
            type="button"
            className="modal-scroll-hint"
            onClick={() => formGridRef.current?.scrollBy({ top: 200, behavior: 'smooth' })}
          >
            <ChevronDown size={14} />
            <span>{t('inv.scrollForMore')}</span>
          </button>
        )}

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('inv.cancel')}</button>
          <button className="btn btn-primary" onClick={save}
            disabled={saving || !form.domain.trim() || !form.team_id}>
            {saving ? t('inv.saving') : t('inv.save')}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Domain'den kaydı çözen sarmalayıcı — dashboard kartında envanter ID'si YOK (sertifikalar
 * domain-anahtarlı). Kayıt bulunamazsa (silinmiş / yetki kapsamı dışı) BOŞ FORM AÇILMAZ:
 * kullanıcı doldurup kaydeder ve mükerrer bir envanter kaydı doğardı.
 */
export function InventoryFormModalForDomain({ domain, mode = 'edit', onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const [record, setRecord] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    api.admin.getInventoryByDomain(domain)
      .then(res => {
        if (!alive) return
        if (res?.success && res.data) { setRecord(res.data); setLoading(false) }
        else { toast.error(t('inv.notFoundForDomain', domain)); onClose?.() }
      })
      .catch(() => { if (alive) { toast.error(t('inv.loadError')); onClose?.() } })
    return () => { alive = false }
  }, [domain])   // eslint-disable-line react-hooks/exhaustive-deps

  if (loading || !record) {
    return (
      <div className="modal-overlay">
        <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
          <LoadingBlock label={t('modal.loading')} fullWidth />
        </div>
      </div>
    )
  }
  return <InventoryFormModal mode={mode} record={record} onClose={onClose} onSaved={onSaved} />
}
