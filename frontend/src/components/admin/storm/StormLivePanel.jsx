// Ayarlar → Alarm Fırtınası → Canlı durum (2026-09-30): takımların şu anki pencere sayımı / açık fırtınalar — sayfanın
// kompakt hâli; ayarları değiştirmeden önce "kim eşiğe yakın" görülür. Veri boş / dizi (fallback mock) → sessizce boş durum.
// Okuma HATASI (403 / 500 / ağ) boş durum DEĞİLDİR (2026-10-22, L-S3 / UIA-C6): eskiden "0 fırtına / 0 açık" + "Görüş kapsamında
// takım yok" çiziliyordu — hata + yeniden dene gösterilir; önceki iyi veri varsa korunur (uyarı şeridiyle), sayılar "—" değil.
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
  const [state, setState] = useState({ loading: true, data: null, failed: false })

  const load = useCallback(async (fresh = false) => {
    let res = null
    try { res = await api.monitoring.storm.status(fresh === true) } catch { res = null }
    if (res?.success) {
      setState({ loading: false, data: res.data && !Array.isArray(res.data) ? res.data : null, failed: false })
    } else {
      // Başarısız yoklama eldeki iyi veriyi SİLMEZ
      setState((s) => ({ loading: false, data: s.data, failed: true }))
    }
  }, [])
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, 30_000, false)

  const teams = state.data?.teams || []
  const totals = state.data?.totals || {}
  // Hiç okunamadıysa sayaçlar "—" (0 değil)
  const unknownCounts = state.failed && !state.data
  const n = (v) => (unknownCounts ? '—' : (v ?? 0))
  return (
    <SettingsSection title={t('sf.settings.liveTitle')} description={t('sf.settings.liveDesc')} contentClassName="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2" data-slot="sf-live-toolbar">
        <Badge variant="outline" data-slot="sf-live-storming" className="gap-1"><CloudLightning aria-hidden="true" className="size-3" />{t('sf.kpi.storming')}: <b className="tabular-nums">{n(totals.storming)}</b></Badge>
        <Badge variant="outline" className="gap-1">{t('sf.kpi.near')}: <b className="tabular-nums">{n(totals.near)}</b></Badge>
        <Badge variant="outline" className="gap-1">{t('sf.kpi.open')}: <b className="tabular-nums">{n(totals.open_storms)}</b></Badge>
        <div className="ml-auto flex gap-1">
          <Button type="button" size="sm" variant="ghost" className="pointer-coarse:h-10" onClick={() => load(true)} aria-label={t('sf.refresh')} title={t('sf.refresh')}><RefreshCw aria-hidden="true" /></Button>
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => navigateTo('storms')}>
            <ExternalLink aria-hidden="true" />{t('sf.settings.openPage')}
          </Button>
        </div>
      </div>
      {state.failed && state.data && (
        <p data-slot="sf-live-stale" role="status" className="m-0 text-xs text-amber-700 dark:text-amber-300">{t('storm.live.staleData')}</p>
      )}
      {state.loading ? (
        <LoadingBlock label={t('sf.loading')} className="justify-start px-0 py-4" />
      ) : unknownCounts ? (
        <div data-slot="sf-live-error" role="alert" className="flex flex-wrap items-center gap-2 text-sm text-destructive">
          <span>{t('storm.live.loadFailed')}</span>
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => load(true)}>{t('hist.retry')}</Button>
        </div>
      ) : teams.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground" data-slot="sf-live-empty">{t('sf.settings.empty')}</p>
      ) : (
        <StormTeamCards teams={teams} compact />
      )}
    </SettingsSection>
  )
}
