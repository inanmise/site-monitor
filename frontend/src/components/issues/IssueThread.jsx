import { useId } from 'react'
import { CircleDot, Hourglass, CheckCircle2, RotateCcw, Lock, Send, Flag, ArrowRightLeft } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Textarea } from '@/components/shadcn/textarea'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { Kbd } from '@/components/shadcn/kbd'
import { cn } from '@/lib/utils'
import { CharCounter } from './report/ReportParts.jsx'
import { buildSteps, buildThread, dayLabel, fmtDate, fmtTime, statusKey, COMMENT_MAX } from './issuesModel.js'

const STEP_ICON = { OPEN: CircleDot, IN_PROGRESS: Hourglass, RESOLVED: CheckCircle2 }
const STEP_LABEL = { OPEN: 'myIssues.tlOpened', IN_PROGRESS: 'myIssues.tlInProgress', RESOLVED: 'myIssues.tlResolved' }

/**
 * Durum adımları — Açıldı → İşlemde → Çözümlendi; her adımda kim/ne zaman. Telefonda da üç sütun (kısa etiketler
 * sarar). Tamamlanan adım dolu, güncel adım halkalı, atlanan adım (doğrudan çözülen) kesikli. Bildirenin yeniden
 * açması altta ayrı satır. `reporterName`: yönetici görünümünde bildirenin adı ("siz" yerine).
 */
export function IssueStepper({ detail, reporterName }) {
  const t = useT()
  const { steps, reopened } = buildSteps(detail)
  const who = (s) => (s.byReporter ? (reporterName || t('myIssues.tlYou')) : (s.by || '—'))
  return (
    <div data-slot="issue-stepper" className="flex flex-col gap-2">
      <ol className="m-0 grid list-none grid-cols-3 gap-1 p-0">
        {steps.map((s, i) => {
          const Icon = STEP_ICON[s.key]
          const on = s.state === 'done' || s.state === 'current'
          return (
            <li key={s.key} data-step={s.key} data-state={s.state} aria-current={s.state === 'current' ? 'step' : undefined}
              className="relative flex min-w-0 flex-col items-center gap-1 text-center">
              {i > 0 && (
                <span aria-hidden="true" className={cn('absolute top-4 right-[calc(50%+1.25rem)] left-[calc(-50%+1.25rem)] h-0.5 rounded-full',
                  on ? 'bg-primary/60' : 'bg-border', s.state === 'skipped' && 'opacity-50')} />
              )}
              <span aria-hidden="true" className={cn('relative grid size-8 place-items-center rounded-full border-2',
                s.state === 'done' && (s.key === 'RESOLVED' ? 'border-success bg-success text-white' : 'border-primary bg-primary text-primary-foreground'),
                s.state === 'current' && 'border-primary bg-primary/10 text-primary ring-4 ring-primary/15',
                s.state === 'todo' && 'border-border bg-background text-muted-foreground',
                s.state === 'skipped' && 'border-dashed border-border bg-background text-muted-foreground')}>
                <Icon className="size-4" />
              </span>
              <span className={cn('text-xs leading-tight font-semibold', !on && 'text-muted-foreground')}>{t(STEP_LABEL[s.key])}</span>
              <span className="min-h-[2.2em] text-[11px] leading-tight text-muted-foreground [overflow-wrap:anywhere]">
                {s.state === 'skipped' ? t('issues.stepSkipped') : s.at ? <>{fmtDate(s.at)}<br />{t('myIssues.tlBy', who(s))}</> : t('issues.stepPending')}
              </span>
            </li>
          )
        })}
      </ol>
      {reopened && (
        <p data-slot="issue-reopened" className="m-0 flex items-center justify-center gap-1.5 text-xs text-amber-700 dark:text-amber-300">
          <RotateCcw aria-hidden="true" className="size-3.5" />
          {t('issues.reopenedBy', reopened.byReporter ? (reporterName || t('myIssues.tlYou')) : (reopened.by || '—'), fmtDate(reopened.at))}
        </p>
      )}
    </div>
  )
}

/**
 * Konuşma — sohbet akışı: bildiren sağda (birincil zemin), yönetici solda (kart + avatar); durum geçişleri ortada
 * küçük sistem satırı; Istanbul gününe göre gün ayırıcıları. Yönetici görünümünde iç notlar kesikli amber çerçeve +
 * kilit + "İç not" rozetiyle AYRIŞIR (bildiren ucunda alan hiç yoktur). Sol renk şeridi YOK.
 */
