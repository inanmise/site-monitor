import { useState, useEffect, useMemo, useId } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import AdminChangeHistory from './AdminChangeHistory.jsx'
import { formatDateSec } from '../../api/client'
import { ArrowRight, Download, Timer, UserPlus, Users } from 'lucide-react'
import { toCsv, downloadCsv, stampedName } from '../../utils/csvExport.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import PaginationBar from '../ui/PaginationBar.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge, { OrgRoleBadge } from './ToneBadge.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const ROLES  = ['PO', 'TECH', 'MANAGER', 'CLEVEL']
const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
/** Alarm seviyesi rozeti — dolgulu ton (eski `.level-badge` renkleri: amber / turuncu / kırmızı). */
const LEVEL_CLS = { WARNING: 'bg-amber-500 text-white', HIGH: 'bg-orange-600 text-white', CRITICAL: 'bg-red-700 text-white' }
const emptyContact = { user_id: '', role: 'TECH', min_alert_level: 'WARNING', webhook_url: '', webhook_type: 'TEAMS', active: true, team_id: '', delay_minutes: '' }

/** Zamana bağlı eskalasyon adımı (2026-10-01): boş = anlık (bugünkü davranış); 1–1440 dk. Sunucu aynı aralığı uygular. */
export const DELAY_MIN = 1
export const DELAY_MAX = 1440
/** Kişinin etkin gecikmesi (dk) ya da null (anlık). */
export const contactDelay = (c) => (Number(c?.delay_minutes) > 0 ? Number(c.delay_minutes) : null)

/**
 * Formdaki gecikme metni → { value, invalid }. Boş = null (anlık). Değer 1–1440 tam sayı olmalı; 0 da geçersiz
 * (anlık için alan boş bırakılır).
 */
export function parseDelayInput(raw) {
  const s = String(raw ?? '').trim()
  if (s === '') return { value: null, invalid: false }
  const n = Number(s)
  const ok = /^\d+$/.test(s) && Number.isInteger(n) && n >= DELAY_MIN && n <= DELAY_MAX
  return { value: ok ? n : null, invalid: !ok }
}

/**
 * @param onOpenSimulator (g_* paramları) → "Kim bilgilendirilir?" sekmesine geçer (AdminPanel.jump). Simülatör
 *        2026-09-27'de bu sayfadan ayrı sekmeye taşındı; burada yalnız oraya götüren bağlantı kalır.
 */
