import { CircleCheck, CircleX, ListChecks, Ruler } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { cn } from '@/lib/utils'
import { AT_LEAST_NOTES, RULE_NOTES, concreteLevel } from './ruleModel.js'

export { RULE_NOTES }
const LEVEL_TONE = { WARNING: 'warning', HIGH: 'danger', CRITICAL: 'danger' }

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Sonuç dizisi geçerli mi (en az bir nesne)? Bozuk / eski sunucu yanıtında null. */
export function ruleResultsOf(raw) {
  if (!Array.isArray(raw)) return null
  const list = raw.filter((r) => r && typeof r === 'object').slice(0, 10)
  return list.length ? list : null
}

/**
 * Kuralların BİRLEŞİK hükmü (son inceleme Y1): `true` (kurallar sağlandı) / `false` (sağlanmadı) / `null` (liste yok —
 * hüküm verilmez; eskiden boş liste "Kurallar sağlandı" sayılıyordu). VE: hepsi sağlanmalı; VEYA: biri yeter.
 */
export function rulesVerdict(list, logic = 'AND') {
  if (!Array.isArray(list) || !list.length) return null
  return logic === 'OR' ? list.some((r) => r?.ok === true) : list.every((r) => r?.ok === true)
}

/**
 * Kural sonuçları listesi (kw paket C) — form testi paneli, ayrıntı "İçerik" sekmesi ve geçmiş satırının hata paneli
 * AYNI bileşeni kullanır. Sunucunun `rule_results` dizisi: `{ id, kind, pattern_preview, count, ok, level, note }`; genel
 * `level` (ihlal seviyesi) ve `size_ok` (boyut kuralı; kapalıysa null). Her satır: durum simgesi, kural adı (etiketi ya da
 * "Kural N"), desen önizlemesi (eş aralıklı, kırpılmaz — sarar), sayım, ihlal seviyesi rozeti, not. Bilinmeyen seviye /
 * not gösterilmez; MONITOR seviyesi izlemenin seviyesine çevrilir.
 *
 * <p>Hüküm rozeti yalnız KURALLAR hakkındadır ve izlemenin genel kararını (`outcome`) yalanlamaz (son inceleme Y1):
 * liste yoksa rozet yok; kurallar tuttu ama kontrol başka bir nedenle (HTTP durumu, son adres, yönlendirme, boyut …)
 * başarısızsa "Kurallar sağlandı (başka neden: …)" — asla "alarm yok" denmez.
 *
 * @param {{ok: boolean|null, reason: string|null}} outcome  genel karar (`keywordFailureModel.ruleOutcomeOf`); null = bilinmiyor
 * @param {'AND'|'OR'} logic  kural birleşimi (VEYA'da bir kuralın tutması yeter)
 *
 * <p>Kancalar: `kw-rule-results` (+ `data-ok`: liste yoksa yok), `kw-rule-results-verdict` (`data-verdict`
 * ok|other|breach|notMet), `kw-rule-result` (`data-ok`, `data-level`), `kw-rule-result-note`, `kw-rule-size` (`data-ok`).
 */
