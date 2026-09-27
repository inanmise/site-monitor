import { Award, Activity, Play } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Field as ShadcnField, FieldDescription, FieldTitle } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import { KINDS, LEVELS, LEVEL_DOT } from './whoNotifiedModel.js'

/**
 * Senaryo kartı — takım, alarm seviyesi, alarm türü, (isteğe bağlı) bildirim grubu. Sonuç her değişiklikte
 * kendiliğinden yenilenir; "Simüle et" aynı senaryoyu yeniden sorar (ör. başka sekmede kişi ekledikten sonra).
 *
 * <p>Yerleşim KAP genişliğine göre (`@container`): Yönetim Paneli içeriği 768 px tablette kenar çubuğu açıkken
 * ~440 px — pencere kırılma noktası orada yanıltır. Kap ≥ 576 px (@xl) iki sütun, altında tek sütun.
 * Telefonda seçiciler ve parçalı kontroller tam genişlik, dokunma hedefleri 40 px (md+ 32–36 px).
 */
const SELECT_TOUCH = '[&_[role=combobox]]:h-10 md:[&_[role=combobox]]:h-9'
const SEGMENT_TOUCH = 'w-full sm:w-fit [&>[data-slot=toggle-group-item]]:h-10 [&>[data-slot=toggle-group-item]]:flex-1 sm:[&>[data-slot=toggle-group-item]]:flex-none md:[&>[data-slot=toggle-group-item]]:h-8'

export default function ScenarioForm({
  teams, teamId, onTeamChange, level, onLevelChange, kind, onKindChange,
  groups, groupsLoaded, groupId, onGroupChange, onRun, running,
}) {
  const t = useT()
  const teamOptions = teams.map((tm) => ({ value: String(tm.id), label: tm.name }))
  const groupOptions = [
    { value: '', label: t('sim.groupDefault') },
    ...groups.map((g) => ({ value: String(g.id), label: g.is_default ? t('wn.groupDefaultMark', g.name) : g.name })),
  ]
  const noGroups = !!teamId && groupsLoaded && groups.length === 0
  const levelOptions = LEVELS.map((l) => ({
    value: l,
    label: (
      <span className="inline-flex items-center gap-1.5">
        <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', LEVEL_DOT[l])} />
        {t(`sim.level.${l}`)}
      </span>
    ),
  }))
  const kindOptions = KINDS.map((k) => ({
    value: k, icon: k === 'CERT' ? Award : Activity, label: k === 'CERT' ? t('sim.kindCert') : t('sim.kindMonitor'),
  }))

  return (
    <Card data-slot="wn-scenario" className="@container gap-4 py-4 sm:py-5">
      <CardHeader className="px-4 sm:px-6">
        <CardTitle role="heading" aria-level={4}>{t('wn.scenario')}</CardTitle>
        <CardDescription>{t('wn.scenarioDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-x-6 gap-y-4 px-4 sm:px-6 @xl:grid-cols-2">
        <Field label={t('sim.team')} className={cn('mb-0 min-w-0', SELECT_TOUCH)}>
          {({ id }) => (
            <SearchableSelect id={id} value={teamId} onChange={onTeamChange} placeholder={t('sim.pickTeam')}
              searchThreshold={6} options={teamOptions} />
          )}
        </Field>
        <Field label={t('wn.groupOptional')} className={cn('mb-0 min-w-0', SELECT_TOUCH)}
          hint={noGroups ? t('wn.groupNone') : t('wn.groupHint')}>
          {({ id }) => (
            <SearchableSelect id={id} value={groupId} onChange={onGroupChange} placeholder={t('sim.groupDefault')}
              disabled={!teamId || noGroups} searchThreshold={8} options={groupOptions} />
          )}
        </Field>
        <ShadcnField role={undefined} className="min-w-0 gap-1.5">
          <FieldTitle className="font-semibold">{t('wn.level')}</FieldTitle>
          <SegmentedControl value={level} onChange={onLevelChange} ariaLabel={t('wn.level')} options={levelOptions}
            className={SEGMENT_TOUCH} />
          <FieldDescription className="text-xs">{t(`wn.levelHint.${level}`)}</FieldDescription>
        </ShadcnField>
        <ShadcnField role={undefined} className="min-w-0 gap-1.5">
          <FieldTitle className="font-semibold">{t('wn.kind')}</FieldTitle>
          <SegmentedControl value={kind} onChange={onKindChange} ariaLabel={t('wn.kind')} options={kindOptions}
            className={SEGMENT_TOUCH} />
          <FieldDescription className="text-xs">{kind === 'MONITOR' ? t('wn.kindMonitorHint') : t('wn.kindCertHint')}</FieldDescription>
        </ShadcnField>
      </CardContent>
      <CardFooter className="flex flex-col items-stretch gap-3 border-t px-4 pt-4 sm:flex-row sm:items-center sm:justify-between sm:px-6 [.border-t]:pt-4">
        <p className="text-xs text-muted-foreground">{t('wn.autoRun')}</p>
        <Button type="button" onClick={onRun} disabled={!teamId || running} aria-busy={running || undefined}
          className="h-10 w-full sm:w-auto md:h-9">
          {running ? <Spinner decorative size={14} /> : <Play aria-hidden="true" />} {t('wn.simulate')}
        </Button>
      </CardFooter>
    </Card>
  )
}
