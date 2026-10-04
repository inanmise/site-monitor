import { useEffect, useMemo, useRef, useState } from 'react'
import { Ban, ChevronDown, ChevronUp, Eye, Headset, MessageSquareText, PhoneCall, RefreshCw, ShieldCheck, Users, X } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { unwrap } from '../noc/nocModel.js'
import { helpLabel, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import {
  diffIds, isDirty, normalizeIds, normalizePreview, normalizeSettings, savePayload, teamOptions,
} from './noc/operatorTeamsModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Önizlemede ilk açılışta gösterilen kişi sayısı (fazlası "Tümünü göster"). */
const PREVIEW_INITIAL = 12

/**
 * Ayarlar → 7/24 İzleme Ekibi → **7/24 izleme ekibi takımları** (2026-10-04, kullanıcı isteği).
 *
 * <p>Global yönetici bir ya da birkaç TAKIMI 7/24 izleme ekibi olarak işaretler. Bu takımların AKTİF üyeleri rolleri
 * değişmeden 7/24 operatörü olur: tüm takımların izlemelerini, alarmlarını, olaylarını, bakım pencerelerini salt okur;
 * her alarma arama kaydı ve not düşer; 7/24 konsolunu kullanır. Ayarlar, denetim kaydı, sistem yönetimi açılmaz;
 * başka takımın izlemesinde düzenleme / şimdi kontrol / sahiplen / çöz yoktur.
 *
 * <p>Seçici: aranabilir çoklu seçim (`ui/MultiTeamSelect`, Popover + Command) + seçili takım çipleri (üye sayısıyla, ×
 * ile çıkarılır). Seçim değişince sunucudan ÖNİZLEME (300 ms sonra; sıra numaralı — bayat yanıt yenisini ezmez):
 * etkilenecek aktif kişiler (ad + 7/24 takımları). Kayıt yeni takım EKLİYORSA önce onay ister (erişim genişler).
 * `readOnly` (kapsamlı müdür / denetçi): her şey görünür ama kilitli; kayıt çubuğu yok.
 */
export default function NocOperatorTeams({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()

  const [settings, setSettings] = useState(null)   // normalizeSettings
  const [draft, setDraft] = useState([])
  const [directory, setDirectory] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState({ loading: false, error: null, data: null })
  const [showAll, setShowAll] = useState(false)
  const alive = useRef(true)
  const loadSeq = useRef(0)
  const previewSeq = useRef(0)

  async function load() {
    const id = ++loadSeq.current
    setLoadError(null)
    try {
      const [cfg, dir] = await Promise.all([
        api.admin.noc.getOperatorTeams(),
        Promise.resolve(api.teams?.directory ? api.teams.directory() : null).catch(() => null),
      ])
      if (!alive.current || id !== loadSeq.current) return
      const r = unwrap(cfg)
      if (!r.ok) { setLoadError(r.error || t('noc.ot.loadError')); return }
      const s = normalizeSettings(r.data)
      setSettings(s)
      setDraft(s.ids)
      const d = unwrap(dir)
      setDirectory(d.ok && Array.isArray(d.data) ? d.data : [])
    } catch (e) {
      if (alive.current && id === loadSeq.current) setLoadError(e?.message || t('noc.ot.loadError'))
    }
  }
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Önizleme: seçim değişince 300 ms sonra (hızlı tıklamalar tek istek) — boş seçimde istek yok.
  const draftKey = draft.join(',')
  useEffect(() => {
    if (!settings) return undefined
    const ids = normalizeIds(draft)
    const id = ++previewSeq.current
    if (ids.length === 0) { setPreview({ loading: false, error: null, data: normalizePreview(null) }); return undefined }
    setPreview((p) => ({ ...p, loading: true, error: null }))
    const timer = setTimeout(async () => {
      try {
        const r = unwrap(await api.admin.noc.previewOperatorTeams(ids))
        if (!alive.current || id !== previewSeq.current) return
        if (r.ok) setPreview({ loading: false, error: null, data: normalizePreview(r.data) })
        else setPreview({ loading: false, error: r.error || t('noc.ot.previewError'), data: null })
      } catch (e) {
        if (alive.current && id === previewSeq.current) setPreview({ loading: false, error: e?.message || t('noc.ot.previewError'), data: null })
      }
    }, 300)
    return () => clearTimeout(timer)
  }, [draftKey, settings]) // eslint-disable-line react-hooks/exhaustive-deps

  const options = useMemo(() => teamOptions(directory, draft, t('noc.ot.inactiveTeam')), [directory, draft, t])
  const nameOf = (id) => options.find((o) => o.value === id)?.name ?? `#${id}`
  const dirty = !!settings && isDirty(settings.ids, draft)
  const max = settings?.maxTeams ?? 50

  async function save() {
    if (!settings || saving) return
    const { added } = diffIds(settings.ids, draft)
    if (added.length) {
      const ok = await showConfirm({
        title: t('noc.ot.confirmTitle'), variant: 'warning', confirmText: t('noc.ot.confirmBtn'),
        message: t('noc.ot.confirmMsg', added.map(nameOf).join(', ')),
      })
      if (!ok) return
    }
    setSaving(true)
    try {
      const r = unwrap(await api.admin.noc.saveOperatorTeams(savePayload(draft)))
      if (!alive.current) return
      if (!r.ok) { toast.error(r.error || t('noc.ot.saveError')); return }
      const s = normalizeSettings(r.data)
      setSettings(s)
      setDraft(s.ids)
      toast.success(t('noc.ot.saved'))
    } catch (e) {
      toast.error(e?.message || t('noc.ot.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  const pv = preview.data
  const users = pv?.users ?? []
  const shownUsers = showAll ? users : users.slice(0, PREVIEW_INITIAL)
  const whatItems = [
    { Icon: Eye, key: 'noc.ot.what.read' },
    { Icon: PhoneCall, key: 'noc.ot.what.calls' },
    { Icon: MessageSquareText, key: 'noc.ot.what.notes' },
    { Icon: Headset, key: 'noc.ot.what.console' },
  ]

  return (
    <SettingsSection title={helpLabel(t('noc.ot.title'), 'help.noc.operatorTeams')} description={t('noc.ot.desc')}
      contentClassName="flex flex-col gap-4">
      <div data-slot="noc-operator-teams" className="flex min-w-0 flex-col gap-4">
        {loadError && (
          <AlertBanner tone="danger" role="alert" title={t('noc.ot.loadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={load}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
            {String(loadError)}
          </AlertBanner>
        )}
        {!settings && !loadError && <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />}

        {settings && (
          <>
            <AlertBanner tone="info" icon={ShieldCheck} title={t('noc.ot.whatTitle')} className="mb-0">
              <ul data-slot="noc-ot-what" className="m-0 flex list-none flex-col gap-1.5 p-0">
                {whatItems.map(({ Icon, key }) => (
                  <li key={key} className="flex min-w-0 items-start gap-2">
                    <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" /><span className="min-w-0">{t(key)}</span>
                  </li>
                ))}
                <li className="flex min-w-0 items-start gap-2 text-muted-foreground">
                  <Ban aria-hidden="true" className="mt-0.5 size-4 shrink-0" /><span className="min-w-0">{t('noc.ot.what.not')}</span>
                </li>
              </ul>
            </AlertBanner>

            {/* Seçici tetiği telefonda 40 px dokunma hedefi (PickerTrigger varsayılanı 36 px) */}
            <div className="flex max-w-3xl min-w-0 flex-col gap-2 [&_[role=combobox]]:h-10 sm:[&_[role=combobox]]:h-9">
              <Field label={t('noc.ot.pickLabel')} hint={readOnly ? t('noc.ot.roHint') : t('noc.ot.pickHint', max)}>
                {({ id }) => (
                  <MultiTeamSelect id={id} value={draft} options={options} disabled={readOnly || saving} searchThreshold={1}
                    placeholder={t('noc.ot.pickPh')} onChange={(ids) => setDraft(normalizeIds(ids).slice(0, max))} />
                )}
              </Field>

              {draft.length === 0 ? (
                <p data-slot="noc-ot-none" className="m-0 text-sm text-muted-foreground">{t('noc.ot.noneSelected')}</p>
              ) : (
                <ul data-slot="noc-ot-chips" aria-label={t('noc.ot.selectedLabel')} className="m-0 flex list-none flex-wrap gap-1.5 p-0">
                  {draft.map((id) => {
                    const name = nameOf(id)
                    const info = pv?.teams.get(id)
                    return (
                      <li key={id} className="min-w-0">
                        <Badge variant="outline" data-slot="noc-ot-chip" data-team-id={id}
                          className={cn('h-10 max-w-full gap-1.5 rounded-full bg-primary/5 pr-1 pl-2.5 font-medium sm:h-8 sm:pointer-coarse:h-10',
                            info && !info.active && 'border-dashed text-muted-foreground')}>
                          <Users aria-hidden="true" className="size-3.5 text-primary" />
                          <span className="max-w-[14rem] truncate" title={name}>{name}</span>
                          {info && (
                            <span data-slot="noc-ot-chip-count" className="text-xs text-muted-foreground tabular-nums">
                              {info.active ? t('noc.ot.members', info.memberCount) : t('noc.ot.inactiveTeam')}
                            </span>
                          )}
                          {!readOnly && (
                            <Button type="button" variant="ghost" size="icon-xs" disabled={saving}
                              className="size-10 rounded-full sm:size-6 sm:pointer-coarse:size-10" aria-label={t('noc.ot.removeTeam', name)}
                              onClick={() => setDraft((d) => d.filter((x) => x !== id))}>
                              <X aria-hidden="true" className="size-3.5" />
                            </Button>
                          )}
                        </Badge>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>

            {draft.length > 0 && (
              <section data-slot="noc-ot-preview" aria-labelledby="noc-ot-preview-title"
                className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <h5 id="noc-ot-preview-title" className="m-0 text-sm font-semibold">{t('noc.ot.previewTitle')}</h5>
                  {preview.loading && <Spinner size={14} inline decorative />}
                  {pv && !preview.loading && (
                    <Badge variant="secondary" data-slot="noc-ot-user-count" className="tabular-nums">
                      {t('noc.ot.previewCount', pv.userCount)}
                    </Badge>
                  )}
                </div>
                {preview.error && <p role="alert" className="m-0 text-sm text-destructive">{String(preview.error)}</p>}
                {pv && pv.userCount === 0 && !preview.loading && (
                  <p data-slot="noc-ot-empty" className="m-0 text-sm text-muted-foreground">{t('noc.ot.previewNone')}</p>
                )}
                {shownUsers.length > 0 && (
                  <ul data-slot="noc-ot-users" className="m-0 grid list-none grid-cols-1 gap-1.5 p-0 sm:grid-cols-2 xl:grid-cols-3">
                    {shownUsers.map((u) => (
                      <li key={u.user_id} data-slot="noc-ot-user" className="flex min-w-0 flex-col rounded-md border bg-card px-2.5 py-1.5">
                        <span className="truncate text-sm font-medium" title={u.display_name}>{u.display_name}</span>
                        <span className="truncate text-xs text-muted-foreground" title={(u.team_names || []).join(', ')}>
                          {(u.team_names || []).join(', ')}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
                {users.length > PREVIEW_INITIAL && (
                  <Button type="button" variant="ghost" size="sm" className="h-10 self-start sm:h-8 sm:pointer-coarse:h-10"
                    aria-expanded={showAll} onClick={() => setShowAll((v) => !v)}>
                    {showAll ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
                    {showAll ? t('noc.ot.showLess') : t('noc.ot.showAll', users.length)}
                  </Button>
                )}
                {pv?.truncated && <p className="m-0 text-xs text-muted-foreground">{t('noc.ot.truncated', users.length)}</p>}
                <p className="m-0 text-xs text-muted-foreground">{t('noc.ot.passiveNote')}</p>
              </section>
            )}

            <p data-slot="noc-ot-meta" className="m-0 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span>{t('noc.ot.current', settings.operatorCount)}</span>
              {settings.updatedAt && (
                <span>{t('noc.ot.updated', formatDate(settings.updatedAt), settings.updatedByName || '—')}</span>
              )}
            </p>

            {!readOnly && (
              <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setDraft(settings.ids)}
                saveLabel={t('noc.ot.save')} />
            )}
          </>
        )}
      </div>
    </SettingsSection>
  )
}
