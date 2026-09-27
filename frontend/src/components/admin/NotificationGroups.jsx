import { useState, useEffect, useMemo, useId } from 'react'
import { Users, Star, Mail, Plus, Trash2, Pencil, History } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import TagInput from '../ui/TagInput.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import NotificationGroupHistory from './NotificationGroupHistory.jsx'
import ToneBadge from './ToneBadge.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

/** Grup başına adres tavanı — backend {@code NotificationGroupService.MAX_EMAILS_PER_GROUP} ile AYNI. */
const MAX_EMAILS = 15

const emptyForm = { team_id: '', name: '', emails: '', is_default: false }

/**
 * Takım Bildirim Grupları — alarm e-postalarının gideceği adlandırılmış alıcı listeleri.
 *
 * <p><b>Sınıf dağarcığı:</b> ekran yeni sınıf UYDURMAZ. İlk sürümde uydurulmuş adlar ({@code ng-panel},
 * {@code data-table}, {@code text-danger} …) hiçbir CSS dosyasında tanımlı değildi: tarayıcı bilinmeyen
 * sınıfı sessizce yok sayar, ekran biçimsiz çizilir ve HİÇBİR yerde hata görünmez (kapı
 * {@code cssClasses.test.js}). 2026-09-26 (D2): legacy sınıflar da kalktı — shadcn Table / Badge (ToneBadge) /
 * Input / Checkbox + Tailwind; adres çipi ui/HintPopover (dokunmatikte de açılır; eskiden yalnız hover).
 * Test kancaları: `data-slot="ng-header-actions|ng-team-filter|ng-mailchip|ng-audit-line"`, denetim hücresi `data-col="audit"`.
 *
 * <p><b>Dürüst boş durum:</b> grup yoksa ekran "hiçbir şey yok" demez; alarmların ŞU AN nereye
 * gittiğini (takımın kendi adresi) açıkça yazar. Kullanıcı grubun bir EK katman olduğunu, bir
 * şeyin bozuk olmadığını görsün.
 */
