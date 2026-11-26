import { Hourglass, Timer, Users, Building2, Radio, Shapes, Gauge, Info } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { ProgressBar } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'
import { SEVERITIES, CATEGORIES, formatMinutes } from './incidentHistoryModel.js'

/** Bir kırılımda gösterilen en çok satır (fazlası "+N daha"). */
export const BREAKDOWN_ROWS = 8

/** Ölçü kutusu — değer bilinmiyorsa "—" (uydurma 0 yok). */
function Metric({ icon: Icon, label, value, sub, slot }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-0.5 rounded-xl border bg-card px-3.5 py-3">
      <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
        <Icon aria-hidden="true" className="size-4 shrink-0" /><span className="min-w-0">{label}</span>
      </span>
      <span data-slot="ih-metric-value" className="text-xl leading-tight font-bold tabular-nums">{value}</span>
      {sub && <span className="text-xs text-muted-foreground">{sub}</span>}
    </div>
  )
}

/**
 * Kırılım listesi — satır başına ad + sayı + oran çubuğu. `onPick` verilirse satır süzgeç düğmesidir (40 px dokunma
 * hedefi); adlar sarar (kesilmez). Boş liste "kayıt yok" der.
 */
function BreakdownList({ icon: Icon, title, rows, total, onPick, slot, t }) {
  // rows null = bu sunucu sürümü kırılımı göndermedi (bilinmiyor) — "kayıt yok" DEĞİL
  if (rows == null) {
    return (
      <section data-slot={slot} data-unknown="true" className="flex min-w-0 flex-col gap-2 rounded-xl border bg-card p-3">
        <h4 className="m-0 flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
          <Icon aria-hidden="true" className="size-3.5" />{title}
        </h4>
        <p className="m-0 text-sm text-muted-foreground">—</p>
      </section>
    )
  }
  const shown = rows.slice(0, BREAKDOWN_ROWS)
  const more = rows.length - shown.length
  const max = shown.reduce((m, r) => Math.max(m, r.count), 0)
  return (
    <section data-slot={slot} className="flex min-w-0 flex-col gap-2 rounded-xl border bg-card p-3">
      <h4 className="m-0 flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5" />{title}
      </h4>
      {shown.length === 0 ? <p className="m-0 text-sm text-muted-foreground">{t('inc.bd.none')}</p> : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {shown.map((r) => {
            const pct = total > 0 ? Math.round((r.count / total) * 100) : 0
            const bar = max > 0 ? Math.max(2, Math.round((r.count / max) * 100)) : 0
            const content = (
              <span className="flex w-full min-w-0 flex-col gap-1">
                <span className="flex w-full min-w-0 items-start justify-between gap-2">
                  <span className="min-w-0 text-left text-sm [overflow-wrap:anywhere]">{r.label}</span>
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{t('inc.bd.countPct', r.count, pct)}</span>
                </span>
                <ProgressBar value={bar} max={100} size="sm" decorative className="h-1.5" />
              </span>
            )
            return (
              <li key={r.key} data-slot="ih-bd-row" data-key={r.key}>
                {onPick && r.pick ? (
                  <Button type="button" variant="ghost" title={t('inc.bd.filterBy')} onClick={() => onPick(r.pick, r)}
                    className="h-auto min-h-10 w-full justify-start px-2 py-1.5 font-normal whitespace-normal">
                    {content}
                  </Button>
                ) : <div className="px-2 py-1.5">{content}</div>}
              </li>
            )
          })}
        </ul>
      )}
      {more > 0 && <p className="m-0 text-xs text-muted-foreground" data-slot="ih-bd-more">{t('inc.bd.more', more)}</p>}
    </section>
  )
}

/**
 * Ayrıntılı döküm ek bölümü (2026-11-26, "Bul ve Raporla"): MTTD (oluş → tespit; ortalama + ortanca), ortanca MTTR,
 * etkilenen müşteri toplamı ve takım / kanal / kategori / önem kırılımları — sayılar eğilim yanıtından (listenin süzgeçlerine
 * göre; özet kartlarının boyutları hariç). Bir satıra tıklamak o süzgeci uygular. Eğilim okunamadıysa "—"; okuma tavanı
 * aşıldıysa ortancalar en yeni kayıtlardan (not).
 *
 * <p>Satır süzgeçleri döküm sayısıyla AYNI kümeyi verir (inceleme 2026-11-26): kanal → `channel_exact` (virgüllü alanda TAM
 * öğe — eski alt dize `channel` "Mobil ATM"yi de sayardı), takım → `team_id` ve `onFilter`'ın ikinci bağımsız değişkeni
 * satırdır (`teamName`: süzgeç seçicisinde olmayan takım — paylaşımla görünen — adıyla eklenir).
 */
