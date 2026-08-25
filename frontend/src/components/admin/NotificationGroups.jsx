import { useState, useEffect, useMemo } from 'react'
import { Users, Star, Mail, Plus, Trash2, Pencil, History } from 'lucide-react'
import { api } from '../../api/client'
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
import NotificationGroupHistory from './NotificationGroupHistory.jsx'

/** Grup başına adres tavanı — backend {@code NotificationGroupService.MAX_EMAILS_PER_GROUP} ile AYNI. */
const MAX_EMAILS = 15

const emptyForm = { team_id: '', name: '', emails: '', is_default: false }

/**
 * Takım Bildirim Grupları — alarm e-postalarının gideceği adlandırılmış alıcı listeleri.
 *
 * <p><b>Sınıf dağarcığı:</b> ekran yeni sınıf UYDURMAZ; kardeş yönetim ekranlarının (özellikle
 * {@code EscalationContacts}) kullandığı sınıfları yeniden kullanır — {@code admin-section},
 * {@code admin-table-wrap}/{@code admin-table}, {@code audit-filters}, {@code badge badge-ok},
 * {@code checkbox-label}, {@code field-hint}. İlk sürümde uydurulmuş adlar ({@code ng-panel},
 * {@code data-table}, {@code text-danger} …) hiçbir CSS dosyasında tanımlı değildi: tarayıcı
 * bilinmeyen sınıfı sessizce yok sayar, ekran biçimsiz çizilir ve HİÇBİR yerde hata görünmez.
 * Kapı: {@code cssClasses.test.js}.
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
  const [fTeam, setFTeam] = useState('')
  // { group, usage } — silme 409 dondugunde ya da kullanici kullanimi merak ettiginde
  const [usageModal, setUsageModal] = useState(null)
  const [moveTarget, setMoveTarget] = useState('')
  const [moving, setMoving] = useState(false)
  // Degisiklik gecmisi — KAPALI baslar: her acilista denetim sorgusu atmak, ekrani asil isi
  // (gruplari yonetmek) icin acan kullaniciya bedava yuk bindirirdi.
  const [histOpen, setHistOpen] = useState(false)
  const [histGroup, setHistGroup] = useState(null)   // { id, name } | null → tek gruba suz
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
    api.notificationGroups.history(histGroup?.id ?? null)
      .then(res => {
        if (cancelled) return
        if (res?.success) setHist(res.data)
        else setHistError(res?.error ?? t('ng.histError'))
      })
      .catch(e => { if (!cancelled) setHistError(e?.message ?? String(e)) })
      .finally(() => { if (!cancelled) setHistLoading(false) })
    return () => { cancelled = true }
  }, [histOpen, histGroup, groups])   // eslint-disable-line react-hooks/exhaustive-deps

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

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <div>
          <h3>{t('ng.title')}</h3>
          <p className="section-desc">{t('ng.howBody')}</p>
        </div>
        {writableTeamIds.length > 0 && (
          <button className="btn btn-success" onClick={openAdd}>
            <Plus size={15} aria-hidden="true" /> {t('ng.add')}
          </button>
        )}
      </div>

      {teams.length > 1 && (
        <div className="audit-filters">
          <SearchableSelect
            value={fTeam}
            onChange={setFTeam}
            options={[{ value: '', label: t('ng.allTeams') },
                      ...teams.map(x => ({ value: String(x.id), label: x.name }))]}
            placeholder={t('ng.allTeams')}
            searchThreshold={2}
          />
        </div>
      )}

      {!loading && teamsAtRisk.length > 0 && (
        <AlertBanner tone="warning" title={t('ng.riskTitle')}>
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
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>{t('ng.colName')}</th>
                <th>{t('ng.colTeam')}</th>
                <th>{t('ng.colEmails')}</th>
                <th>{t('ng.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(g => {
                const count = (g.emails ?? []).length
                return (
                  <tr key={g.id}>
                    <td>
                      <strong>{g.name}</strong>
                      {g.is_default && (
                        <> <span className="badge badge-ok">
                          <Star size={11} aria-hidden="true" /> {t('ng.default')}
                        </span></>
                      )}
                      {!g.active && <> <span className="badge badge-deleted">{t('ng.deletedBadge')}</span></>}
                    </td>
                    <td>{teamMap[String(g.team_id)] ?? `#${g.team_id}`}</td>
                    <td title={(g.emails ?? []).join(', ')}>
                      {t('ng.emailCount').replace('{n}', count)}
                    </td>
                    <td>
                      <KebabMenu
                        label={t('ng.actions')}
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
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="admin-section-header ng-hist-header">
        <div>
          <h3>{t('ng.histTitle')}</h3>
          <p className="section-desc">{t('ng.histDesc')}</p>
        </div>
        <button
          className="btn btn-secondary"
          onClick={() => { if (histOpen) { setHistOpen(false); setHistGroup(null) } else setHistOpen(true) }}
        >
          <History size={15} aria-hidden="true" /> {histOpen ? t('ng.histHide') : t('ng.histShow')}
        </button>
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
            <button className="btn btn-secondary" onClick={() => setUsageModal(null)} disabled={moving}>
              {t('ng.cancel')}
            </button>
            <button className="btn btn-primary" onClick={doMove} disabled={moving}>
              {moving ? t('ng.saving') : t('ng.moveBtn')}
            </button>
          </>
        }
      >
        {usageModal && (
          <>
            <AlertBanner tone="warning">
              {t('ng.inUseBody').replace('{n}', usageModal.usage.total)}
            </AlertBanner>

            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr><th>{t('ng.colName')}</th><th>{t('ng.colTeam')}</th></tr>
                </thead>
                <tbody>
                  {(usageModal.usage.items ?? []).map(it => (
                    <tr key={`${it.type}:${it.id}`}>
                      <td>{it.name}</td>
                      <td>{t(`ng.type.${it.type}`)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {usageModal.usage.truncated && (
              <span className="field-hint">
                {t('ng.inUseMore').replace('{n}',
                  usageModal.usage.total - (usageModal.usage.items ?? []).length)}
              </span>
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
              <span className="field-hint">{t('ng.moveNoTarget')}</span>
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
            <button className="btn btn-secondary" onClick={() => setModal(null)} disabled={saving}>
              {t('ng.cancel')}
            </button>
            <button className="btn btn-primary" onClick={save} disabled={saving || overLimit}>
              {saving ? t('ng.saving') : t('ng.save')}
            </button>
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
            <input
              id={id} aria-describedby={describedBy} className="input" maxLength={100}
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

        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={form.is_default}
            onChange={e => setForm(f => ({ ...f, is_default: e.target.checked }))}
          />
          <span>{t('ng.makeDefaultField')}</span>
        </label>
        <span className="field-hint">{t('ng.makeDefaultHint')}</span>
      </ModalShell>
    </div>
  )
}
