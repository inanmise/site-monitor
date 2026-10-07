import { ChevronDown, CircleHelp, Zap, Wrench, ListTree, Bug, Stethoscope, Info } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { CodeBlock, Kv, KvList, SectionTitle } from '../http/diagnose/HttpDiagnoseParts.jsx'
import { detailRows, failureTexts, technicalText } from './checkFailureModel.js'

/**
 * Kontrol geçmişi HATA TEŞHİSİ — Ping, Port, DNS, Sayfa Bütünlüğü, Sayfa Hızı, Durum, Alan Adı ve Sertifika geçmişinin
 * ORTAK parçaları (2026-10-05, kullanıcı isteği: "hata alındığında detaylıca ne hatası aldığını görelim"). Keyword'ün
 * `KeywordCheckFailure` deseni:
 *
 * <ul>
 *   <li>{@link CheckFailureCell} — başarısız satırın kısa özeti: neden rozeti + tek satır açıklama + aç/kapa düğmesi
 *       (dokunmatikte 40 px). Satır KAPALIYKEN görünen budur.</li>
 *   <li>{@link CheckFailurePanel} — açılınca satırın ALTINDA tam genişlik: Neden / Etkisi / Ne yapmalı, kayıttaki
 *       ayrıntılar (evre, hedef, yol, çözümlenen IP, HTTP durumu, rcode, paket kaybı, zaman aşımı …), ham hata metni
 *       (kopyalanabilir) ve — verildiyse — "Bu kontrolü tanıla" eylemi (`canDiagnose` + `onDiagnose`; ikinci aşama).</li>
 * </ul>
 *
 * Eski satırlar (kod kolonu NULL) zarifçe çizilir: en yakın neden + "ayrıntı kaydedilmemiş" notu. shadcn + Tailwind; sol
 * renk şeridi YOK (durum rozet + `data-code`). Telefonda her şey alt alta; uzun host/hata metni sarar, yatay taşma yok.
 * Test kancaları: `data-slot="chkfail-cell|chkfail-badge|chkfail-toggle|chkfail-panel|chkfail-legacy|chkfail-why|
 * chkfail-effect|chkfail-fix|chkfail-details|chkfail-technical|chkfail-diagnose"`, `data-code`, `data-type`.
 */

/** Yalnız rozet (dar hücreler — sertifika ayrıntı hücresi). Sağlıklı satırda hiçbir şey çizilmez. */
export function CheckFailureBadge({ type, check, monitor, className }) {
  const t = useT()
  const f = failureTexts(type, check, monitor, t)
  if (!f) return null
  return (
    <ToneBadge tone={f.tone} data-slot="chkfail-badge" data-code={f.code} data-type={type}
      className={cn('max-w-full font-semibold whitespace-normal', className)}>
      {f.short}
    </ToneBadge>
  )
}

/**
 * Başarısız satırın özet hücresi: rozet + tek satır neden + aç/kapa. Sağlıklı satırda null.
 *
 * @param {string}   type     ping | port | dns | page | pagespeed | uptime | domain | cert
 * @param {object}   check    geçmiş satırı (snake_case, sunucu varlığı)
 * @param {object}   monitor  izleme satırı (hedef / zaman aşımı / beklenen için)
 * @param {boolean}  open     panel açık mı
 * @param {Function} onToggle aç/kapa
 * @param {string}   when     satırın okunur zamanı (düğmenin erişilebilir adında — 50 aynı adlı düğme olmasın)
 * @param {string}   panelId  açık paneli bağlamak için (aria-controls)
 */
export function CheckFailureCell({ type, check, monitor, open = false, onToggle, when, panelId, className }) {
  const t = useT()
  const f = failureTexts(type, check, monitor, t)
  if (!f) return null
  const label = open ? t('chkhist.hideDetails') : t('chkhist.showDetails')
  return (
    <div data-slot="chkfail-cell" data-code={f.code} data-type={type} data-legacy={f.legacy ? 'true' : undefined}
      className={cn('flex min-w-0 flex-col items-start gap-1', className)}>
      <ToneBadge tone={f.tone} data-slot="chkfail-badge" data-code={f.code} className="max-w-full font-semibold whitespace-normal">
        {f.short}
      </ToneBadge>
      <span data-slot="chkfail-oneline" className="line-clamp-2 min-w-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{f.why}</span>
      {onToggle && (
        <Button type="button" variant="ghost" size="xs" data-slot="chkfail-toggle" aria-expanded={open ? 'true' : 'false'}
          aria-controls={open && panelId ? panelId : undefined}
          onClick={onToggle} aria-label={t('chkhist.toggleAria', when || '', f.short, label)}
          className="h-auto gap-1 px-1.5 py-0.5 text-xs font-semibold text-primary hover:text-primary pointer-coarse:min-h-10">
          {label}
          <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
        </Button>
      )}
    </div>
  )
}

