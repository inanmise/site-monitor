import { formatDate } from '../../../api/client'
import ToneBadge from '../ToneBadge.jsx'
import { relTime } from './healthModel.js'
import { EVAL_VERIFY_TONE, EVAL_UNKNOWN_REASONS, SIDE_JOB_STATE_TONE, otherPodState, EVAL_LOAD_REASONS, HANDOVER_RESULTS }
  from './clusterRuntimeModel.js'
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
  unknown: 'hmp.esLoad.outcome.unknown',
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
      : load.level === 'empty' ? t('hmp.esLoad.emptyHint') : null
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
              {t('hmp.esLoad.from', r.fromGeneration, r.fromPodName || '—', r.at ? (relTime(r.at, t) || formatDate(r.at)) : '—')}
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
    : ['setting_off', 'mode_off', 'tables_missing'].includes(reason) ? t(`hmp.esLoad.reason.${reason}`)
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
