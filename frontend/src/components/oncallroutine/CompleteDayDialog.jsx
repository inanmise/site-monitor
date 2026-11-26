/**
 * Nöbet Rutinleri — "Günü tamamla". Özet kutucuklar (tamamlanan, atlanan, açık sorun, bekleyen); bekleyen zorunlu görev
 * varsa listesi + "Kalanları atla" (tek gerekçe, 3–300) — gerekçesiz onay kapalı. İsteğe bağlı "Günün notu" (e-postaya
 * girer). Bildirim önizlemesi takım ayarından ("takım adresine e-posta + üyelere push"). Başkası adına tamamlarken söylenir.
 * Ctrl/⌘+Enter onaylar. Sonucu belirsiz yanıtta gün yeniden okunur — yazma körlemesine tekrarlanmaz.
 */
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { CheckCheck, Mail, BellRing, BellOff, Info } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import {
  DAY_NOTE_MAX, SKIP_REASON_MAX, SKIP_REASON_MIN, codeOf, isCompletedStatus, pendingRequired, shortDate, thrownResult,
} from './routineModel.js'
import { serverText } from './routineUi.jsx'

/** Bildirim önizleme metni anahtarı (takım ayarından). */
/** Bildirim önizlemesi için okunan ayar: takım başına kısa ömürlü bellek (her açılışta tüm şablon yeniden okunmasın). */
export const NOTICE_CACHE_MS = 60_000
const noticeCache = new Map()   // teamId → { at, settings }
export function resetNoticeCacheForTests() { noticeCache.clear() }

export function noticePreviewKey(settings) {
  const n = settings?.completion_notice
  if (!n) return 'or.complete.noticeUnknown'
  if (n.enabled === false || (!n.mail && !n.push)) return 'or.complete.noticeOff'
  if (n.mail && n.push) return 'or.complete.noticeBoth'
  return n.mail ? 'or.complete.noticeMail' : 'or.complete.noticePush'
}

