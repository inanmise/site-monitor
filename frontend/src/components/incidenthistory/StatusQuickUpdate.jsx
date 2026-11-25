import { useEffect, useRef, useState } from 'react'
import { ChevronDown, ArrowRight, Check, RefreshCw, ListChecks } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { StatusBadge } from './HistoryBadges.jsx'
import { DateInput, CheckInput } from './formFields.jsx'
import { resolvedNowIso, formFromRow } from './incidentHistoryModel.js'
import { nextStatus, statusChoices, statusChangeRequest } from './quickEntryModel.js'

/** Olay / satır tıklamasına yayılmasın (popover içeriği React ağacında satırın çocuğudur — portal da olsa olay kabarır). */
const stop = (e) => e.stopPropagation()

/**
 * Tek tıkla durum güncelleme ("Hızlı Giriş", 2026-11-20) — ayrıntıda ve liste satırında (tablo / kart). Şimdiki durum
 * dışındaki durumlar listelenir, akıştaki SIRADAKİ (Açık → İnceleniyor → Hafifletildi → Çözüldü) vurgulanır. Basit geçiş tek
 * tıklamayla kaydedilir; "Çözüldü"ye geçişte çözülme zamanı (varsayılan şimdi — oluş / tespit zamanından önce olamaz)
 * sorulur, Çözüldü'den geri açışta çözülme zamanını temizleme seçeneği gelir. "Kaydederken e-posta gönder" isteğe bağlı.
 *
 * <p>Gövde formla AYNI kuraldır: yalnız DEĞİŞEN alanlar + `version` (`editPayload`) — başkası bu arada kaydettiyse sunucu
 * 409 döner, kim / ne zaman yazılır ve "son hâli yükle" (`onReload`) önerilir; 404 → kayıt silinmiş. Meşgulken pencere
 * kapanmaz, düğmeler kapalıdır (satır başına ayrı meşgul durumu). Başarıda `onSaved(kayıt)`.
 *
 * <p>`variant`: `badge` (tablo / kart — durum rozeti tetik), `button` (ayrıntı eylemleri).
 */
