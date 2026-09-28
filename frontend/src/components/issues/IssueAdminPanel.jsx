import { useId, useState } from 'react'
import { Hourglass, CheckCircle2, RotateCcw, Trash2, ChevronDown, ChevronRight, Info, Mail, Zap, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { mailPreviewSrcDoc } from '../../utils/mailPreview.js'
import UserBadge from '../ui/UserBadge.jsx'
import Field from '../ui/Field.jsx'
import { Spinner } from '../ui/Progress.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import MaskedValue from '../ui/MaskedValue.jsx'
import { CharCounter } from './report/ReportParts.jsx'
import { fmtDate, prettyJson, mailBodyWithImages, mailStatusInfo, mailTypeLabel, NOTE_MAX } from './issuesModel.js'

const TH = 'h-9 px-3 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase'
const ACTIONS = {
  IN_PROGRESS: { Icon: Hourglass, label: 'loginIssues.actionInProgress', variant: 'secondary' },
  RESOLVED: { Icon: CheckCircle2, label: 'loginIssues.actionResolve', variant: 'success' },
  OPEN: { Icon: RotateCcw, label: 'loginIssues.actionReopen', variant: 'warning' },
}

/**
 * Yönetici eylem çubuğu — durum geçişleri (mevcut uç: PUT /status {status, resolutionNote}). Her geçiş satır içi bir
 * not panelinden geçer: "Çözümlendi" için çözüm notu ZORUNLU (sunucuyla aynı, ≤2000), diğerlerinde opsiyonel ve
 * bildirene giden e-postaya eklenir. Mevcut not önyüklenir (kaybolmaz). Kalıcı silme AYRI yetki: yetkisi olmayana çizilmez.
 */
export function IssueStatusActions({ detail, onChangeStatus, onPurge, canPurge = false, busy = false }) {
  const t = useT()
  const [target, setTarget] = useState(null)      // açık not paneli: hedef durum
  const [note, setNote] = useState('')
  const [err, setErr] = useState('')
  const counterId = useId()
  const status = detail.status
  const targets = status === 'OPEN' ? ['IN_PROGRESS', 'RESOLVED'] : status === 'IN_PROGRESS' ? ['RESOLVED'] : ['OPEN']

  function start(next) { setTarget(next); setNote(detail.resolutionNote || ''); setErr('') }
  async function confirm() {
    if (target === 'RESOLVED' && !note.trim()) { setErr(t('loginIssues.noteRequired')); return }
    const ok = await onChangeStatus(target, note.trim())
    if (ok) { setTarget(null); setErr('') }
  }

  return (
    <div data-slot="issue-admin-actions" className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        {targets.map((s) => {
          const a = ACTIONS[s]
          return (
            <Button key={s} type="button" size="sm" variant={target === s ? a.variant : 'outline'} className="h-9" aria-pressed={target === s}
              disabled={busy} onClick={() => (target === s ? setTarget(null) : start(s))}>
              <a.Icon aria-hidden="true" />{t(a.label)}
            </Button>
          )
        })}
        {canPurge && (
          <Button type="button" size="sm" variant="ghost" className="ml-auto h-9 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
            disabled={busy} onClick={onPurge}>
            <Trash2 aria-hidden="true" />{t('loginIssues.purgeAction')}
          </Button>
        )}
      </div>
      {target && (
        <Card data-slot="issue-status-panel" data-target={target} className="gap-2 px-3 py-3">
          <Field label={target === 'RESOLVED' ? t('loginIssues.resolutionNote') : t('issues.statusNote')} required={target === 'RESOLVED'}
            hint={target === 'RESOLVED' ? t('issues.resolveNoteHint') : t('issues.statusNoteHint')} error={err} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={3} value={note} maxLength={NOTE_MAX} aria-invalid={invalid}
                aria-describedby={[describedBy, counterId].filter(Boolean).join(' ')} className="max-h-48 min-h-20 resize-y"
                placeholder={t('loginIssues.notePlaceholder')} onChange={(e) => { setNote(e.target.value); if (err) setErr('') }} />
            )}
          </Field>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <CharCounter id={counterId} value={note} max={NOTE_MAX} />
            <Button type="button" size="sm" variant="ghost" className="h-9" onClick={() => setTarget(null)} disabled={busy}>
              <X aria-hidden="true" />{t('issue.cancel')}
            </Button>
            <Button type="button" size="sm" className="h-9" variant={ACTIONS[target].variant} onClick={confirm} disabled={busy} aria-busy={busy || undefined}>
              {busy ? <Spinner size={14} inline decorative /> : (() => { const I = ACTIONS[target].Icon; return <I aria-hidden="true" /> })()}
              {t('issues.confirmStatus', t(ACTIONS[target].label))}
            </Button>
          </div>
        </Card>
      )}
    </div>
  )
}