export function IssueConversation({ detail, reporterName, admin = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = lang === 'tr' ? 'tr-TR' : 'en-GB'
  const groups = buildThread(detail)
  const hasComments = (detail?.comments || []).length > 0
  const who = (it) => (it.byReporter ? (reporterName || t('myIssues.tlYou')) : (it.by || '—'))
  return (
    <div data-slot="issue-conversation" className="flex min-w-0 flex-col gap-3">
      {groups.map((g) => (
        <section key={g.day || 'x'} className="flex min-w-0 flex-col gap-2.5" aria-label={dayLabel(g.day, t, locale)}>
          <div aria-hidden="true" className="flex items-center gap-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
            <span className="h-px flex-1 bg-border" /><span>{dayLabel(g.day, t, locale)}</span><span className="h-px flex-1 bg-border" />
          </div>
          <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
            {g.items.map((it) => {
              if (it.kind !== 'comment') {
                const Icon = it.kind === 'opened' ? Flag : ArrowRightLeft
                return (
                  <li key={it.key} data-slot="issue-event" data-status={it.status}
                    className="flex items-center justify-center gap-1.5 text-center text-xs text-muted-foreground">
                    <Icon aria-hidden="true" className="size-3.5 shrink-0" />
                    <span className="[overflow-wrap:anywhere]">
                      {it.kind === 'opened'
                        ? t('issues.evOpened', who(it))
                        : it.byReporter ? t('issues.evReopened', who(it)) : t('issues.evStatus', who(it), t('loginIssues.status' + statusKey(it.status)))}
                      {' · '}<time dateTime={it.at}>{fmtTime(it.at)}</time>
                    </span>
                  </li>
                )
              }
              const mine = !!it.byReporter
              return (
                <li key={it.key} data-slot="issue-comment" data-by-reporter={mine || undefined} data-internal={it.internal || undefined}
                  className={cn('flex max-w-[88%] min-w-0 flex-col gap-1 sm:max-w-[80%]', mine ? 'self-end items-end' : 'self-start items-start')}>
                  <div className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground', mine && 'flex-row-reverse')}>
                    {mine
                      ? <span className="font-semibold text-foreground">{reporterName || t('myIssues.you')}</span>
                      : <UserBadge username={it.author} inline size="sm" />}
                    {it.internal && (
                      <Badge variant="warning" data-slot="internal-badge" className="gap-1"><Lock aria-hidden="true" />{t('loginIssues.internalBadge')}</Badge>
                    )}
                    <time dateTime={it.at} title={fmtDate(it.at)}>{fmtTime(it.at)}</time>
                  </div>
                  <div className={cn('min-w-0 rounded-2xl border px-3.5 py-2 text-sm leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
                    mine ? 'rounded-tr-sm border-primary/25 bg-primary/10' : 'rounded-tl-sm bg-card',
                    it.internal && 'border-dashed border-amber-500/60 bg-amber-500/[0.07] dark:bg-amber-500/10')}>
                    {it.body}
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      ))}
      {!hasComments && (
        <p className="m-0 text-center text-sm text-muted-foreground">{admin ? t('issues.noCommentsAdmin') : t('issues.noCommentsUser')}</p>
      )}
    </div>
  )
}

/**
 * Altta sabit yazıcı — Textarea kendiliğinden büyür (field-sizing, en çok ~10 satır), Ctrl/⌘+Enter gönderir,
 * 4000 sayaç. Yönetici: "İç not" anahtarı (bildiren görmez, bildirim/e-posta yok). Bildiren + çözülmüş kayıt:
 * göndermenin kaydı YENİDEN AÇACAĞI önceden söylenir (düğme de "Gönder ve yeniden aç").
 */
export function IssueComposer({ value, onChange, onSend, sending, admin = false, internal = false, onInternal, resolved = false, textareaRef }) {
  const t = useT()
  const counterId = useId()
  const switchId = useId()
  const hint = admin
    ? (internal ? t('loginIssues.internalHint') : t('issues.replyHintAdmin'))
    : (resolved ? t('myIssues.resolvedHint') : t('issues.replyHintUser'))
  const label = admin ? (internal ? t('issues.addInternal') : t('issues.sendReply')) : (resolved ? t('issues.sendReopen') : t('myIssues.send'))
  return (
    <form data-slot="issue-comment-form" className="flex shrink-0 flex-col gap-2 border-t bg-background px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
      onSubmit={(e) => { e.preventDefault(); onSend() }}>
      <Textarea ref={textareaRef} rows={2} value={value} maxLength={COMMENT_MAX} disabled={sending}
        aria-label={admin ? t('loginIssues.replyLabel') : t('myIssues.commentLabel')} aria-describedby={counterId}
        placeholder={admin ? (internal ? t('issues.internalPlaceholder') : t('loginIssues.replyPlaceholder')) : t('myIssues.commentPlaceholder')}
        className={cn('max-h-40 min-h-11 resize-none', internal && 'border-dashed border-amber-500/60 bg-amber-500/[0.05]')}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); onSend() } }} />
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
        {admin && (
          <div className="flex items-center gap-2">
            <Switch id={switchId} checked={internal} onCheckedChange={onInternal} disabled={sending} />
            <Label htmlFor={switchId} className="flex cursor-pointer items-center gap-1 text-sm"><Lock aria-hidden="true" className="size-3.5" />{t('loginIssues.internalNote')}</Label>
          </div>
        )}
        <span className={cn('min-w-0 flex-1 text-xs text-muted-foreground', resolved && !admin && 'text-amber-700 dark:text-amber-300')}>
          {resolved && !admin && <RotateCcw aria-hidden="true" className="mr-1 inline size-3.5 align-[-2px]" />}{hint}
        </span>
        <span className="ml-auto flex items-center gap-2">
          <span className="hidden text-[11px] text-muted-foreground md:inline"><Kbd>Ctrl</Kbd>+<Kbd>Enter</Kbd></span>
          <CharCounter id={counterId} value={value} max={COMMENT_MAX} />
          <Button type="submit" size="sm" className="h-9" variant={admin && internal ? 'secondary' : 'default'}
            disabled={sending || !value.trim()} aria-busy={sending || undefined}>
            {sending ? <Spinner size={14} inline decorative /> : resolved && !admin ? <RotateCcw aria-hidden="true" /> : internal ? <Lock aria-hidden="true" /> : <Send aria-hidden="true" />}
            {label}
          </Button>
        </span>
      </div>
    </form>
  )
}