export default function StatusQuickUpdate({ record, onSaved, onReload, variant = 'badge', className }) {
  const t = useT()
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [step, setStep] = useState(null)          // null | 'RESOLVED' | 'reopen:<durum>'
  const [resolvedAt, setResolvedAt] = useState('')
  const [clearResolved, setClearResolved] = useState(true)
  const [sendMail, setSendMail] = useState(false)
  const [busy, setBusy] = useState(null)          // kaydedilen hedef durum
  const [problem, setProblem] = useState(null)    // { kind: 'conflict'|'gone'|'error', by, at, message }
  const [fieldErrors, setFieldErrors] = useState(null)
  const aliveRef = useRef(true)
  // Kurulumda da true: StrictMode (geliştirme) etkiyi kur → temizle → kur diye çalıştırır; yalnız temizlikte false yazılsa
  // ikinci kurulumdan sonra bayrak false kalır ve kayıt yanıtı yok sayılır (pencere "meşgul"de takılırdı).
  useEffect(() => {
    aliveRef.current = true
    return () => { aliveRef.current = false }
  }, [])

  const r = record || {}
  const title = r.title || String(r.id ?? '')
  const current = r.status
  const next = nextStatus(current)
  const choices = statusChoices(current)

  const reset = () => {
    setStep(null); setProblem(null); setFieldErrors(null); setSendMail(false); setClearResolved(true); setResolvedAt('')
  }
  const onOpenChange = (o) => {
    if (!o && busy) return                       // kayıt sürerken kapanmaz
    setOpen(o)
    if (!o) reset()
  }

  async function save(target) {
    if (busy) return
    const opts = { sendMail, clearResolved }
    if (target === 'RESOLVED') opts.resolvedAt = resolvedAt || undefined
    const req = statusChangeRequest(r, target, opts)
    if (Object.keys(req.errors).length) {
      setFieldErrors(Object.fromEntries(Object.entries(req.errors).map(([k, e]) => [k, typeof e === 'string' ? t(e) : t(e.key, ...(e.params || []))])))
      return
    }
    setBusy(target)
    setProblem(null)
    setFieldErrors(null)
    let res = null
    try { res = await api.incidents.update(r.id, req.body) } catch { res = null }
    if (!aliveRef.current) return
    setBusy(null)
    if (res?.success) {
      toast.success(t('inc.qs.saved', t('inc.st' + target)))
      setOpen(false)
      reset()
      onSaved?.(res.data && typeof res.data === 'object' ? res.data : null)
      return
    }
    const info = errorInfoOf(res)
    if (info?.code === 'CONCURRENT_MODIFICATION') {
      setProblem({ kind: 'conflict', by: res?.updated_by ?? null, at: res?.updated_at ?? null })
    } else if (info?.status === 404) {
      setProblem({ kind: 'gone' })
    } else {
      const fields = serverFieldErrors(res)
      if (fields && Object.keys(fields).length) setFieldErrors(fields)
      setProblem({ kind: 'error', message: res?.error || t('inc.qs.failed') })
    }
  }

  const choose = (target) => {
    if (target === 'RESOLVED') {
      setStep('RESOLVED')
      setResolvedAt(r.resolved_at || resolvedNowIso(formFromRow(r)))
      setFieldErrors(null)
      return
    }
    if (current === 'RESOLVED' && r.resolved_at) {
      setStep('reopen:' + target)
      setFieldErrors(null)
      return
    }
    save(target)
  }

  const reload = () => {
    setOpen(false)
    reset()
    onReload?.(r)
  }

  const trigger = variant === 'button' ? (
    <Button type="button" variant="outline" size="sm" className={cn('h-9 pointer-coarse:h-10', className)} data-slot="ih-qs-trigger"
      aria-label={t('inc.qs.triggerFor', t('inc.st' + current) || current || '—', title)}>
      <ListChecks aria-hidden="true" />{t('inc.qs.trigger')}<ChevronDown aria-hidden="true" />
    </Button>
  ) : (
    <Button type="button" variant="ghost" size="sm" data-slot="ih-qs-trigger"
      className={cn('h-auto min-h-7 gap-1 px-1 py-0.5 hover:bg-muted pointer-coarse:min-h-10', className)}
      aria-label={t('inc.qs.triggerFor', t('inc.st' + current) || current || '—', title)}
      onClick={stop} onKeyDown={stop}>
      <StatusBadge status={current} />
      <ChevronDown aria-hidden="true" className="size-3.5 text-muted-foreground" />
    </Button>
  )

  const reopenTarget = step?.startsWith('reopen:') ? step.slice(7) : null

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent align="start" sideOffset={6} collisionPadding={8} data-slot="ih-qs" data-incident-id={r.id}
        className="z-(--z-menu) flex max-h-(--radix-popover-content-available-height) w-80 max-w-[calc(100vw-1rem)] flex-col gap-3 overflow-y-auto p-3"
        onClick={stop} onKeyDown={stop}>
        <div className="flex min-w-0 flex-col gap-1">
          <p className="m-0 text-sm font-semibold">{t('inc.qs.title')}</p>
          <p className="m-0 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            {t('inc.qs.current')} <StatusBadge status={current} />
          </p>
        </div>

        {problem?.kind === 'conflict' && (
          <div data-slot="ih-qs-conflict">
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.qs.conflictTitle')}>
              <span className="block text-xs">
                {problem.by && problem.at ? t('inc.qs.conflictBy', problem.by, formatDate(problem.at)) : t('inc.qs.conflictAnon')}
              </span>
              <span className="mt-2 flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" data-action="qs-reload" onClick={reload}>
                  <RefreshCw aria-hidden="true" />{t('inc.fresh.loadLatest')}
                </Button>
              </span>
            </AlertBanner>
          </div>
        )}
        {problem?.kind === 'gone' && (
          <div data-slot="ih-qs-gone">
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.qs.gone')}>
              <span className="mt-2 flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" onClick={reload}>
                  <RefreshCw aria-hidden="true" />{t('inc.refresh')}
                </Button>
              </span>
            </AlertBanner>
          </div>
        )}
        {problem?.kind === 'error' && (
          <div data-slot="ih-qs-error">
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('inc.qs.failedTitle')}>
              <span className="block text-xs [overflow-wrap:anywhere]">{problem.message}</span>
            </AlertBanner>
          </div>
        )}

        {step === null && (
          <>
            <div role="group" aria-label={t('inc.qs.choose')} className="flex min-w-0 flex-col gap-1.5">
              {choices.map((s) => {
                const isNext = s === next
                return (
                  <Button key={s} type="button" variant={isNext ? 'default' : 'outline'} size="sm" data-status={s} data-next={isNext || undefined}
                    disabled={!!busy || problem?.kind === 'gone' || problem?.kind === 'conflict'} aria-busy={busy === s || undefined}
                    className="h-auto min-h-9 justify-start gap-2 whitespace-normal text-left pointer-coarse:min-h-10" onClick={() => choose(s)}>
                    {busy === s ? <Spinner size={14} decorative /> : (isNext ? <ArrowRight aria-hidden="true" /> : <Check aria-hidden="true" className="opacity-0" />)}
                    <span className="min-w-0 flex-1">{t('inc.st' + s)}</span>
                    {isNext && <Badge variant="secondary" className="shrink-0">{t('inc.qs.next')}</Badge>}
                  </Button>
                )
              })}
            </div>
            <div className="-mb-3.5">
              <CheckInput label={t('inc.qs.sendMail')} hint={t('inc.qs.sendMailHint')} checked={sendMail} disabled={!!busy}
                onChange={setSendMail} />
            </div>
          </>
        )}

        {step === 'RESOLVED' && (
          <div data-slot="ih-qs-resolve" className="flex min-w-0 flex-col gap-2">
            <p className="m-0 text-xs text-muted-foreground">{t('inc.qs.resolveHint')}</p>
            <DateInput label={t('inc.fResolvedAt')} req name="resolved_at" value={resolvedAt} error={fieldErrors?.resolved_at}
              min={r.detected_at || r.occurred_at || undefined} minLabel={r.detected_at ? t('inc.fDetectedAt') : t('inc.fOccurredAt')}
              anchor={r.occurred_at || undefined} anchorLabel={t('inc.fOccurredAt')}
              onChange={(v) => { setResolvedAt(v || ''); setFieldErrors(null) }} />
            <div className="-mb-3.5">
              <CheckInput label={t('inc.qs.sendMail')} hint={t('inc.qs.sendMailHint')} checked={sendMail} disabled={!!busy} onChange={setSendMail} />
            </div>
            <div className="flex flex-wrap justify-end gap-2">
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" disabled={!!busy} onClick={() => setStep(null)}>
                {t('inc.qs.back')}
              </Button>
              <Button type="button" size="sm" className="pointer-coarse:h-10" data-action="qs-save" disabled={!!busy || !resolvedAt}
                aria-busy={busy === 'RESOLVED' || undefined} onClick={() => save('RESOLVED')}>
                {busy === 'RESOLVED' ? <Spinner size={14} decorative /> : <Check aria-hidden="true" />}{t('inc.qs.saveResolved')}
              </Button>
            </div>
          </div>
        )}

        {reopenTarget && (
          <div data-slot="ih-qs-reopen" className="flex min-w-0 flex-col gap-2">
            <p className="m-0 text-xs text-muted-foreground">{t('inc.qs.reopenHint', t('inc.st' + reopenTarget))}</p>
            <div className="-mb-3.5">
              <CheckInput label={t('inc.qs.clearResolved')} hint={t('inc.qs.clearResolvedHint')} checked={clearResolved}
                disabled={!!busy} onChange={setClearResolved} />
            </div>
            <div className="-mb-3.5">
              <CheckInput label={t('inc.qs.sendMail')} hint={t('inc.qs.sendMailHint')} checked={sendMail} disabled={!!busy} onChange={setSendMail} />
            </div>
            {fieldErrors?.resolved_at && <p className="m-0 text-xs text-destructive">{fieldErrors.resolved_at}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" disabled={!!busy} onClick={() => setStep(null)}>
                {t('inc.qs.back')}
              </Button>
              <Button type="button" size="sm" className="pointer-coarse:h-10" data-action="qs-save" disabled={!!busy}
                aria-busy={busy === reopenTarget || undefined} onClick={() => save(reopenTarget)}>
                {busy === reopenTarget ? <Spinner size={14} decorative /> : <Check aria-hidden="true" />}{t('inc.qs.saveReopen', t('inc.st' + reopenTarget))}
              </Button>
            </div>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