function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/** Katlanır bölüm başlığı (ayrıntılar / mail geçmişi) — 40 px dokunma hedefi. */
function Section({ icon: Icon, title, count, open, onOpenChange, children, slot }) {
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} data-slot={slot} className="rounded-lg border">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" aria-expanded={open}
          className="h-auto min-h-10 w-full justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm font-semibold whitespace-normal">
          <span className="flex min-w-0 items-center gap-2"><Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{title}
            {count != null && <span className="font-normal text-muted-foreground tabular-nums">({count})</span>}</span>
          <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t px-3 py-3">{children}</CollapsibleContent>
    </Collapsible>
  )
}

/** Teknik ayrıntılar (yönetici): bildiren, e-posta, IP, tarayıcı, sürüm, ekran, bağlı kayıt, otomatik bağlam. */
export function IssueTechDetails({ detail }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  return (
    <Section slot="issue-tech" icon={Info} title={t('issues.techDetails')} open={open} onOpenChange={setOpen}>
      <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
        <Fact label={t('loginIssues.reporterLabel')}>{detail.username ? <UserBadge username={detail.username} size="sm" /> : '—'}</Fact>
        <Fact label={t('loginIssues.colEmail')}>{detail.reporterEmail || '—'}</Fact>
        <Fact label={t('loginIssues.colIp')}>{detail.identity_masked === true && !detail.ipAddress ? <MaskedValue /> : <span className="font-mono text-xs">{detail.ipAddress || '—'}</span>}</Fact>
        {detail.appVersion && <Fact label={t('issue.autoVersion')}>v{detail.appVersion}</Fact>}
        {detail.tabKey && <Fact label={t('loginIssues.tabKey')}>{detail.tabKey}</Fact>}
        {detail.screenSize && <Fact label={t('issue.autoScreen')}>{detail.screenSize}</Fact>}
        {detail.linkedReference && <Fact label={t('loginIssues.linkedRef')}><span className="font-mono text-xs">{detail.linkedReference}</span></Fact>}
        {detail.userAgent
          ? <Fact label={t('loginIssues.userAgent')} className="sm:col-span-2"><span className="text-xs text-muted-foreground">{detail.userAgent}</span></Fact>
          : detail.identity_masked === true && <Fact label={t('loginIssues.userAgent')} className="sm:col-span-2"><MaskedValue /></Fact>}
        {detail.autoContextJson && (
          <Fact label={t('loginIssues.autoContext')} className="sm:col-span-2">
            <pre className="m-0 max-h-56 overflow-auto rounded-md border bg-muted/40 px-2.5 py-2 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">{prettyJson(detail.autoContextJson)}</pre>
          </Fact>
        )}
      </dl>
    </Section>
  )
}

