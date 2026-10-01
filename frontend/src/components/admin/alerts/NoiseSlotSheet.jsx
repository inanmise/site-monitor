// Gürültü analizi → ısı haritası hücresi ayrıntısı (2026-10-01, kullanıcı: "Day × hour density hücreleri tıklanmıyor").
// Hücre (haftanın günü × saat, İstanbul) pencere boyunca birden çok haftaya yayılır — tek tarih aralığı süzgeci bunu
// ifade edemez; bu yüzden sunucu dilimi listeler (`GET /api/admin/alerts/noise/slot`), satır alarm ayrıntısını açar.
// Gün / saat seçicileriyle komşu hücrelere panel kapanmadan geçilir (dokunmatikte küçük hücreler yerine rahat hedef).
import { useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight, ExternalLink } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { AlertLevelBadge } from './AlertBadges.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'

const HOURS = Array.from({ length: 24 }, (_, h) => h)

export default function NoiseSlotSheet({ slot, days, teamId, dayNames, onClose, onSlotChange, onOpenAlert }) {
  const t = useT()
  const open = !!slot
  const [state, setState] = useState({ loading: false, error: false, data: null })

  useEffect(() => {
    if (!slot) return undefined
    let alive = true
    setState({ loading: true, error: false, data: null })
    api.admin.getAlertNoiseSlot(days, slot.dow, slot.hour, teamId).then((r) => {
      if (!alive) return
      if (r?.success && r.data && !Array.isArray(r.data)) setState({ loading: false, error: false, data: r.data })
      else setState({ loading: false, error: true, data: null })
    }).catch(() => { if (alive) setState({ loading: false, error: true, data: null }) })
    return () => { alive = false }
  }, [slot, days, teamId])

  const hh = (h) => `${String(h).padStart(2, '0')}:00`
  const step = (delta) => {
    if (!slot) return
    let dow = slot.dow, hour = slot.hour + delta
    if (hour > 23) { hour = 0; dow = (dow + 1) % 7 }
    if (hour < 0) { hour = 23; dow = (dow + 6) % 7 }
    onSlotChange({ dow, hour })
  }
  const d = state.data
  const items = d?.items || []

  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-md" data-slot="noise-slot-sheet">
        <SheetHeader className="border-b">
          <SheetTitle>{slot ? t('noise.slot.title', dayNames[slot.dow] ?? '', hh(slot.hour)) : ''}</SheetTitle>
          <SheetDescription>{t('noise.slot.desc', days)}</SheetDescription>
          {slot && (
            <div className="flex flex-wrap items-end gap-2 pt-2" data-slot="noise-slot-nav">
              <Button type="button" variant="outline" size="icon" className="size-10" onClick={() => step(-1)} aria-label={t('noise.slot.prev')}>
                <ChevronLeft aria-hidden="true" />
              </Button>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <Label htmlFor="noise-slot-day" className="text-xs text-muted-foreground">{t('noise.slot.day')}</Label>
                <NativeSelect id="noise-slot-day" value={String(slot.dow)} onChange={(e) => onSlotChange({ ...slot, dow: Number(e.target.value) })} className="w-full">
                  {dayNames.map((n, i) => <NativeSelectOption key={i} value={String(i)}>{n}</NativeSelectOption>)}
                </NativeSelect>
              </div>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <Label htmlFor="noise-slot-hour" className="text-xs text-muted-foreground">{t('noise.slot.hour')}</Label>
                <NativeSelect id="noise-slot-hour" value={String(slot.hour)} onChange={(e) => onSlotChange({ ...slot, hour: Number(e.target.value) })} className="w-full">
                  {HOURS.map((h) => <NativeSelectOption key={h} value={String(h)}>{hh(h)}</NativeSelectOption>)}
                </NativeSelect>
              </div>
              <Button type="button" variant="outline" size="icon" className="size-10" onClick={() => step(1)} aria-label={t('noise.slot.next')}>
                <ChevronRight aria-hidden="true" />
              </Button>
            </div>
          )}
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
          {state.loading && <LoadingBlock label={t('noise.slot.loading')} className="justify-start px-0 py-4" />}
          {state.error && <StatusBlock tone="danger" title={t('noise.slot.error')} />}
          {d && (
            <p className="m-0 text-sm text-muted-foreground" data-slot="noise-slot-count">
              {d.truncated ? t('noise.slot.countTruncated', d.total, items.length) : t('noise.slot.count', d.total)}
            </p>
          )}
          {d && items.length === 0 && <StatusBlock tone="neutral" title={t('noise.slot.empty')} />}
          {items.length > 0 && (
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0" data-slot="noise-slot-list">
              {items.map((a) => (
                <li key={a.id}>
                  <Button type="button" variant="outline" data-slot="noise-slot-item" data-alert-id={a.id}
                    className="h-auto min-h-10 w-full flex-col items-stretch gap-1 px-3 py-2 text-left whitespace-normal"
                    onClick={() => onOpenAlert?.(a)} aria-label={t('noise.slot.open', a.domain || `#${a.id}`)}>
                    <span className="flex min-w-0 items-center gap-1.5">
                      <AlertLevelBadge level={a.alert_level} className="text-[0.7em]" />
                      <span className="min-w-0 flex-1 truncate font-semibold">{a.domain || `#${a.id}`}</span>
                      <ExternalLink aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                    </span>
                    <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-normal text-muted-foreground">
                      <Badge variant="outline" className="text-[10px]">{a.alert_type}</Badge>
                      <span className="tabular-nums">{formatDate(a.created_at)}</span>
                      <Badge variant={a.resolved ? 'secondary' : 'destructive'} className="text-[10px]">
                        {a.resolved ? t('noise.slot.resolved') : t('noise.slot.open2')}
                      </Badge>
                      {a.team_name && <TeamBadge teamId={a.team_id} teamName={a.team_name} size={11} static />}
                    </span>
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
