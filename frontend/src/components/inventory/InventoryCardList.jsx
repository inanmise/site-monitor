import { useState } from 'react'
import { Play, Inbox } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { ActiveBadge, CertCell, ContactsCell, DeletedBadge, ExpiryCell, GroupChip, StatusDot, TagChip, TierBadge, platformLabel, rowMenuItems, tagList } from './InventoryTable.jsx'
import ManualCertBadge from '../manualcert/ManualCertBadge.jsx'
import { isManualCert } from '../manualcert/manualCertModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

const stop = (e) => e.stopPropagation()

/**
 * Telefon kart listesi (2026-09-27, mweb kuralı): 390 px'te tablo yerine kart — yatay kaydırma YOK, her eylem ulaşılır.
 * Kart başlığı: durum noktası + alan adı (çekmece) + port + katman; altında sertifika rozeti + göreli bitiş; etiket/değer
 * satırları (takım, platform, sorumlular, grup/etiketler, son kontrol); alt satır aktif anahtarı + kontrol et + menü.
 * Salt okunur (başka takımın) kayıtta kutu/anahtar/kontrol et YOK, rozet var, menüde yalnız "Göster". Sol renk şeridi YOK.
 *
 * Çizim shadcn: Card, Checkbox, Switch, Badge, Button, ui/KebabMenu. Test kancası: kart `[data-inv-card]`, liste `data-slot="inv-cards"`.
 */
