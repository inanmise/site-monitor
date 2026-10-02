import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import {
  X, Pencil, Trash2, Siren, Eye, CheckCircle2, Clock, Hourglass, Users, Receipt, Gauge, Hash, CircleDot, Search, ShieldHalf,
  FileText, Stethoscope, Wrench, Briefcase, Tags, BellRing,
} from 'lucide-react'
import { formatDate } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { alertNavParams } from '../admin/alerts/alertHistoryModel.js'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import MarkdownEditor from '../ui/MarkdownEditor.jsx'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetClose } from '@/components/shadcn/sheet'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { SeverityBadge, StatusBadge, SlaBadge, CsvChips } from './HistoryBadges.jsx'
import { buildTimeline, csvList, detailLink, durationOf, formatMinutes, relativeFrom } from './incidentHistoryModel.js'

// Arka plan kaydırma kilidi sayaçlı (ModalShell / IncidentDetailSheet sözleşmesi): iç içe pencerede erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

const STEP = {
  occurred: { Icon: Siren, ink: 'border-destructive/40 bg-destructive/10 text-destructive' },
  detected: { Icon: Eye, ink: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  resolved: { Icon: CheckCircle2, ink: 'border-success/40 bg-success/10 text-success' },
}
const STATUS_ICON = { OPEN: CircleDot, INVESTIGATING: Search, MITIGATED: ShieldHalf }

/** Anahtar-değer satırı. */
function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}
const Dash = () => <span className="text-muted-foreground">—</span>

/** Öne çıkan ölçü kutusu (süre, tespit süresi, etkilenen müşteri/işlem). */
function Stat({ icon: Icon, label, value, sub }) {
  return (
    <div data-slot="ih-detail-stat" className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-card px-3 py-2.5">
      <span className="flex items-center gap-1.5 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />{label}
      </span>
      <span className="text-base font-bold tabular-nums [overflow-wrap:anywhere]">{value}</span>
      {sub && <span className="text-xs text-muted-foreground">{sub}</span>}
    </div>
  )
}

/** Markdown bölümü — boşsa "girilmedi" (bilgi eksikliği görünür kalır, bölüm kaybolmaz). */
function MdSection({ icon: Icon, title, value, emptyText }) {
  const has = String(value ?? '').trim() !== ''
  return (
    <section data-slot="ih-detail-md" className="min-w-0">
      <h3 className="mb-1.5 flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5" />{title}
      </h3>
      {has
        // Çerçeve/zemin `show-markdown` tipografisinden gelir — ikinci bir kutu (çift çerçeve) çizilmez.
        ? <div className="min-w-0 text-sm"><MarkdownEditor value={value} editable={false} /></div>
        : <p className="m-0 rounded-lg border border-dashed px-3 py-2 text-sm text-muted-foreground">{emptyText}</p>}
    </section>
  )
}

/**
 * Olay ayrıntısı — ÖNCE OKUMA: sağdan açılan Sheet (telefonda tam ekran). Üstte rozetler + başlık, eylemler
 * (Düzenle / Bağlantıyı kopyala / Sil — yalnız yetkiliye), öne çıkan ölçüler, zaman çizelgesi (oluştu → tespit →
 * güncel durum → çözüldü; girilmemiş adım "girilmedi" diye görünür), etki ve sınıflandırma bilgileri, kodlar ve
 * etiketler, markdown bölümleri (işlenmiş). Sol renk şeridi YOK; durum rozetle.
 *
 * <p>`modal={false}` + kendi örtüsü (IncidentDetailSheet gerekçesi): ayrıntının üstünde açılan düzenleme penceresi
 * (ModalShell), onay diyaloğu ve takım üyeleri penceresi body'ye portal'lanır; Radix modal kipi onları tıklanamaz
 * yapardı. Kapatma yolları: örtü, X, Escape.
 */
