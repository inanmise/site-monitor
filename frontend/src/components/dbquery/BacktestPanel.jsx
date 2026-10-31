import { useMemo, useRef, useState } from 'react'
import { History, PlayCircle, RotateCw } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { RULE_KINDS, validateRules } from './ruleModel.js'
import { countText, istanbulTime, rateLimitText } from './dbqueryPageModel.js'

/** Geriye bakış seçenekleri (sunucu yalnız bunları kabul eder). */
export const BACKTEST_DAYS = Object.freeze([7, 30, 90])

/** Sayı → dilin biçimi (gruplu). */
const fmt = (n, lang) => (typeof n === 'number' && Number.isFinite(n)
  ? new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(n) : '—')

/** Atlama nedeni → metin (bilinen kod çevrilir, bilinmeyen genel metne düşer — ham kod basılmaz). */
function skipText(t, code) {
  if (!code) return null
  const key = `dbq.bt.skip.${code}`
  const s = t(key)
  return s === key ? t('dbq.bt.skip.other') : s
}

/**
 * Geriye dönük eşik denemesi (Veritabanı Sorgu İzleme, 2026-10-31 — 2.D). "Bu eşikler son 7 / 30 / 90 günde kaç alarm
 * açardı?" — FORMDAKİ (kaydedilmemiş) kurallar, sunucunun SAKLADIĞI zamanlanmış ölçümlerle yeniden değerlendirilir
 * (`POST /api/monitoring/dbquery/{id}/backtest`). Veritabanına sorgu GİTMEZ, satır verisi DÖNMEZ; saatlik deneme sayacından
 * düşmez. Yalnız kayıtlı izlemede (kimlik gerekir) ve izlemeyi düzenleyebilen kişide çizilir.
 *
 * <p>Durumlar: boş (açıklama) · yükleniyor · sonuç (çalıştırma / değerlendirilen / ihlal / açılacak alarm / ilk–son ihlal,
 * kural başına) · hata · bekleme (429, bilgi tonu) · desteklenmiyor (404 / 405 — eski sunucu). Kurallar sonuçtan sonra
 * değişirse sonuç "eski" işaretlenir (yeniden dene). Kural hatası varken istek gitmez.
 *
 * <p>Test kancaları: kök `dbq-backtest` (+ `data-state`), çalıştır `dbq-backtest-run`, gün seçimi `dbq-backtest-days`,
 * özet `dbq-backtest-summary`, kural satırları `dbq-backtest-rule`, eski `dbq-backtest-stale`.
 */