export default function NotificationGroups({ teams = [], systemRole }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()

  const [groups, setGroups] = useState([])
  const [writableTeamIds, setWritableTeamIds] = useState([])
  const [teamEmails, setTeamEmails] = useState({})
  const [loading, setLoading] = useState(true)
  const [modal, setModal] = useState(null)          // null | 'add' | 'edit'
  const [form, setForm] = useState(emptyForm)
  const [editing, setEditing] = useState(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [fTeam, setFTeam] = useState(() => readUrlParam('g_team', ''))   // URL'de (g_team)
  useUrlQuerySync({ g_team: fTeam })
  // { group, usage } — silme 409 dondugunde ya da kullanici kullanimi merak ettiginde
  const [usageModal, setUsageModal] = useState(null)
  const [moveTarget, setMoveTarget] = useState('')
  const [moving, setMoving] = useState(false)
  // Degisiklik gecmisi — KAPALI baslar: her acilista denetim sorgusu atmak, ekrani asil isi
  // (gruplari yonetmek) icin acan kullaniciya bedava yuk bindirirdi.
  const [histOpen, setHistOpen] = useState(false)
  const [histGroup, setHistGroup] = useState(null)   // { id, name } | null → tek gruba suz
  // Sayfalı geçmiş (2026-09-20) → standart sunucu kancası (2026-09-26): grup süzgeci değişince başa döner
  // (değer karşılaştırmalı), panel ön ayarı, API 0-tabanlı.
  const histPager = useServerPagination({ listKey: 'notify-group-history', preset: 'panel', resetDeps: [histGroup?.id ?? null], apiBase: 0 })
  const { apiPage: histPage, pageSize: histSize } = histPager
  const [hist, setHist] = useState(null)
  const [histLoading, setHistLoading] = useState(false)
  const [histError, setHistError] = useState(null)

  const teamMap = useMemo(() => Object.fromEntries(teams.map(x => [String(x.id), x.name])), [teams])

  useEffect(() => { load() }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  async function load() {
    setLoading(true)
    try {
      const res = await api.notificationGroups.list()
      if (res?.success) {
        setGroups(res.data?.groups ?? [])
        setWritableTeamIds((res.data?.writable_team_ids ?? []).map(String))
        setTeamEmails(res.data?.team_emails ?? {})
      }
    } finally {
      setLoading(false)
    }
  }

  /**
   * Gecmis, gruplar HER degistiginde yeniden okunur: kullanici bir grubu duzenleyip hemen
   * altta "kim ne yapti" listesine bakiyorsa, kendi az onceki degisikligini gormemek listeyi
   * bayat sanmasina yol acardi.
   */
  useEffect(() => {
    if (!histOpen) return
    let cancelled = false
    setHistLoading(true)
    setHistError(null)
    api.notificationGroups.history(histGroup?.id ?? null, { page: histPage, size: histSize })
      .then(res => {
        if (cancelled) return
        if (res?.success) { setHist(res.data); histPager.bind(res) }
        else setHistError(res?.error ?? t('ng.histError'))
      })
      .catch(e => { if (!cancelled) setHistError(e?.message ?? String(e)) })
      .finally(() => { if (!cancelled) setHistLoading(false) })
    return () => { cancelled = true }
  }, [histOpen, histGroup, groups, histPage, histSize])   // eslint-disable-line react-hooks/exhaustive-deps

  function openHistory(g) {
    setHistGroup(g ? { id: g.id, name: g.name } : null)
    setHistOpen(true)
  }

  const visible = useMemo(
    () => (fTeam ? groups.filter(g => String(g.team_id) === String(fTeam)) : groups),
    [groups, fTeam],
  )

  /** Grubu olmayan takımlar — dürüst boş durum satırı bunlar için çizilir. */
  const teamsWithoutGroup = useMemo(() => {
    const withGroup = new Set(groups.map(g => String(g.team_id)))
    return writableTeamIds.filter(id => !withGroup.has(id))
  }, [groups, writableTeamIds])

  /**
   * Alarmları ALICISIZ kalabilecek takımlar: aktif varsayılan grubu da, takım e-posta adresi de
   * olmayanlar. Bu bir GRUP değil TAKIM özelliğidir — eskiden satır başına "Sorun" sütununda
   * gösteriliyordu ve iki yönden yanlıştı: aynı takımın her satırında tekrarlıyor, üstelik uyarıyı
   * sorunu YAŞAMAYAN nesnenin (adresleri olan, çalışan grubun) üstüne yazıyordu. Asıl mağdur
   * hiç grup seçmemiş izlemelerdir ve onlar bu tabloda görünmez.
   *
   * Takım süzgeci uygulanmışsa uyarı da o takımla sınırlanır — ekranda görünmeyen bir takım için
   * uyarı vermek gürültüdür.
   */
  const teamsAtRisk = useMemo(() => {
    const scope = new Set([...groups.map(g => String(g.team_id)), ...writableTeamIds])
    const hasDefault = new Set(
      groups.filter(g => g.active && g.is_default).map(g => String(g.team_id)))
    return [...scope]
      .filter(id => !fTeam || String(fTeam) === id)
      .filter(id => !hasDefault.has(id))
      .filter(id => !String(teamEmails[id] ?? '').trim())
      .map(id => teamMap[id] ?? `#${id}`)
  }, [groups, writableTeamIds, teamEmails, teamMap, fTeam])

  const emailList = (form.emails || '').split(',').map(s => s.trim()).filter(Boolean)
  const overLimit = emailList.length > MAX_EMAILS

  function openAdd() {
    setError(null)
    setEditing(null)
    setForm({ ...emptyForm, team_id: writableTeamIds[0] ?? '' })
    setModal('add')
  }

  function openEdit(g) {
    setError(null)
    setEditing(g)
    setForm({
      team_id: String(g.team_id),
      name: g.name ?? '',
      emails: (g.emails ?? []).join(', '),
      is_default: !!g.is_default,
    })
    setModal('edit')
  }

  async function save() {
    setError(null)
    if (!form.name.trim()) { setError(t('ng.errNameRequired')); return }
    if (emailList.length === 0) { setError(t('ng.errEmailsRequired')); return }
    if (overLimit) { setError(t('ng.errTooMany').replace('{max}', MAX_EMAILS)); return }

    setSaving(true)
    try {
      const payload = {
        team_id: form.team_id ? Number(form.team_id) : null,
        name: form.name.trim(),
        emails: emailList,
        is_default: form.is_default,
      }
      const res = editing
        ? await api.notificationGroups.update(editing.id, payload)
        : await api.notificationGroups.create(payload)
      if (res?.success) {
        toast.success(editing ? t('ng.updated') : t('ng.created'))
        setModal(null)
        load()
      } else {
        setError(res?.error || t('ng.errSave'))
      }
    } catch (e) {
      setError(e?.message || t('ng.errSave'))
    } finally {
      setSaving(false)
    }
  }

  async function makeDefault(g) {
    const res = await api.notificationGroups.makeDefault(g.id)
    if (res?.success) { toast.success(t('ng.defaultSet').replace('{name}', g.name)); load() }
    else toast.error(res?.error || t('ng.errSave'))
  }

  async function remove(g) {
    const ok = await showConfirm({
      title: t('ng.deleteTitle'),
      // Silme KALICI ve yalnız kullanılmayan grupta mümkün; onay metni ikisini de söyler.
      message: t('ng.deleteMsg').replace('{name}', g.name),
      confirmText: t('ng.delete'),
    })
    if (!ok) return
    const res = await api.notificationGroups.remove(g.id)
    if (res?.success) { toast.success(t('ng.deleted')); load(); return }
    // 409: grup kullanimda. Kullaniciyi hata mesajiyla bas basa birakmak yerine NEREDE
    // kullanildigini gosterip tasima adimini onune koyuyoruz.
    if (res?.usage) {
      setMoveTarget('')
      setUsageModal({ group: g, usage: res.usage })
      return
    }
    toast.error(res?.error || t('ng.errSave'))
  }

  async function doMove() {
    if (!usageModal) return
    setMoving(true)
    try {
      const target = moveTarget === '' ? null : Number(moveTarget)
      const res = await api.notificationGroups.reassign(usageModal.group.id, target)
      if (res?.success) {
        toast.success(t('ng.moveDone').replace('{n}', res.data?.moved ?? 0))
        setUsageModal(null)
        load()
      } else {
        toast.error(res?.error || t('ng.errSave'))
      }
    } finally {
      setMoving(false)
    }
  }

  /** Ayni takimin AKTIF ve kaynaktan FARKLI gruplari — tasima hedefi olabilecekler. */
  const moveTargets = usageModal
    ? groups.filter(x => x.active
        && String(x.team_id) === String(usageModal.group.team_id)
        && x.id !== usageModal.group.id)
    : []

  const teamOptions = writableTeamIds.map(id => ({ value: id, label: teamMap[id] ?? `#${id}` }))

  const hint = 'text-xs text-muted-foreground [overflow-wrap:anywhere]'
  return (
    <section className="mb-8 flex min-w-0 flex-col gap-4">
      {/* Başlık + eylemler — telefonda alt alta */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-lg leading-tight font-semibold">{t('ng.title')}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{t('ng.howBody')}</p>
        </div>
        <div data-slot="ng-header-actions" className="flex flex-wrap items-center gap-2">
          {/* 2026-09-20 (kullanıcı bildirimi): takım süzgeci "Grup Ekle" ile aynı hizada; varsayılan = tüm takımlar */}
          {teams.length > 1 && (
            <div data-slot="ng-team-filter" className="w-full min-w-0 sm:w-56">
              <SearchableSelect
                value={fTeam}
                onChange={setFTeam}
                ariaLabel={t('ng.colTeam')}
                options={[{ value: '', label: t('ng.allTeams') },
                          ...teams.map(x => ({ value: String(x.id), label: x.name }))]}
                placeholder={t('ng.allTeams')}
                searchThreshold={2}
              />
            </div>
          )}
          {writableTeamIds.length > 0 && (
            <Button variant="success" onClick={openAdd}>
              <Plus size={15} aria-hidden="true" /> {t('ng.add')}
            </Button>
          )}
        </div>
      </div>

      {!loading && teamsAtRisk.length > 0 && (
        <AlertBanner tone="warning" title={t('ng.riskTitle')} className="mb-0">
          {t('ng.riskBody').replace('{teams}', teamsAtRisk.join(', '))}
        </AlertBanner>
      )}

      {loading ? (
        <StatusBlock tone="neutral" title={t('ng.loading')} />
      ) : visible.length === 0 ? (
        <StatusBlock
          tone="neutral"
          icon={Mail}
          title={t('ng.emptyTitle')}
          description={
            teamsWithoutGroup.length > 0 && teamEmails[teamsWithoutGroup[0]]
              ? t('ng.emptyDesc').replace('{email}', teamEmails[teamsWithoutGroup[0]])
              : t('ng.emptyDescNoEmail')
          }
        />
      ) : (
        <div className="overflow-hidden rounded-lg border bg-card">
          <Table className="text-sm">
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                <TableHead>{t('ng.colName')}</TableHead>
                <TableHead>{t('ng.colTeam')}</TableHead>
                <TableHead>{t('ng.colEmails')}</TableHead>
                {/* Oluşturan / güncelleyen: telefonda düşük öncelik (tablo sığsın) */}
                <TableHead className="hidden md:table-cell">{t('ng.colAudit')}</TableHead>
                <TableHead className="w-12"><span className="sr-only">{t('ng.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map(g => {
                const count = (g.emails ?? []).length
                return (
                  <TableRow key={g.id}>
                    <TableCell className="whitespace-normal">
                      <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                        <strong className="font-semibold [overflow-wrap:anywhere]">{g.name}</strong>
                        {g.is_default && (
                          <ToneBadge tone="success"><Star aria-hidden="true" /> {t('ng.default')}</ToneBadge>
                        )}
                        {!g.active && <ToneBadge tone="muted" className="line-through">{t('ng.deletedBadge')}</ToneBadge>}
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap">{teamMap[String(g.team_id)] ?? `#${g.team_id}`}</TableCell>
                    <TableCell>
                      {/* Dokun / tıkla → tanımlı adresler listelenir (2026-09-20; D2: hover-only balon → HintPopover) */}
                      <EmailChip emails={g.emails ?? []} label={t('ng.emailCount').replace('{n}', count)} title={t('ng.colEmails')} />
                    </TableCell>
                    <TableCell data-col="audit" className="hidden align-top text-xs whitespace-normal md:table-cell">
                      <span data-slot="ng-audit-line" className="flex flex-wrap items-center gap-1" title={g.created_by || ''}>
                        <span className="font-semibold text-muted-foreground">{t('ng.createdBy')}</span>
                        {g.created_by_name || g.created_by ? <UserBadge username={g.created_by} displayName={g.created_by_name} inline size="sm" /> : <span className="text-muted-foreground">—</span>}
                        {g.created_at && <span className="text-muted-foreground"> · {formatDateSec(g.created_at)}</span>}
                      </span>
                      {(g.updated_at && g.updated_at !== g.created_at) || (g.updated_by && g.updated_by !== g.created_by) ? (
                        <span data-slot="ng-audit-line" className="mt-0.5 flex flex-wrap items-center gap-1" title={g.updated_by || ''}>
                          <span className="font-semibold text-muted-foreground">{t('ng.updatedBy')}</span>
                          {g.updated_by_name || g.updated_by ? <UserBadge username={g.updated_by} displayName={g.updated_by_name} inline size="sm" /> : <span className="text-muted-foreground">—</span>}
                          {g.updated_at && <span className="text-muted-foreground"> · {formatDateSec(g.updated_at)}</span>}
                        </span>
                      ) : null}
                    </TableCell>
                    <TableCell className="text-right">
                      <KebabMenu
                        label={t('ng.actions')}
                        rowLabel={g.name}
                        items={[
                          // Gecmis OKUMA'dir: ekrani gorebilen, bu grubun gecmisini de gorebilir.
                          { label: t('ng.histBtn'), icon: <History size={14} />, onClick: () => openHistory(g) },
                          ...(g.can_write ? [
                            { label: t('ng.edit'), icon: <Pencil size={14} />, onClick: () => openEdit(g) },
                            ...(g.active && !g.is_default
                              ? [{ label: t('ng.makeDefault'), icon: <Star size={14} />, onClick: () => makeDefault(g) }]
                              : []),
                            ...(g.active
                              ? [{ label: t('ng.delete'), icon: <Trash2 size={14} />, danger: true, onClick: () => remove(g) }]
                              : []),
                          ] : []),
                        ]}
                      />
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-lg leading-tight font-semibold">{t('ng.histTitle')}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{t('ng.histDesc')}</p>
        </div>
        <Button
          variant="secondary" className="self-start"
          onClick={() => { if (histOpen) { setHistOpen(false); setHistGroup(null) } else setHistOpen(true) }}
        >
          <History size={15} aria-hidden="true" /> {histOpen ? t('ng.histHide') : t('ng.histShow')}
        </Button>
      </div>

      {histOpen && (
        <NotificationGroupHistory
          rows={hist?.items ?? []}
          truncated={!!hist?.truncated}
          hidden={hist?.hidden ?? 0}
          loading={histLoading}
          error={histError}
          filterName={histGroup?.name}
          onClearFilter={() => setHistGroup(null)}
          pagination={histPager.bar}
        />
      )}

      <ModalShell
        open={!!usageModal}
        onClose={() => setUsageModal(null)}
        title={t('ng.inUseTitle')}
        icon={Users}
        busy={moving}
        footer={
          <>
            <Button variant="secondary" onClick={() => setUsageModal(null)} disabled={moving}>
              {t('ng.cancel')}
            </Button>
            <Button onClick={doMove} disabled={moving}>
              {moving ? t('ng.saving') : t('ng.moveBtn')}
            </Button>
          </>
        }
      >
        {usageModal && (
          <>
            <AlertBanner tone="warning">
              {t('ng.inUseBody').replace('{n}', usageModal.usage.total)}
            </AlertBanner>

            <div className="mb-2 overflow-hidden rounded-lg border">
              <Table className="text-sm">
                <TableHeader className="bg-muted/50">
                  <TableRow className="hover:bg-transparent"><TableHead>{t('ng.colName')}</TableHead><TableHead>{t('ng.colTeam')}</TableHead></TableRow>
                </TableHeader>
                <TableBody>
                  {(usageModal.usage.items ?? []).map(it => (
                    <TableRow key={`${it.type}:${it.id}`}>
                      <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{it.name}</TableCell>
                      <TableCell className="whitespace-normal">{t(`ng.type.${it.type}`)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {usageModal.usage.truncated && (
              <p className={`${hint} mb-2`}>
                {t('ng.inUseMore').replace('{n}',
                  usageModal.usage.total - (usageModal.usage.items ?? []).length)}
              </p>
            )}

            <Field label={t('ng.moveTarget')}>
              {() => (
                <SearchableSelect
                  ariaLabel={t('ng.moveTarget')}
                  value={moveTarget}
                  onChange={setMoveTarget}
                  options={[{ value: '', label: t('ng.moveToDefault') },
                            ...moveTargets.map(x => ({ value: String(x.id), label: x.name }))]}
                  placeholder={t('ng.moveToDefault')}
                />
              )}
            </Field>
            {moveTargets.length === 0 && (
              <p className={hint}>{t('ng.moveNoTarget')}</p>
            )}
          </>
        )}
      </ModalShell>

      <ModalShell
        open={!!modal}
        onClose={() => setModal(null)}
        title={editing ? t('ng.editTitle') : t('ng.addTitle')}
        icon={Users}
        busy={saving}
        footer={
          <>
            <Button variant="secondary" onClick={() => setModal(null)} disabled={saving}>
              {t('ng.cancel')}
            </Button>
            <Button onClick={save} disabled={saving || overLimit}>
              {saving ? t('ng.saving') : t('ng.save')}
            </Button>
          </>
        }
      >
        {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}

        {!editing && (
          <Field label={t('ng.fieldTeam')} required>
            {() => (
              <SearchableSelect
                ariaLabel={t('ng.fieldTeam')}
                value={String(form.team_id)}
                onChange={v => setForm(f => ({ ...f, team_id: v }))}
                options={teamOptions}
                placeholder={t('ng.pickTeam')}
                searchThreshold={2}
              />
            )}
          </Field>
        )}

        <Field label={t('ng.fieldName')} required hint={t('ng.fieldNameHint')}>
          {({ id, describedBy }) => (
            <Input
              id={id} aria-describedby={describedBy} maxLength={100}
              value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder={t('ng.namePlaceholder')}
            />
          )}
        </Field>

        <Field
          label={t('ng.fieldEmails')}
          required
          hint={t('ng.emailCounter').replace('{n}', emailList.length).replace('{max}', MAX_EMAILS)}
          // Field YALNIZ 'warn' tonunu tanır; 'danger' sessizce yok sayılıyordu.
          hintTone={overLimit ? 'warn' : undefined}
          error={overLimit ? t('ng.errTooMany').replace('{max}', MAX_EMAILS) : undefined}
        >
          {() => (
            <TagInput
              value={form.emails}
              onChange={v => setForm(f => ({ ...f, emails: v }))}
              placeholder={t('ng.emailsPlaceholder')}
            />
          )}
        </Field>

        <DefaultCheckbox checked={form.is_default} label={t('ng.makeDefaultField')}
          onChange={v => setForm(f => ({ ...f, is_default: v }))} />
        <p className={`${hint} mt-1`}>{t('ng.makeDefaultHint')}</p>
      </ModalShell>
    </section>
  )
}

/** "Takım varsayılanı yap" — shadcn Checkbox + bağlı etiket (form gönderimiyle gider). */
function DefaultCheckbox({ checked, onChange, label }) {
  const id = useId()
  return (
    <div className="flex items-center gap-2">
      <Checkbox id={id} checked={!!checked} onCheckedChange={v => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </div>
  )
}

/**
 * "N adres" çipi — dokun / tıkla / odaklan → tanımlı adresler listelenir (2026-09-20). D2 (2026-09-26):
 * eski yalnız-hover portal baloncuğu (`.ng-mailchip` / `.ng-mailpop`) telefonda HİÇ açılmıyordu; artık
 * ui/HintPopover (shadcn Popover, tetik Button; Escape/dışarı dokunuş kapatır). Test kancası `data-slot="ng-mailchip"`.
 */
function EmailChip({ emails, label, title }) {
  const chip = (
    <span data-slot="ng-mailchip" className="inline-flex items-center gap-1 rounded-full border bg-muted/50 px-2 py-0.5 text-xs font-medium whitespace-nowrap">
      <Mail aria-hidden="true" className="size-3" /> {label}
    </span>
  )
  if (!emails.length) return chip
  return (
    <HintPopover triggerClassName="rounded-full pointer-coarse:min-h-10"
      content={(
        <span className="flex flex-col gap-1">
          <span className="font-semibold">{title} · {emails.length}</span>
          <ul className="flex list-none flex-col gap-0.5 p-0">{emails.map(e => <li key={e} className="font-mono break-all">{e}</li>)}</ul>
        </span>
      )}>
      {chip}
    </HintPopover>
  )
}
