import { useId, useMemo, useState } from 'react'
import { Check, Search, SearchX, ShieldCheck, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { ACTIONS } from '../permissions/permissionModel.js'
import { SectionCard, SectionEmpty, SectionError, SectionSkeleton } from './parts.jsx'
import { filterPermissions } from './userDetailModel.js'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

const actionMeta = (key) => ACTIONS.find((a) => a.key === key) ?? ACTIONS[0]

/** Kaynak etiketi: `perm.res.<anahtar>` çevirisi, yoksa ham anahtar. */
function resLabel(t, key) {
  const k = `perm.res.${key}`
  const s = t(k)
  return s === k ? key : s
}

/**
 * Yetkiler (salt okunur) — kullanıcının SİSTEM ROLÜNDEN gelen etkin yetkiler, Yetki Yönetimi'nin kendi modeliyle
 * (aynı kaynak birleştirme, grup sırası, eylem ikonları: görüntüle / düzenle / çalıştır). Arama (anahtar, açıklama, grup)
 * + "Yalnız verilenler" anahtarı (varsayılan açık). İzin verilmeyen eylem üstü çizili soluk hap olarak görünür —
 * renk tek başına anlam taşımaz (✓ / ✕ ikonu + erişilebilir ad). Yalnız global yönetici (eski panelle aynı kapı).
 */
export default function PermissionsTab({ role, section, perms }) {
  const t = useT()
  const [query, setQuery] = useState('')
  const [onlyGranted, setOnlyGranted] = useState(true)
  const switchId = useId()
  const groups = useMemo(() => filterPermissions(perms, { query, onlyGranted, t }), [perms, query, onlyGranted, t])

  if (section.status === 'error' && !perms) return <SectionError title={t('ud.errPerms')} error={section.error} onRetry={section.reload} />
  if (!perms) return <SectionCard icon={ShieldCheck} title={t('ud.tabPermissions')}><SectionSkeleton rows={4} /></SectionCard>

  return (
    <div data-slot="ud-permissions" className="flex min-w-0 flex-col gap-3">
      <AlertBanner tone="info" icon={ShieldCheck} className="mb-0" title={t('ud.permSummary', perms.granted, perms.total)}>
        {perms.locked ? t('ud.permAdminAll') : t('ud.permIntro', role)}
      </AlertBanner>

      <div data-slot="ud-perm-toolbar" className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <InputGroup className="h-10 w-full sm:h-9 sm:max-w-xs">
          <InputGroupInput type="search" value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder={t('perm.searchPlaceholder')} aria-label={t('ud.permSearchLabel')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {query && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-sm" onClick={() => setQuery('')} aria-label={t('ud.clearSearch')}><X /></InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        <div className="flex min-h-10 items-center gap-2.5 sm:min-h-0">
          <Switch id={switchId} checked={onlyGranted} onCheckedChange={setOnlyGranted} />
          <Label htmlFor={switchId} className="cursor-pointer text-sm font-normal">{t('ud.permOnlyGranted')}</Label>
        </div>
      </div>

      {groups.length === 0 ? (
        <SectionEmpty icon={SearchX} title={t('ud.permNone')} description={t('ud.permNoneHint')} />
      ) : groups.map(([group, rows]) => {
        const granted = rows.reduce((n, r) => n + r.actions.filter((a) => a.granted).length, 0)
        return (
          <SectionCard key={group} data-group={group} title={t(`perm.group.${group}`)} count={granted} bodyClassName="px-0 py-0">
            <ul className="m-0 flex list-none flex-col divide-y p-0">
              {rows.map((r) => (
                <li key={r.resource_key} data-resource={r.resource_key}
                  className="flex min-w-0 flex-col gap-2 px-4 py-2.5 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex min-w-0 flex-col">
                    <span className="text-sm font-medium [overflow-wrap:anywhere]">{resLabel(t, r.resource_key)}</span>
                    <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.resource_key}</span>
                  </div>
                  <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" aria-label={t('ud.permActionsOf', resLabel(t, r.resource_key))}>
                    {r.actions.map((a) => {
                      const meta = actionMeta(a.key)
                      const Icon = meta.Icon
                      const label = t(meta.labelKey)
                      return (
                        <li key={a.key} data-action={a.key} data-granted={a.granted ? 'true' : 'false'}
                          aria-label={a.granted ? t('ud.permGranted', label) : t('ud.permDenied', label)}
                          className={cn('inline-flex h-7 items-center gap-1 rounded-full border px-2.5 text-xs font-medium',
                            a.granted ? 'border-success/30 bg-success/10 text-success' : 'border-dashed text-muted-foreground line-through decoration-muted-foreground/60')}>
                          <Icon aria-hidden="true" className="size-3.5" />
                          <span aria-hidden="true">{t(meta.shortKey)}</span>
                          {a.granted ? <Check aria-hidden="true" className="size-3.5" /> : <X aria-hidden="true" className="size-3.5" />}
                        </li>
                      )
                    })}
                  </ul>
                </li>
              ))}
            </ul>
          </SectionCard>
        )
      })}
    </div>
  )
}
