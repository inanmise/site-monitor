import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { DatabaseZap, Plus, RefreshCw, TriangleAlert, CircleAlert } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { FieldError } from '../ui/Field.jsx'
import ConnectionsManager from './ConnectionsManager.jsx'
import { DbTypeBadge, HealthBadge } from './dbqueryParts.jsx'
import { DB_TYPES, healthState, serverLabel } from './connectionModel.js'

/**
 * Bağlantı seçici (Veritabanı Sorgu İzleme, 2026-10-31) — seçili takımın kayıtlı bağlantıları (sunucu takımla süzer;
 * paylaşımla açılmaz). Seçenek: ad + DB türü · sunucu · sağlık; seçili bağlantı altında kart olarak (simge, ad, sunucu,
 * sağlık rozeti — bilinmeyen asla yeşil değil). Takım yoksa devre dışı + ipucu; liste boşsa "bu takımda kayıtlı bağlantı
 * yok — ekle"; okunamazsa hata + tekrar dene. "Bağlantıları yönet" ve "Yeni bağlantı" ConnectionsManager'ı açar; yeni
 * bağlantı kaydedilince seçilir. `data-field="profile_id"` (sunucu alan hatası kancası).
 *
 * Seçiciden açılan yönetici YALNIZ formun takımını gösterir (DÜŞÜK 3): yeni bağlantı başka takıma kaydedilip "bu takımda
 * bulunamadı" uyarısıyla seçilmesin; dönen profil yine de yalnız formun takımındaysa seçilir. Hata metni seçiciye
 * `aria-describedby` ile bağlıdır (10c).
 */
export default function ConnectionPicker({ teamId, value, onChange, disabled = false, error, canManage = false, teams }) {
  const t = useT()
  const errorId = useId()
  const [load, setLoad] = useState({ state: teamId == null ? 'noTeam' : 'loading', list: [], message: null })
  const [manager, setManager] = useState(null)   // null | 'list' | 'new'
  const seq = useRef(0)

  const reload = useCallback(async () => {
    const n = ++seq.current
    if (teamId == null) { setLoad({ state: 'noTeam', list: [], message: null }); return }
    setLoad((s) => ({ ...s, state: 'loading' }))
    try {
      const res = await api.monitoring.dbConnections(teamId)
      if (n !== seq.current) return
      if (res?.success && Array.isArray(res.data)) setLoad({ state: 'ok', list: res.data, message: null })
      else setLoad({ state: 'error', list: [], message: res?.error || res?.message || t('dbq.conn.loadFailedBody') })
    } catch (e) {
      if (n !== seq.current) return
      setLoad({ state: 'error', list: [], message: e?.message || t('dbq.conn.loadFailedBody') })
    }
  }, [teamId, t])

  useEffect(() => { reload() }, [reload])

  const list = load.list
  const selected = value != null && value !== '' ? list.find((p) => String(p.id) === String(value)) || null : null
  const missing = load.state === 'ok' && value != null && value !== '' && !selected
  const opts = list.map((p) => ({
    value: p.id, label: p.name,
    hint: `${DB_TYPES.includes(p.db_type) ? t(`dbq.conn.type.${p.db_type}`) : t('dbq.conn.type.unknown')} · ${serverLabel(p)} · ${t(`dbq.conn.health.${healthState(p)}`)}`,
  }))
  const formTeam = Array.isArray(teams) ? teams.find((tm) => String(tm.id) === String(teamId)) : null
  const teamList = teamId != null ? [formTeam ? { id: formTeam.id, name: formTeam.name } : { id: teamId, name: '' }] : []
  const noTeam = teamId == null

  const onManagerChanged = (profile) => {
    reload()
    const sameTeam = profile?.team_id == null || String(profile.team_id) === String(teamId)
    if (manager === 'new' && profile?.id != null && sameTeam) onChange?.(profile.id, profile)
  }

  return (
    <div data-slot="dbq-conn-picker" data-field="profile_id" data-state={load.state} className="flex min-w-0 flex-col gap-2">
      <span className="text-sm font-semibold">{t('dbq.conn.pickLabel')} <span data-slot="field-required" className="text-destructive">*</span></span>

      {noTeam && <p data-slot="dbq-conn-no-team" className="m-0 text-xs text-muted-foreground">{t('dbq.conn.pickNeedsTeam')}</p>}

      {!noTeam && load.state === 'loading' && <Skeleton className="h-9 w-full" />}

      {!noTeam && load.state === 'error' && (
        <div data-slot="dbq-conn-picker-error" className="flex flex-col gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-2.5 text-sm">
          <span className="inline-flex items-start gap-1.5 text-destructive"><CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            <span className="[overflow-wrap:anywhere]">{t('dbq.conn.pickLoadFailed')}{load.message ? ` — ${load.message}` : ''}</span></span>
          <div><Button type="button" variant="outline" size="sm" onClick={reload} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.conn.retry')}</Button></div>
        </div>
      )}

      {!noTeam && load.state === 'ok' && list.length === 0 && (
        <div data-slot="dbq-conn-picker-empty" className="flex flex-col items-start gap-2 rounded-md border border-dashed p-3 text-sm">
          <span>{canManage ? t('dbq.conn.pickEmpty') : t('dbq.conn.pickEmptyReadOnly')}</span>
          {canManage && (
            <Button type="button" size="sm" onClick={() => setManager('new')} disabled={disabled} className="pointer-coarse:h-10">
              <Plus aria-hidden="true" />{t('dbq.conn.new')}
            </Button>
          )}
        </div>
      )}

      {!noTeam && load.state === 'ok' && list.length > 0 && (
        <SearchableSelect value={value ?? ''} options={opts} wrapLabels disabled={disabled} ariaLabel={t('dbq.conn.pickLabel')}
          ariaDescribedBy={error ? errorId : undefined} ariaInvalid={!!error}
          placeholder={t('dbq.conn.pickPlaceholder')}
          onChange={(v) => {
            const p = list.find((x) => String(x.id) === String(v)) || null
            onChange?.(p ? p.id : null, p)
          }} />
      )}

      {selected && (
        <div data-slot="dbq-conn-selected" data-id={selected.id} className="flex min-w-0 flex-col gap-1 rounded-md border bg-muted/30 p-2.5">
          <div className="flex flex-wrap items-center gap-1.5">
            <DbTypeBadge type={selected.db_type} /><HealthBadge profile={selected} />
          </div>
          <span className="text-sm font-semibold [overflow-wrap:anywhere]">{selected.name}</span>
          <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{serverLabel(selected)}</span>
        </div>
      )}

      {missing && (
        <p data-slot="dbq-conn-missing" className="m-0 inline-flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-300">
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('dbq.conn.pickMissing')}
        </p>
      )}

      {!noTeam && (
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setManager('list')} className="pointer-coarse:h-10" data-slot="dbq-conn-manage">
            <DatabaseZap aria-hidden="true" />{canManage ? t('dbq.conn.manage') : t('dbq.conn.view')}
          </Button>
          {canManage && list.length > 0 && (
            <Button type="button" variant="ghost" size="sm" onClick={() => setManager('new')} disabled={disabled} className={cn('pointer-coarse:h-10')}>
              <Plus aria-hidden="true" />{t('dbq.conn.new')}
            </Button>
          )}
        </div>
      )}

      {error && <FieldError id={errorId}>{error}</FieldError>}

      {manager && (
        <ConnectionsManager open onOpenChange={(o) => { if (!o) setManager(null) }} teamId={teamId} teams={teamList}
          canEdit={canManage} initialView={manager} onChanged={onManagerChanged} />
      )}
    </div>
  )
}
