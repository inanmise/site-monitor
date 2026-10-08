import { useEffect, useId, useState } from 'react'
import { CheckCircle2, Circle, Clock, X, XCircle } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { sizeLabel } from '../manualCertModel.js'
import { canCancel, stageLabelKey, stageMeter } from './uploadProgressModel.js'

/** Geçen süre bu eşikten sonra gösterilir (kısa aşamalarda "0 sn" gürültüsü olmasın). */
const ELAPSED_FROM_S = 2
/** Bu süreden uzun süren aşamada "beklenenden uzun sürüyor" notu. */
const SLOW_FROM_S = 15

const STATE_ICON = {
  pending: <Circle aria-hidden="true" className="size-4 shrink-0 text-muted-foreground/60" />,
  done: <CheckCircle2 aria-hidden="true" className="size-4 shrink-0 text-success" />,
  error: <XCircle aria-hidden="true" className="size-4 shrink-0 text-destructive" />,
}

/** Etkin aşamanın ayrıntı satırı (ne yapılıyor / kaç dosya / kaç bayt) — yalnız sayaç ve biçim; içerik yok. */
function detailText(t, run) {
  const stage = run.active
  if (stage === 'read') return t('mcert.prog.detail.read')
  if (stage === 'extract') {
    const zip = run.extract?.zip
    if (zip?.total > 0) return t('mcert.prog.detail.zip', zip.done, zip.total)
    if (run.extract?.pkcs12 && run.extract.pkcs12 !== 'done') return t('mcert.prog.detail.pkcs12')
    if (run.extract?.keystore) return t('mcert.prog.detail.keystore', run.extract.keystore)
    return t('mcert.prog.detail.extract')
  }
  if (stage === 'upload') {
    const u = run.upload
    if (u?.total > 0) return t('mcert.prog.detail.upload', sizeLabel(u.loaded), sizeLabel(u.total))
    if (u?.loaded > 0) return t('mcert.prog.detail.uploadUnknown', sizeLabel(u.loaded))
    return t('mcert.prog.detail.uploadStart')
  }
  if (stage === 'analyze') return t('mcert.prog.detail.analyze')
  return run.saveKind === 'renewed' ? t('mcert.prog.detail.saveVersion') : t('mcert.prog.detail.save')
}

/**
 * YÜKLEME DURUMU PANELİ (2026-10-08, kullanıcı isteği). Koşunun TÜM aşamaları baştan listelenir (satır sayısı koşu boyunca
 * değişmez — yerleşim zıplamaz); her satır ikon + ad + durum (Bekliyor / Sürüyor · N sn / Tamam / Hata). Altında etkin
 * aşamanın çubuğu: yüzde biliniyorsa BELİRLİ (`ProgressBar` value/max — yükleme baytı, ZIP girdi sayısı), değilse
 * belirsiz; ve sabit yükseklikli ayrıntı satırı (PKCS#12 notu, "n / N dosya", "12 KB / 40 KB"). Kayıt sunucuya
 * ulaştıysa "bu aşamada iptal edilemez" notu. Başarısız koşuda hangi aşamada durduğu kalır (kapatılabilir); nedeni ve
 * yapılacak şey hata iletisindedir (alanın altında ya da bantta).
 *
 * <p>Saniye sayacı YALNIZ bu bileşende (sihirbaz her saniye yeniden çizilmez). Ekran okuyucu duyurusu sihirbazdaki tek
 * `aria-live` satırındadır (`mcert-progress-live`) — burada ikinci bir canlı bölge yok.
 *
 * <p>Test kancaları: `data-slot="mcert-progress"` + `data-kind` + `data-status`, satır `mcert-progress-stage` +
 * `data-stage` + `data-state`, ayrıntı `mcert-progress-detail`, kapatma `mcert-progress-dismiss`.
 */
