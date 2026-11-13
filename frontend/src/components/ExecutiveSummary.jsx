import { ArrowUpRight, ChevronRight, Gauge, HeartPulse, Minus, RefreshCw, Siren, TrendingDown, TrendingUp, TriangleAlert } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { ProgressBar } from './ui/Progress.jsx'
import SourceGate from './stats/SourceGate.jsx'
import { statsNum, statsPct } from './stats/statsInsights.js'
import { TYPE_META } from './monitoring/overviewMeta.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * İstatistik sayfası operasyon özeti (2026-09-12, zenginleştirme #20; 2026-09-28 shadcn + mobil web; 2026-11-13
 * zenginleştirme): GEZİNME kutucukları — filo sağlığı → Pano, açık alarm → Alarm Geçmişi AÇIK alarmlar (tüm türler; eskiden
 * yalnız sertifika olan Uyarılar'a gidiyordu), SLA ihlali → İzleme Panosu "son 7 günde başarısız koşum" görünümü (9 tür;
 * eskiden Erişilebilirlik) — ve SLA'sı en kötü 5 izleme (ad, tür, izlemeye bağlantı).
 *
 * Veri sayfanın kaynak kapısından (`source` = /api/stats/executive). Kural: bilinmeyen ≠ 0 ≠ yeşil — bir blok okunamadıysa
 * kutucuk "—" ve gri, üstte "okunamadı + yeniden dene"; alarm izni yoksa (`alerts.visible === false`) alarm kutucuğu çizilmez.
 * Sağlık yüzdesi temiz / DEĞERLENDİRİLEN; alt satır aynı paydayı söyler, kontrol edilmemiş sertifikalar ayrı satırda.
 * Takım karşılaştırması 2026-11-13'te birleşik takım karnesine (stats/TeamScorecard) taşındı.
 * Kutucuklarda SOL RENK ŞERİDİ YOK — durum sayının rengi + `data-tone`.
 */
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive', neutral: 'text-muted-foreground' }
const BAR_TONE = { ok: 'ok', warn: 'warn', bad: 'crit' }

/** Sayı tonu: `invert` = büyük iyi (sağlık %); aksi 0 iyi. Bilinmiyor → nötr. */
function toneOf(v, warn, bad, invert = false) {
  if (v == null) return 'neutral'
  if (invert) return v >= warn ? 'ok' : v >= bad ? 'warn' : 'bad'
  return v === 0 ? 'ok' : v <= warn ? 'warn' : 'bad'
}

function Delta({ n, last, prev }) {
  const t = useT()
  if (n == null) return null
  return (
    <span data-slot="exs-delta" className={cn('inline-flex items-center gap-0.5 font-semibold text-muted-foreground', n > 0 && 'text-destructive', n < 0 && 'text-success')}
      title={t('exs.deltaTip', last ?? 0, prev ?? 0)}>
      {n > 0 ? <TrendingUp size={12} aria-hidden="true" /> : n < 0 ? <TrendingDown size={12} aria-hidden="true" /> : <Minus size={12} aria-hidden="true" />} {n > 0 ? `+${n}` : n} {t('exs.vsPrev')}
    </span>
  )
}

/** Gezinme kutucuğu — shadcn Button (outline): tıklayınca ilgili sayfaya gider (köşedeki ok bunu söyler). */
function Kpi({ tone, icon: Icon, label, value, sub, extra, onClick, slot }) {
  return (
    <Button type="button" variant="outline" data-slot="exs-kpi" data-kpi={slot} data-tone={tone} onClick={onClick}
      className="group relative h-auto min-h-28 flex-col items-start justify-start gap-1 rounded-xl bg-card px-4 py-3.5 text-left font-normal whitespace-normal shadow-xs hover:border-primary hover:bg-card">
      <span className="flex w-full items-center gap-2">
        <Icon size={18} aria-hidden="true" className={cn('shrink-0 text-muted-foreground', TONE_INK[tone])} />
        <span className="min-w-0 flex-1 text-xs font-semibold text-muted-foreground">{label}</span>
        <ArrowUpRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-primary motion-reduce:transition-none" />
      </span>
      <b className={cn('text-2xl leading-tight font-extrabold tabular-nums', TONE_INK[tone])}>{value}</b>
      {extra}
      <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">{sub}</span>
    </Button>
  )
}

export default function ExecutiveSummary({ source, onRetry }) {
  const t = useT()
  return (
    <section data-slot="exs" className="flex min-w-0 flex-col gap-3" aria-label={t('exs.title')}>
      <div className="flex min-w-0 flex-col gap-0.5">
        <h2 data-slot="exs-title" className="m-0 text-base font-semibold">{t('exs.title')}</h2>
        <p className="m-0 text-sm text-muted-foreground">{t('stv.opsDesc')}</p>
      </div>
      <SourceGate source={source} onRetry={onRetry} skeletonClassName="h-28 rounded-xl" failedTitle={t('stx.execFailed')}>
        {(data) => <ExecutiveBody data={data} onRetry={onRetry} />}
      </SourceGate>
    </section>
  )
}

