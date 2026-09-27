import { useEffect, useState } from 'react'
import { ArrowLeft, ArrowRight, CalendarDays, CheckCircle2, FilePlus2, Lock, Plus } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { formatWeekRange, isoWeekInfo, isEditableWeek } from '../../utils/isoWeek'
import { isoWeekLabel } from './weeklyModel.js'
import PageHeader from '../ui/PageHeader.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import WeekDatePicker from '../ui/WeekDatePicker.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { cn } from '@/lib/utils'

/** Adım başlığı: numara dairesi + başlık + açıklama. */
function Step({ n, title, description, children }) {
  return (
    <section className="flex min-w-0 flex-col gap-3" aria-label={title}>
      <div className="flex min-w-0 items-start gap-3">
        <span aria-hidden="true" className="inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary">{n}</span>
        <div className="min-w-0">
          <h3 className="text-sm leading-7 font-semibold">{title}</h3>
          {description && <p className="text-sm text-muted-foreground">{description}</p>}
        </div>
      </div>
      <div className="min-w-0 sm:pl-10">{children}</div>
    </section>
  )
}

/**
 * "Yeni Hafta Raporu" (2026-09-27 yeniden tasarım — eski küçük pencerenin yerine yönlendirmeli ekran):
 * 1) hafta — "Bu hafta / Geçen hafta" hızlı seçimleri + ISO haftalı takvim; seçilen haftanın tarih aralığı, o takımın
 *    o hafta raporu zaten varsa "Aç" (sunucu DUPLICATE_WEEK'e düşmeden), düzenleme penceresi dışındaysa uyarı;
 * 2) takım — yönetici seçer, diğerleri kendi takımı; 3) başlangıç — boş şablon / geçen haftanın notlarıyla.
 * Sunucu sözleşmesi aynı: `onCreate({ teamId, year, week, carry })` sayfanın `api.weeklyReports.create` çağrısı.
 */
