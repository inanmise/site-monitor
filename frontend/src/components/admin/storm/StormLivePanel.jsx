// Ayarlar → Alarm Fırtınası → Canlı durum (2026-09-30): takımların şu anki pencere sayımı / açık fırtınalar — sayfanın
// kompakt hâli; ayarları değiştirmeden önce "kim eşiğe yakın" görülür. Veri boş / dizi (fallback mock) → sessizce boş durum.
import { useCallback, useEffect, useState } from 'react'
import { CloudLightning, ExternalLink, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { navigateTo } from '../../../utils/navigate.js'
import { SettingsSection } from '../SettingsControls.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import StormTeamCards from '../../storm/StormTeamCards.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'

export default function StormLivePanel() {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null })

  const load = useCallback(async (fresh = false) => {
    try {
      const res = await api.monitoring.storm.status(fresh === true)
      setState({ loading: false, data: res?.success && res.data && !Array.isArray(res.data) ? res.data : null })
    } catch { setState({ loading: false, data: null }) }
  }, [])
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, 30_000, false)

  const teams = state.data?.teams || []
  const totals = state.data?.totals || {}
  return (
    <SettingsSection title={t('sf.settings.liveTitle')} description={t('sf.settings.liveDesc')} contentClassName="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2" data-slot="sf-live-toolbar">
        <Badge variant="outline" data-slot="sf-live-storming" className="gap-1"><CloudLightning aria-hidden="true" className="size-3" />{t('sf.kpi.storming')}: <b className="tabular-nums">{totals.storming ?? 0}</b></Badge>
        <Badge variant="outline" className="gap-1">{t('sf.kpi.near')}: <b className="tabular-nums">{totals.near ?? 0}</b></Badge>
        <Badge variant="outline" className="gap-1">{t('sf.kpi.open')}: <b className="tabular-nums">{totals.open_storms ?? 0}</b></Badge>
        <div className="ml-auto flex gap-1">
          <Button type="button" size="sm" variant="ghost" className="pointer-coarse:h-10" onClick={() => load(true)} aria-label={t('sf.refresh')} title={t('sf.refresh')}><RefreshCw aria-hidden="true" /></Button>
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => navigateTo('storms')}>
            <ExternalLink aria-hidden="true" />{t('sf.settings.openPage')}
          </Button>
        </div>
      </div>
      {state.loading ? (
        <LoadingBlock label={t('sf.loading')} className="justify-start px-0 py-4" />
      ) : teams.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground" data-slot="sf-live-empty">{t('sf.settings.empty')}</p>
      ) : (
        <StormTeamCards teams={teams} compact />
      )}
    </SettingsSection>
  )
}
