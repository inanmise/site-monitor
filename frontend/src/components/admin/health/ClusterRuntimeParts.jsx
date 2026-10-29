import { formatDate } from '../../../api/client'
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { relTime, formatDurationShort } from './healthModel.js'
import { EVAL_VERIFY_TONE, EVAL_UNKNOWN_REASONS, SIDE_JOB_STATE_TONE, otherPodState, EVAL_LOAD_REASONS, HANDOVER_RESULTS,
  HANDOVER_NOT_READY_FROM_REASON, CHECK_JOBS_TONE, CERT_CHUNKS_TONE, CERT_MODE_TONE, CERT_SWEEP_MODES } from './clusterRuntimeModel.js'
import { KvList, PodChip, SubTitle, WARN_T } from './HealthParts.jsx'

/**
 * Küme kartının çok pod Sürüm 3-1 parçaları (2026-10-23, paket B):
 * - {@link EvalVerifySection}: KÜMENİN son kalıcı durum doğrulaması (tarama lideri yayımlar) — "Küme" etiketli.
 * - {@link EvalRecorderRows} / {@link SideJobsRows}: YANITLAYAN POD'un kaydedicisi ve yan işleri — "Bu pod" bölümünün
 *   `<KvList>` satırları (dt / dd çiftleri).
 * Bilinmeyen / eski / henüz yok → gri metin ve rozet; yalnız TAZE "uyuşuyor" turu yeşil. Adlar sarar (kırpılmaz).
 */

const PodName = ({ children }) => <span className="font-mono text-xs [overflow-wrap:anywhere]">{children}</span>

const VERIFY_LABEL = {
  ok: 'hmp.esVerifyOk', mismatch: 'hmp.esVerifyMismatch', unknown: 'hmp.esVerifyUnknown', stale: 'hmp.esVerifyStale',
  none: 'hmp.esVerifyNone', off: 'hmp.esVerifyOff',
}
const KIND_LABEL = {
  recovery: 'hmp.esKind.recovery', sibling: 'hmp.esKind.sibling', suppression: 'hmp.esKind.suppression',
  round_seen: 'hmp.esKind.round_seen', confirmation: 'hmp.esKind.confirmation', descriptor: 'hmp.esKind.descriptor',
  window_seed: 'hmp.esKind.window_seed',
}
const REASON_LABEL = {
  flush_lag: 'hmp.esReason.flush_lag', read_failed: 'hmp.esReason.read_failed', no_generation: 'hmp.esReason.no_generation',
  snapshot_failed: 'hmp.esReason.snapshot_failed', resync: 'hmp.esReason.resync', verifier_error: 'hmp.esReason.verifier_error',
  payload_too_large: 'hmp.esReason.payload_too_large', leadership_changed: 'hmp.esReason.leadership_changed',
  nothing_compared: 'hmp.esReason.nothing_compared',
}
const MODE_LABEL = { off: 'hmp.esMode.off', write: 'hmp.esMode.write', verify: 'hmp.esMode.verify' }
const JOB_STATE_LABEL = { running: 'hmp.sjStateRunning', queued: 'hmp.sjStateQueued' }
const OUTCOME_LABEL = { failed: 'hmp.sjOutcomeFail', interrupted: 'hmp.sjOutcomeInterrupted' }

const when = (iso, t) => (iso ? `${formatDate(iso)} · ${relTime(iso, t) || ''}` : '—')

function reasonText(t, reason) {
  if (!reason || reason === 'unreadable') return null
  return EVAL_UNKNOWN_REASONS.includes(reason) && REASON_LABEL[reason] ? t(REASON_LABEL[reason]) : t('hmp.esReasonOther', reason)
}

/** Kümenin son doğrulama turu (Küme etiketli bölüm). */
export function EvalVerifySection({ t, es }) {
  if (!es) return null
  const v = es.verify
  const tone = EVAL_VERIFY_TONE[v.level] || 'muted'
  const reason = v.level === 'unknown' ? reasonText(t, v.reason) : null
  const hint = v.reason === 'unreadable' ? t('hmp.esUnreadable')
    : v.level === 'none' ? t('hmp.esNoneHint')
      : v.level === 'off' ? t('hmp.esOffHint')
        : v.level === 'stale' ? t('hmp.esStaleHint', relTime(v.observedAt, t) || formatDate(v.observedAt)) : null
  const measured = v.kinds.some((k) => k.measured)
  return (
    <>
      <section data-slot="cluster-eval-verify" data-level={v.level} aria-labelledby="hmp-es-title" className="flex flex-col gap-1.5 border-t pt-2.5">
        <SubTitle className="mt-0 text-[12px]"><span id="hmp-es-title">{t('hmp.esTitle')}</span><PodChip t={t} scope="cluster" /></SubTitle>
        <KvList>
          <dt>{t('hmp.esVerify')}</dt>
          <dd data-slot="es-verify-status" data-level={v.level} className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 [overflow-wrap:anywhere]">
            <ToneBadge tone={tone} className="h-auto max-w-full overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]">{t(VERIFY_LABEL[v.level] || 'hmp.esVerifyUnknown')}</ToneBadge>
            {v.mismatches != null && (v.level === 'ok' || v.level === 'mismatch') && (
              <span className={`min-w-0 [overflow-wrap:anywhere] ${v.level === 'mismatch' ? WARN_T : 'text-muted-foreground'}`} data-slot="es-verify-mismatches">
                {t('hmp.esKindCount', v.mismatches, v.kinds.filter((k) => !k.measured).reduce((n, k) => n + (k.compared || 0), 0))}
              </span>
            )}
            {reason && <span className="min-w-0 text-muted-foreground [overflow-wrap:anywhere]">· {reason}</span>}
          </dd>
          {v.observedAt && (
            <>
              <dt>{t('hmp.esVerifiedBy')}</dt>
              <dd data-slot="es-verified-by">{v.observedBy ? <PodName>{v.observedBy}</PodName> : '—'}
                <span className="text-muted-foreground"> · {when(v.observedAt, t)}</span></dd>
            </>
          )}
          {v.generation != null && v.generation > 0 && (<><dt>{t('hmp.esGeneration')}</dt><dd data-slot="es-generation">{v.generation}</dd></>)}
          {v.kinds.length > 0 && (
            <>
              <dt>{t('hmp.esKinds')}</dt>
              <dd className="min-w-0 [overflow-wrap:anywhere]">
                <ul data-slot="es-kinds" className="flex list-none flex-col gap-0.5">
                  {v.kinds.map((k) => {
                    const warn = !k.measured && v.level === 'mismatch' && (k.mismatches || 0) > 0
                    return (
                      <li key={k.kind} data-slot="es-kind" data-kind={k.kind} data-warn={warn ? 'true' : undefined}
                        className={`[overflow-wrap:anywhere] ${warn ? WARN_T : ''}`}>
                        <span className="font-medium">{k.known && KIND_LABEL[k.kind] ? t(KIND_LABEL[k.kind]) : <PodName>{k.kind}</PodName>}</span>
                        <span className={warn ? undefined : 'text-muted-foreground'}>
                          {' · '}{t('hmp.esKindCount', k.mismatches ?? '—', k.compared ?? '—')}
                          {Number(k.skipped) > 0 ? ` · ${t('hmp.esKindSkipped', k.skipped)}` : ''}
                          {k.maxDelta != null ? ` · ${t('hmp.esMaxDelta', (k.maxDelta * 100).toFixed(1))}` : ''}
                        </span>
                      </li>
                    )
                  })}
                </ul>
                {measured && <p className="mt-0.5 text-xs text-muted-foreground">{t('hmp.esMeasuredNote')}</p>}
              </dd>
            </>
          )}
          {v.firstMismatches.length > 0 && (
            <>
              <dt>{t('hmp.esFirstKeys')}</dt>
              <dd data-slot="es-first-keys" className="font-mono text-xs [overflow-wrap:anywhere]">{v.firstMismatches.join(', ')}</dd>
            </>
          )}
        </KvList>
        {hint && <p className="text-[0.9em] text-muted-foreground" data-slot="es-verify-hint">{hint}</p>}
        <p className="text-xs text-muted-foreground" data-slot="es-decision-note">{t('hmp.esDecisionNote')}</p>
      </section>
      <EvalLoadSection t={t} load={es.load} />
      <CheckJobsSection t={t} cj={es.checkJobs} />
    </>
  )
}