export default function HistoryDetailSheet({ record, onClose, onEdit, onDelete, allowManage = false, allowDelete = false }) {
  const t = useT()
  const dateLocale = useDateLocale()

  // Odak iadesi: kapanışta tetikleyiciye (satır / kart).
  useEffect(() => {
    const previous = document.activeElement
    return () => { if (previous && typeof previous.focus === 'function' && document.contains(previous)) previous.focus() }
  }, [])
  // Arka plan kaydırma kilidi (sayaçlı).
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])

  if (!record) return null
  const r = record
  const dur = durationOf(r)
  const timeline = buildTimeline(r)
  const ttd = timeline.find((s) => s.kind === 'detected')?.after
  const when = (iso) => (iso ? `${formatDate(iso)} · ${relativeFrom(iso, dateLocale)}` : null)
  const tags = csvList(r.tags)
  const codes = [['inc.fErrorCode', r.error_code], ['inc.fFunctionCode', r.function_code], ['inc.fChannelCode', r.channel_code]]
  const hasCodes = codes.some(([, v]) => v)
  const num = (v) => (v === '' || v == null ? null : Number(v).toLocaleString(dateLocale))

  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next) onClose?.() }}>
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-[1000] bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="right" showCloseButton={false} data-slot="ih-detail" data-incident-id={r.id}
        aria-modal="true"
        onInteractOutside={(e) => e.preventDefault()}
        onCloseAutoFocus={(e) => e.preventDefault()}
        className="z-[1001] flex h-[100dvh] w-full flex-col gap-0 p-0 sm:w-[min(46rem,calc(100vw-2rem))] sm:max-w-none">
        <SheetHeader className="shrink-0 gap-2 border-b px-4 py-3 text-left">
          <div className="flex items-start justify-between gap-3">
            <div className="flex min-w-0 flex-wrap items-center gap-1.5 pt-1">
              <SeverityBadge severity={r.severity} />
              <StatusBadge status={r.status} />
              {r.sla_breached && <SlaBadge />}
            </div>
            <SheetClose asChild>
              <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 text-muted-foreground max-sm:size-10" aria-label={t('app.close')}>
                <X aria-hidden="true" />
              </Button>
            </SheetClose>
          </div>
          <SheetTitle className="text-lg leading-snug font-bold [overflow-wrap:anywhere]">{r.title || '—'}</SheetTitle>
          <SheetDescription className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
            <span className="inline-flex items-center gap-1 font-mono"><Hash aria-hidden="true" className="size-3" />{r.id}</span>
            <span aria-hidden="true">·</span><span>{t('inc.cat' + r.category) || r.category}</span>
            {r.occurred_at && <><span aria-hidden="true">·</span><span title={r.occurred_at}>{relativeFrom(r.occurred_at, dateLocale)}</span></>}
          </SheetDescription>
        </SheetHeader>

        <div data-slot="ih-detail-body" className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {/* Eylemler — okuma öncelikli; yazma yalnız yetkiliye */}
          <div data-slot="ih-detail-actions" className="flex flex-wrap items-center gap-2 max-sm:*:h-10">
            {allowManage && (
              <Button type="button" size="sm" className="h-9" onClick={() => onEdit?.(r)}>
                <Pencil aria-hidden="true" />{t('inc.edit')}
              </Button>
            )}
            <CopyLinkButton variant="outline" className="h-9" url={detailLink(r.id)} />
            {/* Kaynak alarm (2026-10-01): kayıt Alarm Geçmişi'ndeki bir alarmdan açıldıysa o alarma geri bağlantı */}
            {r.alert_event_id != null && r.alert_event_id !== '' && (
              <Button type="button" variant="outline" size="sm" className="h-9" data-slot="ih-source-alert"
                data-alert-id={r.alert_event_id} title={t('inc.sourceAlertHint')}
                onClick={() => navigateTo('alerthistory', alertNavParams({ id: r.alert_event_id }))}>
                <BellRing aria-hidden="true" />{t('inc.sourceAlert', r.alert_event_id)}
              </Button>
            )}
            {allowDelete && (
              <Button type="button" variant="ghost" size="sm"
                className="ml-auto h-9 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                aria-label={t('a11y.rowAction', r.title || String(r.id), t('inc.delete'))} onClick={() => onDelete?.(r)}>
                <Trash2 aria-hidden="true" />{t('inc.delete')}
              </Button>
            )}
          </div>

          {/* Öne çıkan ölçüler */}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Stat icon={Clock} label={t('inc.dDuration')} value={dur.minutes == null ? '—' : formatMinutes(dur.minutes, t)}
              sub={dur.ongoing ? t('inc.stillOngoing') : undefined} />
            <Stat icon={Hourglass} label={t('inc.dTimeToDetect')} value={ttd == null ? '—' : formatMinutes(ttd, t)} />
            <Stat icon={Users} label={t('inc.dCustomers')} value={num(r.affected_customers) ?? '—'} />
            <Stat icon={Receipt} label={t('inc.dTransactions')} value={num(r.affected_transactions) ?? '—'} />
          </div>

          {/* Zaman çizelgesi */}
          <section className="min-w-0">
            <h3 className="mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('inc.timeline')}</h3>
            <ol data-slot="ih-timeline" className="relative m-0 flex list-none flex-col gap-2.5 p-0 pl-10 before:absolute before:top-4 before:bottom-4 before:left-[15px] before:w-px before:bg-border">
              {timeline.map((s) => {
                const style = s.kind === 'status' ? null : STEP[s.kind]
                const Icon = s.kind === 'status' ? (STATUS_ICON[s.status] || CircleDot) : style.Icon
                return (
                  <li key={s.kind} data-slot="ih-timeline-step" data-kind={s.kind} data-pending={s.pending || undefined} className="relative min-w-0">
                    <span aria-hidden="true" className={cn('absolute top-0.5 -left-10 grid size-8 place-items-center rounded-full border bg-background',
                      s.pending ? 'border-dashed text-muted-foreground' : style?.ink ?? 'border-primary/40 bg-primary/10 text-primary')}>
                      <Icon className="size-4" />
                    </span>
                    <div className={cn('min-w-0 rounded-lg border px-3 py-2', s.pending ? 'border-dashed' : 'bg-card')}>
                      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                        <span className={cn('text-sm font-semibold', s.pending && 'text-muted-foreground')}>
                          {s.kind === 'status' ? t('inc.tl.status') : t('inc.tl.' + s.kind)}
                        </span>
                        {s.kind === 'status' && <StatusBadge status={s.status} />}
                        {s.after != null && s.kind === 'detected' && <Badge variant="outline" className="tabular-nums">{t('inc.tl.after', formatMinutes(s.after, t))}</Badge>}
                        {s.after != null && s.kind === 'resolved' && <Badge variant="outline" className="tabular-nums">{t('inc.tl.total', formatMinutes(s.after, t))}</Badge>}
                      </div>
                      <p className="m-0 mt-0.5 text-xs text-muted-foreground tabular-nums">
                        {s.kind === 'status' ? t('inc.tl.statusNote') : (s.pending ? t('inc.tl.pending.' + s.kind) : when(s.at))}
                      </p>
                    </div>
                  </li>
                )
              })}
            </ol>
          </section>

          {/* Etki ve sınıflandırma */}
          <Card data-slot="ih-detail-facts" className="gap-0 py-0">
            <h3 className="px-4 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('inc.dFacts')}</h3>
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-2">
              <Fact label={t('inc.colTeam')}>
                {r.team_name || r.team_id != null ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <Dash />}
              </Fact>
              <Fact label={t('inc.fProblemType')}><CsvChips value={r.problem_types} max={6} empty={<Dash />} /></Fact>
              <Fact label={t('inc.fChannel')}><CsvChips value={r.channel} max={8} empty={<Dash />} /></Fact>
              <Fact label={t('inc.fService')}><CsvChips value={r.service} max={8} empty={<Dash />} /></Fact>
              <Fact label={t('inc.fAffected')}>{r.affected_services || <Dash />}</Fact>
              <Fact label={t('inc.fAffectedApp')}>{r.affected_app || <Dash />}</Fact>
              <Fact label={t('inc.fAffectedSystems')}>{r.affected_systems || <Dash />}</Fact>
              <Fact label={t('inc.fErrorBudget')}>
                <span className="inline-flex items-center gap-1.5"><Gauge aria-hidden="true" className="size-3.5 text-muted-foreground" />
                  {r.error_budget_burn_pct == null || r.error_budget_burn_pct === '' ? <Dash /> : t('inc.pct', Number(r.error_budget_burn_pct).toLocaleString(dateLocale))}</span>
              </Fact>
              <Fact label={t('inc.dRecordedBy')}>
                <span className="flex min-w-0 flex-col gap-0.5">
                  <UserBadge username={r.created_by} inline size="sm" nameOnly />
                  {r.created_at && <span className="text-xs text-muted-foreground tabular-nums">{formatDate(r.created_at)}</span>}
                </span>
              </Fact>
              <Fact label={t('inc.dUpdatedBy')}>
                {r.updated_by || r.updated_at
                  ? (
                    <span className="flex min-w-0 flex-col gap-0.5">
                      {r.updated_by && <UserBadge username={r.updated_by} inline size="sm" nameOnly />}
                      {r.updated_at && <span className="text-xs text-muted-foreground tabular-nums">{formatDate(r.updated_at)}</span>}
                    </span>
                  )
                  : <Dash />}
              </Fact>
            </dl>
          </Card>

          {/* Kodlar ve etiketler */}
          {(hasCodes || tags.length > 0) && (
            <Card data-slot="ih-detail-codes" className="gap-0 py-0">
              <h3 className="flex items-center gap-1.5 px-4 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">
                <Tags aria-hidden="true" className="size-3.5" />{t('inc.sec.codes')}
              </h3>
              <div className="flex flex-col gap-3 px-4 py-3">
                {hasCodes && (
                  <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-3">
                    {codes.map(([k, v]) => (
                      <Fact key={k} label={t(k)}>
                        {v ? <CopyableRef value={v} copyLabel={t('inc.copyCode', v)} copiedLabel={t('share.copied')} buttonClassName="max-sm:size-10" /> : <Dash />}
                      </Fact>
                    ))}
                  </dl>
                )}
                {tags.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {tags.map((tag) => <Badge key={tag} variant="secondary" className="rounded-full">{tag}</Badge>)}
                  </div>
                )}
              </div>
            </Card>
          )}

          <MdSection icon={Briefcase} title={t('inc.fBusinessImpact')} value={r.business_impact} emptyText={t('inc.notEntered')} />
          <MdSection icon={Stethoscope} title={t('inc.fRca')} value={r.rca_summary} emptyText={t('inc.notEntered')} />
          <MdSection icon={FileText} title={t('inc.fDescription')} value={r.description} emptyText={t('inc.notEntered')} />
          <MdSection icon={Wrench} title={t('inc.fResolution')} value={r.resolution_steps} emptyText={t('inc.notEntered')} />
        </div>
      </SheetContent>
    </Sheet>
  )
}