export default function UploadProgress({ run, onDismiss, className }) {
  const t = useT()
  const titleId = useId()
  const running = run?.status === 'running'
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!running) return undefined
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running])
  if (!run) return null

  const label = (stage) => { const [k, ...a] = stageLabelKey(run, stage); return t(k, ...a) }
  const elapsedOf = (stage) => {
    const at = run.startedAt?.[stage]
    return at ? Math.max(0, Math.floor((now - at) / 1000)) : 0
  }
  const activeElapsed = running ? elapsedOf(run.active) : 0
  const meter = stageMeter(run)
  const failed = run.status === 'failed'
  const title = failed ? t('mcert.prog.titleFailed') : run.kind === 'save' ? t('mcert.prog.titleSave') : t('mcert.prog.titleAnalyze')

  return (
    <section data-slot="mcert-progress" data-kind={run.kind} data-status={run.status} aria-labelledby={titleId}
      className={cn('mb-3 flex min-w-0 flex-col gap-2.5 rounded-lg border p-3 sm:p-4',
        failed ? 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10' : 'border-primary/25 bg-primary/5 dark:bg-primary/10', className)}>
      <div className="flex min-w-0 items-center gap-2">
        {running ? <Spinner size={16} decorative className="text-primary" /> : <XCircle aria-hidden="true" className="size-4 shrink-0 text-destructive" />}
        <h3 id={titleId} className="m-0 min-w-0 flex-1 text-sm font-semibold">{title}</h3>
        {failed && onDismiss && (
          <Button type="button" variant="ghost" size="icon" data-slot="mcert-progress-dismiss" className="-my-1 shrink-0 text-muted-foreground"
            onClick={onDismiss} aria-label={t('mcert.prog.dismiss')}>
            <X aria-hidden="true" />
          </Button>
        )}
      </div>

      <ol aria-label={t('mcert.prog.stages')} className="m-0 flex list-none flex-col gap-1 p-0">
        {run.stages.map((stage) => {
          const state = run.state[stage] || 'pending'
          const secs = state === 'active' ? activeElapsed : 0
          return (
            <li key={stage} data-slot="mcert-progress-stage" data-stage={stage} data-state={state}
              className={cn('flex min-h-7 min-w-0 items-center gap-2 text-[13px]',
                state === 'active' ? 'font-semibold text-foreground' : state === 'error' ? 'text-destructive' : 'text-muted-foreground')}>
              {state === 'active' ? <Spinner size={16} decorative className="text-primary" /> : STATE_ICON[state]}
              <span className="min-w-0 flex-1 truncate">{label(stage)}</span>
              <span className="shrink-0 text-xs font-normal tabular-nums">
                {t(`mcert.prog.state.${state}`)}
                {secs >= ELAPSED_FROM_S && <span data-slot="mcert-progress-elapsed"> · {t('mcert.prog.elapsed', secs)}</span>}
              </span>
            </li>
          )
        })}
      </ol>

      {running && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <ProgressBar size="sm" label={label(run.active)} showValue={!!meter}
            value={meter ? meter.value : undefined} max={meter ? meter.max : 100} />
          {/* Sabit yükseklik (iki satır): ayrıntı metni değişince altındaki düğmeler zıplamasın */}
          <p data-slot="mcert-progress-detail" className="m-0 min-h-[2.5rem] text-xs leading-snug text-muted-foreground [overflow-wrap:anywhere]">
            {detailText(t, run)}
            {activeElapsed >= SLOW_FROM_S && (
              <span data-slot="mcert-progress-slow" className="block text-amber-700 dark:text-amber-300">
                <Clock aria-hidden="true" className="mr-1 inline size-3.5 align-[-0.15em]" />
                {run.kind === 'save' ? t('mcert.prog.slowSave') : t('mcert.prog.slowAnalyze')}
              </span>
            )}
          </p>
          {/* İptal notu her an aynı yerde (metni değişir, satır eklenip çıkmaz): analiz her an durur; kayıt yalnız gövde
              giderken — istek sunucuya ulaşınca durdurmak "kaydedilmedi" diyemez. */}
          <p data-slot="mcert-progress-cancel-note" data-cancellable={canCancel(run) ? 'true' : 'false'}
            className="m-0 text-xs text-muted-foreground">
            {!canCancel(run) ? t('mcert.prog.noCancel') : run.kind === 'save' ? t('mcert.prog.cancelSaveHint') : t('mcert.prog.cancelAnalyzeHint')}
          </p>
        </div>
      )}
      {failed && run.failedStage && (
        <p data-slot="mcert-progress-detail" className="m-0 text-xs text-muted-foreground">{t('mcert.prog.failedAt', label(run.failedStage))}</p>
      )}
    </section>
  )
}