/** Gönderilen e-postalar — tür, alıcı, teslim durumu; satır açılınca gönderen + konu + gövde önizlemesi (cid → data-URL). */
export function IssueMailHistory({ detail, onRefresh }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [row, setRow] = useState(null)
  const mails = detail.mailHistory || []
  return (
    <Section slot="issue-mails" icon={Mail} title={t('loginIssues.mailHistory')} count={mails.length} open={open} onOpenChange={setOpen}>
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-xs text-muted-foreground">{mails.length ? t('loginIssues.mailRowHint') : t('loginIssues.mailNone')}</span>
          <Button type="button" variant="outline" size="sm" className="h-8" onClick={onRefresh}>{t('loginIssues.mailRefresh')}</Button>
        </div>
        {mails.length > 0 && (
          <div className="overflow-hidden rounded-md border">
            <Table className="text-[0.86em]">
              <TableHeader className="bg-muted/50">
                <TableRow className="hover:bg-transparent">
                  <TableHead className={TH}>{t('loginIssues.mailColType')}</TableHead>
                  <TableHead className={cn(TH, 'hidden sm:table-cell')}>{t('loginIssues.mailColTo')}</TableHead>
                  <TableHead className={TH}>{t('loginIssues.mailColStatus')}</TableHead>
                  <TableHead className={cn(TH, 'hidden sm:table-cell')}>{t('loginIssues.mailColWhen')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {mails.map((ml, i) => {
                  const info = mailStatusInfo(ml.status)
                  const isOpen = row === i
                  const toggle = () => setRow(isOpen ? null : i)
                  return [
                    <TableRow key={i} tabIndex={0} aria-expanded={isOpen} data-state={isOpen ? 'selected' : undefined}
                      className="cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
                      aria-label={t('a11y.toggleRow', mailTypeLabel(ml.mailType, t))}
                      onClick={toggle} onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); toggle() } }}>
                      <TableCell className="whitespace-normal">
                        <span className="inline-flex items-center gap-1">
                          {isOpen ? <ChevronDown aria-hidden="true" className="size-3.5 shrink-0" /> : <ChevronRight aria-hidden="true" className="size-3.5 shrink-0" />}
                          {mailTypeLabel(ml.mailType, t)}
                        </span>
                      </TableCell>
                      <TableCell className="hidden break-all whitespace-normal sm:table-cell">{ml.to || '—'}{ml.cc ? <div className="text-xs text-muted-foreground">CC: {ml.cc}</div> : null}</TableCell>
                      <TableCell className="whitespace-normal">
                        <span className="inline-flex flex-wrap items-center gap-1.5">
                          <ToneBadge tone={info.tone}>{info.key ? t(info.key) : info.label}</ToneBadge>
                          {ml.forced ? <span title={t('loginIssues.mailForcedHint')} className="text-muted-foreground"><Zap aria-label={t('loginIssues.mailForcedHint')} className="size-3.5" /></span> : null}
                        </span>
                        {ml.error ? <div className="mt-0.5 text-xs break-words text-destructive">{ml.error}</div> : null}
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap sm:table-cell">{fmtDate(ml.sentAt)}</TableCell>
                    </TableRow>,
                    isOpen && (
                      <TableRow key={i + '-content'} className="hover:bg-transparent">
                        <TableCell colSpan={4} className="bg-muted/40 whitespace-normal">
                          <div className="mb-1 text-xs [overflow-wrap:anywhere] sm:hidden"><b>{t('loginIssues.mailColTo')}:</b> {ml.to || '—'} · {fmtDate(ml.sentAt)}</div>
                          <div className="mb-1 text-xs [overflow-wrap:anywhere]"><b>{t('loginIssues.mailFrom')}:</b> {ml.from || '—'}</div>
                          <div className="mb-1.5 text-xs [overflow-wrap:anywhere]"><b>{t('loginIssues.mailSubject')}:</b> {ml.subject || '—'}</div>
                          {ml.body
                            ? <iframe title={`mail-${i}`} sandbox="" srcDoc={mailPreviewSrcDoc(mailBodyWithImages(ml.body, detail.images))} className="h-[340px] w-full rounded-md border bg-white" />
                            : <p className="m-0 text-xs text-muted-foreground">{t('loginIssues.mailNoContent')}</p>}
                        </TableCell>
                      </TableRow>
                    ),
                  ]
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </Section>
  )
}
