import { useEffect, useId, useState } from 'react'
import { CircleCheck, CircleSlash, CircleX, HelpCircle } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { reasonTone } from './bulkModel.js'

/**
 * Toplu işlem ÖNİZLEME + SONUÇ penceresi (2026-11-13, yönetim zenginleştirmesi (d)/(h)) — kişiler, bildirim grupları ve
 * takım toplu düzeltmeleri aynı pencereyi kullanır. İki evre:
 *
 *  1. Önizleme: `children` (parametre alanları: seviye / takım / kullanıcı …) + seçili satırların tahmini sonucu —
 *     "değişecek", "atlanacak (neden)", "sunucu denetleyecek" (kapsam / kullanım bilinmiyorsa İDDİA yok). Adlar KESİLMEZ
 *     (sarar). Onay düğmesi geri alınamaz işlemde `destructive`.
 *  2. Sonuç: sunucunun satır satır yanıtı (işlendi / atlandı + neden / başarısız + güvenli ileti). İstek düşerse pencere
 *     açık kalır, hata gösterilir — "0 işlendi" denmez.
 *
 * `onConfirm` → `summarizeResult` biçiminde sonuç; `false` = çağıran alan hatası gösterdi; null/throw = istek hatası. `onFinished(result)` pencere sonuçla
 * kapandığında bir kez (liste yenilemesi). Test kancaları: `data-slot="bulk-dialog|bulk-preview-row|bulk-result-row|
 * bulk-summary"`, `data-state`, `data-reason`, `data-status`.
 */
/** Sonuç grubu etiketi: `failed` evresi "Uygulanamadı" diye okunur (ayrıntı satırın kendi iletisinde). */
const resultLabelKey = (key) => (key === 'failed' ? 'adm.bulk.result.notApplied' : `adm.bulk.result.${key}`)

export default function BulkPreviewDialog({
  open, onClose, title, icon, description, plan, children, confirmLabel, destructive = false,
  confirmDisabled = false, onConfirm, onFinished, teamNameOf = () => null, extraRowInfo = null, keepOrder = false,
}) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)
  const headingId = useId()

  // Her açılışta temiz evre (önceki sonucun / hatanın kalıntısı yok)
  useEffect(() => { if (open) { setBusy(false); setError(null); setResult(null) } }, [open])

  const close = () => {
    if (busy) return
    const r = result
    onClose?.()
    if (r) onFinished?.(r)
  }

  async function confirm() {
    setBusy(true)
    setError(null)
    try {
      const r = await onConfirm()
      // false: çağıran hatayı ALANIN altında gösterdi (ör. seçilen kullanıcı kapsam dışı) — ayrıca şerit yok
      if (r === false) return
      if (!r) setError(t('adm.bulk.requestFailed'))
      else setResult(r)
    } catch (e) {
      setError(e?.message || t('adm.bulk.requestFailed'))
    } finally {
      setBusy(false)
    }
  }

  const rows = plan?.rows ?? []
  // `keepOrder`: satır içinde düzenlenen seçim varken (varsayılan grup seçimi) satır yer değiştirmesin
  const ordered = keepOrder ? rows : [...rows.filter((r) => r.state === 'change'), ...rows.filter((r) => r.state === 'check'),
    ...rows.filter((r) => r.state === 'skip')]
  const willRun = (plan?.change ?? 0) + (plan?.check ?? 0)

  const reasonText = (r) => {
    if (r.reason === 'in_use' && r.usage != null) return Number(r.usage) === 1 ? t('adm.bulk.reason.in_use_one') : t('adm.bulk.reason.in_use_n', r.usage)
    if (r.reason === 'already_exists' && r.existingLevel) return t('adm.bulk.reason.already_exists_level', t(`ec.level.${String(r.existingLevel).toLowerCase()}`))
    return t(`adm.bulk.reason.${r.reason}`)
  }
  const stateBadge = (r) => {
    if (r.state === 'change') return <ToneBadge tone="info" className={BADGE}>{t('adm.bulk.state.change')}</ToneBadge>
    if (r.state === 'check') return <ToneBadge tone="muted" className={BADGE}><HelpCircle aria-hidden="true" />{t('adm.bulk.state.check')}</ToneBadge>
    return <ToneBadge tone={reasonTone(r.reason)} className={BADGE}>{reasonText(r)}</ToneBadge>
  }

  const footer = result ? (
    <Button onClick={close} data-slot="bulk-close">{t('adm.bulk.close')}</Button>
  ) : (
    <>
      <Button variant="secondary" onClick={close} disabled={busy}>{t('adm.bulk.cancel')}</Button>
      <Button variant={destructive ? 'destructive' : 'default'} onClick={confirm} aria-busy={busy || undefined}
        disabled={busy || confirmDisabled || willRun === 0} data-slot="bulk-confirm">
        {busy ? t('adm.bulk.running') : (confirmLabel || t('adm.bulk.apply'))}
      </Button>
    </>
  )

  return (
    <ModalShell open={open} onClose={close} busy={busy} title={title} icon={icon} size="lg" scrollBody
      dismissOnBackdrop={false} footer={footer}>
      <div data-slot="bulk-dialog" data-phase={result ? 'result' : 'preview'} className="flex min-w-0 flex-col gap-3">
        {!result && description && <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{description}</p>}
        {!result && children}
        {error && <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>}

        {!result ? (plan && (
          <section aria-labelledby={headingId} className="flex min-w-0 flex-col gap-2">
            <h4 id={headingId} data-slot="bulk-summary" className="text-sm font-semibold">
              {t('adm.bulk.previewSummary', plan?.change ?? 0, plan?.skip ?? 0)}
              {(plan?.check ?? 0) > 0 && <span className="font-normal text-muted-foreground"> · {t('adm.bulk.previewCheck', plan.check)}</span>}
            </h4>
            {willRun === 0 && rows.length > 0 && (
              <AlertBanner tone="info" className="mb-0">{t('adm.bulk.nothingToDo')}</AlertBanner>
            )}
            <ul className="flex min-w-0 list-none flex-col divide-y rounded-lg border p-0">
              {ordered.map((r) => (
                <li key={`${r.id}`} data-slot="bulk-preview-row" data-state={r.state} data-reason={r.reason || undefined}
                  className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1 px-3 py-2">
                  <span className="flex min-w-0 flex-[1_1_14rem] flex-col">
                    <span className="font-medium [overflow-wrap:anywhere]">{r.name}</span>
                    {teamNameOf(r.teamId) && r.name !== teamNameOf(r.teamId) && (
                      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{teamNameOf(r.teamId)}</span>
                    )}
                    {extraRowInfo?.(r)}
                  </span>
                  {stateBadge(r)}
                </li>
              ))}
            </ul>
          </section>
        )) : (
          <ResultView result={result} t={t} reasonText={reasonText} teamNameOf={teamNameOf} />
        )}
      </div>
    </ModalShell>
  )
}

