import { useState } from 'react'
import { ChevronDown, Clock, Info, RefreshCw } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { cn } from '@/lib/utils'
import { num, parseTs } from './dbModel.js'

/** Pencere seçicisinin dokunmatik ölçüsü: telefonda/tablette her seçenek 40 px (RESPONSIVE.md §4). */
const SEG_TOUCH = '[&_[data-slot=toggle-group-item]]:h-10 [&_[data-slot=toggle-group-item]]:min-w-12 lg:[&_[data-slot=toggle-group-item]]:h-8 pointer-coarse:[&_[data-slot=toggle-group-item]]:h-10'
const TOUCH = 'h-10 lg:h-9 pointer-coarse:h-10'

/**
 * Başlık: bölüm adı + açıklama, kaynak rozeti (pg_stat_statements var/yok — dokununca açıklama), "son güncelleme"
 * (sunucunun VERİYİ hesapladığı an; 60 sn önbellek), pencere seçici (1/7/30 gün) ve Yenile. Telefonda denetimler
 * başlığın altına iner ve sarar; yatay kaydırma yok.
 */
export function DbHeader({ t, hasData, pgss, generatedAt, updatedAt, days, onDaysChange, onRefresh, loading }) {
  const stampDate = generatedAt ? parseTs(generatedAt) : updatedAt
  const stamp = stampDate && !Number.isNaN(stampDate.getTime?.())
    ? stampDate.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : null
  return (
    <div data-slot="db-header" className="flex flex-col gap-3 @3xl:flex-row @3xl:items-start @3xl:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        <span className="text-[11px] font-semibold tracking-[.12em] text-muted-foreground uppercase">{t('health.dbTitle')}</span>
        <h3 className="m-0 text-xl leading-tight font-semibold tracking-tight">{t('db.heroTitle')}</h3>
        <p className="m-0 text-sm text-muted-foreground">{t('db.subtitle')}</p>
        {hasData && (
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <HintPopover content={pgss ? t('dba.srcHintPgss') : t('dba.srcHintHistory')}
              triggerClassName="rounded-md max-lg:min-h-10 pointer-coarse:min-h-10">
              <ToneBadge tone={pgss ? 'success' : 'warning'} data-testid="db-source" data-pgss={pgss ? 'true' : 'false'}
                className="h-7 gap-1.5 rounded-md px-2 font-semibold">
                {pgss ? t('db.srcBadgePgss') : t('db.srcBadgeHistory')}
                <Info aria-hidden="true" className="size-3.5 opacity-70" />
              </ToneBadge>
            </HintPopover>
            {stamp && (
              <HintPopover content={t('dba.cacheHint')} triggerClassName="rounded-md max-lg:min-h-10 pointer-coarse:min-h-10">
                <Badge variant="outline" data-testid="db-updated" className="h-7 gap-1.5 rounded-md px-2 font-normal text-muted-foreground tabular-nums">
                  <Clock aria-hidden="true" className="size-3.5" />
                  {t('db.updatedAt', stamp)}
                </Badge>
              </HintPopover>
            )}
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <SegmentedControl value={days} onChange={onDaysChange} ariaLabel={t('sml.rangeLabel')} className={SEG_TOUCH}
          options={[1, 7, 30].map((n) => ({ value: n, label: t(`uact.range${n}d`) }))} />
        {/* disabled YOK (2026-09-28c ek-5): asılı istekte Yenile kalıcı kilitleniyordu; SystemHealth.loadDbAnalytics
            dbSeq korumalı — yeni tur eskisini bayat yapar, bayrak yalnız son turda iner. */}
        <Button type="button" variant="outline" className={TOUCH} onClick={() => onRefresh?.()}
          aria-busy={loading || undefined}>
          <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} />
          {loading ? t('health.dbRefreshing') : t('health.dbRefresh')}
        </Button>
      </div>
    </div>
  )
}

/** Kopyalanabilir kod parçası (yapılandırma satırı / SQL komutu). */
function CodeChip({ t, value }) {
  return (
    <span className="mt-1 flex max-w-full items-center gap-1 rounded-md border border-border bg-background/80 py-0.5 pr-0.5 pl-2">
      <code className="min-w-0 flex-1 font-mono text-xs break-all text-foreground">{value}</code>
      <CopyButton value={value} variant="ghost" label={t('a11y.rowAction', t('dba.copyCode'), value)} copiedLabel={t('db.copied')}
        className="shrink-0 max-lg:size-10 pointer-coarse:size-10" />
    </span>
  )
}

/**
 * Bantlar: yükleme hatası (+ Tekrar dene), pg_stat_statements YOK (etkisi + eyleme dönük adımlar — katlanır,
 * kopyalanabilir komutlar) ve pencere satır tavanı. "Yalnız Playground" durumu SESSİZ bırakılmaz.
 */
export function DbNotices({ t, error, loading, onRefresh, hasData, pgss, truncated, rowLimit }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      {error && (
        <AlertBanner tone="danger" role="alert" title={t('db.loadErrorTitle')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="max-lg:h-10 pointer-coarse:h-10" onClick={() => onRefresh?.()} disabled={loading}>{t('db.retry')}</Button>}>
          {typeof error === 'string' ? error : null}
        </AlertBanner>
      )}
      {hasData && !pgss && (
        <AlertBanner tone="info" title={t('db.fallbackTitle')} className="mb-0">
          <p className="m-0">{t('db.fallbackBody')}</p>
          <Collapsible open={open} onOpenChange={setOpen}>
            <CollapsibleTrigger asChild>
              <Button type="button" variant="link" size="sm" className="mt-1 h-auto gap-1 px-0 py-1 font-semibold max-lg:min-h-10 pointer-coarse:min-h-10">
                {t('dba.pgssHow')}
                <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent data-testid="db-pgss-steps">
              <ol className="m-0 mt-1 flex list-decimal flex-col gap-2 pl-5">
                <li>{t('dba.pgssStepConf')}<CodeChip t={t} value="shared_preload_libraries = 'pg_stat_statements'" /></li>
                <li>{t('dba.pgssStepRestart')}</li>
                <li>{t('dba.pgssStepExt')}<CodeChip t={t} value="CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" /></li>
              </ol>
            </CollapsibleContent>
          </Collapsible>
        </AlertBanner>
      )}
      {hasData && truncated && (
        <AlertBanner tone="warning" className="mb-0">{t('db.truncated', num(rowLimit))}</AlertBanner>
      )}
    </>
  )
}