export default function RuleTestResults({
  results, rules = null, level = null, sizeOk = null, monitorLevel = 'WARNING', title = null, className, outcome = null, logic = 'AND',
}) {
  const t = useT()
  const list = ruleResultsOf(results)
  if (!list && sizeOk == null) return null
  const labels = new Map((Array.isArray(rules) ? rules : []).map((r) => [r?.id, r?.label]))
  const rulesOk = rulesVerdict(list, logic)
  const sizeBad = sizeOk === false
  const otherFail = rulesOk === true && (sizeBad || outcome?.ok === false)
  const otherReason = outcome?.reason || (sizeBad ? t('keyword.rule.res.sizeBad') : null)
  const overall = level && level !== 'NONE' ? concreteLevel(level, monitorLevel) : null
  const verdict = rulesOk == null ? null : rulesOk ? (otherFail ? 'other' : 'ok') : overall ? 'breach' : 'notMet'
  return (
    <section data-slot="kw-rule-results" data-ok={verdict == null ? undefined : verdict === 'ok' ? 'true' : 'false'}
      aria-label={title || t('keyword.rule.res.title')} className={cn('flex min-w-0 flex-col gap-2', className)}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{title || t('keyword.rule.res.title')}</span>
        {verdict && (
          <ToneBadge data-slot="kw-rule-results-verdict" data-verdict={verdict}
            tone={verdict === 'ok' ? 'success' : verdict === 'other' ? 'warning' : overall ? LEVEL_TONE[overall] || 'danger' : 'danger'}
            className="h-auto rounded-md overflow-visible whitespace-normal">
            {verdict === 'ok' ? t('keyword.rule.res.allOk')
              : verdict === 'other' ? (otherReason ? t('keyword.rule.res.okOtherReason', otherReason) : t('keyword.rule.res.okOther'))
                : verdict === 'breach' ? t('keyword.rule.res.breach', t(`keyword.rule.level.${overall}`)) : t('keyword.rule.res.notMet')}
          </ToneBadge>
        )}
      </div>
      {list && (
        <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
          {list.map((r, i) => {
            const ok = r.ok === true
            const lv = !ok && r.level && r.level !== 'NONE' ? concreteLevel(r.level, monitorLevel) : null
            const count = num(r.count)
            const note = RULE_NOTES.includes(r.note) ? r.note : null
            // Tarama bütçesi / sayım tavanı: sayı bir ALT SINIR — asla tam sayı gibi gösterilmez ("en az N")
            const atLeast = AT_LEAST_NOTES.includes(note)
            const name = (labels.get(r.id) || '').trim() || t('keyword.rule.cardTitle', i + 1)
            const Icon = ok ? CircleCheck : CircleX
            return (
              <li key={r.id || i} data-slot="kw-rule-result" data-ok={ok ? 'true' : 'false'} data-level={lv || undefined}
                className={cn('flex min-w-0 items-start gap-2 rounded-md border px-2.5 py-2',
                  ok ? 'border-success/25 bg-success/5' : 'border-destructive/30 bg-destructive/5')}>
                <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', ok ? 'text-success' : 'text-destructive')} />
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="text-sm font-semibold [overflow-wrap:anywhere]">{name}</span>
                    <span className="sr-only">{ok ? t('keyword.rule.res.ok') : t('keyword.rule.res.fail')}</span>
                    {count != null && (
                      <span data-slot="kw-rule-result-count" data-at-least={atLeast ? 'true' : undefined} className="text-xs text-muted-foreground tabular-nums">
                        {atLeast ? t('keyword.rule.res.countAtLeast', count) : count === 1 ? t('keyword.rule.res.count1') : t('keyword.rule.res.count', count)}
                      </span>
                    )}
                    {lv && <ToneBadge tone={LEVEL_TONE[lv] || 'danger'} className="h-auto rounded-md overflow-visible whitespace-normal">{t(`keyword.rule.level.${lv}`)}</ToneBadge>}
                  </div>
                  {r.pattern_preview && (
                    <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {r.kind === 'REGEX' ? `/${r.pattern_preview}/` : `« ${r.pattern_preview} »`}
                    </span>
                  )}
                  {note && <span data-slot="kw-rule-result-note" data-note={note} className="text-xs text-amber-700 dark:text-amber-300">{t(`keyword.rule.note.${note}`)}</span>}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      {sizeOk != null && (
        <p data-slot="kw-rule-size" data-ok={sizeOk ? 'true' : 'false'}
          className={cn('m-0 flex items-center gap-1.5 text-xs', sizeOk ? 'text-success' : 'text-destructive')}>
          <Ruler aria-hidden="true" className="size-3.5 shrink-0" />{sizeOk ? t('keyword.rule.res.sizeOk') : t('keyword.rule.res.sizeBad')}
        </p>
      )}
    </section>
  )
}