/** Wrapping rozet: uzun neden metni hapta kırpılmasın (rounded-md + overflow-visible + whitespace-normal). */
const BADGE = 'h-auto max-w-full shrink rounded-md overflow-visible whitespace-normal text-left'

function ResultView({ result, t, reasonText, teamNameOf }) {
  const groups = [
    { key: 'done', Icon: CircleCheck, tone: 'success', rows: result.rows.filter((r) => r.status === 'done') },
    { key: 'failed', Icon: CircleX, tone: 'danger', rows: result.rows.filter((r) => r.status === 'failed') },
    { key: 'skipped', Icon: CircleSlash, tone: 'warning', rows: result.rows.filter((r) => r.status === 'skipped') },
  ]
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div role="status" data-slot="bulk-summary" className="grid grid-cols-3 gap-2">
        {groups.map((g) => (
          <div key={g.key} data-slot="bulk-count" data-status={g.key}
            className={cn('flex min-w-0 flex-col items-start rounded-lg border px-3 py-2',
              g.key === 'failed' && result.failed > 0 && 'border-destructive/40')}>
            <span className="text-xl font-bold tabular-nums">{result[g.key]}</span>
            <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(resultLabelKey(g.key))}</span>
          </div>
        ))}
      </div>
      {groups.filter((g) => g.rows.length > 0).map((g) => (
        <section key={g.key} className="flex min-w-0 flex-col gap-1.5">
          <h4 className="flex items-center gap-1.5 text-sm font-semibold">
            <g.Icon aria-hidden="true" className={cn('size-4', g.tone === 'success' ? 'text-success' : g.tone === 'danger' ? 'text-destructive' : 'text-amber-600 dark:text-amber-400')} />
            {t(resultLabelKey(g.key))} ({g.rows.length})
          </h4>
          <ul className="flex min-w-0 list-none flex-col divide-y rounded-lg border p-0">
            {g.rows.map((r) => (
              <li key={`${g.key}-${r.id}`} data-slot="bulk-result-row" data-status={r.status} data-reason={r.reason}
                className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1 px-3 py-2">
                <span className="flex min-w-0 flex-[1_1_14rem] flex-col">
                  <span className="font-medium [overflow-wrap:anywhere]">{r.name || t('adm.bulk.hiddenRecord')}</span>
                  {(r.teamName || teamNameOf(r.teamId)) && r.name !== (r.teamName || teamNameOf(r.teamId)) && (
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.teamName || teamNameOf(r.teamId)}</span>
                  )}
                  {r.message && <span className="text-xs text-destructive [overflow-wrap:anywhere]">{r.message}</span>}
                </span>
                {r.status !== 'done' && <ToneBadge tone={reasonTone(r.reason)} className={BADGE}>{reasonText(r)}</ToneBadge>}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
