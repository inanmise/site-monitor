import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ArrowRight, Info, RefreshCw, ShieldAlert, ShieldCheck, ShieldQuestion, TrendingDown, TrendingUp, Wrench } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { GRADE_TONE, gradeLabelKey, gradeRank, isGrade, protocolRows, reasonTexts, triTone } from './tlsGradeModel.js'

const VALUE_TONE = {
  ok: 'border-success/30 bg-success/10 text-success dark:bg-success/20',
  bad: 'border-destructive/30 bg-destructive/10 text-destructive dark:bg-destructive/20',
  unknown: 'border-border bg-muted text-muted-foreground',
}
const RUBRIC_GRADES = ['F', 'D', 'C', 'B', 'A', 'A+']

/**
 * Sertifika penceresi → Sağlık sekmesi → "TLS yapılandırma notu" bölümü (2026-10-10).
 *
 * <p>Notu ve TÜM nedenleri (sunucunun parametreleriyle: anahtar boyu, takım adı, HSTS günü) "neden + ne yapmalı" diye
 * açıklar; protokol desteğini (TLS 1.0–1.3), OCSP zımbalamayı, zayıf takım kabulünü, sunucunun seçtiği takımı, HSTS'i ve
 * not geçmişini gösterir. Not SUNUCUDA hesaplanır (`/certificates/{domain}/tls-grade`); burada kural yazılmaz.
 *
 * <p>"TLS profilini yeniden tara" yalnız sunucu `can_rescan` derse çizilir (tanılama izni + takım kapsamı); tarama uç
 * başına en çok ~30 sn sürer, sunucu dakikalık sınır uygular (429 iletisi toast'ta). Saatlik "Şimdi kontrol et" bu
 * taramayı yapmaz.
 *
 * <p>Mobil: başlık alt alta, düğme tam genişlik; nedenler ve profil tek sütun (lg'de iki sütun); uzun takım adları
 * kırılır. Test kancaları: `data-slot="tls-grade-section"` (+ `data-grade`, `data-state`), `tls-grade-reason-row`
 * (+ `data-code`, `data-cap`), `tls-proto` (+ `data-proto`, `data-tone`), `tls-grade-rescan`, `tls-grade-history-row`.
 *
 * @param reloadKey değişince yeniden yüklenir (Sağlık listesinde "Şimdi kontrol et" HSTS'i değiştirmiş olabilir)
 */
