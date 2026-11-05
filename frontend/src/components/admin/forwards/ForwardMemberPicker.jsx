import { useId, useMemo, useState } from 'react'
import { MailX, RefreshCw, Search, UserRound, X } from 'lucide-react'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Skeleton } from '@/components/shadcn/skeleton'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { sameUser } from '../../../utils/userRef.js'
import { cn } from '@/lib/utils'
import { WRAP_BADGE } from './ForwardParts.jsx'
import { filterMemberOptions, plural } from './alertForwardModel.js'

const CHIP_REMOVE = 'relative size-5 rounded-full text-muted-foreground hover:text-destructive pointer-coarse:after:absolute pointer-coarse:after:-inset-2.5 pointer-coarse:after:content-[\'\']'

/**
 * İletim alıcısı — "Seçilen üyeler" seçicisi: alıcı takımın AKTİF üyeleri (`/teams/{id}/members`), Türkçe katlamalı arama
 * (ad, kullanıcı adı, unvan, birim, e-posta), onay kutulu liste (ad + unvan · birim SARAR, kesilmez), seçilenler çip,
 * sayaç "N / sınır". Düzenlemede kayıtlı ama artık pasif / takımdan ayrılmış üye çipte rozetiyle kalır ve kaldırılabilir
 * (bildirim almaz). E-postası olmayan seçili üye için "yalnız push alır" notu. Kimlik opaktır — asla sayıya çevrilmez.
 *
 * Props: `status` (loading|ready|error), `items` (memberOptions), `message`, `onRetry`, `value` (kimlikler), `onChange(next)`,
 * `limit`, `known` (kayıtlı üyeler `[{userId, displayName, active, inTeam}]` — listede olmayanların adı), `t`, `invalid`,
 * `describedBy`.
 */