export default function WeeklyReportCreate({ initial, isAdmin, teams = [], teamId, teamName, onCancel, onCreate, onOpenExisting }) {
  const t = useT()
  const { lang } = useLanguage()
  const now = isoWeekInfo()
  const prev = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
  const [year, setYear] = useState(initial?.year ?? now.year)
  const [week, setWeek] = useState(initial?.week ?? now.week)
  const [team, setTeam] = useState(isAdmin ? (initial?.teamId ?? '') : String(teamId ?? ''))
  const [carry, setCarry] = useState(!!initial?.carry)
  const [picked, setPicked] = useState('')
  const [existing, setExisting] = useState({ key: null, map: null })   // takım+yıl → { hafta: rapor id }
  const [busy, setBusy] = useState(false)

  const teamKey = team ? `${team}:${year}` : null
  useEffect(() => {
    if (!team) return undefined
    let alive = true
    ;(async () => {
      try {
        const r = await api.weeklyReports.list({ teamId: Number(team), year })
        if (!alive) return
        const map = {}
        for (const x of (r?.success ? r.data || [] : [])) if (Number(x.team_id) === Number(team) && x.report_year === year) map[x.week_no] = x.id
        setExisting({ key: `${team}:${year}`, map })
      } catch { if (alive) setExisting({ key: `${team}:${year}`, map: {} }) }
    })()
    return () => { alive = false }
  }, [team, year])

  const checking = !!teamKey && existing.key !== teamKey
  const existingId = !checking && existing.map ? existing.map[week] : null
  const validWeek = Number.isInteger(week) && week >= 1 && week <= 53 && year >= 2000 && year <= 2100
  const readOnlyForMe = !isAdmin && validWeek && !isEditableWeek({ report_year: year, week_no: week })
  const canCreate = validWeek && !!team && !existingId && !checking && !busy
  const isThis = year === now.year && week === now.week
  const isLast = year === prev.year && week === prev.week
  const teamName_ = isAdmin ? (teams.find((tm) => String(tm.id) === String(team))?.name ?? '') : (teamName ?? '')

  function pick(y, w) { setYear(y); setWeek(w); setPicked('') }
  function pickDate(dateStr) {
    setPicked(dateStr)
    if (!dateStr) return
    const d = new Date(dateStr + 'T12:00:00')
    if (d.getFullYear() < 2000 || d.getFullYear() > 2100) return
    const info = isoWeekInfo(d)
    setYear(info.year); setWeek(info.week)
  }
  async function create() {
    if (!canCreate) return
    setBusy(true)
    try { await onCreate({ teamId: team, year, week, carry }) } finally { setBusy(false) }
  }

  const quick = (on, title, y, w) => (
    <Button type="button" variant="outline" aria-pressed={on} onClick={() => pick(y, w)}
      className={cn('h-auto min-h-14 w-full flex-col items-start gap-0.5 px-3 py-2.5 text-left whitespace-normal',
        on && 'border-primary bg-primary/5 ring-1 ring-primary/40 hover:bg-primary/10')}>
      <span className="flex w-full items-center justify-between gap-2 font-semibold">
        {title}
        <span className="font-mono text-[11px] font-medium text-muted-foreground">W{String(w).padStart(2, '0')}</span>
      </span>
      <span className="text-xs font-normal text-muted-foreground">{formatWeekRange(y, w, lang)}</span>
    </Button>
  )

  const choice = (value, title, description) => {
    const id = `wr-carry-${value}`
    return (
      <Label htmlFor={id}
        className="flex cursor-pointer items-start gap-3 rounded-lg border p-3 font-normal leading-normal has-[[data-state=checked]]:border-primary has-[[data-state=checked]]:bg-primary/5">
        <RadioGroupItem id={id} value={value} className="mt-0.5" />
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold">{title}</span>
          <span className="text-xs text-muted-foreground">{description}</span>
        </span>
      </Label>
    )
  }

  return (
    <div data-slot="wr-create" className="flex min-w-0 flex-col gap-3">
      <div>
        <Button type="button" variant="ghost" size="sm" className="-ml-2 pointer-coarse:h-10" onClick={onCancel}>
          <ArrowLeft aria-hidden="true" /> {t('wr.backToList')}
        </Button>
      </div>
      <PageHeader icon={FilePlus2} title={t('wr.newReport')} description={t('wr.new.desc')} className="mb-1" />

      <Card className="w-full max-w-3xl gap-0 py-0">
        <div className="flex flex-col gap-1.5 border-b px-6 py-4">
          <CardTitle>{t('wr.new.cardTitle')}</CardTitle>
          <CardDescription>{t('wr.new.cardDesc')}</CardDescription>
        </div>
        <CardContent className="flex flex-col gap-6 py-5">
          <Step n={1} title={t('wr.new.stepWeek')} description={t('wr.new.stepWeekDesc')}>
            <div role="group" aria-label={t('wr.new.quickPicks')} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {quick(isThis, t('wr.new.thisWeek'), now.year, now.week)}
              {quick(isLast, t('wr.new.lastWeek'), prev.year, prev.week)}
            </div>
            <div className="mt-2.5 flex min-w-0 flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <span>{t('wr.new.orPick')}</span>
              <div className="w-full sm:w-auto">
                <WeekDatePicker value={picked} onChange={pickDate} placeholder={t('wr.pickDate')} hint={t('wr.new.pickHint')}
                  isMarked={(y, w) => (y === year && existing.map ? existing.map[w] != null : false)} />
              </div>
            </div>
            <div data-slot="wr-new-week" className="mt-3 flex min-w-0 items-start gap-3 rounded-lg border bg-muted/30 p-3">
              <CalendarDays aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-muted-foreground" />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="text-base font-semibold">{validWeek ? formatWeekRange(year, week, lang) : '—'}</span>
                  <Badge variant="secondary" className="font-mono text-[11px]">{isoWeekLabel(year, week)}</Badge>
                </p>
                {!team ? (
                  <p className="text-xs text-muted-foreground">{t('wr.new.pickTeamFirst')}</p>
                ) : checking ? (
                  <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Spinner decorative className="size-3.5" /> {t('wr.new.checking')}</p>
                ) : existingId ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-sm font-medium text-amber-700 dark:text-amber-400">{t('wr.new.exists', teamName_)}</p>
                    <Button type="button" size="sm" variant="secondary" onClick={() => onOpenExisting(existingId)}>
                      {t('wr.new.openExisting')} <ArrowRight aria-hidden="true" />
                    </Button>
                  </div>
                ) : (
                  <p className="flex items-center gap-1.5 text-xs font-medium text-success">
                    <CheckCircle2 aria-hidden="true" className="size-3.5" /> {t('wr.new.free')}
                  </p>
                )}
                {readOnlyForMe && (
                  <p className="flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-400">
                    <Lock aria-hidden="true" className="mt-px size-3.5 shrink-0" /> {t('wr.new.outsideWindow')}
                  </p>
                )}
              </div>
            </div>
          </Step>

          <Step n={2} title={t('wr.team')} description={isAdmin ? t('wr.new.stepTeamAdmin') : t('wr.new.stepTeamOwn')}>
            {isAdmin ? (
              <div className="w-full sm:max-w-xs">
                <SearchableSelect value={team} onChange={(v) => setTeam(v)} ariaLabel={t('wr.team')}
                  placeholder={t('wr.selectTeam')} searchThreshold={2}
                  options={teams.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
              </div>
            ) : (
              <span className="inline-flex rounded-md border px-2 py-1.5 text-sm"><TeamBadge teamId={teamId} teamName={teamName} /></span>
            )}
          </Step>

          <Step n={3} title={t('wr.carryTitle')} description={t('wr.new.stepStartDesc')}>
            <RadioGroup value={carry ? 'carry' : 'blank'} aria-label={t('wr.carryTitle')}
              onValueChange={(v) => setCarry(v === 'carry')} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {choice('blank', t('wr.carryBlank'), t('wr.templateHint'))}
              {choice('carry', t('wr.carryPrev'), t('wr.carryHint'))}
            </RadioGroup>
          </Step>
        </CardContent>
        <div className="flex flex-col-reverse gap-2 border-t px-6 py-4 sm:flex-row sm:justify-end">
          <Button type="button" variant="outline" className="w-full sm:w-auto pointer-coarse:h-10" onClick={onCancel}>{t('wr.cancel')}</Button>
          <Button type="button" data-slot="wr-create-submit" className="w-full sm:w-auto pointer-coarse:h-10" disabled={!canCreate} aria-busy={busy || undefined} onClick={create}>
            {busy ? <Spinner decorative className="size-4" /> : <Plus aria-hidden="true" />} {t('wr.new.createCta')}
          </Button>
        </div>
      </Card>
    </div>
  )
}