/** Bu pod'un kaydedicisi — "Bu pod" `<KvList>` satırı. */
export function EvalRecorderRows({ t, es }) {
  if (!es) return null
  const r = es.recorder
  const warn = es.recorderLevel === 'warn'
  const inh = es.inherited
  return (
    <>
      <dt>{t('hmp.esRecorder')}</dt>
      <dd data-slot="cluster-eval-recorder" data-level={es.recorderLevel} className="min-w-0 [overflow-wrap:anywhere]">
        {!r ? t('health.stUnknown') : (
          <span className="flex flex-col gap-0.5">
            <span className={warn ? WARN_T : undefined}>
              {es.mode ? t(MODE_LABEL[es.mode]) : t('health.stUnknown')}
              {es.mode !== 'off' && ` · ${r.active ? t('hmp.esWriting', r.generation ?? '—') : t('hmp.esNotWriting')}`}
            </span>
            {es.mode !== 'off' && (
              <span className="text-muted-foreground">
                {t('hmp.esPending', r.pending ?? '—')} · {t('hmp.esWriteFailures', r.write_failures ?? '—')}
                {Number(r.dropped) > 0 ? ` · ${t('hmp.esDropped', r.dropped)}` : ''}
                {r.last_flush_at ? ` · ${t('hmp.esLastFlush', relTime(r.last_flush_at, t) || formatDate(r.last_flush_at))}` : ''}
              </span>
            )}
            {warn && r.fenced === true && es.mode !== 'off'
              && <span className={WARN_T} data-slot="es-fenced">{t('hmp.esFenced')}</span>}
            {warn && r.fenced !== true && <span className={WARN_T} data-slot="es-failing">{t('hmp.esFailingNow')}</span>}
            {Number(r.hook_failures) > 0 && es.mode !== 'off' && (
              <span className="text-muted-foreground" data-slot="es-hook-failures">{t('hmp.esHookFailures', r.hook_failures)}</span>
            )}
            {r.table_missing && <span className="text-muted-foreground" data-slot="es-table-missing">{t('hmp.esTableMissing')}</span>}
            {inh && (
              <span className="text-muted-foreground [overflow-wrap:anywhere]" data-slot="es-inherited">
                {t('hmp.esInherited', inh.previous_generation ?? '—', inh.recovery_counters ?? 0, inh.down_siblings ?? 0,
                  inh.active_suppressions ?? 0, inh.abandoned_chains ?? 0)}
              </span>
            )}
          </span>
        )}
      </dd>
      <HandoverRows t={t} handover={es.handover} />
      <CheckJobsPodRows t={t} cj={es.checkJobs} />
    </>
  )
}

/** "Başka canlı pod" metni (R3-1 inceleme L4): takipçide "yalnız lider bakar", lider önbelleği boşken son görülen gözlem. */
function otherPodText(t, sj, state) {
  const seenAt = sj.otherLivePodSeen?.at
  const ago = seenAt ? (relTime(seenAt, t) || formatDate(seenAt)) : '—'
  switch (state) {
    case 'off': return null
    case 'yes': return t('hmp.sjOtherPodYes')
    case 'no': return t('hmp.sjOtherPodNo')
    case 'leaderOnly': return t('hmp.sjOtherPodLeaderOnly')
    case 'seenYes': return t('hmp.sjOtherPodSeenYes', ago)
    case 'seenNo': return t('hmp.sjOtherPodSeenNo', ago)
    case 'notChecked': return t('hmp.sjOtherPodNotChecked')
    default: return t('hmp.sjOtherPodUnknown')
  }
}

/** Bu pod'un yan işleri — "Bu pod" `<KvList>` satırı. */
export function SideJobsRows({ t, sj }) {
  if (!sj) return null
  const podState = otherPodState(sj)
  const otherPod = otherPodText(t, sj, podState)
  return (
    <>
      <dt>{t('hmp.sjTitle')}</dt>
      <dd data-slot="cluster-side-jobs" data-known={sj.known ? 'true' : 'false'} className="min-w-0 [overflow-wrap:anywhere]">
        {!sj.known ? t('health.stUnknown') : (
          <span className="flex flex-col gap-0.5">
            <span data-slot="side-jobs-summary">
              {sj.running > 0 || sj.queued > 0
                ? [sj.running > 0 ? t('hmp.sjRunning', sj.running) : null, sj.queued > 0 ? t('hmp.sjQueued', sj.queued) : null].filter(Boolean).join(' · ')
                : t('hmp.sjIdle')}
            </span>
            <span className="text-muted-foreground">{t('hmp.sjCounters', sj.deferred ?? '—', sj.yielded ?? '—')}</span>
            <span className="text-muted-foreground [overflow-wrap:anywhere]" data-slot="side-jobs-yield" data-other-pod={podState}>
              {[sj.leaderYield == null ? null : sj.leaderYield ? t('hmp.sjYieldOn') : t('hmp.sjYieldOff'), otherPod].filter(Boolean).join(' · ')}
            </span>
            {sj.threads === 0 && <span className="text-muted-foreground">{t('hmp.sjInline')}</span>}
            {sj.accepting === false && sj.threads !== 0 && <span className="text-muted-foreground" data-slot="side-jobs-closing">{t('hmp.sjNotAccepting')}</span>}
            {sj.active.length > 0 && (
              <ul data-slot="side-jobs-active" className="flex list-none flex-col gap-0.5">
                {sj.active.map((j) => (
                  <li key={j.name} data-slot="side-job" data-state={j.state} className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 [overflow-wrap:anywhere]">
                    <PodName>{j.name}</PodName>
                    <ToneBadge tone={SIDE_JOB_STATE_TONE[j.state] || 'muted'} className="font-semibold">{t(JOB_STATE_LABEL[j.state] || 'health.stUnknown')}</ToneBadge>
                    {j.state === 'running' && j.started_at && <span className="text-muted-foreground">{t('hmp.sjStarted', relTime(j.started_at, t) || formatDate(j.started_at))}</span>}
                  </li>
                ))}
              </ul>
            )}
            {sj.failedJobs.length > 0 && (
              <ul data-slot="side-jobs-failed" className={`flex list-none flex-col gap-0.5 ${WARN_T}`}>
                {sj.failedJobs.map((j) => (
                  <li key={`f-${j.name}`} data-slot="side-job-failed" className="[overflow-wrap:anywhere]">
                    <PodName>{j.name}</PodName> · {t(OUTCOME_LABEL[j.last_outcome] || 'hmp.sjOutcomeFail')}
                    {j.last_finished_at ? ` · ${t('hmp.sjFinished', formatDate(j.last_finished_at))}` : ''}
                  </li>
                ))}
              </ul>
            )}
          </span>
        )}
      </dd>
    </>
  )
}

// ── Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §8.3) ──────────────────────────────────────────────────────

/** Yükleme seviyesi → rozet metni. */
const LOAD_BADGE = {
  full: 'hmp.esLoad.outcome.full', observations: 'hmp.esLoad.outcome.observations', none: 'hmp.esLoad.outcome.none',
  warn: 'hmp.esLoad.outcome.none', off: 'hmp.esLoad.outcome.off', empty: 'hmp.esLoad.outcome.empty',
  unknown: 'hmp.esLoad.outcome.unknown', stale: 'hmp.esLoad.outcome.stale',
}

/**
 * Yüklenen durumun YAŞI ("9 sn önce yazılmıştı") — devir işaretinin ya da son kalıcı yazımın yükleme anına uzaklığı
 * (inceleme 2026-10-28, L-4: yükleme anı "Yükleyen pod" satırında zaten var). Bilinmiyorsa "—".
 */
function writtenBeforeText(t, r) {
  const ms = r.handoverAgeMs ?? r.durableAgeMs
  if (ms == null || ms < 0) return '—'
  return t('hmp.esLoad.writtenBefore', formatDurationShort(Math.round(ms / 1000), t))
}

/** Neden kodu → cümle (bilinen kodlar sözlükten; yeni sürümün kodu "neden: x" — ham anahtar asla). */
function loadReasonText(t, reason) {
  if (!reason) return null
  return EVAL_LOAD_REASONS.includes(reason) ? t(`hmp.esLoad.reason.${reason}`) : t('hmp.esReasonOther', reason)
}

/**
 * "Devirde yüklenen durum" — KÜMENİN son devir yüklemesi (yeni tarama lideri yayımlar; "Küme"), gözlem yoksa bu pod'un son
 * yüklemesi ("Bu pod: <ad>"). Yalnız "Tam" yeşil; "Yalnız gözlemler" bilgi; beklenen nedenle yüklenmedi / kapalı / henüz yok /
 * bilinmiyor gri; işletim nedeniyle yüklenmedi uyarı. Eski sunucu (alan yok) → gizli.
 */
export function EvalLoadSection({ t, load }) {
  if (!load) return null
  const r = load.report
  const reason = loadReasonText(t, load.reason)
  const loaded = load.level === 'full' || load.level === 'observations'
  const warn = load.level === 'warn'
  const hint = load.error ? t('hmp.esLoad.unreadable')
    : load.level === 'off' ? t('hmp.esLoad.offHint')
      : load.level === 'empty' ? t('hmp.esLoad.emptyHint')
        : load.level === 'stale' && r ? t('hmp.esLoad.staleHint', r.podName || '—', r.at ? when(r.at, t) : '—') : null
  return (
    <section data-slot="cluster-eval-load" data-level={load.level} data-scope={load.scope}
      aria-labelledby="hmp-esload-title" className="flex flex-col gap-1.5 border-t pt-2.5">
      <SubTitle className="mt-0 text-[12px]">
        <span id="hmp-esload-title">{t('hmp.esLoad.title')}</span>
        <PodChip t={t} scope={load.scope} pod={load.scope === 'pod' ? load.podName : undefined} />
      </SubTitle>
      <KvList>
        <dt>{t('hmp.esLoad.outcomeLabel')}</dt>
        <dd data-slot="es-load-outcome" data-level={load.level}
          className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 [overflow-wrap:anywhere]">
          <ToneBadge tone={load.tone} className="h-auto max-w-full overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]">
            {t(LOAD_BADGE[load.level] || 'hmp.esLoad.outcome.unknown')}
          </ToneBadge>
          {reason && (
            <span data-slot="es-load-reason" data-reason={load.reason}
              className={`min-w-0 [overflow-wrap:anywhere] ${warn ? WARN_T : 'text-muted-foreground'}`}>{reason}</span>
          )}
        </dd>
        {r && r.fromGeneration != null && (
          <>
            <dt>{t('hmp.esLoad.source')}</dt>
            <dd data-slot="es-load-from" className="min-w-0 [overflow-wrap:anywhere]">
              {t('hmp.esLoad.from', r.fromGeneration, r.fromPodName || '—', writtenBeforeText(t, r))}
            </dd>
          </>
        )}
        {r && (r.observedBy || r.podName) && (
          <>
            <dt>{t('hmp.esLoad.by')}</dt>
            <dd data-slot="es-load-by" className="min-w-0 [overflow-wrap:anywhere]">
              <PodName>{r.observedBy || r.podName}</PodName>
              {r.at && <span className="text-muted-foreground"> · {when(r.at, t)}</span>}
            </dd>
          </>
        )}
        {r && loaded && (
          <>
            <dt>{t('hmp.esLoad.loadedLabel')}</dt>
            <dd data-slot="es-load-counts" className="min-w-0 text-muted-foreground [overflow-wrap:anywhere]">
              {t('hmp.esLoad.counts', r.recoveryCounters, r.siblings, r.suppressions, r.roundSeen, r.windowDomains)}
            </dd>
          </>
        )}
        {r && r.waiters > 0 && (
          <>
            <dt>{t('hmp.esLoad.waitedLabel')}</dt>
            <dd data-slot="es-load-waited" className="min-w-0 text-muted-foreground [overflow-wrap:anywhere]">
              {t('hmp.esLoad.waited', r.waiters, r.maxWaitMs)}
            </dd>
          </>
        )}
      </KvList>
      {hint && <p className="text-[0.9em] text-muted-foreground" data-slot="es-load-hint">{hint}</p>}
      <p className="text-xs text-muted-foreground" data-slot="es-load-note">{t('hmp.esLoad.note')}</p>
    </section>
  )
}