export default function BacktestPanel({ monitorId, rules, ruleLogic, disabled = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const [days, setDays] = useState(30)
  const [state, setState] = useState({ name: 'idle' })
  const [busy, setBusy] = useState(false)
  const seq = useRef(0)
  const list = useMemo(() => (Array.isArray(rules) ? rules : []), [rules])
  const fingerprint = JSON.stringify({ list, ruleLogic })
  const ruleErrors = Object.keys(validateRules(list)).length > 0
  const ruleName = (id, i) => {
    const r = list.find((x) => x?.id === id)
    if (r?.label?.trim()) return r.label.trim()
    if (r && RULE_KINDS.includes(r.kind)) return t(`dbq.rule.kindName.${r.kind}`)
    return t('dbq.rule.cardTitle', i + 1)
  }

  const run = async () => {
    if (busy || disabled || monitorId == null) return
    if (!list.length) { setState({ name: 'noRules' }); return }
    if (ruleErrors) { setState({ name: 'invalid' }); return }
    const my = ++seq.current
    setBusy(true)
    try {
      const res = await api.monitoring.dbQueryBacktest(monitorId, { rules: list, rule_logic: ruleLogic === 'ALL' ? 'ALL' : 'ANY', days })
      if (my !== seq.current) return
      if (res?.success && res.data && typeof res.data === 'object') setState({ name: 'ok', data: res.data, days, fingerprint })
      else if (res?.status === 404 || res?.status === 405) setState({ name: 'unsupported' })
      else if (res?.code === 'RATE_LIMITED' || res?.status === 429) setState({ name: 'wait', message: rateLimitText(res, t, 'dbq.bt.failed') })
      else setState({ name: 'error', message: res?.error || res?.message || t('dbq.bt.failed') })
    } catch (e) {
      if (my === seq.current) setState({ name: 'error', message: e?.message || t('dbq.bt.failed') })
    } finally {
      if (my === seq.current) setBusy(false)
    }
  }

  const stale = state.name === 'ok' && state.fingerprint !== fingerprint
  const d = state.name === 'ok' ? state.data : null
  const wb = d?.would_breach || {}
  const perRule = Array.isArray(d?.per_rule) ? d.per_rule : []

  return (
    <section data-slot="dbq-backtest" data-state={state.name} aria-live="polite"
      className="flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/20 px-3.5 py-3 sm:col-span-2">
      <div className="flex min-w-0 flex-col gap-0.5">
        <h4 className="m-0 inline-flex items-center gap-1.5 text-sm font-semibold"><History aria-hidden="true" className="size-4" />{t('dbq.bt.title')}</h4>
        <p className="m-0 text-xs text-muted-foreground">{t('dbq.bt.hint')}</p>
      </div>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <div data-slot="dbq-backtest-days">
          <SegmentedControl value={days} onChange={(v) => setDays(Number(v))} ariaLabel={t('dbq.bt.daysLabel')}
            options={BACKTEST_DAYS.map((n) => ({ value: n, label: t('dbq.bt.days', n), disabled: busy || disabled }))} />
        </div>
        <Button type="button" variant="secondary" onClick={run} disabled={busy || disabled || monitorId == null}
          aria-busy={busy || undefined} className="pointer-coarse:h-10" data-slot="dbq-backtest-run">
          {busy ? <Spinner size={14} inline decorative /> : <PlayCircle aria-hidden="true" />}
          {busy ? t('dbq.bt.running') : t('dbq.bt.run')}
        </Button>
      </div>

      {state.name === 'noRules' && <p className="m-0 text-xs text-muted-foreground">{t('dbq.bt.noRules')}</p>}
      {state.name === 'invalid' && <p className="m-0 text-xs text-destructive">{t('dbq.bt.invalid')}</p>}
      {state.name === 'unsupported' && <AlertBanner tone="info" className="mb-0">{t('dbq.bt.unsupported')}</AlertBanner>}
      {state.name === 'wait' && <AlertBanner tone="info" className="mb-0">{state.message}</AlertBanner>}
      {state.name === 'error' && <AlertBanner tone="danger" title={t('dbq.bt.failedTitle')} className="mb-0">{state.message}</AlertBanner>}

      {d && (
        <div data-slot="dbq-backtest-result" className={cn('flex min-w-0 flex-col gap-2', stale && 'opacity-70')}>
          {stale && (
            <p data-slot="dbq-backtest-stale" className="m-0 inline-flex flex-wrap items-center gap-2 text-xs text-amber-700 dark:text-amber-300">
              {t('dbq.bt.stale')}
              <Button type="button" variant="outline" size="sm" onClick={run} disabled={busy} className="h-7 pointer-coarse:h-10">
                <RotateCw aria-hidden="true" />{t('dbq.bt.rerun')}
              </Button>
            </p>
          )}
          {Number(d.runs) > 0 ? (
            <p data-slot="dbq-backtest-summary" className="m-0 text-sm [overflow-wrap:anywhere]">
              {t('dbq.bt.summary', state.days, fmt(d.runs, lang), fmt(d.evaluated, lang), fmt(wb.warning ?? 0, lang), fmt(wb.critical ?? 0, lang))}
              {' '}
              <strong data-slot="dbq-backtest-alarms">{countText(t, 'dbq.bt.alarms', Number(d.would_open_alarms) || 0, fmt(Number(d.would_open_alarms) || 0, lang))}</strong>
            </p>
          ) : (
            <p data-slot="dbq-backtest-summary" className="m-0 text-sm text-muted-foreground">{t('dbq.bt.noRuns', state.days)}</p>
          )}
          {(d.first_at || d.last_at) && (
            <p className="m-0 text-xs text-muted-foreground tabular-nums">
              {t('dbq.bt.range', istanbulTime(d.first_at) || '—', istanbulTime(d.last_at) || '—')}
            </p>
          )}
          {perRule.length > 0 && (
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {perRule.map((r, i) => (
                <li key={r?.id || i} data-slot="dbq-backtest-rule" data-id={r?.id}
                  className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 rounded-md border bg-background px-2.5 py-1.5 text-xs">
                  <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{ruleName(r?.id, i)}</span>
                  {r?.skipped_reason ? (
                    <span className="text-muted-foreground [overflow-wrap:anywhere]">{skipText(t, r.skipped_reason)}</span>
                  ) : (
                    <>
                      <span className="text-muted-foreground tabular-nums">{t('dbq.bt.evaluated', fmt(r?.evaluated, lang))}</span>
                      <Badge variant="warning" className="px-1.5 py-0 text-[10.5px] tabular-nums">{t('dbq.bt.warn', fmt(r?.warning ?? 0, lang))}</Badge>
                      <Badge variant="destructive" className="px-1.5 py-0 text-[10.5px] tabular-nums">{t('dbq.bt.crit', fmt(r?.critical ?? 0, lang))}</Badge>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="m-0 text-xs text-muted-foreground">{t('dbq.bt.note')}</p>
        </div>
      )}
    </section>
  )
}