export default function EscalationContacts({ teams = [], systemRole, isAdmin: isAdminProp = false, onOpenSimulator }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole ? systemRole === 'ADMIN' : isAdminProp
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const { showConfirm } = useDialog()
  const [contacts, setContacts] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [users, setUsers]       = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyContact)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)
  // Alan-bazlı doğrulama (2026-09-30 kuralı): hata alanın altında, modal yeniden açılınca sıfırlanır.
  const fe = useFormErrors(modal)

  // İstemci-taraflı filtre + sayfalama (getContacts tüm listeyi döndürür)
  // Süzgeçler URL'de (g_*): derin bağlantı + yenileme korur (2026-09-20).
  const [histFilter, setHistFilter] = useState(null)   // { id, name } — satırdan "Geçmiş"
  const [deliveries, setDeliveries] = useState({})     // e-posta → son webhook teslimatı (2026-09-20)
  const [testingId, setTestingId] = useState(null)
  const [q, setQ]         = useState(() => readUrlParam('g_q', ''))
  const [fRole, setFRole] = useState(() => readUrlParam('g_role', ''))
  const [fLevel, setFLevel] = useState(() => readUrlParam('g_level', ''))
  const [fTeam, setFTeam] = useState(() => readUrlParam('g_team', ''))
  useUrlQuerySync({ g_q: q, g_role: fRole, g_level: fLevel, g_team: fTeam })

  const teamMap = Object.fromEntries(teams.map(t => [t.id, t.name]))
  const userMap = Object.fromEntries(users.map(u => [String(u.id), u]))

  const roleLabelMap = {
    PO: t('ec.role.po'), TECH: t('ec.role.tech'), MANAGER: t('ec.role.manager'), CLEVEL: t('ec.role.clevel'),
  }
  const levelLabelMap = {
    WARNING: t('ec.level.warning'), HIGH: t('ec.level.high'), CRITICAL: t('ec.level.critical'),
  }

  const filteredContacts = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return contacts.filter(c =>
      (!needle || (c.name || '').toLowerCase().includes(needle) || (c.email || '').toLowerCase().includes(needle))
      && (!fRole  || c.role === fRole)
      && (!fLevel || c.min_alert_level === fLevel)
      && (!fTeam  || String(c.team_id) === String(fTeam)))
  }, [contacts, q, fRole, fLevel, fTeam])

  // Proje standardı sayfalama (2026-09-21): usePagination + PaginationBar (istemci-taraflı)
  const pager = usePagination(filteredContacts, { listKey: 'admin-contacts', preset: 'panel', resetDeps: [q, fRole, fLevel, fTeam] })
  const pagedContacts = pager.pageItems

  useEffect(() => { load(); loadUsers() }, [])

  async function load() {
    // Bos liste ile YUKLENEMEDI ayni ekrani uretiyordu (ne else ne catch vardi).
    // Tirmanma kisileri alarmin KIME gidecegini belirliyor: "kisi yok" goren yonetici
    // eksik sanip yeniden ekler, gercekte kayitlar duruyordur.
    try {
      loadDeliveries()
      const res = await api.admin.getContacts()
      if (res?.success) { setContacts(res.data); setLoadError(null) }
      else setLoadError(res?.error || t('settings.loadError'))
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers((res.data ?? []).filter(u => u.active))   // data null gelirse ekran cokmesin
  }

  async function loadDeliveries() {
    try {
      const r = await api.admin.contactWebhookStatus?.()
      if (r?.success) setDeliveries(r.data || {})
    } catch { /* sütun boş kalır; liste yine gelir */ }
  }

  /** Webhook testi (2026-09-20): sonuç toast + son teslimat sütunu tazelenir. */
  async function testWebhook(c) {
    setTestingId(c.id)
    try {
      const r = await api.admin.testContactWebhook(c.id)
      if (r?.success && r.data?.status === 'SENT') toast.success(t('ec.testWebhookSent', r.data.target || ''))
      else toast.error(t('ec.testWebhookFailed', r?.data?.error || r?.error || '—'))
    } catch (e) {
      toast.error(t('ec.testWebhookFailed', e?.message || '—'))
    } finally {
      setTestingId(null)
      loadDeliveries()
    }
  }

  function openAdd() {
    setMsg(null)
    // Takım BİLİNÇLİ seçilir (2026-09-28): eskiden ilk takım önceden seçiliydi — değiştirmeyi unutan yönetici kişiyi
    // o takıma yazıyor, kişi o takımın alarmlarını alıyordu. Tek takım görünüyorsa belirsizlik yok, o seçilir.
    setForm({ ...emptyContact, team_id: teams.length === 1 ? teams[0].id : '' })
    setModal('add')
  }
  function openEdit(c) {
    setMsg(null)
    setForm({
      user_id: c.user_id ? String(c.user_id) : '',
      role: c.role || 'TECH',
      min_alert_level: c.min_alert_level || 'WARNING',
      webhook_url: c.webhook_url || '',
      webhook_type: c.webhook_type || 'TEAMS',
      active: c.active !== false,
      team_id: c.team_id ?? '',
      delay_minutes: contactDelay(c) != null ? String(contactDelay(c)) : '',
    })
    setModal(c)
  }

  async function save() {
    // Gecikme alanı: hata alanın altında (tost değil); ilk hatalı alana kaydırılır.
    const delay = parseDelayInput(form.delay_minutes)
    if (fe.check({ delay_minutes: delay.invalid && t('ec.delayInvalid') })) return
    setSaving(true)
    try {
      const payload = {
        user_id: form.user_id ? Number(form.user_id) : null,
        role: form.role,
        min_alert_level: form.min_alert_level,
        webhook_url: form.webhook_url || null,
        webhook_type: form.webhook_type || null,
        active: form.active,
        team_id: form.team_id ? Number(form.team_id) : null,
      }
      // Gecikme YALNIZ gerektiğinde gönderilir: boş alan + gecikmesiz kişi → payload bugünküyle AYNI (anahtar yok;
      // sunucu anahtar yoksa alana dokunmaz). Var olan gecikme silindiyse açıkça null gider (anlık davranışa dönüş).
      if (delay.value != null) payload.delay_minutes = delay.value
      else if (modal !== 'add' && contactDelay(modal) != null) payload.delay_minutes = null
      const res = modal === 'add'
        ? await api.admin.addContact(payload)
        : await api.admin.updateContact(modal.id, payload)
      if (res?.success) { setModal(null); toast.success(t('ec.saved')); load() }
      else setMsg(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  async function del(id) {
    const contact = contacts.find(c => c.id === id)
    const ok = await showConfirm({
      title: t('ec.deleteTitle'),
      message: t('ec.deleteMsg', contact?.name ?? id),
      variant: 'danger',
      confirmText: t('ec.deleteConfirm'),
      cancelText: t('ec.deleteCancel'),
    })
    if (!ok) return
    const res = await api.admin.deleteContact(id)
    if (res?.success) { toast.success(t('ec.deleted')); load() }
    else toast.error(res?.error || 'Error')
  }

  const selectedUser = form.user_id ? userMap[form.user_id] : null
  // Takım zorunlu (sunucu da 400 verir): takımsız kişi hiçbir bildirim almaz. Takım seçimi yalnız yöneticide
  // görünür; diğer yazarlarda sunucu kendi takımını yazar.
  const teamPickable = isAdmin && teams.length > 0
  const canSave = !!form.user_id && (!teamPickable || !!form.team_id)

  const TH = 'h-9 px-3 text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase'
  const hint = 'text-xs text-muted-foreground [overflow-wrap:anywhere]'
  return (
    <section className="mb-8 flex min-w-0 flex-col gap-4">
      {/* Başlık + eylemler — telefonda alt alta */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-lg leading-tight font-semibold">{t('ec.title')}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{t('ec.desc')}</p>
          {/* Seviye → alıcı zinciri (eskiden tanımsız `.escalation-legend` sınıfıyla düz metin) */}
          <ul data-slot="ec-legend" className="mt-2 flex list-none flex-wrap gap-1.5 p-0">
            {[['WARNING', 'ec.legendWarn'], ['HIGH', 'ec.legendHigh'], ['CRITICAL', 'ec.legendCrit']].map(([lvl, key]) => (
              <li key={lvl}>
                <Badge variant="outline" className="gap-1.5 font-medium text-muted-foreground">
                  <span aria-hidden="true" className={cn('size-2 rounded-full', LEVEL_CLS[lvl])} />{t(key)}
                </Badge>
              </li>
            ))}
          </ul>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {onOpenSimulator && (
            // Takım süzgeci yalnız yöneticide görünür; seçiliyse simülasyon o takımla açılır.
            <Button variant="link" className="h-10 px-1 md:h-9" onClick={() => onOpenSimulator(isAdmin && fTeam ? { g_team: fTeam } : undefined)}>
              {t('wn.testLink')} <ArrowRight aria-hidden="true" />
            </Button>
          )}
          <Button variant="outline" title={t('ec.exportCsv')} onClick={() => {
            const rows = filteredContacts.map(c => [c.name, c.email, teamMap[c.team_id] || '', roleLabelMap[c.role] || c.role, levelLabelMap[c.min_alert_level] || c.min_alert_level,
              c.webhook_url ? c.webhook_type : '', c.active ? t('ec.active') : t('ec.inactive'), contactDelay(c) ?? ''])
            downloadCsv(stampedName('eskalasyon-kisileri'), toCsv([t('ec.colName'), t('ec.colEmail'), t('ec.colTeam'), t('ec.colRole'), t('ec.colLevel'), t('ec.colWebhook'), t('ec.colActive'), t('ec.colDelay')], rows))
          }}><Download aria-hidden="true" /> {t('ec.exportCsv')}</Button>
          {canManage && <Button variant="success" onClick={openAdd}><UserPlus aria-hidden="true" /> {t('ec.addBtn')}</Button>}
        </div>
      </div>
      {loadError && contacts.length === 0 && (
        <AlertBanner tone="danger" title={t('settings.loadError')} role="alert" className="mb-0">{String(loadError)}</AlertBanner>
      )}

      {/* Filtre çubuğu — ad/e-posta araması + Rol/Seviye/Takım (telefonda tam genişlik, alt alta) */}
      <div className="flex flex-wrap items-center gap-2">
        <ToolbarSearch value={q} onChange={setQ} placeholder={t('ec.searchPlaceholder')} ariaLabel={t('ec.searchPlaceholder')}
          clearLabel={t('app.clear')} className="w-full max-w-none sm:w-auto sm:max-w-xs" />
        <div className="w-full min-w-0 sm:w-44">
          <SearchableSelect value={fRole} onChange={setFRole} placeholder={t('ec.allRoles')} ariaLabel={t('flt.role')}
            options={[{ value: '', label: t('ec.allRoles') },
              ...ROLES.map(r => ({ value: r, label: roleLabelMap[r] }))]} />
        </div>
        <div className="w-full min-w-0 sm:w-44">
          <SearchableSelect value={fLevel} onChange={setFLevel} placeholder={t('ec.allLevels')} ariaLabel={t('flt.level')}
            options={[{ value: '', label: t('ec.allLevels') },
              ...LEVELS.map(l => ({ value: l, label: levelLabelMap[l] }))]} />
        </div>
        {isAdmin && teams.length > 0 && (
          <div className="w-full min-w-0 sm:w-48">
            <SearchableSelect value={fTeam} onChange={setFTeam} placeholder={t('ec.allTeams')} searchThreshold={2} ariaLabel={t('flt.team')}
              options={[{ value: '', label: t('ec.allTeams') },
                ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]} />
          </div>
        )}
      </div>

      <div className="overflow-hidden rounded-lg border bg-card">
        <Table className="text-[0.86em]">
          <TableHeader className="bg-muted/50">
            <TableRow className="hover:bg-transparent">
              <TableHead className={TH}>{t('ec.colName')}</TableHead>
              {/* Telefonda düşük öncelikli sütunlar gizli (ad + rol + seviye + durum + eylem kalır) */}
              <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('ec.colEmail')}</TableHead>
              <TableHead className={cn(TH, 'hidden sm:table-cell')}>{t('ec.colTeam')}</TableHead>
              <TableHead className={TH}>{t('ec.colRole')}</TableHead>
              <TableHead className={TH}>{t('ec.colLevel')}</TableHead>
              <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('ec.colWebhook')}</TableHead>
              <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('ec.colLastDelivery')}</TableHead>
              <TableHead className={TH}>{t('ec.colActive')}</TableHead>
              <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('ec.colActions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredContacts.length === 0 && (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={9} className="py-5 text-center text-muted-foreground">{t('ec.noResults')}</TableCell>
              </TableRow>
            )}
            {pagedContacts.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="max-w-[16rem] whitespace-normal">
                  <UserBadge displayName={c.name} email={c.email} inline size="sm" />
                  {/* Takım sütunu telefonda gizli — takımsız kişinin uyarısı orada da görünsün */}
                  {c.team_id == null && <NoTeamBadge label={t('ec.noTeamBadge')} className="mt-1 sm:hidden" />}
                </TableCell>
                <TableCell className="hidden break-all whitespace-normal lg:table-cell">{c.email}</TableCell>
                <TableCell className="hidden whitespace-normal sm:table-cell">
                  {c.team_id == null ? <NoTeamBadge label={t('ec.noTeamBadge')} /> : (teamMap[c.team_id] || '—')}
                </TableCell>
                <TableCell><OrgRoleBadge role={c.role}>{roleLabelMap[c.role] || c.role}</OrgRoleBadge></TableCell>
                <TableCell>
                  {/* Seviye + (varsa) eskalasyon gecikmesi — telefonda da görünen sütun, rozetler alt satıra kayar */}
                  <div className="flex flex-wrap items-center gap-1">
                    <Badge data-level={c.min_alert_level} className={cn('font-bold', LEVEL_CLS[c.min_alert_level] || 'bg-muted-foreground text-white')}>
                      {levelLabelMap[c.min_alert_level] || c.min_alert_level}
                    </Badge>
                    {contactDelay(c) != null && (
                      <Badge variant="outline" data-slot="ec-delay" title={t('ec.delayBadgeTitle', contactDelay(c))}
                        className="gap-1 font-medium whitespace-nowrap text-muted-foreground">
                        <Timer aria-hidden="true" />{t('ec.delayBadge', contactDelay(c))}
                      </Badge>
                    )}
                  </div>
                </TableCell>
                <TableCell className="hidden md:table-cell">{c.webhook_url ? <ToneBadge tone="success">{c.webhook_type}</ToneBadge> : '—'}</TableCell>
                <TableCell className="hidden md:table-cell">{(() => {
                  if (!c.webhook_url) return '—'
                  const d = deliveries[String(c.email || '').trim().toLowerCase()]
                  if (!d) return <span className={hint}>{t('ec.lastDeliveryNone')}</span>
                  return (
                    <span data-slot="ec-delivery" className="inline-flex flex-col items-start gap-0.5" title={d.detail || ''}>
                      <ToneBadge tone={d.status === 'SENT' ? 'success' : 'danger'}>{d.status}{d.trigger === 'WEBHOOK_TEST' ? ` · ${t('ec.lastDeliveryTest')}` : ''}</ToneBadge>
                      <span className="font-mono text-[0.85em] text-muted-foreground">{formatDateSec(d.at)}</span>
                    </span>
                  )
                })()}</TableCell>
                <TableCell><ToneBadge tone={c.active ? 'success' : 'danger'}>{c.active ? t('ec.active') : t('ec.inactive')}</ToneBadge></TableCell>
                <TableCell className="text-right">
                  <KebabMenu label={t('ec.colActions')} rowLabel={c.name || c.email} items={[
                    ...(canManage ? [{ label: t('ec.edit'), onClick: () => openEdit(c) }] : []),
                    { label: t('hist.title'), onClick: () => setHistFilter({ id: c.id, name: c.name || c.email }) },
                    ...(canManage && c.webhook_url ? [{ label: t('ec.testWebhook'), onClick: () => { if (testingId !== c.id) testWebhook(c) } }] : []),
                    ...(canManage ? [{ label: t('ec.delete'), danger: true, onClick: () => del(c.id) }] : []),
                  ]} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <PaginationBar {...pager} />

      <AdminChangeHistory resource="ESCALATION_CONTACT" filter={histFilter} onClearFilter={() => setHistFilter(null)} />

      {/* Ekle / düzenle — ui/ModalShell (shadcn Dialog): odak tuzağı, Escape, telefonda sığan kutu + sabit altlık */}
      <ModalShell open={modal !== null} onClose={() => setModal(null)} busy={saving}
        title={modal === 'add' ? t('ec.addTitle') : t('ec.editTitle')} icon={Users} scrollBody
        footer={<>
          <Button variant="secondary" onClick={() => setModal(null)} disabled={saving}>{t('ec.cancel')}</Button>
          <Button onClick={save} disabled={saving || !canSave}>
            {saving ? t('ec.saving') : t('ec.save')}
          </Button>
        </>}>
        {msg && <AlertBanner tone="danger" role="alert">{msg}</AlertBanner>}
        <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <Field label={t('contact.user')} required className="sm:col-span-2"
            hint={selectedUser ? `${selectedUser.display_name || selectedUser.username} — ${selectedUser.email}` : (users.length === 0 ? t('team.noUsersHint') : undefined)}
            hintTone={!selectedUser && users.length === 0 ? 'warn' : undefined}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.user_id}
                onChange={v => setForm({ ...form, user_id: v })}
                placeholder={t('contact.selectUser')}
                searchThreshold={2}
                options={[
                  { value: '', label: t('contact.selectUser') },
                  ...users.map(u => ({
                    value: String(u.id),
                    label: `${u.display_name || u.username} (${u.email || u.username})`,
                  })),
                ]}
              />
            )}
          </Field>
          {teamPickable && (
            <Field label={t('ec.formTeam')} required>
              {({ id }) => (
                <SearchableSelect id={id}
                  value={form.team_id}
                  onChange={v => setForm({ ...form, team_id: v })}
                  placeholder={t('ec.pickTeam')}
                  searchThreshold={2}
                  options={teams.map(team => ({ value: team.id, label: team.name }))}
                />
              )}
            </Field>
          )}
          <Field label={t('ec.formRole')}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.role}
                onChange={v => setForm({ ...form, role: v })}
                options={ROLES.map(r => ({ value: r, label: roleLabelMap[r] }))}
              />
            )}
          </Field>
          <Field label={t('ec.formLevel')}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.min_alert_level}
                onChange={v => setForm({ ...form, min_alert_level: v })}
                options={[
                  { value: 'WARNING',  label: t('ec.levelWarn') },
                  { value: 'HIGH',     label: t('ec.levelHigh') },
                  { value: 'CRITICAL', label: t('ec.levelCrit') },
                ]}
              />
            )}
          </Field>
          {/* Zamana bağlı eskalasyon adımı (2026-10-01, opt-in): boş = bugünkü anlık davranış */}
          <Field label={t('ec.formDelay')} hint={t('ec.formDelayHint')} className="sm:col-span-2"
            {...fe.fieldProps('delay_minutes')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="number" inputMode="numeric" min={DELAY_MIN} max={DELAY_MAX} step={1}
                className="h-10 sm:max-w-48 md:h-9" aria-describedby={describedBy} aria-invalid={invalid}
                value={form.delay_minutes} placeholder={t('ec.formDelayPlaceholder')}
                onChange={(e) => { setForm({ ...form, delay_minutes: e.target.value }); fe.clear('delay_minutes') }} />
            )}
          </Field>
          <Field label={t('ec.formWebhook')}>
            {({ id }) => (
              <Input id={id} type="url" value={form.webhook_url} placeholder="https://..."
                onChange={(e) => setForm({ ...form, webhook_url: e.target.value })} />
            )}
          </Field>
          <Field label={t('ec.formWebhookType')}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.webhook_type}
                onChange={v => setForm({ ...form, webhook_type: v })}
                options={[
                  { value: 'TEAMS', label: 'Microsoft Teams' },
                  { value: 'SLACK', label: 'Slack' },
                ]}
              />
            )}
          </Field>
          <ActiveCheckbox checked={form.active} label={t('ec.formActive')}
            onChange={v => setForm({ ...form, active: v })} />
        </div>
      </ModalShell>
    </section>
  )
}

/**
 * Takıma atanmamış (team_id NULL, eski veri) kişi — 2026-09-28 kuralı: eskalasyon kişileri yalnız KENDİ takımlarının
 * alarmlarını alır, takımsız kişi hiçbir bildirim almaz. Düzenle → takım seçilince yeniden devreye girer.
 */
function NoTeamBadge({ label, className = '' }) {
  return <ToneBadge tone="warning" data-slot="ec-no-team" className={cn('whitespace-normal', className)}>{label}</ToneBadge>
}

/** "Aktif" — shadcn Checkbox + bağlı etiket (form gönderimiyle gider). */
function ActiveCheckbox({ checked, onChange, label }) {
  const id = useId()
  return (
    <div className="flex min-h-9 items-center gap-2 sm:col-span-2">
      <Checkbox id={id} checked={!!checked} onCheckedChange={v => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </div>
  )
}