export default function CompleteDayDialog({ open, onClose, teamId, sheet, progress, onCompleted, onReload }) {
  const t = useT()
  const toast = useToast()
  const uid = useId()
  const [pending, setPending] = useState(() => pendingRequired(sheet))
  const [skip, setSkip] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonErr, setReasonErr] = useState(null)
  const [note, setNote] = useState(sheet?.day?.note || '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  // Başkası tamamladıysa (409 DAY_COMPLETED) onay bir daha gönderilmez; pencere yalnız kapanır
  const [finished, setFinished] = useState(false)
  const [settings, setSettings] = useState(undefined)
  const reasonRef = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    setPending(pendingRequired(sheet))
    setNote(sheet?.day?.note || '')
    setError(null)
    // sheet değişince yeniden kurulmaz: pencere açılış anının listesiyle çalışır (sunucu DAY_NOT_READY ile tazeler)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Önizleme kaynağı: gün sayfası `settings.completion_notice` taşıyorsa o (ek istek yok); yoksa takımın ayarı — tüm
  // şablonu okuyan istek takım başına en çok dakikada bir (bellek), başarısız okuma bellekte tutulmaz
  const sheetNotice = sheet?.settings && Object.prototype.hasOwnProperty.call(sheet.settings, 'completion_notice')
    ? sheet.settings : null
  useEffect(() => {
    if (!open) return undefined
    if (sheetNotice) { setSettings(sheetNotice); return undefined }
    const hit = noticeCache.get(String(teamId))
    if (hit && Date.now() - hit.at < NOTICE_CACHE_MS) { setSettings(hit.settings); return undefined }
    const ac = new AbortController()
    api.oncallRoutines.template(teamId, { signal: ac.signal })
      .then((res) => {
        if (ac.signal.aborted) return
        const st = res?.success ? (res.data?.settings || null) : null
        if (st) noticeCache.set(String(teamId), { at: Date.now(), settings: st })
        setSettings(st)
      })
      .catch(() => { if (!ac.signal.aborted) setSettings(null) })
    return () => ac.abort()
  }, [open, teamId, sheetNotice])

  const r = sheet?.responsible
  const onBehalf = r?.name && r.is_me === false
  const resolved = Number(progress?.done || 0) + Number(progress?.skipped || 0)
  const needsSkip = pending.length > 0
  const canConfirm = !busy && !finished && (!needsSkip || skip)
  const noticeKey = settings === undefined ? null : noticePreviewKey(settings)

  const confirm = async () => {
    if (!canConfirm) {
      if (!finished && !busy && needsSkip && !skip) setError(t('or.complete.needSkip'))
      return
    }
    if (needsSkip) {
      const rr = reason.trim()
      if (rr.length < SKIP_REASON_MIN || rr.length > SKIP_REASON_MAX) {
        setReasonErr(t('or.complete.reasonRule', SKIP_REASON_MIN, SKIP_REASON_MAX))
        reasonRef.current?.focus()
        return
      }
    }
    setBusy(true)
    setError(null)
    const noteChanged = note.replace(/\r\n?/g, '\n').trim() !== String(sheet?.day?.note || '').trim()
    let res
    try {
      res = await api.oncallRoutines.completeDay(teamId, sheet.date, {
        version: sheet?.day?.version ?? 0,
        skip_pending: needsSkip ? { reason: reason.trim() } : null,
        note: noteChanged ? note : null,
      })
    } catch (e) {
      res = thrownResult(e)
    } finally {
      setBusy(false)
    }
    if (res === null) return
    if (res?.success) {
      onCompleted?.(res.data)
      const n = res.data?.notice
      toast.success(n?.enabled === false || n?.reason ? t('or.complete.doneQuiet') : t('or.complete.done'))
      onClose()
      return
    }
    const code = codeOf(res)
    if (code === 'DAY_NOT_READY' && Array.isArray(res.pending)) {
      setPending(res.pending.map((p) => ({ ...p, key: `${p.period_kind}:${p.period_key}:${p.task_id}` })))
      setError(t('or.complete.changedPending'))
      onReload?.()
      return
    }
    if (code === 'CONCURRENT_MODIFICATION' || code === 'DAY_COMPLETED') {
      if (code === 'DAY_COMPLETED') setFinished(true)
      setError(code === 'DAY_COMPLETED' ? t('or.complete.alreadyDone', res.completed_by_name || res.day?.completed_by_name || '—')
        : serverText(res, t, 'or.complete.saveFailed'))
      onReload?.()
      return
    }
    if (isUnsureResult(res)) {
      setError(t('or.complete.unsureChecking'))
      const fresh = await onReload?.()
      if (fresh && isCompletedStatus(fresh?.day?.status)) {
        toast.success(t('or.complete.done'))
        onClose()
      } else {
        setError(t('or.complete.unsureNotDone'))
      }
      return
    }
    setError(serverText(res, t, 'or.complete.saveFailed'))
  }

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); confirm() }
  }

  const tiles = useMemo(() => ([
    { k: 'done', label: t('or.complete.tileDone'), value: `${resolved}/${progress?.required ?? '—'}` },
    { k: 'skipped', label: t('or.complete.tileSkipped'), value: progress?.skipped ?? 0 },
    { k: 'problems', label: t('or.complete.tileProblems'), value: progress?.problems_open ?? '—' },
    { k: 'pending', label: t('or.complete.tilePending'), value: pending.length, warn: pending.length > 0 },
  ]), [pending.length, progress, resolved, t])

  return (
    <ModalShell open={open} onClose={busy ? () => {} : onClose} busy={busy} size="md" icon={CheckCheck} scrollBody
      title={t('or.complete.title', shortDate(sheet?.date, true))} className={PHONE_FULLSCREEN}
      footer={(
        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>{t('or.cancel')}</Button>
          <Button type="button" onClick={confirm} disabled={!canConfirm} data-slot="or-complete-confirm" aria-keyshortcuts="Control+Enter">
            {busy ? <Spinner /> : <CheckCheck aria-hidden="true" />}{t('or.complete.confirm')}
          </Button>
        </div>
      )}>
      <div data-slot="or-complete-dialog" className="flex flex-col gap-4" onKeyDown={onKeyDown}>
        <ul className="m-0 grid list-none grid-cols-2 gap-2 p-0 @container sm:grid-cols-4">
          {tiles.map((tl) => (
            <li key={tl.k} data-slot="or-complete-tile" data-tile={tl.k}
              className={cn('flex flex-col gap-0.5 rounded-lg border px-3 py-2', tl.warn && 'border-amber-500/40 bg-amber-500/10')}>
              <span className="text-[11px] text-muted-foreground">{tl.label}</span>
              <span className="text-lg leading-tight font-semibold tabular-nums">{tl.value}</span>
            </li>
          ))}
        </ul>

        {onBehalf && (
          <AlertBanner tone="info" icon={Info} className="mb-0">{t('or.complete.onBehalf', r.name)}</AlertBanner>
        )}

        {needsSkip && (
          <section className="flex flex-col gap-2" aria-labelledby={`${uid}-pending`}>
            <h3 id={`${uid}-pending`} className="m-0 text-sm font-semibold">{t('or.complete.pendingTitle', pending.length)}</h3>
            <ul data-slot="or-complete-pending" className="m-0 flex max-h-48 list-none flex-col gap-1 overflow-y-auto rounded-lg border p-2">
              {pending.map((p) => (
                <li key={p.key || `${p.period_kind}:${p.period_key}:${p.task_id}`} className="text-sm [overflow-wrap:anywhere]">
                  <span className="font-medium">{p.title}</span>
                  {p.section_title && <span className="text-xs text-muted-foreground"> · {p.section_title}</span>}
                </li>
              ))}
            </ul>
            <div className="flex items-start gap-3">
              <Checkbox id={`${uid}-skip`} checked={skip} onCheckedChange={(v) => { setSkip(v === true); setError(null); if (v === true) setTimeout(() => reasonRef.current?.focus(), 0) }}
                data-slot="or-complete-skip" className="mt-0.5" />
              <label htmlFor={`${uid}-skip`} className="text-sm">{t('or.complete.skipRest', pending.length)}</label>
            </div>
            {skip ? (
              <div className="flex flex-col gap-1 pl-7">
                <label htmlFor={`${uid}-reason`} className="text-xs font-medium text-muted-foreground">{t('or.complete.reasonLabel')}</label>
                <Input id={`${uid}-reason`} ref={reasonRef} data-slot="or-complete-skip-reason" value={reason} maxLength={SKIP_REASON_MAX}
                  aria-invalid={!!reasonErr || undefined} onChange={(e) => { setReason(e.target.value); setReasonErr(null) }}
                  placeholder={t('or.complete.reasonPlaceholder')} className="pointer-coarse:h-10" />
                {reasonErr && <p className="m-0 text-xs text-destructive" role="alert">{reasonErr}</p>}
              </div>
            ) : (
              <p className="m-0 pl-7 text-xs text-muted-foreground">{t('or.complete.tickFirst')}</p>
            )}
          </section>
        )}

        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${uid}-note`} className="text-sm font-medium">{t('or.dayNote.label')}</label>
          <Textarea id={`${uid}-note`} value={note} rows={3} maxLength={DAY_NOTE_MAX} onChange={(e) => setNote(e.target.value)}
            placeholder={t('or.dayNote.placeholder')} className="text-sm" />
          <p className="m-0 text-xs text-muted-foreground">{t('or.complete.noteHint')}</p>
        </div>

        <p data-slot="or-complete-notice" className="m-0 flex items-start gap-2 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
          {noticeKey === 'or.complete.noticeOff' ? <BellOff aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            : noticeKey === 'or.complete.noticePush' ? <BellRing aria-hidden="true" className="mt-px size-3.5 shrink-0" />
              : <Mail aria-hidden="true" className="mt-px size-3.5 shrink-0" />}
          <span>{noticeKey ? t(noticeKey) : t('or.complete.noticeLoading')}</span>
        </p>

        {error && <AlertBanner tone="danger" className="mb-0" role="alert">{error}</AlertBanner>}
      </div>
    </ModalShell>
  )
}
