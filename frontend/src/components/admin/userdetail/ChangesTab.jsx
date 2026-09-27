import { useState } from 'react'
import { ChevronDown, ChevronRight, History } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import ChangeChipList from '../../history/ChangeChipList.jsx'
import DiffTable from '../audit/DiffTable.jsx'
import { EventBadge } from '../ToneBadge.jsx'
import { SectionCard, SectionEmpty, SectionError, SectionSkeleton } from './parts.jsx'
import { actionLabel, changeModel, eventKind, fieldLabel, formatValue } from './userDetailModel.js'
import { relTime } from '../useractivity/uactModel.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** Açılım: fark tablosu (alan · eski → yeni) ya da anlık görüntü (oluşturma/silme) + IP. */
function ChangeDetail({ row, model }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-2 text-xs">
      {model.diff ? (
        <div className="min-w-0 overflow-x-auto">
          <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
            rows={model.all.map((c) => [c.key, c.label, c.from, c.to])} />
        </div>
      ) : (
        <div className="flex flex-col gap-1">
          <span className="text-muted-foreground">{String(row.action).endsWith('DELETE') ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}</span>
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {Object.entries(model.parsed).map(([f, v]) => (
              <li key={f} className="flex min-w-0 flex-wrap gap-x-2">
                <span className="font-semibold">{fieldLabel(t, f)}</span>
                <span className="min-w-0 font-mono break-all">{formatValue(t, v)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      <span className="text-muted-foreground tabular-nums">{formatDateSec(row.at)}{row.ip ? ` · ${t('ud.fromIp', row.ip)}` : ''}</span>
    </div>
  )
}

/**
 * Değişiklikler — kullanıcının denetim geçmişi (`USER` kaynağı, bu kayıt): işlem rozeti · kim (avatar + ad) · ne zaman
 * (göreli + tam — dokunmatikte de görünür) · alan çipleri (İzleme Değişiklikleri ve Değişiklik Geçmişi ile AYNI
 * `ChangeChipList`) · "Ayrıntılar" açılımında ortak `DiffTable`. Sunucu sayfalı: standart `useServerPagination`
 * (pencere ön ayarı) + `PaginationBar` — çağıran bağlar. Yalnız global yönetici (kişisel veri; eski panelle aynı kapı).
 */
export default function ChangesTab({ section, pagination }) {
  const t = useT()
  const [open, setOpen] = useState(() => new Set())
  const items = section.data?.items || []
  const now = Date.now()
  const toggle = (id) => setOpen((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n })

  if (section.status === 'error' && !section.data) return <SectionError title={t('ud.errChanges')} error={section.error} onRetry={section.reload} />

  return (
    <SectionCard icon={History} title={t('ud.tabChanges')} count={section.data ? Number(section.data.total ?? items.length) : null}
      bodyClassName="gap-2.5">
      {section.status === 'error' && <SectionError title={t('ud.errChanges')} error={section.error} onRetry={section.reload} />}
      {section.loading && !section.data ? <SectionSkeleton rows={4} /> : items.length === 0 ? (
        <SectionEmpty icon={History} title={t('ud.noChanges')} description={t('ud.noChangesHint')} />
      ) : (
        <ul data-slot="ud-changes" aria-busy={section.loading || undefined}
          className={cn('m-0 flex list-none flex-col divide-y overflow-hidden rounded-lg border p-0 transition-opacity motion-reduce:transition-none', section.loading && 'opacity-60')}>
          {items.map((r) => {
            const model = changeModel(r, t)
            const rel = relTime(r.at, now)
            const isOpen = open.has(r.id)
            return (
              <li key={r.id} data-slot="ud-change-row" data-action={r.action} className="flex min-w-0 flex-col gap-2 px-3 py-2.5">
                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                  <EventBadge kind={eventKind(r.action)}>{actionLabel(t, r.action)}</EventBadge>
                  <span className="min-w-0 max-w-full">
                    {r.actor ? <UserBadge username={r.actor} inline size="sm" systemLabel={t('audit.systemActor')} /> : <span className="text-muted-foreground">—</span>}
                  </span>
                  <time dateTime={r.at} className="text-xs text-muted-foreground tabular-nums sm:ml-auto">
                    {rel ? t(`uact.rel.${rel.unit}`, rel.n) : formatDateSec(r.at)}
                    <span className="hidden sm:inline"> · {formatDateSec(r.at)}</span>
                  </time>
                </div>
                {model.chips.length > 0 && <ChangeChipList entries={model.chips} more={model.more} wrap />}
                {model.parsed && (
                  <Collapsible open={isOpen} onOpenChange={() => toggle(r.id)}>
                    <CollapsibleTrigger asChild>
                      <Button type="button" variant="ghost" size="sm" className="-ml-2 h-10 text-muted-foreground sm:h-8"
                        aria-label={t('a11y.rowAction', formatDateSec(r.at), isOpen ? t('ud.hideDetails') : t('ud.showDetails'))}>
                        {isOpen ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
                        {isOpen ? t('ud.hideDetails') : t('ud.showDetails')}
                      </Button>
                    </CollapsibleTrigger>
                    <CollapsibleContent className="pt-1">
                      <ChangeDetail row={r} model={model} />
                    </CollapsibleContent>
                  </Collapsible>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {pagination && section.data && <PaginationBar {...pagination} />}
      <p className="m-0 text-xs text-muted-foreground">{t('ng.histRetentionNote')}</p>
    </SectionCard>
  )
}
