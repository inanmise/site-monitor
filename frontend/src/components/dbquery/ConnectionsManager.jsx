import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, DatabaseZap, Pencil, Plug, PlugZap, Plus, RefreshCw, Trash2, Eye, Table2, ListTree, Server, CircleAlert, CircleHelp,
  ShieldAlert, ShieldCheck, Info,
} from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Label } from '@/components/shadcn/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import ConnectionForm from './ConnectionForm.jsx'
import { DbTypeBadge, HealthBadge } from './dbqueryParts.jsx'
import {
  PG_WEAK_SSL, changedConnectionPayload, emptyForm, formFromProfile, healthState, payloadFromForm, privilegeFindings, privilegeKey, isInfoPrivilege,
  privilegeState, serverFieldErrors, serverLabel, validateConnection,
} from './connectionModel.js'
import { countText } from './dbqueryPageModel.js'

/** Tablo ↔ kart eşiği — KAP genişliği (pencere içinde, kenar çubuğuyla daralmış sayfada farklı). */
export const CONNECTIONS_TABLE_MIN_PX = 760

/**
 * Bağlantıları yönet (Veritabanı Sorgu İzleme, 2026-10-31) — takımın kayıtlı veritabanı bağlantıları. Takımlar arası
 * görünürlük YOK: liste sunucunun o takım için döndürdüğüdür (paylaşımla açılmaz). Görünümler: liste (kap ≥ 760 px tablo,
 * altında kart) → ayrıntı (sağlık paneli: son deneme / son kullanım / son başarılı / son hata (maskeli) / sunucu sürümü /
 * ortalama süre / kullanan izlemeler / sorgulardaki tablolar; bilinmeyen durum asla yeşil değil) → ekle / düzenle formu
 * ("Bağlantıyı dene": kaydetmeden `testDbConnectionDraft`, kayıtlıda `testDbConnection`). Silme onaylı; kullanan izleme
 * varsa 409 listesi gösterilir. `canEdit` false (ya da satırın `can_edit` false) → salt okunur.
 *
 * Ek isteğe bağlı prop'lar (sözleşmeye EKLENDİ, mevcutlar aynı): `initialView` ('list' | 'new') — seçicinin "Yeni
 * bağlantı" kısayolu; `onChanged(profile?)` oluşturulan / güncellenen profili de taşır.
 *
 * Form görünümünde (Y1) Escape ve örtü pencereyi KAPATMAZ; kaydedilmemiş değişiklikle kapat (X) / "Geri" / "Vazgeç" onay
 * ister (izleme formunun "kaydedilmemiş değişiklikler" deseni) — yazılan sunucu / kullanıcı / parola sessizce gitmez.
 * Düzenleme yalnız değişen alanları gönderir (hedef alanları + parola kuralı birlikte; DÜŞÜK 7).
 */
/** Sunucunun `security_warnings` kodlarından ekranda AYRICA gösterilecekler (bilinmeyen kod çizilmez). */
export const SERVER_SECURITY_WARNINGS = ['SSL_NOT_VERIFIED', 'MSSQL_UNENCRYPTED', 'MSSQL_TRUST_SERVER_CERT']
export function serverSecurityWarnings(p) {
  const list = Array.isArray(p?.security_warnings) ? p.security_warnings : []
  return SERVER_SECURITY_WARNINGS.filter((c) => list.includes(c))
}