/** Neden / Etkisi / Ne yapmalı kutusu. */
function CauseBlock({ icon: Icon, title, slot, children }) {
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1 rounded-md border bg-muted/30 px-3 py-2">
      <span className="flex items-center gap-1.5 text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />{title}
      </span>
      <p className="m-0 min-w-0 text-[13px] leading-snug [overflow-wrap:anywhere]">{children}</p>
    </div>
  )
}

const KV_TONE = { bad: 'text-destructive', warn: 'text-amber-700 dark:text-amber-300' }

/**
 * Açılan teşhis paneli (satırın altında tam genişlik). Sağlıklı satırda null.
 *
 * @param {boolean}  showRaw     ham hata metni bloğu çizilsin mi (sertifika ayrıntısı hatayı zaten kendi bandında gösterir)
 * @param {boolean}  canDiagnose uçtan uca tanılama yetkisi (ikinci aşama) — false/verilmemişse düğme HİÇ çizilmez
 * @param {Function} onDiagnose  tanılama penceresini aç
 */
export function CheckFailurePanel({ type, check, monitor, id, showRaw = true, canDiagnose = false, onDiagnose, className }) {
  const t = useT()
  const f = failureTexts(type, check, monitor, t)
  if (!f) return null
  const rows = detailRows(type, check, monitor, t, f)
  const technical = showRaw ? technicalText(type, check, f) : ''
  return (
    <section id={id} data-slot="chkfail-panel" data-code={f.code} data-type={type} aria-label={t('chkhist.panelAria', f.short)}
      className={cn('flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 text-xs sm:p-4', className)}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <ToneBadge tone={f.tone} className="max-w-full font-semibold whitespace-normal">{f.short}</ToneBadge>
        <h4 className="m-0 min-w-0 text-[13px] font-semibold">{t('chkhist.panelTitle')}</h4>
      </div>

      {f.legacy && (
        <div data-slot="chkfail-legacy">
          <AlertBanner tone="info" icon={Info} className="mb-0">{t(f.noText ? 'chkhist.legacyNoText' : 'chkhist.legacy')}</AlertBanner>
        </div>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-3">
        <CauseBlock icon={CircleHelp} title={t('chkhist.why')} slot="chkfail-why">{f.why}</CauseBlock>
        <CauseBlock icon={Zap} title={t('chkhist.effect')} slot="chkfail-effect">{f.effect}</CauseBlock>
        <CauseBlock icon={Wrench} title={t('chkhist.fix')} slot="chkfail-fix">{f.fix}</CauseBlock>
      </div>

      {rows.length > 0 && (
        <div data-slot="chkfail-details" className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={ListTree}>{t('chkhist.detailsTitle')}</SectionTitle>
          <KvList>
            {rows.map((r) => (
              <Kv key={r.key} label={t(`chkhist.kv.${r.key}`)} mono={!!r.mono}>
                <span data-key={r.key} className={cn('min-w-0', KV_TONE[r.tone])}>{r.value}</span>
              </Kv>
            ))}
          </KvList>
        </div>
      )}

      {technical && (
        <div data-slot="chkfail-technical" className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={Bug}>{t('chkhist.technical')}</SectionTitle>
          <CodeBlock maxH="max-h-40" copy={technical} copyLabel={t('chkhist.copyTechnical')}>{technical}</CodeBlock>
        </div>
      )}

      {canDiagnose && onDiagnose && (
        <div className="flex min-w-0 flex-col gap-1.5 border-t pt-3 sm:flex-row sm:items-center sm:justify-between">
          <span className="min-w-0 text-xs text-muted-foreground">{t('chkhist.diagnoseHint')}</span>
          <Button type="button" size="sm" data-slot="chkfail-diagnose" onClick={onDiagnose} className="shrink-0 pointer-coarse:h-10">
            <Stethoscope aria-hidden="true" /> {t('chkhist.diagnose')}
          </Button>
        </div>
      )}
    </section>
  )
}

/**
 * Hücre + (açıksa) panel tek blokta — kendi sütunu olmayan geçmişlerde (Alan Adı) satırın altındaki tam genişlik ek
 * satırda kullanılır: kapalıyken özet, açıkken özet + panel.
 */
export function CheckFailureBlock({ type, check, monitor, open = false, onToggle, when, panelId, canDiagnose, onDiagnose }) {
  return (
    <div data-slot="chkfail-block" className="flex min-w-0 flex-col gap-2">
      <CheckFailureCell type={type} check={check} monitor={monitor} open={open} onToggle={onToggle} when={when} panelId={panelId} />
      {open && (
        <CheckFailurePanel type={type} check={check} monitor={monitor} id={panelId}
          canDiagnose={canDiagnose} onDiagnose={onDiagnose} />
      )}
    </div>
  )
}

export default CheckFailurePanel