/** Bu pod'un devir hazırlığı — "Bu pod" `<KvList>` satırı (eski sunucu → gizli). */
export function HandoverRows({ t, handover }) {
  if (!handover) return null
  const reason = handover.reason
  const reasonText = !reason ? null
    : HANDOVER_NOT_READY_FROM_REASON.includes(reason) ? t(`hmp.esLoad.reason.${reason}`)
      : t(`hmp.esLoad.ready.${reason}`)
  return (
    <>
      <dt>{t('hmp.esLoad.readyTitle')}</dt>
      <dd data-slot="cluster-handover" data-ready={handover.ready ? 'true' : 'false'} data-reason={reason || undefined}
        className="min-w-0 [overflow-wrap:anywhere]">
        <span className="flex flex-col gap-0.5">
          <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5">
            {handover.ready
              ? <ToneBadge tone="success" className="h-auto max-w-full overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]">{t('hmp.esLoad.ready')}</ToneBadge>
              : <span className="text-muted-foreground [overflow-wrap:anywhere]" data-slot="handover-not-ready">{t('hmp.esLoad.notReady', reasonText)}</span>}
          </span>
          {handover.lastHandover && (
            <span className="text-muted-foreground [overflow-wrap:anywhere]" data-slot="handover-last" data-result={handover.lastHandover}>
              {t('hmp.esLoad.lastHandover', handover.lastHandoverKnown
                ? t(`hmp.esLoad.handover.${handover.lastHandover}`) : t('hmp.esReasonOther', handover.lastHandover))}
            </span>
          )}
        </span>
      </dd>
    </>
  )
}

// ── Çok pod Sürüm 3-3 (2026-10-29, paket D1; tasarım R3-3 §7.2): dağıtık kontroller ─────────────────────────────────

/** İşçi tablosuna geçiş genişliği (px, KAP genişliği — görünüm alanı değil); altında kartlar. jsdom 0 = tablo. */
export const CHECK_WORKERS_TABLE_MIN_PX = 760

const CJ_STATUS_LABEL = {
  on: 'hmp.cj.status.on', warn: 'hmp.cj.status.warn', idle: 'hmp.cj.status.idle', off: 'hmp.cj.status.off',
  unknown: 'hmp.cj.status.unknown',
}
const CJ_MODE_LABEL = {
  distributed: 'hmp.cj.mode.distributed', classic: 'hmp.cj.mode.classic', off: 'hmp.cj.mode.off', unknown: 'hmp.cj.mode.unknown',
}
const CJ_WORKER_STATE_LABEL = { live: 'hmp.cj.w.state.live', silent: 'hmp.cj.w.state.silent', stopping: 'hmp.cj.w.state.stopping' }
const CJ_WORKERS_REASON = { not_read_yet: 'hmp.cj.w.notReadYet', table_missing: 'hmp.cj.w.tableMissing' }

/** Tür kodu → okunur ad (izleme türü sözlüğü); bilinmeyen tür (yeni sürüm) adıyla. */
function typeName(t, type) {
  const label = t(`mw.type.${type}`)
  return label && label !== `mw.type.${type}` ? label : type
}

/** Süre (ms) → "850 ms" / "4 sn" / "2 dk 5 sn"; bilinmiyorsa "—". */
function durationText(t, ms) {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '—'
  if (ms < 1000) return t('hmp.cj.ms', Math.round(ms))
  return formatDurationShort(Math.round(ms / 1000), t)
}

/** Kuyruk kutucuğu (etiket + değer); uyarı tonunda amber. */
function QueueTile({ label, value, warn, slot }) {
  return (
    <div data-slot={slot} data-warn={warn ? 'true' : undefined}
      className={`flex min-w-0 flex-col gap-0.5 rounded-md border p-2 ${warn ? 'border-amber-500/40 bg-amber-500/5' : 'bg-card'}`}>
      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{label}</span>
      <span className={`text-base font-semibold tabular-nums [overflow-wrap:anywhere] ${warn ? WARN_T : ''}`}>{value}</span>
    </div>
  )
}

function CapChips({ t, caps, missing }) {
  if (caps.length === 0 && missing.length === 0) return <span className="text-muted-foreground">{t('hmp.cj.w.noCaps')}</span>
  return (
    <span className="flex min-w-0 flex-wrap gap-1">
      {caps.map((c) => (
        <ToneBadge key={c} tone="muted" data-slot="cj-cap" data-cap={c}
          className="h-auto max-w-full overflow-visible rounded-md whitespace-normal [overflow-wrap:anywhere]">{typeName(t, c)}</ToneBadge>
      ))}
      {missing.map((c) => (
        <ToneBadge key={`m-${c}`} tone="warning" data-slot="cj-cap-missing" data-cap={c}
          className="h-auto max-w-full overflow-visible rounded-md whitespace-normal [overflow-wrap:anywhere]">{t('hmp.cj.w.missingCap', typeName(t, c))}</ToneBadge>
      ))}
    </span>
  )
}

function WorkerState({ t, w }) {
  return (
    <ToneBadge tone={w.tone || 'muted'} data-slot="cj-worker-state" data-state={w.state}
      className="h-auto overflow-visible rounded-md whitespace-normal">{t(CJ_WORKER_STATE_LABEL[w.state] || 'health.stUnknown')}</ToneBadge>
  )
}

function WorkerPod({ t, w }) {
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="flex min-w-0 flex-wrap items-center gap-1.5">
        <PodName>{w.pod}</PodName>
        {w.self && <ToneBadge tone="info" data-slot="cj-worker-self" className="h-auto overflow-visible rounded-md whitespace-normal">{t('hmp.cj.w.self')}</ToneBadge>}
      </span>
      {w.version && <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('hmp.cj.w.version', w.version)}</span>}
    </span>
  )
}