export default function ConnectionsManager({ open, onOpenChange, teamId, teams = [], canEdit = false, onChanged, initialView = 'list' }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const teamList = useMemo(() => (Array.isArray(teams) ? teams : []), [teams])
  const [team, setTeam] = useState(teamId ?? teamList[0]?.id ?? null)
  const [load, setLoad] = useState({ state: 'loading', list: [], message: null })
  const [view, setView] = useState({ name: 'list' })
  const seq = useRef(0)
  const dirtyRef = useRef(false)   // form görünümünün kaydedilmemiş değişikliği (FormView bildirir)

  useEffect(() => {
    if (!open) return
    setTeam(teamId ?? teamList[0]?.id ?? null)
    setView(initialView === 'new' && canEdit ? { name: 'form', id: null } : { name: 'list' })
    // yalnız açılışta — takım değişince liste yeniden okunur (aşağıda)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const reload = useCallback(async () => {
    const n = ++seq.current
    if (team == null) { setLoad({ state: 'noTeam', list: [], message: null }); return }
    setLoad((s) => ({ ...s, state: s.list.length ? 'refreshing' : 'loading' }))
    try {
      const res = await api.monitoring.dbConnections(team)
      if (n !== seq.current) return
      if (res?.success && Array.isArray(res.data)) setLoad({ state: 'ok', list: res.data, message: null })
      else setLoad({ state: 'error', list: [], message: res?.error || res?.message || t('dbq.conn.loadFailedBody') })
    } catch (e) {
      if (n !== seq.current) return
      setLoad({ state: 'error', list: [], message: e?.message || t('dbq.conn.loadFailedBody') })
    }
  }, [team, t])

  useEffect(() => { if (open) reload() }, [open, reload])

  /** Kaydedilmemiş değişiklik varsa onay — "Değişiklikleri at" true döner. */
  const confirmDiscard = useCallback(async () => {
    if (!dirtyRef.current) return true
    return showConfirm({
      title: t('dbq.form.discardTitle'), message: t('dbq.conn.discardMsg'),
      confirmText: t('dbq.form.discard'), cancelText: t('dbq.form.keepEditing'), variant: 'danger',
    })
  }, [showConfirm, t])
  const close = async () => {
    if (view.name === 'form' && !(await confirmDiscard())) return
    dirtyRef.current = false
    onOpenChange?.(false)
  }
  const leaveForm = async (next) => {
    if (!(await confirmDiscard())) return
    dirtyRef.current = false
    setView(next)
  }
  const byId = (id) => load.list.find((p) => p.id === id) || null
  const editable = (p) => canEdit && p?.can_edit !== false
  const teamName = teamList.find((tm) => tm.id === team)?.name || null

  const afterSave = (profile) => {
    dirtyRef.current = false
    onChanged?.(profile)
    reload()
    setView(profile?.id != null ? { name: 'detail', id: profile.id, fresh: profile } : { name: 'list' })
  }

  const remove = async (p) => {
    const ok = await showConfirm({
      title: t('dbq.conn.deleteTitle'), message: t('dbq.conn.deleteBody', p.name),
      confirmText: t('dbq.conn.deleteConfirm'), variant: 'danger',
    })
    if (!ok) return
    let res
    try { res = await api.monitoring.deleteDbConnection(p.id) } catch (e) { res = { success: false, error: e?.message } }
    if (res?.success) {
      toast.success(t('dbq.conn.deleted', p.name))
      onChanged?.(null)
      setView({ name: 'list' })
      reload()
      return
    }
    if (res?.code === 'DBQUERY_CONNECTION_IN_USE') {
      setView({ name: 'detail', id: p.id, inUse: Array.isArray(res.monitors) ? res.monitors : [], inUseMessage: res.error })
      return
    }
    toast.error(res?.error || t('dbq.conn.deleteFailedBody'))
  }

  const detail = view.name === 'detail' ? (byId(view.id) || view.fresh || null) : null
  const editing = view.name === 'form' && view.id != null ? byId(view.id) : null

  return (
    <ModalShell open={!!open} onClose={close} size="xl" scrollBody dismissOnBackdrop={view.name !== 'form'} dismissOnEscape={view.name !== 'form'}
      icon={DatabaseZap} title={t('dbq.conn.managerTitle')}>
      <div data-slot="dbq-conn-manager" data-view={view.name} className="flex min-w-0 flex-col gap-3">
        {view.name === 'list' && (
          <ListView t={t} load={load} teams={teamList} team={team} setTeam={setTeam} canEdit={canEdit} teamName={teamName}
            onReload={reload} onOpen={(p) => setView({ name: 'detail', id: p.id })}
            onEdit={(p) => setView({ name: 'form', id: p.id })} onNew={() => setView({ name: 'form', id: null })}
            onDelete={remove} editable={editable} />
        )}
        {view.name === 'detail' && (
          detail
            ? <DetailView t={t} p={detail} editable={editable(detail)} inUse={view.inUse} inUseMessage={view.inUseMessage}
                onBack={() => setView({ name: 'list' })} onEdit={() => setView({ name: 'form', id: detail.id })}
                onDelete={() => remove(detail)} onTested={reload} />
            : <StatusBlock tone="neutral" title={t('dbq.conn.goneTitle')} description={t('dbq.conn.goneBody')}
                actions={<Button type="button" variant="outline" onClick={() => setView({ name: 'list' })}>{t('dbq.conn.backToList')}</Button>} />
        )}
        {view.name === 'form' && view.id != null && !editing && (
          <StatusBlock tone="neutral" title={t('dbq.conn.goneTitle')} description={t('dbq.conn.goneBody')}
            actions={<Button type="button" variant="outline" onClick={() => setView({ name: 'list' })}>{t('dbq.conn.backToList')}</Button>} />
        )}
        {view.name === 'form' && (view.id == null || editing) && (
          <FormView key={view.id ?? 'new'} t={t} team={team} teams={teamList} profile={editing} creating={view.id == null}
            dirtyRef={dirtyRef}
            onCancel={() => leaveForm(view.id != null ? { name: 'detail', id: view.id } : { name: 'list' })}
            onNoChanges={() => { dirtyRef.current = false; setView({ name: 'detail', id: view.id }) }}
            onSaved={afterSave} />
        )}
      </div>
    </ModalShell>
  )
}