function ExecutiveBody({ data, onRetry }) {
  const t = useT()
  const c = data.certs || {}, a = data.alerts || {}, s = data.sla || {}
  const certsFailed = !!c.error_msg, alertsFailed = !!a.error_msg, slaFailed = !!s.error_msg
  const alertsHidden = a.visible === false
  const health = certsFailed ? null : c.health_pct ?? null
  const hTone = toneOf(health, 95, 90, true)
  // Eski sunucu `evaluated` göndermez → ok + under30 + expired + error (sunucunun paydası)
  const evaluated = c.evaluated ?? (c.total != null ? (c.total - (c.unchecked ?? 0)) : null)
  const open = alertsFailed ? null : a.open ?? null
  const breaches = slaFailed ? null : s.breaches ?? null
  const failedParts = [certsFailed && t('stx.part.certs'), alertsFailed && t('stx.part.alerts'), slaFailed && t('stx.part.sla')].filter(Boolean)
  const worst = Array.isArray(s.worst) ? s.worst : []
  const tiles = alertsHidden ? 'sm:grid-cols-2' : 'sm:grid-cols-3'
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {failedParts.length > 0 && (
        <div data-slot="exs-partial" role="status" className="flex min-w-0 flex-wrap items-center gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-900 dark:text-amber-100">
          <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />
          <span className="min-w-0 flex-1">{t('stx.partsFailed', failedParts.join(', '))}</span>
          {onRetry && (
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10 max-sm:h-10" onClick={onRetry}>
              <RefreshCw aria-hidden="true" />{t('stx.retry')}
            </Button>
          )}
        </div>
      )}
      <div className={cn('grid grid-cols-1 gap-3', tiles)}>
        <Kpi slot="health" tone={hTone} icon={HeartPulse} label={t('exs.health')} value={statsPct(health)}
          extra={<span className="block w-full py-1"><ProgressBar value={health ?? 0} max={100} size="sm" decorative tone={BAR_TONE[hTone]} className={cn(health == null && 'opacity-40')} /></span>}
          sub={certsFailed ? t('stx.unreadable') : (
            <>
              <span>{t('stx.healthSub', statsNum(c.ok ?? 0), statsNum(evaluated ?? 0))}</span>
              {Number(c.unchecked) > 0 && <span data-slot="exs-unchecked">· {t('stx.unchecked', statsNum(c.unchecked))}</span>}
            </>
          )}
          onClick={() => navigateTo('dashboard')} />
        {!alertsHidden && (
          <Kpi slot="alerts" tone={toneOf(open, 3, 10)} icon={Siren} label={t('exs.alerts')} value={open == null ? '—' : statsNum(open)}
            sub={alertsFailed ? t('stx.unreadable') : <>{t('exs.alertsSub', statsNum(a.critical ?? 0))} <Delta n={a.delta} last={a.opened_last30} prev={a.opened_prev30} /></>}
            onClick={() => navigateTo('alerthistory', { view: 'open' })} />
        )}
        <Kpi slot="sla" tone={toneOf(breaches, 2, 5)} icon={Gauge} label={t('exs.sla', statsPct(s.target_pct ?? 99.9))} value={breaches == null ? '—' : statsNum(breaches)}
          sub={slaFailed ? t('stx.unreadable') : t('exs.slaSub', statsNum(s.monitors ?? 0), data.window_days ?? 30)}
          onClick={() => navigateTo('monitoring', { mo_ck: 'failed', mo_win: '168' })} />
      </div>
      {!slaFailed && worst.length > 0 && <SlaWorst rows={worst} target={s.target_pct} />}
    </div>
  )
}

/** SLA'sı en kötü izlemeler (en çok 5) — ad + tür + oran; satır izlemeyi açar. */
function SlaWorst({ rows, target }) {
  const t = useT()
  return (
    <Card data-slot="exs-sla-worst" className="min-w-0 gap-2 py-3.5 shadow-xs">
      <CardHeader className="gap-1 px-4">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-sm"><Gauge aria-hidden="true" className="size-4 text-primary" />{t('stx.slaWorstTitle')}</CardTitle>
        <CardDescription className="text-xs">{t('stx.slaWorstDesc', statsPct(target ?? 99.9))}</CardDescription>
      </CardHeader>
      <CardContent className="px-2 sm:px-3">
        <ul className="m-0 flex min-w-0 list-none flex-col p-0">
          {rows.map((w) => {
            const meta = TYPE_META[w.type]
            const name = w.name || t('stx.monitorNo', w.id)
            return (
              <li key={`${w.type}:${w.id}`} className="min-w-0 border-b last:border-b-0">
                <Button type="button" variant="ghost" data-slot="exs-sla-row" data-type={w.type} data-id={w.id}
                  aria-label={t('stx.openMonitor', name)} disabled={!meta}
                  onClick={() => meta && navigateTo(meta.tab, { monitor: String(w.id) })}
                  className="h-auto min-h-11 w-full justify-start gap-3 rounded-md px-2 py-2 text-left font-normal whitespace-normal">
                  {meta && <meta.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{name}</span>
                    <span className="text-xs text-muted-foreground">
                      {meta ? t(meta.labelKey) : w.type}{w.bad_hours != null ? ` · ${t('stx.badHours', statsNum(w.bad_hours))}` : ''}
                    </span>
                  </span>
                  <b className="shrink-0 text-destructive tabular-nums">{statsPct(w.up_pct)}</b>
                  <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                </Button>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}
