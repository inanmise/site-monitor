import { useEffect, useRef, useState } from 'react'
import { Search, UserPlus, UserCheck, ArrowLeft, Building2, IdCard, Mail, Users, AlertTriangle } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../../ui/modalClasses.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../../ui/MultiTeamSelect.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { ORG_ROLES, roleOptionsFor } from '../user-editor/userEditorModel.js'
import {
  AD_SEARCH_MIN, buildProvisionPayload, provisionErrors, matchDerivedTeams, canProvision,
} from './adUserModel.js'

/**
 * AD'DEN KULLANICI EKLE (2026-11-11, ürün isteği) — kişi GİRİŞ YAPMADAN sisteme çekilir.
 *
 * <p>Akış: kısmi arama (ad / kullanıcı adı / e-posta) → sonuç listesi (rozetler: "zaten sistemde" → Düzenle,
 * "ön ek uyarısı") → bir sonucu seç → DETAY (AD bilgileri + türetilen takım önizlemesi; yalnız görüntülemek
 * istersen burada durursun) → "Sisteme ekle" → yöneticinin rol / org rolü / takımları seçtiği öndolu form →
 * Ekle (provision). Yalnız global admin'e açılır (çağıran kapılar).
 */
const FIXED = cn(
  'grid-cols-[minmax(0,1fr)] sm:h-[min(720px,calc(100dvh-2rem))] sm:w-full',
  '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable]',
)