/** Liste görünümü — takım seçici + tablo / kart. */
function ListView({ t, load, teams, team, setTeam, canEdit, teamName, onReload, onOpen, onEdit, onNew, onDelete, editable }) {
  const [ref, width] = useElementWidth()
  const table = width === 0 || width >= CONNECTIONS_TABLE_MIN_PX
  return (
    <div ref={ref} className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        {teams.length > 1 ? (
          <div className="flex min-w-0 flex-col gap-1 [&>[data-slot=native-select-wrapper]]:w-full sm:[&>[data-slot=native-select-wrapper]]:w-72">
            <Label htmlFor="dbq-conn-team" className="text-xs font-semibold text-muted-foreground">{t('dbq.conn.team')}</Label>
            <NativeSelect id="dbq-conn-team" value={team ?? ''} onChange={(e) => setTeam(Number(e.target.value))} className="h-9 pointer-coarse:h-10">
              {teams.map((tm) => <NativeSelectOption key={tm.id} value={tm.id}>{tm.name}</NativeSelectOption>)}
            </NativeSelect>
          </div>
        ) : (
          <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">
            {teamName ? t('dbq.conn.teamScope', teamName) : t('dbq.conn.teamScopeOwn')}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" onClick={onReload} disabled={load.state === 'loading' || load.state === 'refreshing'}
            className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.conn.refresh')}</Button>
          {canEdit && team != null && (
            <Button type="button" onClick={onNew} className="pointer-coarse:h-10" data-slot="dbq-conn-new"><Plus aria-hidden="true" />{t('dbq.conn.new')}</Button>
          )}
        </div>
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t('dbq.conn.scopeNote')}</p>

      {load.state === 'noTeam' && <StatusBlock tone="neutral" icon={Server} title={t('dbq.conn.noTeamTitle')} description={t('dbq.conn.noTeamBody')} />}
      {load.state === 'loading' && (
        <div className="flex flex-col gap-2" aria-busy="true"><Skeleton className="h-14 w-full" /><Skeleton className="h-14 w-full" /></div>
      )}
      {load.state === 'error' && (
        <StatusBlock tone="danger" icon={CircleAlert} title={t('dbq.conn.loadFailedTitle')} description={load.message}
          actions={<Button type="button" variant="outline" onClick={onReload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.conn.retry')}</Button>} />
      )}
      {(load.state === 'ok' || load.state === 'refreshing') && load.list.length === 0 && (
        <StatusBlock tone="neutral" icon={Plug} title={t('dbq.conn.emptyTitle')}
          description={canEdit ? t('dbq.conn.emptyBody') : t('dbq.conn.emptyBodyReadOnly')}
          actions={canEdit ? <Button type="button" onClick={onNew} className="pointer-coarse:h-10"><Plus aria-hidden="true" />{t('dbq.conn.new')}</Button> : null} />
      )}
      {load.list.length > 0 && (table
        ? (
          <Table data-slot="dbq-conn-table">
            <TableHeader>
              <TableRow>
                <TableHead className="whitespace-normal">{t('dbq.conn.col.name')}</TableHead>
                <TableHead className="whitespace-normal">{t('dbq.conn.col.server')}</TableHead>
                <TableHead className="whitespace-normal">{t('dbq.conn.col.health')}</TableHead>
                <TableHead className="whitespace-normal">{t('dbq.conn.col.used')}</TableHead>
                <TableHead className="w-36 text-right whitespace-normal">{t('dbq.conn.col.actions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {load.list.map((p) => (
                <TableRow key={p.id} data-slot="dbq-conn-row" data-id={p.id}>
                  <TableCell className="align-top whitespace-normal">
                    <div className="flex min-w-0 flex-col gap-1">
                      <Button type="button" variant="link" onClick={() => onOpen(p)}
                        className="h-auto justify-start p-0 text-left font-semibold whitespace-normal [overflow-wrap:anywhere]">{p.name}</Button>
                      <DbTypeBadge type={p.db_type} />
                      {p.description && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{p.description}</span>}
                    </div>
                  </TableCell>
                  <TableCell className="align-top font-mono text-xs whitespace-normal [overflow-wrap:anywhere]">{serverLabel(p)}</TableCell>
                  <TableCell className="align-top whitespace-normal"><HealthBadge profile={p} /></TableCell>
                  <TableCell className="align-top text-sm whitespace-normal tabular-nums">{countText(t, 'dbq.conn.usedCount', (p.used_by || []).length)}</TableCell>
                  <TableCell className="text-right align-top">
                    <RowActions t={t} p={p} editable={editable(p)} onOpen={onOpen} onEdit={onEdit} onDelete={onDelete} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )
        : (
          <ul data-slot="dbq-conn-cards" className="m-0 flex list-none flex-col gap-2 p-0">
            {load.list.map((p) => (
              <li key={p.id}>
                <Card data-slot="dbq-conn-card" data-id={p.id} className="gap-2 px-3 py-3 shadow-none">
                  <div className="flex min-w-0 items-start gap-2">
                    <div className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="font-semibold [overflow-wrap:anywhere]">{p.name}</span>
                      <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{serverLabel(p)}</span>
                    </div>
                    <RowActions t={t} p={p} editable={editable(p)} onOpen={onOpen} onEdit={onEdit} onDelete={onDelete} />
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <DbTypeBadge type={p.db_type} />
                    <HealthBadge profile={p} />
                    <span className="text-xs text-muted-foreground">{countText(t, 'dbq.conn.usedCount', (p.used_by || []).length)}</span>
                  </div>
                  <Button type="button" variant="outline" onClick={() => onOpen(p)} className="w-full pointer-coarse:h-10">
                    <Eye aria-hidden="true" />{t('dbq.conn.details')}
                  </Button>
                </Card>
              </li>
            ))}
          </ul>
        ))}
    </div>
  )
}

function RowActions({ t, p, editable, onOpen, onEdit, onDelete }) {
  return (
    <KebabMenu variant="labeled" label={t('dbq.conn.col.actions')} rowLabel={p.name}
      items={[
        { label: t('dbq.conn.details'), icon: <Eye aria-hidden="true" />, onClick: () => onOpen(p) },
        { label: t('dbq.conn.edit'), icon: <Pencil aria-hidden="true" />, onClick: () => onEdit(p), hidden: !editable },
        { label: t('dbq.conn.delete'), icon: <Trash2 aria-hidden="true" />, onClick: () => onDelete(p), danger: true, hidden: !editable },
      ]} />
  )
}

/** Sağlık paneli + kullananlar + tablolar. */
function DetailView({ t, p, editable, inUse, inUseMessage, onBack, onEdit, onDelete, onTested }) {
  const toast = useToast()
  const [test, setTest] = useState(null)
  const [testing, setTesting] = useState(false)
  const h = p.health || {}
  const state = healthState(p)
  const when = (iso) => (iso ? formatDate(iso) : t('dbq.conn.never'))

  const runTest = async () => {
    setTesting(true)
    let res
    try { res = await api.monitoring.testDbConnection(p.id) } catch (e) { res = { success: false, error: e?.message } } finally { setTesting(false) }
    setTest(res)
    if (res?.success) onTested?.()
    else if (res?.code === 'RATE_LIMITED') toast.info(res.error || t('dbq.conn.testWait', res.retry_after_seconds ?? 5))
  }

  return (
    <div data-slot="dbq-conn-detail" data-id={p.id} data-state={state} className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="ghost" onClick={onBack} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" />{t('dbq.conn.backToList')}</Button>
        <div className="ml-auto flex flex-wrap gap-2">
          <Button type="button" variant="outline" onClick={runTest} disabled={testing} aria-busy={testing || undefined} className="pointer-coarse:h-10">
            {testing ? <Spinner size={14} inline decorative /> : <PlugZap aria-hidden="true" />}{t('dbq.conn.test')}
          </Button>
          {editable && <Button type="button" variant="outline" onClick={onEdit} className="pointer-coarse:h-10"><Pencil aria-hidden="true" />{t('dbq.conn.edit')}</Button>}
          {editable && <Button type="button" variant="outline" onClick={onDelete} className="text-destructive hover:text-destructive pointer-coarse:h-10"><Trash2 aria-hidden="true" />{t('dbq.conn.delete')}</Button>}
        </div>
      </div>

      <div className="flex min-w-0 flex-col gap-1">
        <h3 className="m-0 text-base font-semibold [overflow-wrap:anywhere]">{p.name}</h3>
        <div className="flex flex-wrap items-center gap-1.5">
          <DbTypeBadge type={p.db_type} /><HealthBadge profile={p} />
          {!editable && <Badge variant="outline" className="font-normal text-muted-foreground">{t('dbq.conn.readOnly')}</Badge>}
        </div>
        <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{serverLabel(p)}</span>
        {p.description && <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{p.description}</p>}
      </div>

      {Array.isArray(inUse) && (
        <AlertBanner tone="warning" title={t('dbq.conn.inUseTitle')} className="mb-0">
          <p className="m-0">{inUseMessage || t('dbq.conn.inUseBody')}</p>
          {inUse.length > 0 && (
            <ul data-slot="dbq-conn-inuse" className="mt-1.5 mb-0 list-disc pl-5">
              {inUse.map((m) => <li key={m.id} className="[overflow-wrap:anywhere]">{m.name}</li>)}
            </ul>
          )}
        </AlertBanner>
      )}

      {test && <TestOutcome t={t} res={test} />}

      <Card data-slot="dbq-conn-health" className="gap-0 py-0 shadow-none">
        <h4 className="m-0 border-b px-4 py-2.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('dbq.conn.healthTitle')}</h4>
        {state === 'unknown' && <p className="m-0 px-4 pt-3 text-sm text-muted-foreground">{t('dbq.conn.healthUnknown')}</p>}
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-2">
          <Fact label={t('dbq.conn.h.lastTest')}>
            {h.last_test_at
              ? <>{when(h.last_test_at)} · {h.last_test_ok === true ? t('dbq.conn.h.testOk') : (h.last_test_ok === false ? t('dbq.conn.h.testFail') : t('dbq.conn.h.testUnknown'))}{h.last_test_ms != null ? ` · ${t('dbq.conn.ms', h.last_test_ms)}` : ''}</>
              : t('dbq.conn.never')}
          </Fact>
          <Fact label={t('dbq.conn.h.serverVersion')}>{h.server_version || t('dbq.conn.h.unknownValue')}</Fact>
          <Fact label={t('dbq.conn.h.lastUsed')}>{when(h.last_used_at)}</Fact>
          <Fact label={t('dbq.conn.h.lastOk')}>{when(h.last_ok_at)}</Fact>
          <Fact label={t('dbq.conn.h.avgDuration')}>{h.last_duration_ms != null ? t('dbq.conn.ms', h.last_duration_ms) : t('dbq.conn.h.unknownValue')}</Fact>
          <Fact label={t('dbq.conn.h.lastProblemAt')}>{h.last_error_at ? when(h.last_error_at) : t('dbq.conn.h.noProblem')}</Fact>
          {privilegeState(h.privileges) !== 'absent' && (
            // Son testin hesap yetkisi özeti (2.B — sunucu hedefli UPDATE ile yazar; alan yoksa satır yok)
            <Fact label={t('dbq.conn.h.privileges')} className="sm:col-span-2">
              <PrivilegesSummary t={t} priv={h.privileges} compact />
            </Fact>
          )}
          {p.db_type === 'POSTGRESQL' && PG_WEAK_SSL.includes(p.ssl_mode) && (
            <Fact label={t('dbq.conn.h.encryption')} className="sm:col-span-2">
              <span data-slot="dbq-conn-ssl-weak" data-mode={p.ssl_mode} className="inline-flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200">
                <ShieldAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                <span className="[overflow-wrap:anywhere]">{t(p.ssl_mode === 'disable' ? 'dbq.conn.sslWeak.disable' : 'dbq.conn.sslWeak.prefer')}</span>
              </span>
            </Fact>
          )}
          {serverSecurityWarnings(p).length > 0 && (
            // Sunucunun bağlantı güvenlik uyarıları (r177 2.B; PG prefer / disable yukarıda istemci kuralıyla)
            <Fact label={t('dbq.conn.h.security')} className="sm:col-span-2">
              <ul data-slot="dbq-conn-security-warnings" className="m-0 flex list-none flex-col gap-1 p-0">
                {serverSecurityWarnings(p).map((code) => (
                  <li key={code} data-code={code} className="inline-flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200">
                    <ShieldAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                    <span className="[overflow-wrap:anywhere]">{t(`dbq.conn.secw.${code}`)}</span>
                  </li>
                ))}
              </ul>
            </Fact>
          )}
          {(h.last_error || h.last_test_error) && (
            <Fact label={t('dbq.conn.h.lastProblem')} className="sm:col-span-2">
              <code data-slot="dbq-conn-last-error" className="block rounded-md bg-muted/60 px-2 py-1.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">
                {h.last_error || h.last_test_error}
              </code>
            </Fact>
          )}
        </dl>
      </Card>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <Card data-slot="dbq-conn-usedby" className="gap-2 px-4 py-3 shadow-none">
          <h4 className="m-0 inline-flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase"><ListTree aria-hidden="true" className="size-3.5" />{t('dbq.conn.usedBy')}</h4>
          {(p.used_by || []).length === 0
            ? <p className="m-0 text-sm text-muted-foreground">{t('dbq.conn.usedByNone')}</p>
            : (
              <ul className="m-0 flex list-none flex-col gap-1 p-0">
                {p.used_by.map((m) => (
                  <li key={m.id} className="flex flex-wrap items-center gap-1.5 text-sm">
                    <span className="[overflow-wrap:anywhere]">{m.name}</span>
                    {m.active === false && <Badge variant="outline" className="font-normal text-muted-foreground">{t('dbq.conn.paused')}</Badge>}
                  </li>
                ))}
              </ul>
            )}
        </Card>
        <Card data-slot="dbq-conn-tables" className="gap-2 px-4 py-3 shadow-none">
          <h4 className="m-0 inline-flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase"><Table2 aria-hidden="true" className="size-3.5" />{t('dbq.conn.tablesUsed')}</h4>
          {(p.tables_used || []).length === 0
            ? <p className="m-0 text-sm text-muted-foreground">{t('dbq.conn.tablesNone')}</p>
            : (
              <div className="flex flex-wrap gap-1.5">
                {p.tables_used.map((tb) => <Badge key={tb} variant="outline" className="h-auto rounded-md font-mono font-normal whitespace-normal [overflow-wrap:anywhere]">{tb}</Badge>)}
              </div>
            )}
        </Card>
      </div>
    </div>
  )
}

function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/**
 * Hesap yetkisi özeti (2.B — bağlantı testinin `privileges`'ı): salt okunur → yeşil tek satır; yazabilen / yönetici →
 * uyarı + bulgular (kod metinleri) + DBA betiği yönlendirmesi; denetlenemedi → nötr (asla yeşil değil); alan yok (eski
 * sunucu) → hiçbir şey. `compact`: sağlık panelinin satırı.
 */
export function PrivilegesSummary({ t, priv, compact = false }) {
  const state = privilegeState(priv)
  if (state === 'absent') return null
  const findings = privilegeFindings(priv)
  if (state === 'read_only') {
    const info = findings.filter((f) => isInfoPrivilege(f.code))
    return (
      <div data-slot="dbq-conn-priv" data-state={state} className="flex min-w-0 flex-col gap-1 text-xs">
        <p className="m-0 inline-flex items-start gap-1.5 text-success">
          <ShieldCheck aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('dbq.conn.privReadOnly')}
        </p>
        {info.map((f, i) => (
          // Bilgi kodu (ör. PG ≤ 14 public şeması CREATE — herkese açık varsayılan): uyarı değil, DBA'ya not
          <p key={`${f.code}-${i}`} data-slot="dbq-conn-priv-info" data-code={f.code}
            className="m-0 inline-flex items-start gap-1.5 text-muted-foreground">
            <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="[overflow-wrap:anywhere]">{t(privilegeKey(f.code))}</span>
          </p>
        ))}
      </div>
    )
  }
  if (state === 'unknown') {
    return (
      <p data-slot="dbq-conn-priv" data-state={state} className="m-0 inline-flex items-start gap-1.5 text-xs text-muted-foreground">
        <CircleHelp aria-hidden="true" className="mt-px size-3.5 shrink-0" />
        <span className="[overflow-wrap:anywhere]">{t('dbq.conn.privUnknown')}</span>
      </p>
    )
  }
  return (
    <div data-slot="dbq-conn-priv" data-state={state}
      className={cn('flex min-w-0 flex-col gap-1 rounded-md border border-amber-500/40 bg-amber-500/5 px-2.5 py-2 text-xs dark:bg-amber-500/10',
        compact && 'py-1.5')}>
      <span className="inline-flex items-start gap-1.5 font-semibold text-amber-800 dark:text-amber-200">
        <ShieldAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('dbq.conn.privWritable')}
      </span>
      {findings.length > 0 && (
        <ul data-slot="dbq-conn-priv-findings" className="m-0 flex list-disc flex-col gap-0.5 pl-5">
          {findings.map((f, i) => (
            <li key={`${f.code}-${i}`} data-code={f.code} className="[overflow-wrap:anywhere]">
              {t(privilegeKey(f.code))}{f.detail ? <span className="text-muted-foreground"> — {f.detail}</span> : null}
            </li>
          ))}
        </ul>
      )}
      {!compact && <span className="text-muted-foreground">{t('dbq.conn.privAdvice')}</span>}
    </div>
  )
}

/** Deneme sonucu — başarı (süre + sunucu sürümü + yetki özeti), başarısız (maskeli hata), bekleme. */
function TestOutcome({ t, res }) {
  if (res?.success && res.data?.ok === true) {
    const d = res.data
    return (
      <AlertBanner tone="success" title={t('dbq.conn.testOkTitle')} className="mb-0">
        <span className="flex min-w-0 flex-col gap-1.5">
          <span data-slot="dbq-conn-test" data-ok="true">
            {t('dbq.conn.testOkBody', d.duration_ms ?? '—')}{d.server_version ? ` · ${d.server_version}` : ''}
          </span>
          <PrivilegesSummary t={t} priv={d.privileges} />
        </span>
      </AlertBanner>
    )
  }
  const d = res?.success ? res.data : null
  const blocked = d?.failure_reason === 'DB_ACCOUNT_WRITABLE' || res?.code === 'DB_ACCOUNT_WRITABLE'
  const msg = d?.error || res?.error || res?.message || t('dbq.conn.testFailedBody')
  return (
    <AlertBanner tone={res?.code === 'RATE_LIMITED' ? 'info' : 'danger'}
      title={res?.code === 'RATE_LIMITED' ? t('dbq.conn.testWaitTitle') : blocked ? t('dbq.conn.testBlockedTitle') : t('dbq.conn.testFailTitle')} className="mb-0">
      <span className="flex min-w-0 flex-col gap-1.5">
        <code data-slot="dbq-conn-test" data-ok="false" className="block font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">{msg}</code>
        {d?.duration_ms != null && <span className="text-xs">{t('dbq.conn.ms', d.duration_ms)}</span>}
        <PrivilegesSummary t={t} priv={d?.privileges} />
      </span>
    </AlertBanner>
  )
}

/** Ekle / düzenle formu + kaydetmeden deneme. */
function FormView({ t, team, teams, profile, creating, onCancel, onSaved, onNoChanges, dirtyRef }) {
  const toast = useToast()
  const [baseline] = useState(() => (profile ? formFromProfile(profile) : emptyForm(team)))
  const [form, setForm] = useState(baseline)
  const [server, setServer] = useState({})
  const [client, setClient] = useState({})
  const [banner, setBanner] = useState(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [test, setTest] = useState(null)
  const errors = { ...client, ...server }
  // Kaydedilmemiş değişiklik = gövde (parola dahil) açılıştakinden farklı — yöneticinin kapat / geri onayı bunu okur
  const dirty = JSON.stringify(payloadFromForm(form)) !== JSON.stringify(payloadFromForm(baseline))
  useEffect(() => {
    if (dirtyRef) dirtyRef.current = dirty
  }, [dirty, dirtyRef])
  // Görünüm kapanınca (kayıt, geri, silinmiş kayıt) bayrak iner — yöneticinin sonraki kapatması boşuna onay sormasın
  useEffect(() => () => { if (dirtyRef) dirtyRef.current = false }, [dirtyRef])
  const shownErrors = Object.fromEntries(Object.entries(errors).map(([k, v]) => [k, client[k] && !server[k] ? t(`dbq.conn.v.${v}`) : v]))

  const onChange = (next) => {
    // düzenlenen alanın hatası silinir (sunucu + istemci)
    const changed = Object.keys(next).filter((k) => next[k] !== form[k])
    setForm(next)
    if (changed.length) {
      setServer((s) => Object.fromEntries(Object.entries(s).filter(([k]) => !changed.includes(k))))
      setClient((s) => Object.fromEntries(Object.entries(s).filter(([k]) => !changed.includes(k))))
    }
  }

  const check = () => {
    const v = validateConnection(form)
    setClient(v)
    if (Object.keys(v).length) {
      requestAnimationFrame?.(() => {
        const el = document.querySelector(`[data-slot="dbq-conn-form"] [data-field="${Object.keys(v)[0]}"] input, [data-slot="dbq-conn-form"] [data-field="${Object.keys(v)[0]}"] select`)
        el?.focus?.()
      })
      return false
    }
    return true
  }

  const save = async () => {
    setBanner(null)
    if (!check()) return
    // Düzenleme yalnız değişen alanları gönderir; hiçbir şey değişmediyse istek yok
    const body = creating ? payloadFromForm(form) : changedConnectionPayload(form, baseline)
    if (!creating && Object.keys(body).length === 0) { toast.info(t('dbq.conn.noChanges')); onNoChanges?.(); return }
    setSaving(true)
    let res
    try {
      res = creating ? await api.monitoring.createDbConnection(body) : await api.monitoring.updateDbConnection(form.id, body)
    } catch (e) { res = { success: false, error: e?.message } } finally { setSaving(false) }
    if (res?.success) {
      const name = String(form.name || '').trim()
      toast.success(creating ? t('dbq.conn.created', name) : t('dbq.conn.updated', name))
      onSaved(res.data || null)
      return
    }
    const fe = serverFieldErrors(res)
    setServer(fe)
    if (!Object.keys(fe).length) setBanner(res?.error || res?.message || t('dbq.conn.saveFailedBody'))
  }

  const runTest = async () => {
    setTest(null)
    if (!check()) return
    setTesting(true)
    let res
    try { res = await api.monitoring.testDbConnectionDraft(payloadFromForm(form, { withId: true })) } catch (e) { res = { success: false, error: e?.message } } finally { setTesting(false) }
    setTest(res)
    const fe = serverFieldErrors(res)
    if (Object.keys(fe).length) setServer(fe)
  }

  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="dbq-conn-form-view">
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={saving} className="pointer-coarse:h-10"><ArrowLeft aria-hidden="true" />{t('dbq.conn.back')}</Button>
        <h3 className="m-0 text-base font-semibold [overflow-wrap:anywhere]">{creating ? t('dbq.conn.newTitle') : t('dbq.conn.editTitle', profile?.name || '')}</h3>
      </div>
      {banner && <AlertBanner tone="danger" title={t('dbq.conn.saveFailedTitle')} className="mb-0">{banner}</AlertBanner>}
      <ConnectionForm form={form} onChange={onChange} errors={shownErrors} disabled={saving} teams={teams} showTeam={creating} />
      {test && <TestOutcome t={t} res={test} />}
      <div className="flex flex-col-reverse gap-2 border-t pt-3 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving} className="pointer-coarse:h-10">{t('dbq.conn.cancel')}</Button>
        <Button type="button" variant="outline" onClick={runTest} disabled={saving || testing} aria-busy={testing || undefined} className="pointer-coarse:h-10" data-slot="dbq-conn-test-draft">
          {testing ? <Spinner size={14} inline decorative /> : <PlugZap aria-hidden="true" />}{t('dbq.conn.testDraft')}
        </Button>
        <Button type="button" onClick={save} disabled={saving} aria-busy={saving || undefined} className="pointer-coarse:h-10" data-slot="dbq-conn-save">
          {saving && <Spinner size={14} inline decorative />}{t('dbq.conn.save')}
        </Button>
      </div>
    </div>
  )
}
