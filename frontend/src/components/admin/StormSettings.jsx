import { useState, useEffect } from 'react'
import { CloudLightning } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { SETTINGS_STACK, helpLabel, MasterToggleCard, SettingsHeader, SettingsSaveBar, SettingsSection, ToggleRow } from './SettingsControls.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Slider } from '@/components/shadcn/slider'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * "Alert Settings" — Alarm fırtınası (alert storm) yapılandırması. Master toggle + eşik (sayı + birim)
 * + zaman penceresi (1–15 dk slider) + grup-bazlı toggle + Kaydet. Kalıcılık site.monitor.storm.* key'lerine
 * (StormSettingsController → AppSettingsService) gider; değişiklik CANLI yansır. Default AÇIK.
 * Çizim shadcn: SettingsHeader/MasterToggleCard/SettingsSection, Switch (ToggleRow), Input + NativeSelect, Slider.
 */
export default function StormSettings() {
  const t = useT()
  const toast = useToast()

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [enabled, setEnabled] = useState(true)
  const [unit, setUnit] = useState('COUNT')
  const [value, setValue] = useState(5)
  const [windowMin, setWindowMin] = useState(5)
  const [perGroup, setPerGroup] = useState(false)
  const [total, setTotal] = useState(0)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.monitoring.storm.getSettings()
      if (res?.success) applyData(res.data)
      else toast.error(res?.error || t('settings.loadError'))
    } finally {
      setLoading(false)
    }
  }

  function applyData(d) {
    setEnabled(!!d.enabled)
    setUnit(d.threshold_unit || 'COUNT')
    setValue(Number(d.threshold_value ?? 5))
    setWindowMin(Number(d.window_minutes ?? 5))
    setPerGroup(!!d.per_group)
    setTotal(Number(d.total_active_monitors ?? 0))
  }

  function validate() {
    const v = Number(value)
    if (unit === 'PERCENT') {
      if (!(v >= 1 && v <= 100)) return t('storm.errPercent')
    } else if (!(v >= 2)) {
      return t('storm.errCount')
    }
    if (!(windowMin >= 1 && windowMin <= 15)) return t('storm.errWindow')
    return null
  }

  async function save() {
    const err = validate()
    if (err) { toast.error(err); return }
    setSaving(true)
    try {
      const res = await api.monitoring.storm.saveSettings({
        enabled,
        threshold_unit: unit,
        threshold_value: Number(value),
        window_minutes: Number(windowMin),
        per_group: perGroup,
      })
      if (res?.success) { applyData(res.data); toast.success(t('storm.saved')) }
      else toast.error(res?.error || res?.message || t('settings.saveError'))
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  // Yüzde önizlemesi: ceil(value/100 × total), taban 2 (sunucu ile aynı round kuralı). 2026-09-29: fırtına TAKIM
  // BAZINDA değerlendirilir — yüzde her takımın KENDİ aktif izlemelerinden; buradaki kuruluş toplamı yalnız bir örnektir.
  // Yüzde kipinde mutlak taban 3 farklı hedef (O-4, sunucu StormService.PERCENT_MIN_TARGETS ile aynı).
  const pctPreview = Math.max(3, Math.ceil((Number(value) || 0) / 100 * total))
  const unitLabel = t('storm.unitCount') + ' / ' + t('storm.unitPercentTeam')

  return (
    <div className={SETTINGS_STACK} data-testid="storm-settings">
      <SettingsHeader icon={CloudLightning} description={t('storm.desc')}
        title={<>{t('storm.title')} <Badge variant="warning" className="font-bold tracking-wider">BETA</Badge></>} />

      {/* Takım yalıtımı (ürün kararı 2026-09-29): eşik takım kümesinde, bildirim yalnız o takıma */}
      <AlertBanner tone="info" className="mb-0">{t('storm.teamScopeNote')}</AlertBanner>

      {/* Master toggle */}
      <MasterToggleCard checked={enabled} onChange={setEnabled} label={t('storm.enabled')}
        helpKey="help.set.site.monitor.storm.enabled" hint={t('storm.enabledHint')}>
        {!enabled && <AlertBanner tone="warning" className="mt-1 mb-0">{t('storm.disabledWarn')}</AlertBanner>}
      </MasterToggleCard>

      {/* Eşik + pencere geniş ekranda yan yana */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        {/* Threshold: number + unit */}
        <SettingsSection title={helpLabel(t('storm.thresholdTitle'), 'help.set.site.monitor.storm.threshold-value')}
          description={t('storm.thresholdDesc')} contentClassName="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <Input type="number" className="w-28" aria-label={t('storm.thresholdTitle')}
              min={unit === 'PERCENT' ? 1 : 2} max={unit === 'PERCENT' ? 100 : 100000} value={value}
              onChange={(e) => setValue(e.target.value === '' ? '' : Number(e.target.value))} />
            <NativeSelect value={unit} aria-label={unitLabel} onChange={(e) => setUnit(e.target.value)}>
              <NativeSelectOption value="COUNT">{t('storm.unitCount')}</NativeSelectOption>
              <NativeSelectOption value="PERCENT">{t('storm.unitPercentTeam')}</NativeSelectOption>
            </NativeSelect>
            <HelpTip helpKey="help.set.site.monitor.storm.threshold-unit" label={unitLabel} />
          </div>
          <p className="text-xs text-muted-foreground">
            {unit === 'PERCENT' ? t('storm.pctPreviewTeam', value || 0, total, pctPreview) : t('storm.countHint')}
          </p>
        </SettingsSection>

        {/* Time window slider (1–15 min) — shadcn Slider (Radix); başparmak adı + okunur değer */}
        <SettingsSection title={helpLabel(t('storm.windowTitle'), 'help.set.site.monitor.storm.window-minutes')}
          description={t('storm.windowDesc')} contentClassName="flex flex-col gap-2">
          <div className="flex max-w-[560px] items-center gap-4">
            <Slider min={1} max={15} step={1} value={[windowMin]} className="py-1.5"
              onValueChange={([v]) => setWindowMin(v)}
              thumbProps={{ 'aria-label': t('storm.windowTitle'), 'aria-valuetext': t('storm.windowValue', windowMin) }} />
            <span className="min-w-16 shrink-0 text-sm font-semibold tabular-nums">{t('storm.windowValue', windowMin)}</span>
          </div>
          <div className="flex max-w-[480px] justify-between text-[11px] text-muted-foreground" aria-hidden="true">
            {[1, 5, 10, 15].map((n) => (
              <span key={n} data-active={n === windowMin ? 'true' : undefined}
                className={cn('tabular-nums', n === windowMin && 'font-semibold text-foreground')}>{n}</span>
            ))}
          </div>
        </SettingsSection>
      </div>

      {/* Per-group toggle */}
      <Card className="gap-2 py-4">
        <CardContent className="flex flex-col gap-2 px-4 sm:px-6">
          <ToggleRow checked={perGroup} onChange={setPerGroup} label={t('storm.perGroup')}
            helpKey="help.set.site.monitor.storm.per-group" />
          <p className="text-xs text-muted-foreground">{t('storm.perGroupHintTeam')}</p>
        </CardContent>
      </Card>

      {/* Save */}
      <SettingsSaveBar saving={saving} onSave={save} saveLabel={t('storm.save')} />
    </div>
  )
}