export default function AdUserSearch({ teams = [], onClose, onAdded, onEditExisting }) {
  const t = useT()
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState(null)      // null = henüz aranmadı
  const [searching, setSearching] = useState(false)
  const [searchErr, setSearchErr] = useState(null)
  const [selected, setSelected] = useState(null)    // seçilen kullanıcı adı
  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailErr, setDetailErr] = useState(null)
  const [adding, setAdding] = useState(false)
  const [form, setForm] = useState({ systemRole: 'USER', orgRole: '', teamIds: [], primaryTeamId: null })
  const [errors, setErrors] = useState({})
  const [provisioning, setProvisioning] = useState(false)
  const [provErr, setProvErr] = useState(null)

  const searchSeq = useRef(0)
  const detailSeq = useRef(0)

  // ── Kısmi arama (debounce 300 ms, sıra korumalı) ──
  useEffect(() => {
    const q = query.trim()
    if (q.length < AD_SEARCH_MIN) { setResults(null); setSearching(false); setSearchErr(null); return }
    const seq = ++searchSeq.current
    setSearching(true); setSearchErr(null)
    const h = setTimeout(async () => {
      try {
        const res = await api.admin.adUserSearch(q)
        if (seq !== searchSeq.current) return
        if (res?.success) setResults(res.results || [])
        else { setResults([]); setSearchErr(res?.error || t('aduser.searchFailed')) }
      } catch (e) {
        if (seq !== searchSeq.current) return
        setResults([]); setSearchErr(e?.message || t('aduser.searchFailed'))
      } finally {
        if (seq === searchSeq.current) setSearching(false)
      }
    }, 300)
    return () => clearTimeout(h)
  }, [query, t])

  async function openDetail(username) {
    setSelected(username); setAdding(false); setProvErr(null); setErrors({})
    setForm({ systemRole: 'USER', orgRole: '', teamIds: [], primaryTeamId: null })
    const seq = ++detailSeq.current
    setDetailLoading(true); setDetail(null); setDetailErr(null)
    try {
      const res = await api.admin.adUserDetail(username)
      if (seq !== detailSeq.current) return
      if (res?.success) setDetail(res.data)
      else setDetailErr(res?.error || t('aduser.detailFailed'))
    } catch (e) {
      if (seq !== detailSeq.current) return
      setDetailErr(e?.message || t('aduser.detailFailed'))
    } finally {
      if (seq === detailSeq.current) setDetailLoading(false)
    }
  }

  function backToList() { setSelected(null); setDetail(null); setDetailErr(null); setAdding(false); setProvErr(null) }

  function startAdding() {
    const suggested = matchDerivedTeams(detail?.derived_teams, teams)
    setForm({ systemRole: 'USER', orgRole: '', teamIds: suggested, primaryTeamId: suggested[0] ?? null })
    setErrors({}); setProvErr(null); setAdding(true)
  }

  async function doProvision() {
    const errs = provisionErrors(form, t)
    setErrors(errs)
    if (Object.keys(errs).length) return
    setProvisioning(true); setProvErr(null)
    try {
      const res = await api.admin.adUserProvision(buildProvisionPayload(selected, form))
      if (res?.success) {
        toast.success(t('aduser.added', res.user?.display_name || res.user?.username || selected),
          res.prefix_blocked ? { duration: 9000, details: t('aduser.addedPrefixWarn') } : undefined)
        onAdded?.()
        onClose?.()
        return
      }
      if (res?.code === 'USER_EXISTS') {
        setProvErr(res.error || t('aduser.existsBody'))
        setDetail((d) => ({ ...(d || {}), exists_locally: true, local_user_id: res.user?.id }))
      } else {
        setProvErr(res?.error || t('aduser.addFailed'))
      }
    } catch (e) {
      setProvErr(e?.message || t('aduser.addFailed'))
    } finally {
      setProvisioning(false)
    }
  }

  const roleOptions = roleOptionsFor('ADMIN', form.systemRole, { globalAdmin: true }).map((r) => ({ value: r, label: r }))
  const orgOptions = [{ value: '', label: t('usr.orgRoleNone') }, ...ORG_ROLES.map((r) => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]
  const teamOptions = (teams || []).map((tm) => ({ value: tm.id, label: tm.name }))
  const primaryOptions = form.teamIds.map((id) => {
    const tm = (teams || []).find((x) => x.id === id)
    return { value: String(id), label: tm ? tm.name : '#' + id }
  })

  return (
    <ModalShell open onClose={onClose} size="lg" scrollBody busy={provisioning}
      icon={UserPlus} title={t('aduser.title')} className={cn(FIXED, PHONE_FULLSCREEN)}>
      <div data-slot="aduser" className="flex min-w-0 flex-col gap-3 max-sm:[&_[data-slot=input]]:h-10 max-sm:[&_[role=combobox]]:min-h-10">
        <p className="m-0 text-sm text-muted-foreground">{t('aduser.intro')}</p>

        {/* ── Arama ── */}
        {!selected && (
          <>
            <Field label={t('aduser.searchLabel')}>
              {({ id }) => (
                <InputGroup>
                  <InputGroupAddon><Search aria-hidden="true" className="size-4" /></InputGroupAddon>
                  <InputGroupInput id={id} data-slot="aduser-search" data-page-search autoFocus
                    value={query} onChange={(e) => setQuery(e.target.value)}
                    placeholder={t('aduser.searchPlaceholder')} />
                  {searching && <InputGroupAddon align="inline-end"><Spinner className="size-4" /></InputGroupAddon>}
                </InputGroup>
              )}
            </Field>
            <p className="m-0 text-xs text-muted-foreground">{t('aduser.searchHint')}</p>

            {searchErr && <AlertBanner tone="danger">{searchErr}</AlertBanner>}

            {results != null && !searching && results.length === 0 && !searchErr && (
              <div data-slot="aduser-empty" className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">
                {t('aduser.noResults')}
              </div>
            )}

            {results != null && results.length > 0 && (
              <ul data-slot="aduser-results" className="m-0 flex list-none flex-col gap-2 p-0">
                {results.map((r) => {
                  const exists = r.exists_locally === true
                  return (
                    <li key={r.dn || r.username} data-slot="aduser-result" data-username={r.username}
                      data-exists={exists ? 'true' : 'false'}
                      className="rounded-lg border p-3">
                      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                        <div className="min-w-0 flex-1">
                          <div className="flex min-w-0 flex-wrap items-center gap-2">
                            <span className="font-medium [overflow-wrap:anywhere]">{r.display_name || r.username}</span>
                            {exists && <Badge variant="secondary" data-slot="aduser-badge-exists">{t('aduser.badgeExists')}</Badge>}
                            {r.prefix_blocked && <Badge variant="outline" className="border-amber-500 text-amber-600" data-slot="aduser-badge-prefix">
                              <AlertTriangle aria-hidden="true" className="size-3" />{t('aduser.badgePrefix')}</Badge>}
                          </div>
                          <div className="mt-0.5 flex min-w-0 flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                            {r.username && <span className="[overflow-wrap:anywhere]">{r.username}</span>}
                            {r.email && <span className="[overflow-wrap:anywhere]">{r.email}</span>}
                            {r.department && <span className="[overflow-wrap:anywhere]">{r.department}</span>}
                          </div>
                        </div>
                        {exists ? (
                          <Button variant="outline" size="sm" className="shrink-0 max-sm:min-h-10"
                            data-slot="aduser-edit-existing"
                            onClick={() => { onEditExisting?.(r.local_user_id); onClose?.() }}>
                            <UserCheck aria-hidden="true" />{t('aduser.editExisting')}</Button>
                        ) : (
                          <Button variant="secondary" size="sm" className="shrink-0 max-sm:min-h-10"
                            data-slot="aduser-view" onClick={() => openDetail(r.username)}>
                            {t('aduser.view')}</Button>
                        )}
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}
          </>
        )}

        {/* ── Detay + ekleme ── */}
        {selected && (
          <div data-slot="aduser-detail-wrap" className="flex min-w-0 flex-col gap-3">
            <Button variant="ghost" size="sm" className="self-start max-sm:min-h-10" data-slot="aduser-back" onClick={backToList}>
              <ArrowLeft aria-hidden="true" />{t('aduser.back')}</Button>

            {detailLoading && <div className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner className="size-4" />{t('aduser.loading')}</div>}
            {detailErr && <div data-slot="aduser-detail-error"><AlertBanner tone="danger">{detailErr}</AlertBanner></div>}

            {detail && (
              <>
                <div data-slot="aduser-detail" className="rounded-lg border p-3">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <span className="text-base font-semibold [overflow-wrap:anywhere]">{detail.display_name || detail.username}</span>
                    {detail.exists_locally && <Badge variant="secondary">{t('aduser.badgeExists')}</Badge>}
                    {detail.prefix_blocked && <Badge variant="outline" className="border-amber-500 text-amber-600">
                      <AlertTriangle aria-hidden="true" className="size-3" />{t('aduser.badgePrefix')}</Badge>}
                  </div>
                  <dl className="mt-2 grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-2">
                    <DetailRow icon={IdCard} label={t('aduser.fUsername')} value={detail.username} />
                    <DetailRow icon={Mail} label={t('aduser.fEmail')} value={detail.email} />
                    <DetailRow icon={IdCard} label={t('aduser.fEmployeeId')} value={detail.employee_id} />
                    <DetailRow icon={Building2} label={t('aduser.fDepartment')} value={detail.department} />
                    <DetailRow label={t('aduser.fTitle')} value={detail.title} />
                    <DetailRow label={t('aduser.fManager')} value={detail.manager} />
                  </dl>
                  {detail.derived_teams?.length > 0 && (
                    <div className="mt-2" data-slot="aduser-derived-teams">
                      <div className="mb-1 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                        <Users aria-hidden="true" className="size-3.5" />{t('aduser.derivedTeams')}</div>
                      <div className="flex flex-wrap gap-1.5">
                        {detail.derived_teams.map((n) => <Badge key={n} variant="outline" className="[overflow-wrap:anywhere]">{n}</Badge>)}
                      </div>
                    </div>
                  )}
                </div>

                {detail.exists_locally ? (
                  <div data-slot="aduser-exists-note">
                    <AlertBanner tone="info">
                      {t('aduser.existsBody')}
                      {detail.local_user_id != null && (
                        <Button variant="outline" size="sm" className="ms-2 max-sm:min-h-10" data-slot="aduser-edit-existing"
                          onClick={() => { onEditExisting?.(detail.local_user_id); onClose?.() }}>
                          <UserCheck aria-hidden="true" />{t('aduser.editExisting')}</Button>
                      )}
                    </AlertBanner>
                  </div>
                ) : !adding ? (
                  <div className="flex flex-wrap gap-2">
                    <Button variant="success" data-slot="aduser-start-add" className="max-sm:min-h-10" onClick={startAdding}>
                      <UserPlus aria-hidden="true" />{t('aduser.addBtn')}</Button>
                    <Button variant="outline" className="max-sm:min-h-10" onClick={onClose}>{t('aduser.viewOnly')}</Button>
                  </div>
                ) : (
                  <div data-slot="aduser-add-form" className="flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/30 p-3">
                    <p className="m-0 text-xs text-muted-foreground">{t('aduser.addFormHint')}</p>
                    {provErr && <div data-slot="aduser-prov-error"><AlertBanner tone="danger">{provErr}</AlertBanner></div>}
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <Field label={t('usr.formRole')}>
                        {({ id }) => (
                          <SearchableSelect id={id} value={form.systemRole} options={roleOptions}
                            onChange={(v) => setForm((f) => ({ ...f, systemRole: v }))} />
                        )}
                      </Field>
                      <Field label={t('usr.orgRole')}>
                        {({ id }) => (
                          <SearchableSelect id={id} value={form.orgRole} options={orgOptions}
                            onChange={(v) => setForm((f) => ({ ...f, orgRole: v }))} />
                        )}
                      </Field>
                    </div>
                    <Field label={t('usr.teamsLabel')} required={form.systemRole !== 'ADMIN'}
                      error={errors.team_ids} name="team_ids">
                      {({ id }) => (
                        <MultiTeamSelect id={id} value={form.teamIds} searchThreshold={2} options={teamOptions}
                          placeholder={t('usr.teamsPlaceholder')}
                          onChange={(ids) => setForm((f) => {
                            const next = ids.map(Number)
                            const primary = next.includes(Number(f.primaryTeamId)) ? f.primaryTeamId : (next[0] ?? null)
                            return { ...f, teamIds: next, primaryTeamId: primary }
                          })} />
                      )}
                    </Field>
                    {form.teamIds.length > 1 && (
                      <Field label={t('aduser.primaryTeam')}>
                        {({ id }) => (
                          <SearchableSelect id={id} value={String(form.primaryTeamId ?? '')} options={primaryOptions}
                            onChange={(v) => setForm((f) => ({ ...f, primaryTeamId: v ? Number(v) : null }))} />
                        )}
                      </Field>
                    )}
                    <div className="flex flex-wrap justify-end gap-2">
                      <Button variant="outline" className="max-sm:min-h-10" onClick={() => setAdding(false)} disabled={provisioning}>
                        {t('aduser.cancelAdd')}</Button>
                      <Button variant="success" className="max-sm:min-h-10" data-slot="aduser-confirm-add"
                        onClick={doProvision} disabled={provisioning}>
                        {provisioning ? <Spinner className="size-4" /> : <UserPlus aria-hidden="true" />}{t('aduser.confirmAdd')}</Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </ModalShell>
  )
}

function DetailRow({ icon: Icon, label, value }) {
  if (!value) return null
  return (
    <div className="flex min-w-0 items-baseline gap-2">
      <dt className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
        {Icon && <Icon aria-hidden="true" className="size-3.5" />}{label}</dt>
      <dd className="m-0 min-w-0 [overflow-wrap:anywhere]">{value}</dd>
    </div>
  )
}
