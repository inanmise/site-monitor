import { useState } from 'react'
import { CalendarClock, CalendarPlus } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import { countText } from '../dbquery/dbqueryPageModel.js'
import { formatHoliday } from './holidayCalendarModel.js'

/**
 * Tatil takviminin kapsam uyarısı (2026-10-31, 2.A — Veritabanı Sorgu İzleme). `coverage` = `holidayCoverage(...)`:
 * takvim ≤ 60 gün içinde bitiyorsa / bittiyse / boşsa uyarı bandı ("tatillerde çalışma" açık izlemeler tatilde sessizce
 * çalışmaya başlar). Global yönetici (`canEdit`) "YYYY sabit tarihli resmî tatillerini ekle" der
 * (`POST /api/admin/holidays/add-fixed?year=`: 1 Ocak, 23 Nisan, 1 Mayıs, 19 Mayıs, 15 Temmuz, 30 Ağustos, 29 Ekim — var
 * olan güne dokunmaz); dini bayramlar her yıl değişir, ELLE girilir — metin söyler. `blockedReason`: düğme kapalı + neden
 * (ayar sayfasında kaydedilmemiş taslak varken). `showOk`: kapsam yeterliyken de tek satır bilgi.
 *
 * <p>Sunucu alanları yoksa (eski sunucu, `coverage` null) hiçbir şey çizilmez — istemci kapsam uydurmaz.
 * Test kancaları: `holiday-coverage` (+ `data-state` ok|soon|ended|empty), düğme `holiday-add-fixed`.
 */
export default function HolidayCoverageNotice({ coverage, canEdit = false, onAdded, blockedReason = null, showOk = false, className = 'mb-0' }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  if (!coverage) return null
  const state = coverage.until == null ? 'empty' : coverage.ended ? 'ended' : coverage.endsSoon ? 'soon' : 'ok'
  if (state === 'ok') {
    if (!showOk) return null
    return (
      <p data-slot="holiday-coverage" data-state="ok" className="m-0 inline-flex items-start gap-1.5 text-xs text-muted-foreground">
        <CalendarClock aria-hidden="true" className="mt-px size-3.5 shrink-0" />
        <span className="[overflow-wrap:anywhere]">{t('dbq.hol.cov.ok', formatHoliday(coverage.until, lang))}</span>
      </p>
    )
  }
  const year = coverage.nextYear
  const addFixed = async () => {
    if (busy) return
    setBusy(true)
    try {
      const res = await api.admin.addFixedHolidays(year)
      if (res?.success) {
        const added = Number(res.data?.added)
        toast.success(Number.isInteger(added) ? countText(t, 'dbq.hol.cov.added', added, added) : t('dbq.hol.cov.addedGeneric', year))
        onAdded?.(res.data || null)
      } else if (res?.status === 404 || res?.status === 405) {
        toast.error(t('dbq.hol.cov.unsupported'))
      } else {
        toast.error(res?.error || res?.message || t('dbq.hol.cov.addFailed'))
      }
    } catch (e) {
      toast.error(e?.message || t('dbq.hol.cov.addFailed'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div data-slot="holiday-coverage" data-state={state} className={className}>
      <AlertBanner tone="warning" icon={CalendarClock} className="mb-0"
        title={t(state === 'empty' ? 'dbq.hol.cov.emptyTitle' : state === 'ended' ? 'dbq.hol.cov.endedTitle' : 'dbq.hol.cov.soonTitle')}>
        <span className="flex min-w-0 flex-col gap-1.5 [overflow-wrap:anywhere]">
          <span>{state === 'empty' ? t('dbq.hol.cov.emptyBody') : t('dbq.hol.cov.body', formatHoliday(coverage.until, lang))}</span>
          <span className="text-xs">{t('dbq.hol.cov.religious')}</span>
          {canEdit && (
            <span className="flex min-w-0 flex-col gap-1">
              <Button type="button" variant="outline" size="sm" onClick={addFixed} disabled={busy || !!blockedReason}
                aria-busy={busy || undefined} data-slot="holiday-add-fixed" className="self-start whitespace-normal pointer-coarse:h-10">
                {busy ? <Spinner size={14} inline decorative /> : <CalendarPlus aria-hidden="true" />}{t('dbq.hol.cov.addFixed', year)}
              </Button>
              {blockedReason && <span className="text-xs">{blockedReason}</span>}
            </span>
          )}
        </span>
      </AlertBanner>
    </div>
  )
}