export default function ForwardMemberPicker({ status, items = [], message, onRetry, value = [], onChange, limit = 50, known = [], t, invalid, describedBy }) {
  const [q, setQ] = useState('')
  const base = useId()
  const shown = useMemo(() => filterMemberOptions(items, q), [items, q])
  const has = (id) => value.some((v) => sameUser(v, id))
  const full = value.length >= limit
  const toggle = (id, on) => {
    if (on) { if (!has(id) && !full) onChange([...value, id]) } else onChange(value.filter((v) => !sameUser(v, id)))
  }

  // Seçilenlerin görünümü: listeden ad; listede yoksa kayıtlı üyeden (pasif / ayrılmış) — hiçbiri yoksa "bilinmeyen kişi"
  const chips = value.map((id) => {
    const opt = items.find((m) => sameUser(m.userId, id))
    const old = known.find((m) => sameUser(m.userId, id))
    return {
      id,
      name: opt?.displayName || old?.displayName || t('fwd.unknownPerson'),
      noEmail: !!opt && !opt.email,
      passive: !opt && old?.active === false,
      left: !opt && status === 'ready' && old?.active !== false,
    }
  })
  const noEmailCount = chips.filter((c) => c.noEmail).length

  return (
    <div className="flex min-w-0 flex-col gap-2" data-slot="fwd-member-picker" data-status={status}>
      {status === 'loading' ? (
        <div className="flex flex-col gap-2" role="status" aria-label={t('fwd.mem.loading')}>
          <Skeleton className="h-10 w-full" />
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-12 w-full" />)}
        </div>
      ) : status === 'error' ? (
        <AlertBanner tone="danger" className="mb-0"
          actions={<Button type="button" variant="secondary" size="sm" className="gap-1.5 pointer-coarse:h-10" onClick={onRetry}><RefreshCw aria-hidden="true" />{t('fwd.retry')}</Button>}>
          {message || t('fwd.mem.loadFailed')}
        </AlertBanner>
      ) : items.length === 0 ? (
        <StatusBlock tone="neutral" description={t('fwd.mem.none')} className="my-0 py-4 md:py-4" />
      ) : (
        <>
          <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
            <InputGroup className="h-10 min-w-0 flex-[1_1_14rem]">
              <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('fwd.mem.search')} aria-label={t('fwd.mem.search')}
                data-slot="fwd-member-search" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
            <span className={cn('text-xs font-semibold tabular-nums', full ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}
              data-slot="fwd-member-count" aria-live="polite">
              {t('fwd.mem.count', value.length, limit)}
            </span>
          </div>
          <ul className="m-0 flex max-h-72 min-w-0 list-none flex-col gap-0.5 overflow-y-auto rounded-lg border p-1" data-slot="fwd-member-list"
            aria-describedby={describedBy} aria-invalid={invalid || undefined}>
            {shown.map((m, i) => {
              const on = has(m.userId)
              const cid = `${base}-${i}`
              const sub = [m.title, m.department].filter(Boolean).join(' · ')
              return (
                <li key={String(m.userId)} className="min-w-0">
                  <label htmlFor={cid} data-slot="fwd-member-item"
                    className={cn('flex min-h-10 min-w-0 cursor-pointer items-start gap-2.5 rounded-md px-2 py-2 hover:bg-muted',
                      on && 'bg-primary/5', !on && full && 'cursor-not-allowed opacity-60')}>
                    <Checkbox id={cid} checked={on} disabled={!on && full} onCheckedChange={(v) => toggle(m.userId, v === true)} className="mt-0.5" />
                    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                      <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm font-medium">
                        <span className="min-w-0 [overflow-wrap:anywhere]">{m.displayName}</span>
                        {!m.email && (
                          <Badge variant="warning" className={cn(WRAP_BADGE, 'gap-1 font-normal')} data-slot="fwd-member-no-email">
                            <MailX aria-hidden="true" className="size-3 shrink-0" />{t('fwd.mem.noEmail')}
                          </Badge>
                        )}
                      </span>
                      {sub && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{sub}</span>}
                    </span>
                  </label>
                </li>
              )
            })}
            {shown.length === 0 && <li className="px-2 py-3 text-sm text-muted-foreground">{t('fwd.mem.noMatch')}</li>}
          </ul>
          {full && <p className="m-0 text-xs text-amber-700 dark:text-amber-300" data-slot="fwd-member-full">{t('fwd.mem.full', limit)}</p>}
        </>
      )}

      {chips.length === 0 ? (
        <p className="m-0 text-xs text-muted-foreground" data-slot="fwd-member-none-picked">{t('fwd.mem.nothingPicked')}</p>
      ) : (
        <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0" data-slot="fwd-member-chips">
          {chips.map((c) => (
            <li key={String(c.id)} className="min-w-0 max-w-full">
              <Badge variant="outline" data-slot="fwd-member-chip" className={cn(WRAP_BADGE, 'gap-1 py-1 pr-1 pl-2 font-normal',
                (c.passive || c.left) && 'text-muted-foreground')}>
                <UserRound aria-hidden="true" className="size-3.5 shrink-0" />
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  {c.name}
                  {c.passive && <span className="font-semibold whitespace-nowrap text-destructive"> · {t('fwd.memberPassive')}</span>}
                  {c.left && <span className="font-semibold whitespace-nowrap text-amber-700 dark:text-amber-300"> · {t('fwd.memberLeft')}</span>}
                </span>
                <Button type="button" variant="ghost" size="icon-xs" className={CHIP_REMOVE} aria-label={t('fwd.mem.remove', c.name)}
                  onClick={() => toggle(c.id, false)}>
                  <X aria-hidden="true" className="size-3" />
                </Button>
              </Badge>
            </li>
          ))}
        </ul>
      )}
      {noEmailCount > 0 && <p className="m-0 text-xs text-muted-foreground" data-slot="fwd-member-no-email-note">{plural(t, 'fwd.mem.noEmailNote', noEmailCount)}</p>}
      {chips.some((c) => c.passive || c.left) && (
        <p className="m-0 text-xs text-amber-700 dark:text-amber-300" data-slot="fwd-member-stale-note">{t('fwd.mem.staleNote')}</p>
      )}
    </div>
  )
}
