import { ClipboardCheck, XCircle, Info, Lock, RefreshCw } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { CheckFailurePanel } from '../../checks/CheckFailurePanel.jsx'
import { KeywordFailurePanel } from '../../keyword/KeywordCheckFailure.jsx'
import HttpErrorDetail from '../../http/HttpErrorDetail.jsx'
import RuleResultsList from '../../dbquery/RuleResultsList.jsx'
import { Button } from '@/components/shadcn/button'
import { ApproxBadge, Fact, HealthBadge, SectionCard } from './detailParts.jsx'
import {
  FAILURE_PANEL_TYPES, activityFailed, measuresOf, pseudoCheck, rowHealth, rowReason,
} from './activityDetailModel.js'

/** DB sorgu kural sonuçları — satır dizi ya da JSON metni taşıyabilir. */
function ruleResultsOf(row) {
  const v = row?.rule_results
  if (Array.isArray(v)) return v
  if (typeof v === 'string' && v.trim()) {
    try { const a = JSON.parse(v); return Array.isArray(a) ? a : [] } catch { return [] }
  }
  return []
}

const ACTIVITY_HEALTH = { SUCCESS: 'up', WARNING: 'warn', ERROR: 'down', TIMEOUT: 'down' }

/**
 * KONTROL SONUCU (A-2): aktivitenin kontrolünün TAM sonucu. Hatalıysa türün mevcut teşhis paneli (Neden / Etkisi / Ne
 * yapmalı + kayıttaki ayrıntılar + ham hata) — ortak `CheckFailurePanel`, Keyword `KeywordFailurePanel`, HTTP
 * `HttpErrorDetail`, DB sorgusu kural sonuçları; başarılıysa ana ölçüler. Kontrol satırı bulunamazsa ve aktivite hatalıysa
 * aktivitenin kendi hata alanlarından (yeni kolonlar) aynı panel — "aktivite kaydından" notuyla. Eşleşme yaklaşıksa söylenir.
 *
 * <p>Okunamayan / gizli kontrol satırı (inceleme FR Y1 / CT Y2) "bulunamadı" DENMEZ: sunucu `check_hidden` (görme izni
 * yok ya da izleme artık görülemeyen bir takımda) → nötr "izniniz yok" notu, sahte teşhis paneli YOK; `check_error` →
 * "okunamadı" + Yeniden dene. İkisinde de aktivite kaydının kendi ölçüleri ve hata metni gösterilir.
 * Test kancaları: `act-dt-check` (`data-match` exact|approximate|none|hidden|error, `data-health`), `act-dt-measures`,
 * `act-dt-check-hidden`, `act-dt-check-error`.
 */
export default function CheckResultSection({ detail, type, monitor, onRetry }) {
  const t = useT()
  const a = detail.data
  const hidden = detail.checkHidden === true
  const readError = !hidden && detail.errors?.check === true
  const row = detail.check?.row || null
  // Gizli / okunamayan satırda aktivite alanlarından teşhis KURULMAZ (neden yetki ya da okuma hatası — saklama süresi değil)
  const pseudo = row || hidden || readError ? null : pseudoCheck(type, a)
  const subject = row || pseudo
  const health = row ? rowHealth(type, row) : (activityFailed(a) ? 'down' : ACTIVITY_HEALTH[a.result_status] || 'unknown')
  const match = row ? detail.check.match : hidden ? 'hidden' : readError ? 'error' : 'none'
  const reason = subject && (health === 'down' || health === 'warn') ? rowReason(type, subject, monitor, t) : null
  const measures = row ? measuresOf(type, row, t) : measuresOf(type, { response_ms: a.response_ms, http_status: a.http_status, days_remaining: a.days_remaining }, t)
  const description = match === 'exact' ? t('act.dt.chk.exact')
    : match === 'approximate' ? t('act.dt.chk.approximate')
      : match === 'hidden' ? t('act.dt.chk.hiddenDesc')
        : match === 'error' ? t('act.dt.chk.readErrorDesc')
          : pseudo ? t('act.dt.chk.fromActivity') : t('act.dt.chk.none')

  let panel = null
  if (reason && subject) {
    if (FAILURE_PANEL_TYPES.has(type)) {
      panel = <CheckFailurePanel type={type} check={subject} monitor={monitor} className="border-0 bg-transparent p-0 sm:p-0" />
    } else if (type === 'keyword') {
      panel = <KeywordFailurePanel check={subject} monitor={monitor} />
    } else if (type === 'http') {
      panel = <HttpErrorDetail check={subject} t={t} />
    }
  }
  const results = type === 'dbquery' && subject ? ruleResultsOf(subject) : []
  const rawError = !panel && health === 'down' ? (a.error_message || subject?.error_message || subject?.error || '') : ''

  return (
    <SectionCard slot="act-dt-check" icon={ClipboardCheck} title={t('act.dt.chk.title')} description={description}
      data-match={match} data-health={health}
      action={<>{match === 'approximate' && <ApproxBadge />}<HealthBadge health={health} /></>}>
      {hidden && (
        <div data-slot="act-dt-check-hidden" data-reason={detail.hiddenReason || 'permission'}>
          <AlertBanner tone="info" icon={Lock} className="mb-0">
            {t(detail.hiddenReason === 'team' ? 'act.dt.chk.hiddenTeam' : 'act.dt.chk.hidden')}
          </AlertBanner>
        </div>
      )}
      {readError && (
        <div data-slot="act-dt-check-error">
          <AlertBanner tone="danger" role="alert" className="mb-0"
            actions={onRetry ? (
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={onRetry}>
                <RefreshCw aria-hidden="true" />{t('act.dt.retry')}
              </Button>
            ) : null}>
            {t('act.dt.chk.readError')}
          </AlertBanner>
        </div>
      )}
      {pseudo && (
        <AlertBanner tone="info" icon={Info} className="mb-0">{t('act.dt.chk.pseudoNote')}</AlertBanner>
      )}
      {panel}
      {results.length > 0 && <RuleResultsList results={results} />}
      {rawError && (
        <AlertBanner tone="danger" icon={XCircle} className="mb-0">
          {rawError}{a.error_class ? ` (${a.error_class})` : ''}
        </AlertBanner>
      )}
      {/* Teşhis paneli kayıttaki ölçüleri (HTTP durumu, yanıt süresi, paket kaybı …) zaten listeler — tekrar çizilmez */}
      {!panel && measures.length > 0 ? (
        <dl data-slot="act-dt-measures" className="m-0 grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-[repeat(auto-fit,minmax(9rem,1fr))]">
          {measures.map((m) => (
            <Fact key={m.key} label={t('act.dt.m.' + m.key)}>
              <span data-key={m.key} className={m.mono ? 'font-mono text-xs break-all' : 'tabular-nums'}>{m.value}</span>
            </Fact>
          ))}
        </dl>
      ) : !panel && !rawError && results.length === 0 && (
        <p className="m-0 text-sm text-muted-foreground">{t('act.dt.chk.noMeasures')}</p>
      )}
    </SectionCard>
  )
}