/** İşçiler: kap ≥ {@link CHECK_WORKERS_TABLE_MIN_PX} tablo, değilse kartlar. Adlar SARAR. */
function CheckWorkers({ t, cj }) {
  const [ref, width] = useElementWidth()
  const table = width === 0 || width >= CHECK_WORKERS_TABLE_MIN_PX
  const workers = cj.workers
  const hb = (w) => (w.heartbeatAgeMs != null ? t('health.ago', durationText(t, w.heartbeatAgeMs)) : '—')
  return (
    <div ref={ref} data-slot="cj-workers" data-layout={table ? 'table' : 'cards'} className="min-w-0">
      {table ? (
        <Table className="table-fixed text-[0.86em]">
          <TableHeader className="bg-muted/50">
            <TableRow>
              <TableHead className="w-[30%] whitespace-normal">{t('hmp.cj.w.col.pod')}</TableHead>
              <TableHead className="w-[26%] whitespace-normal">{t('hmp.cj.w.col.caps')}</TableHead>
              <TableHead className="w-[11%] whitespace-normal">{t('hmp.cj.w.col.slots')}</TableHead>
              <TableHead className="w-[13%] whitespace-normal">{t('hmp.cj.w.col.jobs')}</TableHead>
              <TableHead className="w-[9%] whitespace-normal">{t('hmp.cj.w.col.lag')}</TableHead>
              <TableHead className="w-[11%] whitespace-normal">{t('hmp.cj.w.col.heartbeat')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {workers.map((w) => (
              <TableRow key={w.instanceId || w.pod} data-slot="cj-worker" data-state={w.state} data-self={w.self ? 'true' : undefined}
                data-mismatch={w.mismatch ? 'true' : undefined}>
                <TableCell className="align-top whitespace-normal"><WorkerPod t={t} w={w} /></TableCell>
                <TableCell className="align-top whitespace-normal"><CapChips t={t} caps={w.caps} missing={w.missing} /></TableCell>
                <TableCell className="align-top whitespace-normal tabular-nums">{t('hmp.cj.w.slots', w.busy ?? '—', w.slots ?? '—')}</TableCell>
                <TableCell className="align-top whitespace-normal tabular-nums">{t('hmp.cj.w.jobs', w.completed ?? '—', w.late ?? '—')}</TableCell>
                <TableCell className="align-top whitespace-normal tabular-nums">{durationText(t, w.lagMs)}</TableCell>
                <TableCell className="align-top whitespace-normal">
                  <span className="flex min-w-0 flex-col items-start gap-0.5"><WorkerState t={t} w={w} /><span className="text-xs text-muted-foreground">{hb(w)}</span></span>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {workers.map((w) => (
            <li key={w.instanceId || w.pod} data-slot="cj-worker" data-state={w.state} data-self={w.self ? 'true' : undefined}
              data-mismatch={w.mismatch ? 'true' : undefined} className="flex min-w-0 flex-col gap-1.5 rounded-md border bg-card p-2.5">
              <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                <WorkerPod t={t} w={w} />
                <WorkerState t={t} w={w} />
              </div>
              <CapChips t={t} caps={w.caps} missing={w.missing} />
              <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {t('hmp.cj.w.slots', w.busy ?? '—', w.slots ?? '—')} · {t('hmp.cj.w.jobs', w.completed ?? '—', w.late ?? '—')}
                {' · '}{t('hmp.cj.w.lag', durationText(t, w.lagMs))} · {t('hmp.cj.w.heartbeat', hb(w))}
              </span>
            </li>
          ))}
        </ul>
      )}
      {cj.workersTotal > workers.length && (
        <p className="mt-1 text-xs text-muted-foreground" data-slot="cj-workers-more">{t('hmp.cj.w.more', cj.workersTotal - workers.length)}</p>
      )}
    </div>
  )
}

/**
 * "Dağıtık kontroller" — Küme kartının çok pod Sürüm 3-3 bölümü ("Küme" etiketli; kuyruk ve işçi kaydı veritabanından): ana
 * anahtar, tür kipleri (YANITLAYAN pod'un görüşü — yalnız liderde anlamlı), kuyruk kutucukları (en eski bekleyen /
 * değerlendirilmeyen 30 sn'den eski → uyarı), işçiler (kap ≥ 760 px tablo, değilse kart), yetenek uyuşmazlığı uyarısı. Açık
 * anahtar yeşil DEĞİL; okunamayan kuyruk / kayıt "bilinmiyor" (gri). Eski sunucu (alan yok) → gizli.
 */
export function CheckJobsSection({ t, cj }) {
  if (!cj) return null
  const tone = CHECK_JOBS_TONE[cj.status] || 'muted'
  const q = cj.queue
  const hint = cj.status === 'off' ? t('hmp.cj.offHint')
    : cj.status === 'idle' ? t('hmp.cj.idleHint')
      : cj.error ? t('hmp.cj.unreadable') : null
  return (
    <section data-slot="cluster-check-jobs" data-level={cj.level} data-status={cj.status} aria-labelledby="hmp-cj-title"
      className="flex flex-col gap-2 border-t pt-2.5">
      <SubTitle className="mt-0 text-[12px]"><span id="hmp-cj-title">{t('hmp.cj.title')}</span><PodChip t={t} scope="cluster" /></SubTitle>
      <KvList>
        <dt>{t('hmp.cj.switch')}</dt>
        <dd data-slot="cj-switch" data-status={cj.status} className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5">
          <ToneBadge tone={tone} className="h-auto max-w-full overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]">
            {t(CJ_STATUS_LABEL[cj.status] || 'hmp.cj.status.unknown')}
          </ToneBadge>
        </dd>
        {cj.enabled && (
          <>
            <dt>{t('hmp.cj.modes')}</dt>
            <dd className="min-w-0">
              <ul data-slot="cj-modes" className="m-0 flex list-none flex-wrap gap-1.5 p-0">
                {cj.modes.map((m) => (
                  <li key={m.type} data-slot="cj-mode" data-type={m.type} data-mode={m.mode} className="min-w-0 max-w-full">
                    <ToneBadge tone={m.tone || 'muted'} className="h-auto max-w-full overflow-visible rounded-md text-left whitespace-normal [overflow-wrap:anywhere]">
                      {t('hmp.cj.modeValue', typeName(t, m.type), t(CJ_MODE_LABEL[m.mode] || 'hmp.cj.mode.unknown'))}
                    </ToneBadge>
                  </li>
                ))}
              </ul>
              {cj.types.length > 0 && cj.leaderView !== true && (
                <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="cj-mode-follower">
                  {t('hmp.cj.modeFollowerNote', cj.modeByPod || '—')}
                </p>
              )}
              {cj.types.length > 0 && cj.leaderView === true && (
                <p className="mt-1 text-xs text-muted-foreground" data-slot="cj-mode-leader">{t('hmp.cj.modeLeaderNote')}</p>
              )}
            </dd>
          </>
        )}
      </KvList>
      {cj.enabled && cj.types.length > 0 && (
        q ? (
          <div className="flex flex-col gap-1.5" data-slot="cj-queue" data-level={cj.queueLevel}>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <QueueTile slot="cj-q-pending" label={t('hmp.cj.q.pending')} value={q.pending ?? '—'} />
              <QueueTile slot="cj-q-oldest-pending" label={t('hmp.cj.q.oldestPending')} warn={q.pendingWarn}
                value={q.oldestPendingMs == null ? t('hmp.cj.q.none') : durationText(t, q.oldestPendingMs)} />
              <QueueTile slot="cj-q-done" label={t('hmp.cj.q.done')} value={q.done ?? '—'} />
              <QueueTile slot="cj-q-oldest-done" label={t('hmp.cj.q.oldestDone')} warn={q.doneWarn}
                value={q.oldestDoneMs == null ? t('hmp.cj.q.none') : durationText(t, q.oldestDoneMs)} />
            </div>
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="cj-q-claimed">
              {t('hmp.cj.q.claimed', q.claimed ?? '—')}
              {q.expired > 0 ? <span className={WARN_T} data-slot="cj-q-expired"> · {t('hmp.cj.q.expired', q.expired)}</span> : null}
            </p>
            {q.byType.some((b) => b.pending + b.claimed + b.done > 0) && (
              <ul data-slot="cj-q-by-type" className="m-0 flex list-none flex-wrap gap-x-3 gap-y-0.5 p-0 text-xs text-muted-foreground">
                {q.byType.filter((b) => b.pending + b.claimed + b.done > 0).map((b) => (
                  <li key={b.type} data-type={b.type} className="[overflow-wrap:anywhere]">
                    {t('hmp.cj.q.typeCounts', typeName(t, b.type), b.pending, b.claimed, b.done)}
                  </li>
                ))}
              </ul>
            )}
            {cj.queueLevel === 'warn' && <p className={`m-0 text-xs ${WARN_T}`} data-slot="cj-q-warn">{t('hmp.cj.q.warnHint')}</p>}
          </div>
        ) : (
          <p className="m-0 text-[0.9em] text-muted-foreground [overflow-wrap:anywhere]" data-slot="cj-queue" data-level="unknown">
            {t('hmp.cj.q.unreadable', cj.queueReason || '—')}
          </p>
        )
      )}
      {cj.enabled && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('hmp.cj.workers')}</span>
          {!cj.workersKnown && cj.workers.length === 0 ? (
            <p className="m-0 text-[0.9em] text-muted-foreground" data-slot="cj-workers-unknown">
              {t(CJ_WORKERS_REASON[cj.workersReason] || 'hmp.cj.w.unknown')}
            </p>
          ) : cj.workers.length === 0 ? (
            <p className="m-0 text-[0.9em] text-muted-foreground" data-slot="cj-workers-none">{t('hmp.cj.w.none')}</p>
          ) : (
            <>
              {!cj.workersKnown && <p className="m-0 text-xs text-muted-foreground" data-slot="cj-workers-stale">{t('hmp.cj.w.stale')}</p>}
              <CheckWorkers t={t} cj={cj} />
            </>
          )}
        </div>
      )}
      {cj.mismatch.length > 0 && (
        <AlertBanner tone="warning" title={t('hmp.cj.mismatchTitle')} className="mb-0">
          <span data-slot="cj-cap-mismatch" className="[overflow-wrap:anywhere]">
            {t('hmp.cj.mismatchBody', cj.mismatch.map((w) => `${w.pod} (${w.missing.map((c) => typeName(t, c)).join(', ')})`).join('; '))}
          </span>
        </AlertBanner>
      )}
      {hint && <p className="m-0 text-[0.9em] text-muted-foreground" data-slot="cj-hint">{hint}</p>}
      {cj.enabled && <p className="m-0 text-xs text-muted-foreground" data-slot="cj-note">{t('hmp.cj.note')}</p>}
    </section>
  )
}

/** Bu pod'un dağıtık kontrol işçisi — "Bu pod" `<KvList>` satırı (anahtar kapalı / eski sunucu → gizli). */
export function CheckJobsPodRows({ t, cj }) {
  if (!cj || !cj.enabled || !cj.thisPod) return null
  const p = cj.thisPod
  const w = p.worker
  return (
    <>
      <dt>{t('hmp.cj.podRow')}</dt>
      <dd data-slot="cluster-check-worker" data-known={p.workerKnown ? 'true' : 'false'} className="min-w-0 [overflow-wrap:anywhere]">
        <span className="flex flex-col gap-0.5">
          {!w ? <span>{t('health.stUnknown')}</span>
            : !w.enabled ? <span data-slot="cj-pod-off">{t('hmp.cj.pod.workerOff')}</span>
              : (
                <>
                  <span data-slot="cj-pod-slots">
                    {t('hmp.cj.pod.slots', w.slots ?? '—', w.busy ?? '—')}
                    {!w.started ? ` · ${t('hmp.cj.pod.notStarted')}` : ''}
                    {w.closing ? ` · ${t('hmp.cj.pod.closing')}` : ''}
                  </span>
                  <span className="text-muted-foreground" data-slot="cj-pod-jobs">
                    {t('hmp.cj.pod.jobs', w.completed, w.late, w.timeouts, w.failed)} · {t('hmp.cj.w.lag', durationText(t, w.lagMs))}
                  </span>
                  {(w.claimErrors > 0 || w.completeErrors > 0) && (
                    <span className="text-muted-foreground" data-slot="cj-pod-errors">{t('hmp.cj.pod.issues', w.claimErrors, w.completeErrors)}</span>
                  )}
                </>
              )}
          {p.localCaps && (
            <span className="text-muted-foreground" data-slot="cj-pod-caps">
              {t('hmp.cj.pod.caps', p.localCaps.length ? p.localCaps.map((c) => typeName(t, c)).join(', ') : t('hmp.cj.w.noCaps'))}
            </span>
          )}
          {p.dbClockStale === true && <span className={WARN_T} data-slot="cj-pod-clock-stale">{t('hmp.cj.pod.clockStale')}</span>}
          {p.leader === true && p.dispatch && (
            <span className="text-muted-foreground" data-slot="cj-pod-leader">
              {t('hmp.cj.pod.leader', p.dispatch.dispatched, p.dispatch.applied, p.dispatch.lost, p.dispatch.dropped ?? '—')}
              {p.dispatch.failures > 0 ? ` · ${t('hmp.cj.pod.dispatchFailures', p.dispatch.failures)}` : ''}
            </span>
          )}
          {p.leader === true && p.drainer && p.drainer.started && (
            <span className="text-muted-foreground" data-slot="cj-pod-drainer">
              {t('hmp.cj.pod.drainer', p.drainer.taken, p.drainer.waves,
                p.drainer.lastTakeAt ? (relTime(p.drainer.lastTakeAt, t) || formatDate(p.drainer.lastTakeAt)) : '—')}
              {p.drainer.applyFailures + p.drainer.takeFailures > 0
                ? ` · ${t('hmp.cj.pod.drainerFailures', p.drainer.takeFailures, p.drainer.applyFailures)}` : ''}
            </span>
          )}
        </span>
      </dd>
    </>
  )
}

// ── Çok pod Sürüm 3-4 (2026-10-29, paket C1; tasarım R3-4 §7.2): parçalı sertifika taraması ─────────────────────────

/** Pod tablosuna geçiş genişliği (px, KAP genişliği); altında kartlar. jsdom 0 = tablo. */
export const CERT_PODS_TABLE_MIN_PX = 760

const CC_STATUS_LABEL = {
  running: 'hmp.cc.status.running', idle: 'hmp.cc.status.idle', warn: 'hmp.cc.status.warn', off: 'hmp.cc.status.off',
  unknown: 'hmp.cc.status.unknown',
}
const CC_MODE_LABEL = { chunked: 'hmp.cc.mode.chunked', classic: 'hmp.cc.mode.classic', unknown: 'hmp.cc.mode.unknown' }
const CC_WARN_LABEL = {
  pending: 'hmp.cc.warn.pending', outlier: 'hmp.cc.warn.outlier', secondOpinion: 'hmp.cc.warn.secondOpinion',
  coordinatorSilent: 'hmp.cc.warn.coordinatorSilent', lastFailed: 'hmp.cc.warn.lastRunBad',
}
const CC_KIND_LABEL = { hourly: 'hmp.kind.hourly', stale: 'hmp.kind.stale', full: 'hmp.kind.full' }

/** Tür kodu → okunur ad; bilinmeyen tür (yeni sürüm) adıyla. */
function kindName(t, kind) {
  return CC_KIND_LABEL[kind] ? t(CC_KIND_LABEL[kind]) : (kind || '—')
}

/** Koşu sonucu → okunur ad; bilinmeyen sonuç "sonuç: x" (ham kod tek başına basılmaz). */
function outcomeText(t, r) {
  if (r.outcome && r.outcomeKnown) return t(`hmp.cc.outcome.${r.outcome}`)
  if (r.outcome) return t('hmp.esReasonOther', r.outcome)
  return t(`hmp.cc.state.${r.state === 'DONE' || r.state === 'FAILED' || r.state === 'ABANDONED' ? r.state : 'other'}`)
}

/** Ağ hatası oranı (0–1) → arayüz yerelinde yüzde ("%12,5" / "12.5%"); bilinmiyorsa "—". */
function rateText(rate) {
  if (rate == null || !Number.isFinite(rate)) return '—'
  return formatPercent((rate * 100).toLocaleString(dateLocale(), { maximumFractionDigits: 1 }))
}

/** Pod hücresi: ad SARAR, "bu pod" ve "aykırı" rozetleri. */
function CertPodCell({ t, p }) {
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1.5">
      <PodName>{p.pod}</PodName>
      {p.self && <ToneBadge tone="info" data-slot="cc-pod-self" className="h-auto overflow-visible rounded-md whitespace-normal">{t('hmp.cj.w.self')}</ToneBadge>}
      {p.outlier && <ToneBadge tone="warning" data-slot="cc-pod-outlier" className="h-auto overflow-visible rounded-md whitespace-normal">{t('hmp.cc.outlierBadge')}</ToneBadge>}
    </span>
  )
}

/** Tur kipi rozeti (parçalı bilgi, klasik / bilinmiyor gri). */
export function CertModeBadge({ t, mode, slot }) {
  const m = CERT_SWEEP_MODES.includes(mode) ? mode : 'unknown'
  return (
    <ToneBadge tone={CERT_MODE_TONE[m] || 'muted'} {...(slot ? { 'data-slot': slot } : {})} data-mode={m}
      className="h-auto max-w-full overflow-visible rounded-md whitespace-normal [overflow-wrap:anywhere]">{t(CC_MODE_LABEL[m])}</ToneBadge>
  )
}

/** Açık koşunun pod başına payı: kap ≥ {@link CERT_PODS_TABLE_MIN_PX} tablo, değilse kartlar. Adlar SARAR. */
function CertChunkPods({ t, run }) {
  const [ref, width] = useElementWidth()
  const table = width === 0 || width >= CERT_PODS_TABLE_MIN_PX
  return (
    <div ref={ref} data-slot="cc-pods" data-layout={table ? 'table' : 'cards'} className="min-w-0">
      {table ? (
        <Table className="table-fixed text-[0.86em]">
          <TableHeader className="bg-muted/50">
            <TableRow>
              <TableHead className="w-[46%] whitespace-normal">{t('hmp.cc.col.pod')}</TableHead>
              <TableHead className="w-[14%] whitespace-normal">{t('hmp.cc.col.chunks')}</TableHead>
              <TableHead className="w-[20%] whitespace-normal">{t('hmp.cc.col.domains')}</TableHead>
              <TableHead className="w-[20%] whitespace-normal">{t('hmp.cc.col.rate')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {run.pods.map((p) => (
              <TableRow key={p.instanceId || p.pod} data-slot="cc-pod" data-outlier={p.outlier ? 'true' : undefined} data-self={p.self ? 'true' : undefined}>
                <TableCell className="align-top whitespace-normal"><CertPodCell t={t} p={p} /></TableCell>
                <TableCell className="align-top whitespace-normal tabular-nums">{p.chunks ?? '—'}</TableCell>
                <TableCell className="align-top whitespace-normal tabular-nums">{t('hmp.cc.podDomains', p.doneDomains ?? '—', p.domains ?? '—')}</TableCell>
                <TableCell className={`align-top whitespace-normal tabular-nums ${p.outlier ? WARN_T : ''}`}>
                  {rateText(p.rate)}{p.networkErrors != null ? ` · ${t('hmp.cc.podNet', p.networkErrors)}` : ''}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {run.pods.map((p) => (
            <li key={p.instanceId || p.pod} data-slot="cc-pod" data-outlier={p.outlier ? 'true' : undefined} data-self={p.self ? 'true' : undefined}
              className="flex min-w-0 flex-col gap-1 rounded-md border bg-card p-2.5">
              <CertPodCell t={t} p={p} />
              <span className={`text-xs [overflow-wrap:anywhere] ${p.outlier ? WARN_T : 'text-muted-foreground'}`}>
                {t('hmp.cc.podCard', p.chunks ?? '—', p.doneDomains ?? '—', p.domains ?? '—', rateText(p.rate))}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Kapanmış son koşular (yalnız parçalı koşular tutulur — hepsi "parçalı" kipinde). */
function CertLastRuns({ t, runs }) {
  if (runs.length === 0) return <p className="m-0 text-[0.9em] text-muted-foreground" data-slot="cc-last-none">{t('hmp.cc.lastNone')}</p>
  return (
    <ul data-slot="cc-last-runs" className="m-0 flex list-none flex-col gap-1.5 p-0">
      {runs.map((r) => (
        <li key={r.runId || `${r.createdAt}-${r.kind}`} data-slot="cc-last-run" data-state={r.state} data-tone={r.tone}
          className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[0.9em] [overflow-wrap:anywhere]">
          <span className="font-semibold">{kindName(t, r.kind)}</span>
          <CertModeBadge t={t} mode="chunked" />
          <ToneBadge tone={r.tone} data-slot="cc-last-outcome" className="h-auto max-w-full overflow-visible rounded-md whitespace-normal [overflow-wrap:anywhere]">
            {outcomeText(t, r)}
          </ToneBadge>
          <span className="text-muted-foreground">{r.finishedAt ? relTime(r.finishedAt, t) || formatDate(r.finishedAt) : '—'}</span>
          {r.durationMs != null && <span className="text-muted-foreground">· {durationText(t, r.durationMs)}</span>}
          {r.pods != null && <span className="text-muted-foreground">· {t('hmp.cc.lastPods', r.pods)}</span>}
          {r.domainsTotal != null && <span className="text-muted-foreground">· {t('hmp.cc.lastDomains', r.domainsTotal)}</span>}
          {(r.takeovers ?? 0) > 0 && <span className="text-muted-foreground">· {t('hmp.cc.lastTakeovers', r.takeovers)}</span>}
          {(r.secondOpinions ?? 0) > 0 && <span className={WARN_T}>· {t('hmp.cc.lastSecondOpinions', r.secondOpinions)}</span>}
          {r.coordinatorPod && <span className="text-muted-foreground">· <PodName>{r.coordinatorPod}</PodName></span>}
        </li>
      ))}
    </ul>
  )
}

/**
 * "Parçalı sertifika taraması" — Küme kartının çok pod Sürüm 3-4 bölümü ("Küme" etiketli; koşu ve parça satırları veritabanından):
 * anahtar durumu, son turun kipi, açık turun ilerlemesi (parça / alan adı), kutucuklar (bekleyen, en eski bekleyen > 30 sn uyarı,
 * devralınan bilgi, ikinci görüş > 0 uyarı), pod başına pay (kap ≥ 760 px tablo, değilse kart; aykırı pod uyarı), son 5 koşu.
 * Açık anahtar yeşil DEĞİL; okunamayan durum "bilinmiyor" (gri). Eski sunucu (alan yok) → gizli.
 */
export function CertChunksSection({ t, cc }) {
  if (!cc) return null
  const tone = CERT_CHUNKS_TONE[cc.status] || 'muted'
  const run = cc.run
  const hint = cc.status === 'off' ? t('hmp.cc.offHint')
    : cc.error || cc.statusError ? t('hmp.cc.unreadable', cc.statusError || cc.error || '—')
      : cc.status === 'idle' && !run ? t('hmp.cc.idleHint') : null
  return (
    <section data-slot="cluster-cert-chunks" data-status={cc.status} data-level={cc.level} aria-labelledby="hmp-cc-title"
      className="flex flex-col gap-2 border-t pt-2.5">
      <SubTitle className="mt-0 text-[12px]"><span id="hmp-cc-title">{t('hmp.cc.title')}</span><PodChip t={t} scope="cluster" /></SubTitle>
      <KvList>
        <dt>{t('hmp.cc.switch')}</dt>
        <dd data-slot="cc-switch" data-status={cc.status} className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5">
          <ToneBadge tone={tone} className="h-auto max-w-full overflow-visible rounded-md font-semibold whitespace-normal [overflow-wrap:anywhere]">
            {t(CC_STATUS_LABEL[cc.status] || 'hmp.cc.status.unknown')}
          </ToneBadge>
        </dd>
        {cc.enabled && (
          <>
            <dt>{t('hmp.cc.lastMode')}</dt>
            <dd className="min-w-0"><CertModeBadge t={t} mode={cc.lastMode} slot="cc-last-mode" /></dd>
          </>
        )}
        {run && (
          <>
            <dt>{t('hmp.cc.run')}</dt>
            <dd data-slot="cc-run" className="min-w-0 [overflow-wrap:anywhere]">
              {t('hmp.cc.runLine', kindName(t, run.kind), run.coordinatorPod || '—',
                run.ageS != null ? formatDurationShort(run.ageS, t) : '—')}
              {run.deciding && <span className="text-muted-foreground" data-slot="cc-run-deciding"> · {t('hmp.cc.deciding')}</span>}
            </dd>
          </>
        )}
      </KvList>
      {run && (
        <div className="flex min-w-0 flex-col gap-2" data-slot="cc-progress">
          <ProgressBar value={run.measuredChunks} max={run.chunksTotal || 0} size="sm" showValue label={t('hmp.cc.progress')} />
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="cc-progress-text">
            {t('hmp.cc.progressText', run.measuredChunks, run.chunksTotal ?? '—', run.domainsMeasured ?? '—', run.domainsTotal ?? '—')}
          </p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <QueueTile slot="cc-t-pending" label={t('hmp.cc.t.pending')} value={run.chunks.pending} />
            <QueueTile slot="cc-t-oldest" label={t('hmp.cc.t.oldest')} warn={run.pendingWarn}
              value={run.oldestPendingMs == null ? t('hmp.cj.q.none') : durationText(t, run.oldestPendingMs)} />
            <QueueTile slot="cc-t-takeovers" label={t('hmp.cc.t.takeovers')} value={run.takeovers ?? run.chunks.cancelled} />
            <QueueTile slot="cc-t-second" label={t('hmp.cc.t.second')} warn={(run.secondOpinions ?? 0) > 0} value={run.secondOpinions ?? '—'} />
          </div>
          {run.pods.length > 0 ? <CertChunkPods t={t} run={run} />
            : <p className="m-0 text-[0.9em] text-muted-foreground" data-slot="cc-pods-none">{t('hmp.cc.podsNone')}</p>}
        </div>
      )}
      {cc.warnings.length > 0 && (
        <AlertBanner tone="warning" title={t('hmp.cc.warnTitle')} className="mb-0">
          <ul data-slot="cc-warnings" className="m-0 flex list-none flex-col gap-0.5 p-0">
            {cc.warnings.map((w) => (
              <li key={w} data-warn={w} className="[overflow-wrap:anywhere]">
                {w === 'outlier'
                  ? t(CC_WARN_LABEL.outlier, run?.pods.filter((p) => p.outlier).map((p) => p.pod).join(', ') || '—')
                  : t(CC_WARN_LABEL[w])}
              </li>
            ))}
          </ul>
        </AlertBanner>
      )}
      {cc.enabled && !cc.statusError && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('hmp.cc.lastRuns')}</span>
          <CertLastRuns t={t} runs={cc.lastRuns} />
        </div>
      )}
      {hint && <p className="m-0 text-[0.9em] text-muted-foreground [overflow-wrap:anywhere]" data-slot="cc-hint">{hint}</p>}
      {cc.enabled && <p className="m-0 text-xs text-muted-foreground" data-slot="cc-note">{t('hmp.cc.note')}</p>}
    </section>
  )
}

/** Bu pod'un parça işçisi ve koordinatör sayaçları — "Bu pod" `<KvList>` satırı (anahtar kapalı / eski sunucu → gizli). */
export function CertChunksPodRows({ t, cc }) {
  if (!cc || !cc.enabled || !cc.thisPod) return null
  const w = cc.thisPod.worker
  const c = cc.thisPod.coordinator
  return (
    <>
      <dt>{t('hmp.cc.podRow')}</dt>
      <dd data-slot="cluster-cert-chunk-worker" data-known={w ? 'true' : 'false'} className="min-w-0 [overflow-wrap:anywhere]">
        <span className="flex flex-col gap-0.5">
          {!w ? <span data-slot="cc-pod-unknown">{t('hmp.cc.pod.noWorker')}</span>
            : !w.enabled ? <span data-slot="cc-pod-off">{t('hmp.cc.pod.workerOff')}</span>
              : (
                <>
                  <span data-slot="cc-pod-inflight">
                    {t('hmp.cc.pod.inflight', w.inflightChunks, w.inflightDomains, w.budget ?? '—')}
                    {!w.started ? ` · ${t('hmp.cc.pod.notStarted')}` : ''}
                    {w.leaderYielding ? ` · ${t('hmp.cc.pod.leaderYielding')}` : ''}
                  </span>
                  <span className="text-muted-foreground" data-slot="cc-pod-counts">
                    {t('hmp.cc.pod.counts', w.completed, w.late, w.released, w.discarded)}
                    {w.lastClaimAt ? ` · ${t('hmp.cc.pod.lastClaim', relTime(w.lastClaimAt, t) || formatDate(w.lastClaimAt))}` : ''}
                  </span>
                  {w.dbClockStale && <span className={WARN_T} data-slot="cc-pod-clock-stale">{t('hmp.cj.pod.clockStale')}</span>}
                </>
              )}
          {c && (
            <span className="text-muted-foreground" data-slot="cc-pod-coordinator">
              {t('hmp.cc.pod.coordinator', c.chunkedRuns, c.classicFallback, c.takeovers, c.remeasured, c.secondOpinions, c.abandoned)}
            </span>
          )}
        </span>
      </dd>
    </>
  )
}
