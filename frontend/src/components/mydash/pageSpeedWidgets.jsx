import { Gauge, TriangleAlert } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, List, Metric, More, Row, Tile, linkTo, listLimit } from './widgetParts.jsx'
import { pageSpeedOverBudget, pageSpeedSlowest } from './pageSpeedWidgetModel.js'
import { humanizeMs } from '../pagespeed/pageSpeedCardModel.js'

/**
 * Sayfa hızı izlemelerinin listesi (mevcut uç; kapsam sunucuda). İki kutu AYNI getiriciyi kullanır → sharedFetch tek istek
 * yapar. 403 = okuma yetkisi yok → `visible:false` (kutu gri "yetki yok", asla yeşil).
 */
export const fetchPageSpeedMonitors = async () => {
  const res = await api.monitoring.getPageSpeedMonitors()
  if (res && res.success === false && (res.status === 403 || res.errorInfo?.status === 403)) return { success: true, data: { visible: false } }
  return res
}

/** Süre metni: "1.8 s" / "340 ms" (+ alt sınırda "≥"). */
const msText = (ms, lowerBound) => {
  const p = humanizeMs(ms)
  return p ? `${lowerBound ? '≥ ' : ''}${p.num} ${p.unit}` : '—'
}

/** Aşılan ölçü → kısa etiket anahtarı (kutu satırı dar). */
const METRIC_KEY = {
  LOAD: 'pspd.mLoad', TTFB: 'pspd.mTtfb', SIZE: 'pspd.mSize', REQUESTS: 'pspd.mRequests', MINSIZE: 'pspd.minPageKb',
  JS: 'pspd.type.js', IMG: 'pspd.type.img', FONT: 'pspd.type.font', THIRD: 'pspd.res.party.third',
}

/**
 * "En yavaş sayfalar" (2026-11-14): son ölçümde en yavaş yüklenen sayfa hızı izlemeleri. Sıralama kutusu tek sayıya inmez —
 * TV'de de ayrıntılı (liste) çizilir (`DETAIL_ONLY_TYPES`). Satır izlemenin ayrıntısını açar; başlık Sayfa Hızı sayfasını.
 */
export function PageSpeedSlowestWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchPageSpeedMonitors, { refreshKey })
  const s = pageSpeedSlowest(data, listLimit(frame.widget.size, frame.tv, 5))
  const top = s.rows[0]
  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.down > 0 ? t('mydash.psp.downCount', s.down)
    : s.rows.some((r) => r.over) ? t('mydash.psp.overLoadCount', s.rows.filter((r) => r.over).length)
    : s.active === 0 ? t('mydash.psp.none')
    : s.unknown > 0 ? t('mydash.psp.unknownCount', s.unknown)
    : t('mydash.allClear')
  return (
    <WidgetCard frame={frame} Icon={Gauge} title={t('mydash.w.pagespeed_slowest')} tab="pagespeed"
      loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} /> : (<>
        <div className="flex flex-col gap-3 @md:flex-row @md:items-end @md:gap-x-6">
          <Metric value={top ? msText(top.ms, top.lowerBound) : '—'} label={t('mydash.psp.slowestLabel')} slot="mydash-psp-slowest"
            tv={frame.tv} tone={top?.over ? 'warning' : 'neutral'}
            onClick={top?.id != null ? linkTo(frame, 'pagespeed', { monitor: top.id }) : linkTo(frame, 'pagespeed')} hint={t('mydash.linkHint')} />
          <div className="grid min-w-0 grid-cols-3 gap-2 @md:flex-1">
            <Tile value={s.measured} label={t('mydash.psp.measured')} tone="muted" tv={frame.tv} slot="mydash-psp-measured"
              onClick={linkTo(frame, 'pagespeed')} hint={t('mydash.linkHint')} />
            <Tile value={s.down} label={t('mydash.psp.down')} tone={s.down ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-psp-down"
              onClick={linkTo(frame, 'pagespeed', { stat: 'down' })} hint={t('mydash.linkHint')} />
            <Tile value={s.unknown} label={t('mydash.psp.unknown')} tone="muted" tv={frame.tv} slot="mydash-psp-unknown"
              onClick={linkTo(frame, 'pagespeed')} hint={t('mydash.linkHint')} />
          </div>
        </div>
        {s.active === 0 && <Empty tone="muted" text={t('mydash.psp.noneBody')} tv={frame.tv} />}
        {s.active > 0 && s.measured === 0 && <Empty tone="muted" text={t('mydash.psp.noMeasured')} tv={frame.tv} />}
        {s.rows.length > 0 && (
          <List slot="mydash-psp-list">
            {s.rows.map((r) => (
              <Row key={r.id ?? r.url} title={r.name || '—'} meta={r.team}
                onClick={r.id != null ? linkTo(frame, 'pagespeed', { monitor: r.id }) : undefined} hint={t('mydash.linkDetail')}
                trail={<ToneBadge tone={r.over ? 'warning' : 'muted'} className="h-auto rounded-md whitespace-normal tabular-nums"
                  title={r.over ? t('mydash.psp.overLoadTip') : undefined}>{msText(r.ms, r.lowerBound)}</ToneBadge>} />
            ))}
          </List>
        )}
        <More n={s.more} t={t} />
      </>)}
    </WidgetCard>
  )
}