export default function InventoryCardList({
  rows, canManage, canEditRow = () => canManage, canManageRow = () => true, isAdmin, teamsCount, teamMap = {}, selected, onToggle,
  onShow, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge, onDiagnose, onCheckNow, onInline, onTagClick, onGroupClick,
  platformNames = {}, onClearFilters = null,
}) {
  const t = useT()
  // Meşgul KÜMESİ (useRunningChecks deseni): tek yuvada A sürerken B'ye basınca A'nın spinner'ı sönüyor,
  // önce biten diğerinin kilidini de açıyordu. Her kontrol yalnız KENDİ alan adını ekler/siler.
  const [busy, setBusy] = useState(() => new Set())
  async function checkNow(r) {
    setBusy((prev) => { const next = new Set(prev); next.add(r.domain); return next })
    try { await onCheckNow(r) } finally { setBusy((prev) => { const next = new Set(prev); next.delete(r.domain); return next }) }
  }
  if (rows.length === 0) {
    return (
      <StatusBlock tone="neutral" icon={Inbox} title={t('inv.noMatch')} description={t('empty.hintFilter')}
        actions={onClearFilters ? <Button type="button" variant="secondary" size="sm" onClick={onClearFilters}>{t('inv.filterClear')}</Button> : null} />
    )
  }
  const DT = 'text-[.78em] font-semibold tracking-wide text-muted-foreground uppercase'
  const DD = 'm-0 min-w-0 text-[.9em]'
  return (
    <ul data-slot="inv-cards" aria-label={t('inv.cardList')} className="m-0 flex list-none flex-col gap-2 p-0">
      {rows.map((r) => {
        const del = !!r.deleted_at
        const ro = !canManageRow(r)
        const editable = !ro && canEditRow(r)
        const port = r.port ?? 443
        const isSel = selected.has(r.id)
        const team = r.team_name || teamMap[String(r.team_id)]
        const menu = ro
          ? rowMenuItems({ r, t, ro, onShow })
          : rowMenuItems({ r, t, ro, isAdmin, canManage, canEditRow, teamsCount, onShow, onCheckNow: checkNow, onDiagnose, onEdit, onDuplicate, onTransfer, onDelete, onRestore, onPurge })
        return (
          <li key={r.id}>
            <Card data-inv-card={r.domain} data-state={isSel ? 'selected' : undefined} data-deleted={del ? 'true' : undefined}
              className={cn('gap-2.5 rounded-[10px] px-3 py-3 shadow-none', isSel && 'border-primary bg-primary/5', del && 'opacity-70')}>
              <div className="flex items-start gap-2.5">
                {canManage && !del && !ro && (
                  <Checkbox checked={isSel} onCheckedChange={() => onToggle(r.id)} className="mt-1.5 size-5" aria-label={t('bulk.selectOneFor', r.domain)} />
                )}
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
                    <StatusDot r={r} />
                    <Button type="button" variant="link" data-inv-domain="true" onClick={() => onShow(r)}
                      // `shrink`: Button tabanı `shrink-0` taşır → uzun alan adı satırı tek parça kalıp kartı telefonda 260 px
                      // taşırıyordu (e2e responsive domains@phone/tablet, 2026-09-27); break-all ancak daralabilirse işler.
                      className={cn('h-auto min-w-0 shrink p-0 text-left text-[1.02em] font-bold break-all whitespace-normal text-foreground', del && 'line-through')}>
                      {r.domain}
                    </Button>
                    {port !== 443 && <Badge variant="outline" className="px-1.5 font-mono text-[.72em] text-muted-foreground">:{port}</Badge>}
                    {isManualCert(r) && <ManualCertBadge version={r.manual_version ?? null} uploadedAt={r.manual_uploaded_at ?? null} rowLabel={r.domain} />}
                    <TierBadge tier={r.tier} />
                  </div>
                  <div className="mt-1.5 flex flex-wrap items-center gap-2">
                    <CertCell r={r} t={t} />
                    <ExpiryCell r={r} t={t} showDate={false} className="text-[.9em]" />
                  </div>
                </div>
                <div className="shrink-0" onClick={stop}>
                  <KebabMenu label={t('inv.colActions')} rowLabel={r.domain} items={menu} placement="right" />
                </div>
              </div>

              <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1.5">
                <dt className={DT}>{t('inv.colTeam')}</dt>
                <dd className={DD}>{team ? <TeamBadge teamId={r.team_id} teamName={team} /> : <span className="text-amber-700 dark:text-amber-400">{t('inv.hy.no_team')}</span>}</dd>
                <dt className={DT}>{t('inv.colPlatform')}</dt>
                <dd className={DD}>{r.platform ? <>{platformLabel(r.platform, platformNames)}{r.platform_detail ? <span className="text-muted-foreground"> · {r.platform_detail}</span> : null}</> : <span className="text-muted-foreground">—</span>}</dd>
                <dt className={DT}>{t('inv.colContacts')}</dt>
                <dd className={DD}><ContactsCell r={r} t={t} /></dd>
                {(r.group_name || r.tags) && (
                  <>
                    <dt className={DT}>{t('inv.colGroup')}</dt>
                    <dd className={cn(DD, 'flex flex-wrap items-center gap-1')}>
                      {r.group_name && <GroupChip name={r.group_name} onClick={() => onGroupClick?.(r.group_name)} />}
                      {tagList(r).map((tag) => <TagChip key={tag} tag={tag} onClick={() => onTagClick?.(tag)} />)}
                    </dd>
                  </>
                )}
                <dt className={DT}>{t('inv.colLastCheck')}</dt>
                <dd className={cn(DD, 'text-muted-foreground')} title={r.cert_checked_at ? formatDate(r.cert_checked_at) : undefined}>
                  {r.cert_checked_at ? (relativeTime(r.cert_checked_at, t) || formatDate(r.cert_checked_at)) : t('inv.certNever')}
                </dd>
              </dl>

              {/* Alt satır: anahtar + rozet solda; "Kontrol et" (telefonda yalnız ikon, ad aria-label'da) + Düzenle sağda — tek satır */}
              <div className="flex flex-wrap items-center gap-2 border-t pt-2.5">
                {del ? <DeletedBadge r={r} t={t} /> : ro ? (
                  <ReadOnlyBadge compact teamId={r.team_id} teamName={team} />
                ) : canManage ? (
                  <label className="inline-flex min-h-10 cursor-pointer items-center gap-2" title={t('inv.inlineEditTip')}>
                    <Switch checked={!!r.active} onCheckedChange={(on) => onInline(r, { active: on })} aria-label={`${r.domain} — ${t('inv.colActive')}`} />
                    <ActiveBadge r={r} t={t} />
                  </label>
                ) : <ActiveBadge r={r} t={t} />}
                {!del && !ro && (
                  <Button type="button" variant="outline" size="sm" className="ml-auto size-10 sm:w-auto" disabled={busy.has(r.domain)} aria-busy={busy.has(r.domain) || undefined}
                    onClick={() => checkNow(r)} aria-label={`${r.domain} — ${t('inv.checkNow')}`}>
                    {busy.has(r.domain) ? <Spinner size={12} inline decorative /> : <Play size={12} aria-hidden="true" />}<span className="hidden sm:inline">{t('inv.checkNow')}</span>
                  </Button>
                )}
                {!del && !ro && editable && (
                  <Button type="button" variant="secondary" size="sm" className="h-10" onClick={() => onEdit(r)}
                    aria-label={t('a11y.rowAction', r.domain, t('inv.edit'))}>{t('inv.edit')}</Button>
                )}
              </div>
            </Card>
          </li>
        )
      })}
    </ul>
  )
}
