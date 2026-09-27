import { Fragment, useRef, useState } from 'react'
import { ChevronDown, Info, Lock } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Switch } from '@/components/shadcn/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import HintPopover from '../../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'
import { cellKey } from './permissionModel.js'

/**
 * Masaüstü / tablet matrisi — kaynaklar (satır) × rol ve tür (sütun). shadcn Table kendi kabında iki eksende kayar:
 * iki başlık satırı üstte, kaynak sütunu solda YAPIŞKAN. Gruplar açılır başlık satırlarıdır (disclosure düğmesi,
 * `aria-expanded`); varsayılan hepsi KAPALI (kullanıcı kararı 2026-09-26).
 *
 * <p>Klavye (ızgara deseni): matriste TEK sekme durağı var (gezici tabindex) — ok tuşları hücreler arasında gezer,
 * Home/End satır başı/sonu, Ctrl+Home/End ilk/son hücre, Boşluk anahtarı çevirir. Kilitli (ADMIN) ve geçersiz (—)
 * hücreler atlanır. Değişen hücrede nokta + ekran okuyucu metni.
 */
export default function MatrixTable({
  groups, openGroups, onToggleGroup, roles, kinds, isOn, pending, failures, canEdit, busy, onToggle, focusRole,
}) {
  const t = useT()
  const frameRef = useRef(null)
  const [active, setActive] = useState(null)
  const cols = roles.flatMap((role) => kinds.map((action) => ({ role, action })))
  const colCount = 1 + cols.length
  const kindLabel = (a) => t(a.labelKey)

  // Gezilebilir hücre konumları (açık grupların satırları) — gezici tabindex'in durağı bunlardan biri.
  const positions = []
  let r = 0
  const rowIndex = new Map()
  for (const [groupName, items] of groups) {
    if (!openGroups.has(groupName)) continue
    for (const item of items) {
      rowIndex.set(item.resource_key, r)
      cols.forEach(({ role, action }, c) => {
        if (!role.locked && item.actions.includes(action.key)) positions.push({ r, c })
      })
      r++
    }
  }
  const stop = positions.find((p) => active && p.r === active.r && p.c === active.c) ?? positions[0] ?? null

  function focusCell(nr, nc) {
    // Ref çerçevede: shadcn Table forwardRef değil (React 18 ref'i düşürür).
    const el = frameRef.current?.querySelector(`[data-nav-r="${nr}"][data-nav-c="${nc}"]`)
    if (el && !el.disabled) { el.focus(); return true }
    return false
  }

  function onKeyDown(e) {
    const el = e.target
    if (!el?.dataset || el.dataset.navR == null) return
    const cr = Number(el.dataset.navR)
    const cc = Number(el.dataset.navC)
    const inRow = positions.filter((p) => p.r === cr)
    const inCol = positions.filter((p) => p.c === cc)
    let target = null
    switch (e.key) {
      case 'ArrowRight': target = inRow.find((p) => p.c > cc); break
      case 'ArrowLeft': target = [...inRow].reverse().find((p) => p.c < cc); break
      case 'ArrowDown': target = inCol.find((p) => p.r > cr); break
      case 'ArrowUp': target = [...inCol].reverse().find((p) => p.r < cr); break
      case 'Home': target = e.ctrlKey ? positions[0] : inRow[0]; break
      case 'End': target = e.ctrlKey ? positions[positions.length - 1] : inRow[inRow.length - 1]; break
      default: return
    }
    e.preventDefault()
    if (target) focusCell(target.r, target.c)
  }

  return (
    <div ref={frameRef} data-slot="perm-matrix-frame"
      className="overflow-hidden rounded-xl border bg-card shadow-xs [&>[data-slot=table-container]]:max-h-[calc(100dvh-220px)] [&>[data-slot=table-container]]:overscroll-x-contain">
      <Table data-testid="perm-matrix" aria-label={t('perm.title')} onKeyDown={onKeyDown}
        className="border-separate border-spacing-0">
        <TableHeader className="[&_tr]:border-b-0">
          <TableRow className="hover:bg-transparent">
            <TableHead rowSpan={2}
              className="sticky top-0 left-0 z-[4] h-11 min-w-[200px] border-r border-b bg-muted px-3 text-xs font-semibold tracking-wide text-muted-foreground uppercase lg:min-w-[240px]">
              {t('perm.feature')}
            </TableHead>
            {roles.map((role) => (
              <TableHead key={role.key} colSpan={kinds.length} data-role={role.key} data-highlight={focusRole === role.key || undefined}
                className={cn('sticky top-0 z-[3] h-11 border-b border-l bg-muted px-2 text-center',
                  focusRole === role.key && 'shadow-[inset_0_-2px_0_var(--color-primary)]')}>
                <span className="inline-flex items-center justify-center gap-1.5">
                  <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', role.dot)} />
                  <span className="text-xs font-bold tracking-wider">{role.key}</span>
                  {role.locked && <Lock aria-hidden="true" className="size-3 text-muted-foreground" />}
                  <HintPopover content={t(role.hintKey)} side="bottom" align="center" aria-label={t('perm.roleInfo', role.key)}
                    triggerClassName="size-7 min-h-7 text-muted-foreground hover:text-foreground">
                    <Info aria-hidden="true" className="size-3.5" />
                  </HintPopover>
                </span>
              </TableHead>
            ))}
          </TableRow>
          <TableRow className="hover:bg-transparent">
            {cols.map(({ role, action }, i) => (
              <TableHead key={role.key + '-' + action.key} title={kindLabel(action)}
                className={cn('sticky top-11 z-[2] h-9 min-w-16 border-b bg-card px-1 text-center text-[11px] font-semibold tracking-wide text-muted-foreground uppercase',
                  (i % kinds.length === 0) && 'border-l',
                  focusRole === role.key && 'bg-accent text-foreground')}>
                <span className="inline-flex items-center justify-center gap-1">
                  <action.Icon aria-hidden="true" className="size-3 shrink-0" />
                  <span>{t(action.shortKey)}</span>
                </span>
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map(([groupName, items]) => {
            const open = openGroups.has(groupName)
            const groupPending = items.reduce((n, item) =>
              n + cols.filter(({ role, action }) => pending.has(cellKey(role.key, item.resource_key, action.key))).length, 0)
            return (
              <Fragment key={groupName}>
                <TableRow data-group={groupName} data-open={open ? 'true' : undefined} className="hover:bg-transparent">
                  <TableCell colSpan={colCount} className="border-b bg-muted/50 p-0">
                    <Button type="button" variant="ghost" aria-expanded={open} onClick={() => onToggleGroup(groupName)}
                      className="sticky left-0 h-10 max-w-full justify-start gap-2 rounded-none px-3 font-semibold hover:bg-transparent">
                      <ChevronDown aria-hidden="true"
                        className={cn('transition-transform motion-reduce:transition-none', !open && '-rotate-90')} />
                      <span className="truncate">{t(`perm.group.${groupName}`)}</span>
                      <Badge variant="secondary" className="rounded-full tabular-nums">{items.length}</Badge>
                      {groupPending > 0 && (
                        <Badge variant="warning" className="rounded-full tabular-nums">{t('perm.groupPending', groupPending)}</Badge>
                      )}
                    </Button>
                  </TableCell>
                </TableRow>
                {open && items.map((item) => {
                  const ri = rowIndex.get(item.resource_key)
                  return (
                    <TableRow key={item.resource_key} data-resource={item.resource_key} className="group/row hover:bg-transparent">
                      <TableCell
                        className="sticky left-0 z-[1] max-w-[300px] border-r border-b bg-card px-3 py-2.5 text-left whitespace-normal group-hover/row:bg-muted">
                        <span className="flex flex-wrap items-center gap-1.5">
                          <strong className="font-mono text-[0.82rem] font-semibold break-all">{item.resource_key}</strong>
                          {item.sensitive.length > 0 && <SensitiveBadge t={t} />}
                        </span>
                        <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">{t(`perm.res.${item.resource_key}`)}</span>
                      </TableCell>
                      {cols.map(({ role, action }, ci) => {
                        const cls = cn('relative border-b px-2 py-2.5 text-center group-hover/row:bg-muted',
                          (ci % kinds.length === 0) && 'border-l',
                          focusRole === role.key && 'bg-primary/[0.05]')
                        const key = role.key + '-' + action.key
                        if (!item.actions.includes(action.key)) {
                          return (
                            <TableCell key={key} data-cell="na" className={cn(cls, 'text-muted-foreground/60')}>
                              <span aria-hidden="true">—</span><span className="sr-only">{t('perm.legendNa')}</span>
                            </TableCell>
                          )
                        }
                        if (role.locked) {
                          return (
                            <TableCell key={key} data-cell="locked" className={cls}>
                              <LockedBadge />
                              {/* Görsel yalnız ikon; metin ağaçta (sr-only) — hücre ekran okuyucuda BOŞ okunmasın (R16). */}
                              <span className="sr-only">{t('perm.adminLocked')}</span>
                            </TableCell>
                          )
                        }
                        const k = cellKey(role.key, item.resource_key, action.key)
                        const changed = pending.has(k)
                        const failed = failures.has(k)
                        const isStop = stop && stop.r === ri && stop.c === ci
                        return (
                          <TableCell key={key} data-cell="toggle" data-changed={changed || undefined} data-failed={failed || undefined} className={cls}>
                            <Switch
                              checked={isOn(role.key, item.resource_key, action.key)}
                              disabled={!canEdit || busy}
                              onCheckedChange={() => onToggle(role.key, item, action.key)}
                              aria-label={t('perm.cellLabel', role.key, item.resource_key, kindLabel(action))}
                              data-nav-r={ri} data-nav-c={ci}
                              tabIndex={isStop ? 0 : -1}
                              onFocus={() => setActive({ r: ri, c: ci })}
                              // Salt okunurda SOLUK değil: durum okunabilir kalsın. after: dokunma alanı ≥40 px.
                              className="relative after:absolute after:-inset-[11px] disabled:cursor-default disabled:opacity-100"
                            />
                            {changed && <ChangeDot failed={failed} t={t} />}
                          </TableCell>
                        )
                      })}
                    </TableRow>
                  )
                })}
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

/** Kaydedilmemiş (amber) / kaydedilemedi (kırmızı) işareti — görsel nokta + ekran okuyucu metni. */
export function ChangeDot({ failed, t, className }) {
  return (
    <>
      <span aria-hidden="true" data-slot="perm-change-dot"
        className={cn('absolute top-1.5 right-1.5 size-2 rounded-full ring-2 ring-card', failed ? 'bg-destructive' : 'bg-amber-500', className)} />
      <span className="sr-only">{failed ? t('perm.failed') : t('perm.changed')}</span>
    </>
  )
}

/** "Hassas" rozeti — açıklaması dokunmatikte de açılır (HintPopover). */
export function SensitiveBadge({ t }) {
  return (
    <HintPopover content={t('perm.sensitiveHint')} aria-label={`${t('perm.sensitiveBadge')} — ${t('perm.sensitiveHint')}`}>
      <Badge variant="warning" className="rounded-full">{t('perm.sensitiveBadge')}</Badge>
    </HintPopover>
  )
}

/** ADMIN kilit rozeti — ikon dekoratif. */
export function LockedBadge() {
  return (
    <span data-slot="perm-locked-badge" aria-hidden="true"
      className="inline-flex size-6 items-center justify-center rounded-full bg-red-600/10 text-red-600 dark:bg-red-500/20 dark:text-red-400">
      <Lock size={11} aria-hidden="true" />
    </span>
  )
}