export default function HistoryBreakdown({ trends, failed = false, onFilter, onCard }) {
  const t = useT()
  const locale = useDateLocale()
  const s = trends?.summary || null
  const unknown = !s
  const dash = '—'
  const min = (v) => (v == null ? dash : formatMinutes(v, t))
  const total = Number(s?.total) || 0

  const byTeam = Array.isArray(trends?.by_team) ? trends.by_team.map((r) => ({
    key: r.team_id == null ? 'none' : String(r.team_id), count: Number(r.count) || 0,
    label: r.team_id == null ? t('inc.bd.teamless') : (r.team_name || t('inc.teamUnknown')),
    pick: r.team_id == null ? null : { team_id: String(r.team_id) },
    teamName: r.team_id != null && r.team_name ? String(r.team_name) : null,
  })) : null   // eski sunucu by_team göndermez → bilinmiyor
  const byChannel = Object.entries(trends?.by_channel || {}).map(([k, v]) => ({ key: k, label: k, count: Number(v) || 0, pick: { channel_exact: k } }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'tr'))
  const byCategory = CATEGORIES.filter((c) => trends?.by_category?.[c] != null)
    .map((c) => ({ key: c, label: t('inc.cat' + c), count: Number(trends.by_category[c]) || 0, pick: { category: c } }))
    .sort((a, b) => b.count - a.count)
  const bySeverity = SEVERITIES.map((sv) => ({ key: sv, label: t('inc.sev' + sv), count: Number(trends?.by_severity?.[sv]) || 0, card: sv.toLowerCase() }))

  return (
    <div data-slot="ih-breakdown" className="flex min-w-0 flex-col gap-3">
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <Metric slot="ih-metric-mttd" icon={Hourglass} label={t('inc.bd.mttd')}
          value={unknown ? dash : min(s.mttd_minutes)}
          sub={unknown ? (failed ? t('inc.kpiUnavailable') : '') : s.mttd_sample == null ? ''   // eski sunucu: bilinmiyor
            : (s.mttd_sample ? t('inc.bd.mttdSub', min(s.mttd_median_minutes), s.mttd_sample) : t('inc.bd.mttdNone'))} />
        <Metric slot="ih-metric-mttr-median" icon={Timer} label={t('inc.bd.mttrMedian')}
          value={unknown ? dash : min(s.mttr_median_minutes)}
          sub={unknown ? '' : (s.mttr_minutes != null ? t('inc.bd.mttrMean', min(s.mttr_minutes)) : t('inc.kpiMttrNone'))} />
        <Metric slot="ih-metric-customers" icon={Users} label={t('inc.bd.customers')}
          value={unknown || s.customers_sum == null ? dash : Number(s.customers_sum).toLocaleString(locale)}
          sub={unknown || s.customers_sample == null ? '' : (s.customers_sample ? t('inc.bd.customersSub', s.customers_sample) : t('inc.bd.customersNone'))} />
        <Metric slot="ih-metric-total" icon={Gauge} label={t('inc.kpiTotal')} value={unknown ? dash : total}
          sub={trends?.filtered === true ? t('inc.bd.filtered') : ''} />
      </div>
      {trends?.capped === true && (
        <p data-slot="ih-bd-capped" className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('inc.bd.capped', Number(trends?.rows_read) || 0)}
        </p>
      )}
      {!unknown && (
        <div className={cn('grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2')}>
          <BreakdownList slot="ih-bd-team" icon={Building2} title={t('inc.bd.byTeam')} rows={byTeam} total={total} onPick={onFilter} t={t} />
          <BreakdownList slot="ih-bd-channel" icon={Radio} title={t('inc.bd.byChannel')} rows={byChannel} total={total} onPick={onFilter} t={t} />
          <BreakdownList slot="ih-bd-category" icon={Shapes} title={t('inc.bd.byCategory')} rows={byCategory} total={total} onPick={onFilter} t={t} />
          <BreakdownList slot="ih-bd-severity" icon={Gauge} title={t('inc.bd.bySeverity')}
            rows={bySeverity.map((r) => ({ ...r, pick: onCard ? r.card : null }))} total={total} onPick={onCard} t={t} />
        </div>
      )}
    </div>
  )
}
