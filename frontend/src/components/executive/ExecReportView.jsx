import { useId, useMemo } from 'react'
import { Building2, Users, ListTree } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Card, CardContent } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { SectionCard, StatusBadge, KpiTile, VerdictItem, sectionIcon, STATUS_DOT, STATUS_LABEL } from './ExecParts.jsx'
import {
  STATUS_SEGMENTS, fmtPct, isTeamScope, monthLabel, orderedSections, sectionTitle, statusCounts,
} from './executiveModel.js'

/**
 * Bölüm sağlığı — tek yatay yığılmış çubuk (kötüden iyiye) + sayılı lejant. Renk tek taşıyıcı değil: her dilim lejantta
 * metinle söylenir; çubuğun kendisi süs (aria-hidden), özet cümlesi ekran okuyucuya.
 */
function SectionHealth({ sections }) {
  const t = useT()
  const counts = useMemo(() => statusCounts(sections), [sections])
  const total = sections.length
  if (!total) return null
  const segs = STATUS_SEGMENTS.filter((s) => counts[s.key] > 0)
  return (
    <div data-slot="ex-health" className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="text-xs font-medium text-muted-foreground">{t('exec.health.title', total)}</span>
        <span className="sr-only">
          {segs.map((s) => t('exec.health.part', counts[s.key], t(STATUS_LABEL[s.key]))).join(', ')}
        </span>
      </div>
      <div aria-hidden="true" className="flex h-2.5 w-full gap-0.5 overflow-hidden rounded-full bg-muted">
        {segs.map((s) => (
          <span key={s.key} data-slot="ex-health-seg" data-key={s.key} className="h-full min-w-1 basis-0 first:rounded-l-full last:rounded-r-full"
            style={{ flexGrow: counts[s.key], background: s.color }} />
        ))}
      </div>
      <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs text-muted-foreground" aria-hidden="true">
        {segs.map((s) => (
          <li key={s.key} data-slot="ex-health-legend" data-key={s.key} className="inline-flex items-center gap-1.5">
            <span className="size-2 rounded-full" style={{ background: s.color }} />
            <span className="font-semibold text-foreground tabular-nums">{counts[s.key]}</span>
            {t(STATUS_LABEL[s.key])}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Bölüm bağlantıları — dar ekranda yatay kayan çip satırı, geniş ekranda yapışkan içindekiler kartı. */
function SectionLinks({ sections, variant }) {
  const t = useT()
  if (sections.length < 2) return null
  if (variant === 'chips') {
    return (
      <nav aria-label={t('exec.jump')} data-slot="ex-jump-chips" className="relative -mx-1 overflow-x-auto px-1 pb-1 lg:hidden">
        <ul className="m-0 flex w-max list-none gap-2 p-0">
          {sections.map((s) => (
            <li key={s.key}>
              <Button asChild variant="outline" size="sm" className="h-10 gap-2 rounded-full bg-card lg:h-8">
                <a href={`#ex-sec-${s.key}`} data-slot="ex-jump" data-key={s.key}>
                  <span aria-hidden="true" className={cn('size-2 rounded-full', STATUS_DOT[s.status] || STATUS_DOT.no_data)} />
                  {sectionTitle(t, s)}
                  <span className="sr-only">{` — ${t(STATUS_LABEL[s.status] || STATUS_LABEL.no_data)}`}</span>
                </a>
              </Button>
            </li>
          ))}
        </ul>
      </nav>
    )
  }
  return (
    <nav aria-label={t('exec.toc')} data-slot="ex-toc" className="hidden lg:block">
      <Card className="gap-2 py-3 shadow-xs">
        <CardContent className="flex flex-col gap-1 px-2">
          <p className="m-0 flex items-center gap-2 px-2 pb-1 text-xs font-semibold text-muted-foreground">
            <ListTree aria-hidden="true" className="size-3.5" />{t('exec.toc')}
          </p>
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
            {sections.map((s) => {
              const Icon = sectionIcon(s.key)
              return (
                <li key={s.key}>
                  <Button asChild variant="ghost" size="sm" className="h-auto w-full justify-start gap-2 px-2 py-1.5 text-left font-normal">
                    <a href={`#ex-sec-${s.key}`} data-slot="ex-jump" data-key={s.key}>
                      <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                      <span className="min-w-0 flex-1 text-sm leading-snug whitespace-normal">{sectionTitle(t, s)}</span>
                      <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', STATUS_DOT[s.status] || STATUS_DOT.no_data)} />
                      <span className="sr-only">{` — ${t(STATUS_LABEL[s.status] || STATUS_LABEL.no_data)}`}</span>
                    </a>
                  </Button>
                </li>
              )
            })}
          </ul>
        </CardContent>
      </Card>
    </nav>
  )
}

/**
 * Raporun gövdesi: üst kart (kapsam, ay, genel durum, bölüm sağlığı, ana göstergeler, ana hükümler) → bölüm bağlantıları
 * → bölüm kartları. Geniş ekranda sağda yapışkan içindekiler. Özet e-posta ve PDF ile AYNI içeriktir.
 */
export default function ExecReportView({ data, loading = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const headId = useId()
  const sections = useMemo(() => orderedSections(data), [data])
  // Üst şerit hükümleri: her bölümün İLK hükmü (sunucunun `headline`'ı ile aynı kural; bölüm anahtarı i18n için gerekli)
  const headline = useMemo(() => sections.filter((s) => s.verdicts?.length).map((s) => ({ key: s.key, verdict: s.verdicts[0] })), [sections])
  const bySection = useMemo(() => Object.fromEntries(sections.map((s) => [s.key, s])), [sections])
  const team = isTeamScope(data)
  const target = data?.settings?.availability_target
  const ScopeIcon = team ? Users : Building2
  return (
    <div className={cn('flex min-w-0 flex-col gap-4 transition-opacity motion-reduce:transition-none', loading && 'opacity-70')}>
      <Card data-slot="ex-headline" data-status={data.status} data-scope={team ? 'team' : 'org'} role="region"
        aria-labelledby={headId} className="min-w-0 gap-0 overflow-hidden py-0 shadow-xs">
        <CardContent className="flex min-w-0 flex-col gap-5 px-4 py-4 sm:px-6 sm:py-5">
          <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 flex-col gap-1.5">
              <span data-slot="ex-scope-chip"
                className="inline-flex w-fit max-w-full items-center gap-1.5 rounded-full border bg-muted/40 px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                <ScopeIcon aria-hidden="true" className="size-3.5 shrink-0" />
                <span className="truncate">{team ? t('exec.scope.teamChip', data.scope.team_name || `#${data.scope.team_id}`) : t('exec.scope.org')}</span>
              </span>
              <h3 id={headId} className="m-0 text-xl leading-tight font-semibold tracking-tight sm:text-2xl">
                {t('exec.headline.title', monthLabel(data.month, lang))}
              </h3>
              <p className="m-0 text-sm text-muted-foreground">
                {target != null ? t('exec.headline.desc', fmtPct(target, lang)) : null}
              </p>
            </div>
            <StatusBadge status={data.status} className="px-2.5 py-1 text-sm" />
          </div>

          <SectionHealth sections={sections} />

          {data.headline_kpis?.length > 0 && (
            <div role="group" aria-label={t('exec.headline.kpis')} data-slot="ex-headline-kpis"
              className="grid grid-cols-2 gap-2.5 md:grid-cols-3 xl:grid-cols-4">
              {data.headline_kpis.map((h) => (
                <KpiTile key={h.section} sectionKey={h.section} kpi={h.kpi} emphasis
                  caption={bySection[h.section] ? sectionTitle(t, bySection[h.section]) : null} />
              ))}
            </div>
          )}
          {headline.length > 0 && (
            <div className="flex min-w-0 flex-col gap-2 border-t pt-4">
              <h4 className="m-0 text-sm font-semibold">{t('exec.headline.verdicts')}</h4>
              <ul data-slot="ex-headline-verdicts" className="m-0 flex list-none flex-col gap-2 p-0">
                {headline.map(({ key, verdict }) => <VerdictItem key={key} sectionKey={key} verdict={verdict} />)}
              </ul>
            </div>
          )}
        </CardContent>
      </Card>

      <SectionLinks sections={sections} variant="chips" />

      <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1fr)_15rem] lg:items-start">
        <div className="flex min-w-0 flex-col gap-4">
          {sections.map((s, i) => <SectionCard key={s.key} section={s} index={i + 1} />)}
          <p className="m-0 text-xs text-muted-foreground">{team ? t('exec.footerTeam') : t('exec.footer')}</p>
        </div>
        <div className="hidden lg:sticky lg:top-4 lg:block">
          <SectionLinks sections={sections} variant="toc" />
        </div>
      </div>
    </div>
  )
}
