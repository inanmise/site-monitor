import { useId } from 'react'
import { ChevronDown, Lock } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { actionOf, cellKey } from './permissionModel.js'
import { ChangeDot, SensitiveBadge } from './MatrixTable.jsx'

/**
 * Telefon görünümü (<768 px): 13 sütunlu matris telefona sığmaz — üstte ROL SEÇİCİ (ToggleGroup, tek seçim), altında
 * gruplar (Collapsible, varsayılan KAPALI) ve her kaynak için etiketli anahtar satırları (Görüntüleme / Düzenleme /
 * Çalıştırma — yalnız kaynağın desteklediği türler). Satır 40 px; etikete dokunmak anahtarı çevirir.
 */
export function RolePicker({ roles, value, onChange }) {
  const t = useT()
  const labelId = useId()
  return (
    <div data-slot="perm-role-picker" className="flex flex-col gap-1.5">
      <span id={labelId} className="text-xs font-semibold text-muted-foreground">{t('perm.rolePicker')}</span>
      {/* Tek seçim: Radix öğeleri role="radio" → kap radiogroup (Radix kök role vermez) */}
      <ToggleGroup type="single" variant="outline" value={value} role="radiogroup" aria-labelledby={labelId}
        onValueChange={(v) => { if (v) onChange(v) }}
        className="flex w-full">
        {/* flex-auto: genişlik içeriğe göre + kalan eşit — "TEAM_ADMIN" eşit sütunda kırpılıyordu (390 px) */}
        {roles.map((r) => (
          <ToggleGroupItem key={r.key} value={r.key}
            className="h-10 min-w-0 flex-auto px-1.5 text-[11px] font-semibold tracking-tight data-[state=on]:bg-primary data-[state=on]:text-primary-foreground">
            <span className="truncate">{r.key}</span>
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )
}

export default function MobileRoleList({
  groups, openGroups, onToggleGroup, role, kinds, isOn, pending, failures, canEdit, busy, onToggle,
}) {
  const t = useT()
  const uid = useId()
  return (
    <div data-slot="perm-mobile-list" className="flex flex-col gap-2">
      {groups.map(([groupName, items]) => {
        const open = openGroups.has(groupName)
        const groupPending = items.reduce((n, item) =>
          n + kinds.filter((a) => pending.has(cellKey(role.key, item.resource_key, a))).length, 0)
        return (
          <Collapsible key={groupName} open={open} onOpenChange={() => onToggleGroup(groupName)} data-group={groupName}
            className="min-w-0 rounded-xl border bg-card shadow-xs">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost"
                className="h-auto min-h-11 w-full justify-start gap-2 rounded-xl px-3 py-2 text-left font-semibold whitespace-normal">
                <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
                <span className="min-w-0 flex-1 break-words">{t(`perm.group.${groupName}`)}</span>
                {groupPending > 0 && <Badge variant="warning" className="rounded-full tabular-nums">{t('perm.groupPending', groupPending)}</Badge>}
                <Badge variant="secondary" className="rounded-full tabular-nums">{items.length}</Badge>
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent>
              <ul className="flex flex-col divide-y border-t">
                {items.map((item) => (
                  <li key={item.resource_key} data-slot="perm-mobile-card" data-resource={item.resource_key} className="min-w-0 px-3 py-3">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <strong className="font-mono text-[0.82rem] font-semibold break-all">{item.resource_key}</strong>
                      {item.sensitive.length > 0 && <SensitiveBadge t={t} />}
                    </div>
                    <p className="mt-0.5 text-xs leading-snug text-muted-foreground">{t(`perm.res.${item.resource_key}`)}</p>
                    <div className="mt-2 flex flex-col">
                      {item.actions.filter((a) => kinds.includes(a)).map((a) => {
                        const action = actionOf(a)
                        const id = `${uid}-${item.resource_key}-${a}`
                        const k = cellKey(role.key, item.resource_key, a)
                        const changed = pending.has(k)
                        return (
                          <div key={a} data-slot="perm-mobile-row" data-cell={role.locked ? 'locked' : 'toggle'}
                            className="relative flex min-h-10 items-center justify-between gap-3 rounded-md">
                            <Label htmlFor={role.locked ? undefined : id} className="min-h-10 min-w-0 flex-1 py-2 text-sm font-medium">
                              <action.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                              {t(action.labelKey)}
                              {changed && (
                                <span className="relative inline-flex size-3">
                                  <ChangeDot failed={failures.has(k)} t={t} className="top-0.5 right-0.5" />
                                </span>
                              )}
                            </Label>
                            {role.locked ? (
                              <span className="inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                                <Lock aria-hidden="true" className="size-3.5" />{t('perm.alwaysGranted')}
                              </span>
                            ) : (
                              <Switch id={id}
                                checked={isOn(role.key, item.resource_key, a)}
                                disabled={!canEdit || busy}
                                onCheckedChange={() => onToggle(role.key, item, a)}
                                aria-label={t('perm.cellLabel', role.key, item.resource_key, t(action.labelKey))}
                                className="relative after:absolute after:-inset-[11px] disabled:cursor-default disabled:opacity-100" />
                            )}
                          </div>
                        )
                      })}
                    </div>
                  </li>
                ))}
              </ul>
            </CollapsibleContent>
          </Collapsible>
        )
      })}
    </div>
  )
}
