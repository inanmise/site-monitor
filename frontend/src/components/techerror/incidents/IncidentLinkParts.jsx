import { createContext, useContext } from 'react'
import { ExternalLink, Link2, PenLine, RefreshCw, RotateCcw, Unlink } from 'lucide-react'
import { Spinner } from '@/components/shadcn/spinner'
import { useT } from '../../../i18n/index.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { StatusBadge as IncidentStatusBadge } from '../../incidenthistory/HistoryBadges.jsx'
import { canOpenLinkedIncident, canRefreshLink, canWriteFromIncident, groupLabelKey, linkStateMeta, noteKey } from './incidentImportModel.js'

const TONE = {
  ok: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-200',
  info: 'border-sky-500/40 bg-sky-500/10 text-sky-800 dark:text-sky-200',
  warn: 'border-amber-500/50 bg-amber-500/10 text-amber-900 dark:text-amber-200',
  danger: 'border-destructive/40 bg-destructive/10 text-destructive',
  muted: 'text-muted-foreground',
}

const HINT_KEY = {
  stale: 'te.inc.staleHint',
  frozen_stale: 'te.inc.frozenHint',
  deleted: 'te.inc.deletedHint',
  hidden: 'te.inc.hiddenHint',
}

/** Tekil / çoğul (projenin deseni: `<anahtar>.one` yalnız 1 için). */
const plural = (t, key, n, ...rest) => t(Number(n) === 1 ? `${key}.one` : key, ...rest)

/**
 * Düzenleyicide bir işlem sürüyor mu (`{ locked }` — kayıt, PDF, gönderim, içe alma, güncelleme, bağı koparma, açılış
 * eşitlemesi). Sürerken olay kaydı eylemleri (içe alma düğmesi, "Güncelle", "Olaydaki değere dön", menü öğeleri) KAPALI
 * görünür — eskiden tıklama sessizce yutuluyordu (inceleme 2026-11-20). Bağlam olarak gelir: kart memo'su bozulmasın
 * (yalnız tüketen çubuk / işaret yeniden çizilir, görsel yuvaları değil).
 */
export const IncidentOpContext = createContext(Object.freeze({ locked: false }))

/**
 * BAĞLI KONUNUN KAYNAK ÇUBUĞU (2026-11-20, olay kaydından içe alınan konu): "Olay kaydı #<no> · <başlık>" (sarar; `hidden`
 * — okuma izni yok YA DA olay başka bir takıma taşındı — "Olay kaydı görüntülenemiyor (…)"), bağ durumu rozeti, duruma göre
 * açıklama, "Güncelle" (olay değişti), elle
 * değiştirilen alan sayısı, son eşitlemenin notları ve bir menü: "Olay kaydını aç", "Tüm alanları olaydan yeniden al",
 * "Bağı kopar". Salt okunur görünümde yazan eylemler çizilmez; olaydan değer alan eylemler ("Güncelle", "Tüm alanları
 * olaydan yeniden al") ayrıca olay okuma iznini (`canOpenIncident` = `incidents.view` — sunucunun yenileme kapısı) ve
 * silinmemiş / erişilebilir olayı ister.
 *
 * Hepsi DEĞER (ilkel) props — kart memo'su bozulmasın; `onAction(eylem, arg)` sabit geri çağrının konuya bağlanmış hâli.
 * `busy` — BU konuda süren işlem ('refresh' | 'unpin' | 'unlink' ya da ''): güncellemede düğme dönen simgeyle "Güncelleniyor…",
 * bağı koparmada "Bağ koparılıyor…" satırı. Başka bir işlem sürerken (`IncidentOpContext.locked`) eylemler kapalı.
 */