export default function TlsGradeSection({ domain, reloadKey = 0 }) {
  const t = useT()
  const toast = useToast()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const seqRef = useRef(0)
  const headingId = useId()

  const load = useCallback(async () => {
    const seq = ++seqRef.current
    setError(null)
    try {
      const res = await api.getTlsGrade(domain)
      if (seq !== seqRef.current) return
      if (res?.success) setData(res.data)
      else { setData(null); setError(res?.error || t('tlsg.sec.loadError')) }
    } catch (e) {
      if (seq !== seqRef.current) return
      setData(null)
      setError(e?.message || t('tlsg.sec.loadError'))
    }
  }, [domain, t])

  useEffect(() => { setData(null); load() }, [load, reloadKey])

  async function rescan() {
    const seq = seqRef.current
    setBusy(true)
    try {
      const res = await api.rescanTlsProfile(domain)
      if (seq !== seqRef.current) return
      if (res?.success) { setData(res.data); toast.success(t('tlsg.sec.rescanDone')) }
      else toast.error(res?.error || t('tlsg.sec.rescanError'))
    } catch (e) {
      if (seq === seqRef.current) toast.error(e?.message || t('tlsg.sec.rescanError'))
    } finally {
      if (seq === seqRef.current) setBusy(false)
    }
  }

  if (error && !data) {
    return <AlertBanner tone="danger" title={t('tlsg.sec.title')} className="mb-3">{error}</AlertBanner>
  }
  if (!data) return <LoadingBlock label={t('tlsg.sec.loading')} className="mb-3" />

  const graded = data.state === 'graded' && isGrade(data.grade)
  const grade = graded ? data.grade : null
  const reasons = Array.isArray(data.reasons) ? data.reasons : []
  const notes = Array.isArray(data.notes) ? data.notes : []
  const decisive = new Set(Array.isArray(data.decisive) ? data.decisive : [])
  const profile = data.profile || null
  const history = Array.isArray(data.history) ? data.history : []
  const drop = data.drop && isGrade(data.drop.from) ? data.drop : null
  const TileIcon = !graded ? ShieldQuestion : gradeRank(grade) >= gradeRank('A') ? ShieldCheck : ShieldAlert
  const decisiveTitles = reasons.filter((r) => decisive.has(r.code)).map((r) => t(`tlsg.reason.${r.code}.title`))

  return (
    <Card data-slot="tls-grade-section" data-grade={grade || 'none'} data-state={data.state}
      role="region" aria-labelledby={headingId} className="mb-3 gap-0 overflow-hidden py-0 shadow-none">
      {/* ── Başlık: not karosu · başlık + gerekçe · yeniden tara ── */}
      <div className="flex flex-col gap-3 border-b p-3 sm:flex-row sm:items-start sm:gap-4 sm:p-4">
        <div className="flex min-w-0 flex-1 items-start gap-3">
          <span data-slot="tls-grade-tile" aria-hidden="true"
            className={cn('flex size-14 shrink-0 flex-col items-center justify-center rounded-xl border text-2xl leading-none font-extrabold',
              graded ? GRADE_TONE[grade] : 'border-dashed bg-muted text-muted-foreground')}>
            {graded ? grade : <TileIcon className="size-6" />}
          </span>
          <div className="min-w-0 flex-1">
            <h3 id={headingId} className="m-0 flex flex-wrap items-center gap-x-2 text-base leading-snug font-semibold">
              {t('tlsg.sec.title')}
              {graded && <span className="sr-only">{t('tlsg.sec.gradeSr', grade)}</span>}
            </h3>
            <p className="m-0 mt-0.5 text-sm text-muted-foreground">
              {graded ? t(gradeLabelKey(grade)) : t(`tlsg.state.${data.state_reason || 'NO_CHECK'}`)}
            </p>
            {graded && decisiveTitles.length > 0 && (
              <p data-slot="tls-grade-why" className="m-0 mt-1.5 text-sm [overflow-wrap:anywhere]">
                <span className="font-semibold">{t('tlsg.why', grade)}</span> {decisiveTitles.join(' · ')}
              </p>
            )}
            {graded && reasons.length === 0 && (
              <p className="m-0 mt-1.5 text-sm text-success">{t('tlsg.noReasons')}</p>
            )}
          </div>
        </div>
        {data.can_rescan && (
          <Button type="button" variant="outline" size="sm" data-slot="tls-grade-rescan" onClick={rescan} disabled={busy}
            aria-busy={busy || undefined} className="w-full shrink-0 sm:w-auto max-md:h-10 pointer-coarse:h-10">
            {busy ? <Spinner decorative size={14} /> : <RefreshCw aria-hidden="true" />}
            {busy ? t('tlsg.sec.rescanning') : t('tlsg.sec.rescan')}
          </Button>
        )}
      </div>

      {drop && (
        <div className="px-3 pt-3 sm:px-4">
          <AlertBanner tone="warning" icon={TrendingDown} title={t('tlsg.sec.dropTitle', drop.from, drop.to)}>
            {t('tlsg.sec.dropBody', formatDateSec(drop.at))}
          </AlertBanner>
        </div>
      )}

      {data.state !== 'not_applicable' && (
        <CardContent className="grid gap-4 p-3 sm:p-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
          {/* ── Nedenler ── */}
          <section className="min-w-0">
            <h4 className="m-0 mb-2 text-sm font-semibold">{t('tlsg.sec.reasons')}</h4>
            {!graded ? (
              <p className="m-0 text-sm text-muted-foreground">{t('tlsg.sec.noGrade')}</p>
            ) : reasons.length === 0 ? (
              <p className="m-0 text-sm text-muted-foreground">{t('tlsg.sec.allClear')}</p>
            ) : (
              <ul className="m-0 flex list-none flex-col gap-2 p-0">
                {reasons.map((r) => {
                  const x = reasonTexts(t, r)
                  return (
                    <li key={r.code} data-slot="tls-grade-reason-row" data-code={r.code} data-cap={r.cap}
                      data-decisive={decisive.has(r.code) ? 'true' : undefined}
                      className={cn('flex min-w-0 items-start gap-2.5 rounded-lg border p-2.5', decisive.has(r.code) && 'border-foreground/25 bg-muted/40')}>
                      <Badge variant="outline" className={cn('h-6 shrink-0 px-1.5 text-[11px] font-bold tabular-nums', GRADE_TONE[r.cap])}>
                        {t('tlsg.sec.capShort', r.cap)}
                      </Badge>
                      <div className="min-w-0">
                        <p className="m-0 text-sm leading-snug font-medium [overflow-wrap:anywhere]">{x.title}</p>
                        <p className="m-0 mt-0.5 text-xs leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">{x.why}</p>
                        <p className="m-0 mt-1 flex items-start gap-1.5 text-xs leading-relaxed [overflow-wrap:anywhere]">
                          <Wrench aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-muted-foreground" />
                          <span><span className="font-semibold">{t('tlsg.sec.fixLabel')}</span> {x.fix}</span>
                        </p>
                      </div>
                    </li>
                  )
                })}
              </ul>
            )}
            {notes.length > 0 && (
              <ul className="m-0 mt-3 flex list-none flex-col gap-1.5 p-0" aria-label={t('tlsg.sec.notes')}>
                {notes.map((n) => {
                  const x = reasonTexts(t, n)
                  return (
                    <li key={n.code} data-slot="tls-grade-note" data-code={n.code}
                      className="flex min-w-0 items-start gap-2 rounded-lg bg-muted/50 p-2 text-xs leading-relaxed">
                      <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                      <span className="min-w-0 [overflow-wrap:anywhere]"><span className="font-semibold">{x.title}</span> — {x.why} {x.fix}</span>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>

          {/* ── TLS profili ── */}
          <section className="min-w-0">
            <h4 className="m-0 mb-2 text-sm font-semibold">{t('tlsg.sec.profile')}</h4>
            {!profile ? (
              <p data-slot="tls-profile-never" className="m-0 text-sm text-muted-foreground">{t('tlsg.sec.profileNever')}</p>
            ) : (
              <>
                <dl className="m-0 grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1.5 text-sm">
                  {protocolRows(profile).map((p) => (
                    <ProfileRow key={p.key} slot="tls-proto" data={{ 'data-proto': p.key, 'data-tone': p.tone }} label={p.label}
                      value={t(`tlsg.sec.proto.${p.value}`)} tone={p.tone} />
                  ))}
                  <ProfileRow slot="tls-stapling" label={t('tlsg.sec.stapling')} tone={triTone(profile.ocsp_stapling, 'YES')}
                    value={t(`tlsg.sec.staplingVal.${profile.ocsp_stapling || 'UNKNOWN'}`)} />
                  <ProfileRow slot="tls-weak" label={t('tlsg.sec.weak')} tone={triTone(profile.weak_cipher, 'NO')}
                    value={t(`tlsg.sec.weakVal.${profile.weak_cipher || 'UNKNOWN'}`)} />
                </dl>
                {profile.weak_cipher === 'YES' && profile.weak_cipher_suite && (
                  <p className="m-0 mt-1 font-mono text-xs break-all text-destructive">{profile.weak_cipher_suite}</p>
                )}
                <dl className="m-0 mt-3 flex flex-col gap-2 border-t pt-3 text-xs">
                  {profile.preferred_cipher && (
                    <div className="min-w-0">
                      <dt className="text-muted-foreground">{t('tlsg.sec.preferred')}</dt>
                      <dd className="m-0 font-mono break-all">{profile.preferred_cipher}</dd>
                    </div>
                  )}
                  {data.negotiated?.tls_version && (
                    <div className="min-w-0">
                      <dt className="text-muted-foreground">{t('tlsg.sec.negotiated')}</dt>
                      <dd className="m-0 font-mono break-all">
                        {data.negotiated.tls_version}{data.negotiated.cipher_suite ? ` · ${data.negotiated.cipher_suite}` : ''}
                      </dd>
                    </div>
                  )}
                  {data.hsts && (
                    <div className="min-w-0">
                      <dt className="text-muted-foreground">HSTS</dt>
                      <dd className="m-0">{hstsText(t, data.hsts)}</dd>
                    </div>
                  )}
                  <div className="min-w-0">
                    <dt className="text-muted-foreground">{t('tlsg.sec.lastScan')}</dt>
                    <dd data-slot="tls-profile-meta" data-status={profile.status} className="m-0">
                      {formatDateSec(profile.probed_at)} · {t(`tlsg.sec.via.${profile.via === 'proxy' ? 'proxy' : 'direct'}`)}
                      {' · '}{t(`tlsg.sec.status.${profile.status || 'FAILED'}`)}
                      {profile.trigger === 'MANUAL' ? ` · ${t('tlsg.sec.manualScan')}` : ''}
                    </dd>
                  </div>
                  {profile.error && profile.status !== 'OK' && (
                    <p className="m-0 font-mono text-[11px] break-all text-muted-foreground">{profile.error}</p>
                  )}
                  {profile.via === 'proxy' && <p className="m-0 text-muted-foreground">{t('tlsg.sec.proxyNote')}</p>}
                </dl>
              </>
            )}
            <p className="m-0 mt-2 text-xs text-muted-foreground">{t('tlsg.sec.scheduleNote')}</p>
          </section>
        </CardContent>
      )}

      {/* ── Not geçmişi + kural ── */}
      <Accordion type="multiple" className="border-t px-3 sm:px-4">
        {data.state !== 'not_applicable' && (
          <AccordionItem value="history">
            <AccordionTrigger className="py-3 max-md:min-h-10 pointer-coarse:min-h-10">{t('tlsg.sec.history', history.length)}</AccordionTrigger>
            <AccordionContent>
              {history.length === 0 ? (
                <p className="m-0 text-sm text-muted-foreground">{t('tlsg.sec.historyEmpty')}</p>
              ) : (
                <ol className="m-0 flex list-none flex-col gap-1.5 p-0">
                  {history.map((h, i) => (
                    <li key={`${h.at}-${i}`} data-slot="tls-grade-history-row" data-direction={h.direction}
                      className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                      {h.direction === 'DROP'
                        ? <TrendingDown aria-hidden="true" className="size-4 shrink-0 text-destructive" />
                        : h.direction === 'REFINE'
                          ? <Info aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                          : <TrendingUp aria-hidden="true" className="size-4 shrink-0 text-success" />}
                      <Badge variant="outline" className={cn('font-bold', GRADE_TONE[h.from])}>{h.from}</Badge>
                      <ArrowRight aria-hidden="true" className="size-3.5 text-muted-foreground" />
                      <Badge variant="outline" className={cn('font-bold', GRADE_TONE[h.to])}>{h.to}</Badge>
                      {h.direction === 'REFINE'
                        ? <span className="text-xs text-muted-foreground">{t('tlsg.sec.dir.REFINE')}</span>
                        : <span className="sr-only">{t(`tlsg.sec.dir.${h.direction === 'DROP' ? 'DROP' : 'RISE'}`)}</span>}
                      <span className="text-xs text-muted-foreground">{formatDateSec(h.at)}</span>
                    </li>
                  ))}
                </ol>
              )}
            </AccordionContent>
          </AccordionItem>
        )}
        <AccordionItem value="rubric">
          <AccordionTrigger className="py-3 max-md:min-h-10 pointer-coarse:min-h-10">{t('tlsg.sec.rubricTitle')}</AccordionTrigger>
          <AccordionContent>
            <p className="m-0 mb-2 text-sm text-muted-foreground">{t('tlsg.sec.rubricIntro')}</p>
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {RUBRIC_GRADES.map((g) => (
                <li key={g} data-slot="tls-rubric-row" className="flex min-w-0 items-start gap-2 text-sm">
                  <Badge variant="outline" className={cn('w-8 shrink-0 justify-center font-bold', GRADE_TONE[g])}>{g}</Badge>
                  <span className="min-w-0 leading-relaxed [overflow-wrap:anywhere]">{t(`tlsg.rubric.${g.replace('+', 'plus')}`)}</span>
                </li>
              ))}
            </ul>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </Card>
  )
}

function ProfileRow({ slot, data = {}, label, value, tone }) {
  return (
    <>
      <dt className="min-w-0 text-muted-foreground">{label}</dt>
      <dd className="m-0 justify-self-end" data-slot={slot} data-tone={tone} {...data}>
        <Badge variant="outline" className={cn('font-semibold', VALUE_TONE[tone] ?? VALUE_TONE.unknown)}>{value}</Badge>
      </dd>
    </>
  )
}

function hstsText(t, h) {
  const s = String(h?.status || '').toUpperCase()
  if (s === 'ENABLED') return h.max_age_days != null ? t('tlsg.sec.hstsOn', h.max_age_days) : t('tlsg.sec.hstsOnNoAge')
  if (s === 'MISSING') return t('tlsg.sec.hstsOff')
  return t('tlsg.sec.hstsNotChecked')
}
