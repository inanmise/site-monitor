import { DatabaseZap } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, List, Metric, More, Row, Tile, linkTo, listLimit } from './widgetParts.jsx'
import { dataRulesSummary } from './dataRulesModel.js'

/**
 * Veritabanı sorgu izlemelerinin listesi (mevcut uç; kapsam sunucuda). 403 = okuma yetkisi yok → `visible:false` (kutu gri
 * "yetki yok", ASLA yeşil); başka hata olduğu gibi döner (kutu hata durumunda kalır — okunamayan veriden renk çıkmaz).
 */
export const fetchDataRules = async () => {
  const res = await api.monitoring.getDbQueryMonitors()
  if (res && res.success === false && (res.status === 403 || res.errorInfo?.status === 403)) return { success: true, data: { visible: false } }
  return res
}

/** Sorun sınıfı → rozet tonu + metin anahtarı. */
const PROBLEM = {
  fail: { tone: 'danger', key: 'mydash.rules.p.fail' },
  critical: { tone: 'danger', key: 'mydash.rules.p.critical' },
  warning: { tone: 'warning', key: 'mydash.rules.p.warning' },
  slow: { tone: 'warning', key: 'mydash.rules.p.slow' },
  alarm: { tone: 'warning', key: 'mydash.rules.p.alarm' },
  unknown: { tone: 'muted', key: 'mydash.rules.p.unknown' },
}

/** Sayfa bağlantısı: "Şu an ihlalde" süzgeciyle Veritabanı Sorgusu sayfası (dbq2 C3 — `dq=breach`). */
const BREACH_PARAMS = { dq: 'breach' }

/**
 * "Veri kuralları" kutusu (2026-11-05, dbq2 C5): ihlalde / hatalı / değerlendirilemeyen veritabanı sorgu izlemesi sayıları,
 * en ağır 5 izleme. Renkli panel: hatalı → kırmızı, ihlal → turuncu, hepsi temiz → yeşil, okunamadı / bilinmiyor → gri.
 * Tıklama: sayfa "Şu an ihlalde" süzgeciyle; satır o izlemenin ayrıntısıyla (`monitor=<id>`).
 */
export function DataRulesWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchDataRules, { refreshKey })
  const s = dataRulesSummary(data, listLimit(frame.widget.size, frame.tv, 5))
  const problemTotal = s.fail + s.breach + s.alarm
  const toneLabel = s.hidden ? t('mydash.noAccess')
    : s.fail > 0 ? t('mydash.rules.failCount', s.fail)
    : s.breach + s.alarm > 0 ? t('mydash.rules.breachCount', s.breach + s.alarm)
    : s.slow > 0 ? t('mydash.rules.slowCount', s.slow)
    : s.active === 0 ? t('mydash.rules.none')
    : s.unknown > 0 ? t('mydash.rules.unknownCount', s.unknown)
    : t('mydash.allClear')
  const open = linkTo(frame, 'dbquery', BREACH_PARAMS)
  return (
    <WidgetCard frame={frame} Icon={DatabaseZap} title={t('mydash.w.data_rules')} tab="dbquery" tabParams={BREACH_PARAMS}
      loading={loading} error={error} at={at} tone={s.tone} toneLabel={toneLabel}
      stat={s.hidden ? null : {
        value: problemTotal, label: t('mydash.rules.problemLabel'),
        sub: t('mydash.rules.statSub', s.fail, s.breach + s.alarm, s.unknown), onClick: open, hint: t('mydash.linkHint'),
      }}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} /> : (<>
        <div className="flex flex-col gap-3 @md:flex-row @md:items-end @md:gap-x-6">
          <Metric value={problemTotal} label={t('mydash.rules.problemLabel')} slot="mydash-rules-total" tv={frame.tv}
            tone={s.fail ? 'danger' : problemTotal ? 'warning' : s.tone === 'success' ? 'success' : 'neutral'}
            onClick={open} hint={t('mydash.linkHint')} />
          <div className="grid min-w-0 grid-cols-3 gap-2 @md:flex-1">
            <Tile value={s.fail} label={t('mydash.rules.fail')} tone={s.fail ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-rules-fail"
              onClick={open} hint={t('mydash.linkHint')} />
            <Tile value={s.breach + s.alarm} label={t('mydash.rules.breach')} tone={s.breach + s.alarm ? 'warning' : 'muted'} tv={frame.tv}
              slot="mydash-rules-breach" onClick={open} hint={t('mydash.linkHint')} />
            {/* Değerlendirilemeyen: süzgeç yok — sayfanın tamamı açılır */}
            <Tile value={s.unknown} label={t('mydash.rules.unknown')} tone="muted" tv={frame.tv} slot="mydash-rules-unknown"
              onClick={linkTo(frame, 'dbquery')} hint={t('mydash.linkHint')} />
          </div>
        </div>
        {s.active === 0 && <Empty tone="muted" text={t('mydash.rules.noneBody')} tv={frame.tv} />}
        {s.active > 0 && s.problems === 0 && s.tone === 'success' && <Empty text={t('mydash.rules.allClean')} tv={frame.tv} />}
        {s.rows.length > 0 && (
          <List slot="mydash-rules-list">
            {s.rows.map((r) => (
              <Row key={r.id} title={r.name || '—'} meta={[r.connection, r.team].filter(Boolean).join(' · ') || null}
                onClick={r.id != null ? linkTo(frame, 'dbquery', { monitor: r.id }) : undefined} hint={t('mydash.linkDetail')}
                trail={<ToneBadge tone={PROBLEM[r.problem].tone} className="h-auto rounded-md whitespace-normal">{t(PROBLEM[r.problem].key)}</ToneBadge>} />
            ))}
          </List>
        )}
        <More n={s.more} t={t} />
      </>)}
    </WidgetCard>
  )
}

export const DATA_RULES_WIDGETS = {
  data_rules: { Icon: DatabaseZap, Component: DataRulesWidget },
}