export function IncidentSourceBar({ incidentId, state, title, status = null, pinned, notes, busy, syncing, readOnly, canOpenIncident, onAction, slotBase }) {
  const t = useT()
  const { locked } = useContext(IncidentOpContext)
  const off = !!busy || !!locked
  const updating = busy === 'refresh' || busy === 'unpin'
  const meta = linkStateMeta(state, { syncing })
  const hidden = state === 'hidden'
  const source = hidden ? t('te.inc.sourceHidden')
    : title ? t('te.inc.source', incidentId ?? '—', title) : t('te.inc.sourceNoTitle', incidentId ?? '—')
  const hintKey = state === 'stale' && syncing ? null : HINT_KEY[state] || (state === 'unknown' ? 'te.inc.unknownHint' : null)
  // Olaydan değer alan eylemler olay kaydını okur (sunucu: incidents.view); silinmiş / erişilemeyen olaydan alınamaz
  const fromIncident = canWriteFromIncident(state, { readOnly, canViewIncidents: canOpenIncident })
  const refreshable = fromIncident && canRefreshLink(state) && !syncing
  const pinnedCount = pinned.length
  const items = [
    { label: t('te.inc.openIncident'), icon: <ExternalLink aria-hidden="true" />, onClick: () => onAction('open'),
      hidden: !(canOpenIncident && canOpenLinkedIncident(state, incidentId)) },
    { label: t('te.inc.refreshAll'), icon: <RotateCcw aria-hidden="true" />, onClick: () => onAction('unpinAll'),
      hidden: !(fromIncident && pinnedCount > 0), disabled: off },
    { label: t('te.inc.unlink'), icon: <Unlink aria-hidden="true" />, onClick: () => onAction('unlink'), danger: true,
      hidden: readOnly, disabled: off },
  ]
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-lg border border-dashed bg-muted/30 p-3" data-slot="te-issue-incident"
      data-state={state} data-incident={incidentId ?? undefined} id={slotBase ? `${slotBase}-inc` : undefined}>
      <div className="flex min-w-0 flex-wrap items-start gap-2">
        <Link2 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 basis-48 text-sm font-medium leading-snug [overflow-wrap:anywhere]"
          data-slot="te-issue-incident-source">{source}</span>
        <Badge variant="outline" className={cn('h-auto gap-1 whitespace-normal rounded-md py-1 text-xs leading-snug overflow-visible', TONE[meta.tone])}
          data-slot="te-issue-incident-state" data-state={state}>
          {state === 'stale' && syncing ? <Spinner aria-hidden="true" className="size-3.5" /> : null}
          <span>{t(meta.key)}</span>
        </Badge>
        {!hidden && status ? <IncidentStatusBadge status={status} /> : null}
        <KebabMenu items={items} label={t('te.inc.menu')} rowLabel={hidden ? t('te.inc.chipHidden') : t('te.inc.chip', incidentId ?? '—')} />
      </div>
      {hintKey && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-issue-incident-hint">{t(hintKey)}</p>}
      {refreshable && (
        <Button type="button" size="sm" variant="outline" className="w-fit pointer-coarse:min-h-10" disabled={off}
          aria-busy={updating || undefined} onClick={() => onAction('refresh')} data-slot="te-issue-incident-update">
          {updating ? <Spinner aria-hidden="true" /> : <RefreshCw aria-hidden="true" />}
          {updating ? t('te.inc.updating') : t('te.inc.update')}
        </Button>
      )}
      {busy === 'unlink' && (
        <p className="m-0 flex items-center gap-1.5 text-xs text-muted-foreground" role="status" data-slot="te-issue-incident-busy">
          <Spinner aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('te.inc.unlinking')}</span>
        </p>
      )}
      {pinnedCount > 0 && (
        <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-issue-incident-pinned">
          <PenLine aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span>{plural(t, 'te.inc.pinnedCount', pinnedCount, pinnedCount, pinned.map((g) => t(groupLabelKey(g))).join(', '))}</span>
        </p>
      )}
      {notes.length > 0 && (
        <div className="flex min-w-0 flex-col gap-1" data-slot="te-issue-incident-notes">
          <p className="m-0 text-xs font-medium">{t('te.inc.lastSyncNotes')}</p>
          <ul className="m-0 flex list-disc flex-col gap-0.5 ps-5 text-xs text-muted-foreground">
            {notes.map((code) => <li key={code} data-note={code} className="[overflow-wrap:anywhere]">{t(noteKey(code))}</li>)}
          </ul>
        </div>
      )}
    </div>
  )
}

/**
 * Elle değiştirilen (olayı artık izlemeyen) alan grubunun işareti: "Elle değiştirildi" + "Olaydaki değere dön". Salt
 * okunurda yalnız işaret.
 */
export function PinnedMarker({ group, busy, readOnly, onUnpin }) {
  const t = useT()
  const { locked } = useContext(IncidentOpContext)
  const label = t(groupLabelKey(group))
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1.5" data-slot="te-issue-pinned" data-group={group}>
      <Badge variant="outline" className="h-auto gap-1 whitespace-normal rounded-md py-0.5 text-xs font-normal leading-snug overflow-visible">
        <PenLine aria-hidden="true" className="size-3 shrink-0" />{t('te.inc.pinnedOf', label)}
      </Badge>
      {!readOnly && (
        <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs pointer-coarse:h-10" disabled={!!busy || !!locked}
          aria-label={t('te.inc.unpinOf', label)} onClick={() => onUnpin(group)} data-slot="te-issue-unpin">
          <RotateCcw aria-hidden="true" />{t('te.inc.unpin')}
        </Button>
      )}
    </span>
  )
}
