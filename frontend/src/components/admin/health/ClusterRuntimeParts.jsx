import { formatDate } from '../../../api/client'
import ToneBadge from '../ToneBadge.jsx'
import { relTime } from './healthModel.js'
import { EVAL_VERIFY_TONE, EVAL_UNKNOWN_REASONS, SIDE_JOB_STATE_TONE, otherPodState } from './clusterRuntimeModel.js'
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
    <section data-slot="cluster-eval-verify" data-level={v.level} aria-labelledby="hmp-es-title" className="flex flex-col gap-1.5 border-t pt-2.5">
      <SubTitle className="mt-0 text-[12px]"><span id="hmp-es-title">{t('hmp.esTitle')}</span><PodChip t={t} scope="cluster" /></SubTitle>
      <KvList>
        <dt>{t('hmp.esVerify')}</dt>
        <dd data-slot="es-verify-status" data-level={v.level} className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
          <ToneBadge tone={tone} className="h-auto overflow-visible rounded-md font-semibold whitespace-normal">{t(VERIFY_LABEL[v.level] || 'hmp.esVerifyUnknown')}</ToneBadge>
          {v.mismatches != null && (v.level === 'ok' || v.level === 'mismatch') && (
            <span className={v.level === 'mismatch' ? WARN_T : 'text-muted-foreground'} data-slot="es-verify-mismatches">
              {t('hmp.esKindCount', v.mismatches, v.kinds.filter((k) => !k.measured).reduce((n, k) => n + (k.compared || 0), 0))}
            </span>
          )}
          {reason && <span className="text-muted-foreground">· {reason}</span>}
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
            <dd>
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
      <dd data-slot="cluster-eval-recorder" data-level={es.recorderLevel}>
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
            {warn && <span className={WARN_T} data-slot="es-failing">{t('hmp.esFailingNow')}</span>}
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
      <dd data-slot="cluster-side-jobs" data-known={sj.known ? 'true' : 'false'}>
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