/** Sayfa hızı sayfasının "Eşik aşıldı" sayım kartı süzgeci — TAM OLARAK aşan sayfaları listeler. */
const SLOW_PARAMS = { stat: 'slow' }

/**
 * "Bütçeyi aşan sayfalar" (2026-11-14): son ölçümde bir eşiği ya da tür bütçesini aşan sayfa hızı izlemeleri ve aşılan
 * ölçüler. Renkli panel: aşan varsa turuncu; etkinlerin HEPSİ ölçülmüş ve eşik içindeyse yeşil; alınamayan / ölçülmemiş
 * varsa gri (karar verilemez — asla yeşil değil). Tıklama sayfayı "Eşik aşıldı" süzgeciyle açar.
 */
export function PageSpeedOverBudgetWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchPageSpeedMonitors, { refreshKey })
  const s = pageSpeedOverBudget(data, listLimit(frame.widget.size, frame.tv, 5))
  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.over > 0 ? t('mydash.psp.overCount', s.over)
    : s.active === 0 ? t('mydash.psp.none')
    : s.down > 0 ? t('mydash.psp.downCount', s.down)
    : s.unknown > 0 ? t('mydash.psp.unknownCount', s.unknown)
    : t('mydash.allClear')
  const open = linkTo(frame, 'pagespeed', SLOW_PARAMS)
  const metricsText = (ms) => ms.map((k) => (METRIC_KEY[k] ? t(METRIC_KEY[k]) : k)).join(', ')
  return (
    <WidgetCard frame={frame} Icon={TriangleAlert} title={t('mydash.w.pagespeed_over_budget')} tab="pagespeed" tabParams={SLOW_PARAMS}
      loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}
      stat={s.hidden ? null : {
        value: s.over, label: t('mydash.psp.overLabel'), sub: t('mydash.psp.statSub', s.down, s.unknown, s.ok),
        onClick: open, hint: t('mydash.linkHint'),
      }}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} /> : (<>
        <div className="flex flex-col gap-3 @md:flex-row @md:items-end @md:gap-x-6">
          <Metric value={s.over} label={t('mydash.psp.overLabel')} slot="mydash-psp-over" tv={frame.tv}
            tone={s.over ? 'warning' : s.tone === 'success' ? 'success' : 'neutral'} onClick={open} hint={t('mydash.linkHint')} />
          <div className="grid min-w-0 grid-cols-3 gap-2 @md:flex-1">
            <Tile value={s.ok} label={t('mydash.psp.within')} tone="muted" tv={frame.tv} slot="mydash-psp-ok"
              onClick={linkTo(frame, 'pagespeed', { stat: 'ok' })} hint={t('mydash.linkHint')} />
            <Tile value={s.down} label={t('mydash.psp.down')} tone={s.down ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-psp-down"
              onClick={linkTo(frame, 'pagespeed', { stat: 'down' })} hint={t('mydash.linkHint')} />
            <Tile value={s.unknown} label={t('mydash.psp.unknown')} tone="muted" tv={frame.tv} slot="mydash-psp-unknown"
              onClick={linkTo(frame, 'pagespeed')} hint={t('mydash.linkHint')} />
          </div>
        </div>
        {s.active === 0 && <Empty tone="muted" text={t('mydash.psp.noneBody')} tv={frame.tv} />}
        {s.active > 0 && s.over === 0 && s.tone === 'success' && <Empty text={t('mydash.psp.allClean')} tv={frame.tv} />}
        {s.rows.length > 0 && (
          <List slot="mydash-psp-over-list">
            {s.rows.map((r) => (
              <Row key={r.id ?? r.url} title={r.name || '—'} meta={r.team}
                onClick={r.id != null ? linkTo(frame, 'pagespeed', { monitor: r.id }) : undefined} hint={t('mydash.linkDetail')}
                trail={<ToneBadge tone="warning" className="h-auto rounded-md whitespace-normal">{metricsText(r.metrics)}</ToneBadge>} />
            ))}
          </List>
        )}
        <More n={s.more} t={t} />
      </>)}
    </WidgetCard>
  )
}

export const PAGESPEED_WIDGETS = {
  pagespeed_slowest: { Icon: Gauge, Component: PageSpeedSlowestWidget },
  pagespeed_over_budget: { Icon: TriangleAlert, Component: PageSpeedOverBudgetWidget },
}
