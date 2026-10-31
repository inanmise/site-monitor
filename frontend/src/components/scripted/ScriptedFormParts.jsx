import { useEffect, useId, useState } from 'react'
import { AlertTriangle, BookOpen, ChevronDown, CircleHelp, FileCode2, FlaskConical, Globe, Info, KeyRound, LockKeyhole, Plus } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { MonitorGuideDialog } from '../ui/MonitorGuideButton.jsx'
import { parseK6Markers } from '../../utils/k6Errors.js'
import { envLint } from './scriptedFormModel.js'

/**
 * Sentetik İzleme formunun sunum parçaları (2026-10-31, k6r fe1). Form ve test paneli ScriptedMonitorPage.jsx'te
 * kalır (kaynak okuyan form kapıları — monitorFormWiring / notifyBlockStandard — orayı tarar); burada yalnız yeni,
 * kendi başına sınanabilen parçalar var. Mantık `scriptedFormModel.js`'te (saf).
 */

/** Formun üst düzey bölümü (Genel / Senaryo / Bildirimler): başlık + isteğe bağlı açıklama + içerik. */
export function FormBlock({ title, icon: Icon, description, slot, className, children }) {
  const id = useId()
  return (
    <section data-slot={slot} aria-labelledby={id} className={cn('flex min-w-0 flex-col gap-3', className)}>
      <div className="flex items-center gap-2 border-b pb-1.5">
        {Icon && <Icon size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />}
        <h3 id={id} className="m-0 text-sm font-semibold">{title}</h3>
      </div>
      {description && <p className="m-0 text-xs text-muted-foreground">{description}</p>}
      {children}
    </section>
  )
}

/**
 * Katlanabilir "Zamanlama ve alarm" bölümü. Kapalıyken başlıkta tek satırlık özet (aralık · zaman aşımı · teyit)
 * görünür; içerik DOM'da değildir (Radix). Hatalı alan varsa çağıran `open`'ı açar ve başlıkta hata sayısı yazar.
 * Tetik başlığın (h3) İÇİNDE tam genişlik düğme — WAI düzeni; telefonda ≥ 40 px.
 */
export function TimingBlock({ title, summary, open, onOpenChange, errorCount = 0, invalidText, children }) {
  const contentId = useId()
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} data-slot="sc-timing" data-state={open ? 'open' : 'closed'}
      className="flex min-w-0 flex-col gap-3">
      <h3 className="m-0 border-b pb-1.5">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost" aria-controls={contentId}
            className="h-auto min-h-9 w-full justify-start gap-2 px-1 py-1 text-left whitespace-normal pointer-coarse:min-h-10">
            <ChevronDown aria-hidden="true"
              className={cn('size-4 shrink-0 text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', !open && '-rotate-90')} />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="text-sm font-semibold">{title}</span>
              {!open && summary && (
                <span data-slot="sc-timing-summary" className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">{summary}</span>
              )}
            </span>
            {errorCount > 0 && (
              <Badge variant="destructive" data-slot="sc-timing-invalid"
                className="ml-auto h-auto shrink-0 rounded-md overflow-visible whitespace-normal">{invalidText}</Badge>
            )}
          </Button>
        </CollapsibleTrigger>
      </h3>
      <CollapsibleContent id={contentId} className="flex min-w-0 flex-col gap-3">
        {children}
      </CollapsibleContent>
    </Collapsible>
  )
}

/** Metindeki `kod` parçalarını <code> olarak çizer (i18n metninde ters tırnak — düz metin gibi görünmesin). */
export function InlineCodeText({ text }) {
  const parts = String(text ?? '').split('`')
  return <>{parts.map((p, i) => (i % 2 === 1
    ? <code key={i} className="rounded bg-muted px-1 py-px font-mono text-[.95em]">{p}</code>
    : <span key={i}>{p}</span>))}</>
}

/** Betik ↔ env denetimi (debounce ile): yazarken her tuşta yeniden taranmaz. */
export function useEnvLint(script, env, delay = 400) {
  const [lint, setLint] = useState(() => envLint(script, env))
  useEffect(() => {
    const id = setTimeout(() => setLint(envLint(script, env)), delay)
    return () => clearTimeout(id)
  }, [script, env, delay])
  return lint
}

/**
 * Canlı `__ENV` denetimi çipleri: tanımsız (ekle), kullanılmıyor, gizli işaretli değil (gizli yap). Hiçbiri kayıt ya
 * da testi engellemez — sunucu kayıtta ayrıca uyarır. Bir şey yoksa hiçbir şey çizilmez.
 */
export function EnvLintChips({ t, lint, onAdd, onMakeSecret }) {
  const undef = lint?.undefinedNames || []
  const unused = lint?.unusedNames || []
  const notSecret = lint?.notSecret || []
  if (!undef.length && !unused.length && !notSecret.length) return null
  return (
    <div data-slot="env-lint" className="flex min-w-0 flex-col gap-1.5 rounded-md border border-dashed px-3 py-2">
      <span className="text-xs font-semibold text-muted-foreground">{t('scripted.f.lintTitle')}</span>
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {undef.map((n) => (
          <LintItem key={`u-${n}`} kind="undefined" icon={AlertTriangle} tone="warning" text={t('scripted.f.lintUndefined', n)}
            action={onAdd && <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10"
              aria-label={t('scripted.f.lintAddFor', n)} onClick={() => onAdd(n)}><Plus aria-hidden="true" />{t('scripted.f.lintAdd')}</Button>} />
        ))}
        {notSecret.map((n) => (
          <LintItem key={`s-${n}`} kind="notSecret" icon={KeyRound} tone="warning" text={t('scripted.f.lintNotSecret', n)}
            action={onMakeSecret && <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10"
              aria-label={t('scripted.f.lintMakeSecretFor', n)} onClick={() => onMakeSecret(n)}><LockKeyhole aria-hidden="true" />{t('scripted.f.lintMakeSecret')}</Button>} />
        ))}
        {unused.map((n) => (
          <LintItem key={`x-${n}`} kind="unused" icon={Info} tone="muted" text={t('scripted.f.lintUnused', n)} />
        ))}
      </ul>
    </div>
  )
}

function LintItem({ kind, icon: Icon, tone, text, action }) {
  return (
    <li data-lint={kind} className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
      <span className={cn('inline-flex min-w-0 flex-1 basis-48 items-start gap-1.5 text-xs [overflow-wrap:anywhere]',
        tone === 'warning' ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}>
        <Icon size={14} aria-hidden="true" className="mt-px shrink-0" /><span>{text}</span>
      </span>
      {action}
    </li>
  )
}

/**
 * Env satırının altındaki notlar: şablonun değişken açıklaması, "Hedef: host" önizlemesi, "kayıtlı değerle" rozeti.
 * Hiçbiri yoksa hiçbir şey çizilmez.
 */
export function EnvRowNotes({ t, desc, host, savedValue }) {
  if (!desc && !host && !savedValue) return null
  return (
    <div data-slot="env-row-notes" className="-mt-1 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
      {savedValue && (
        <span className="inline-flex items-center gap-1">
          <Badge variant="secondary" data-slot="env-saved-value" className="h-auto rounded-md overflow-visible whitespace-normal">
            <LockKeyhole aria-hidden="true" />{t('scripted.f.envSaved')}
          </Badge>
          <HintPopover content={t('scripted.f.envSavedHint')} aria-label={t('scripted.f.envSavedHintBtn')}
            triggerClassName="size-6 pointer-coarse:size-10">
            <CircleHelp size={14} aria-hidden="true" className="text-muted-foreground" />
          </HintPopover>
        </span>
      )}
      {host && (
        <span data-slot="env-target" className="inline-flex min-w-0 items-center gap-1 [overflow-wrap:anywhere]">
          <Globe size={13} aria-hidden="true" className="shrink-0" />{t('scripted.f.envTarget', host)}
        </span>
      )}
      {desc && (
        <span data-slot="env-desc" className="inline-flex min-w-0 basis-full items-start gap-1 [overflow-wrap:anywhere]">
          <Info size={13} aria-hidden="true" className="mt-px shrink-0" /><span>{desc}</span>
        </span>
      )}
    </div>
  )
}

/** Değeri boş değişkenler uyarısı (Test / Kaydet sonrası; engellemez). */
export function EmptyEnvWarning({ t, names }) {
  if (!names?.length) return null
  return (
    <AlertBanner tone="warning" className="mb-0" title={t('scripted.f.envEmptyTitle')}>
      <span data-slot="env-empty-warning" className="[overflow-wrap:anywhere]">{t('scripted.f.envEmptyBody', names.join(', '))}</span>
    </AlertBanner>
  )
}

/** Hata metnindeki ilk satır:sütun → "N. satıra git" düğmesi (düzenleyicide satırı seçer). Satır yoksa çizilmez. */
export function GotoLineButton({ t, text, onGoto }) {
  const line = parseK6Markers(text || '')[0]?.line
  if (!line || !onGoto) return null
  return (
    <Button type="button" variant="outline" size="sm" data-slot="goto-line" data-line={line}
      className="mt-1.5 h-8 self-start pointer-coarse:h-10" onClick={() => onGoto(line)}>
      <FileCode2 aria-hidden="true" />{t('scripted.f.gotoLine', line)}
    </Button>
  )
}

/**
 * Boş durum: hiç izleme yokken "Şablondan başla" (yazma izni + k6 varsa) ve "Kılavuz" (herkes). Eskiden yalnız
 * "Yeni Monitör ile ekleyin" yazıyordu — şablonlara ve kılavuza giden yol yoktu.
 */
export function ScriptedEmptyState({ t, canWrite, onStartFromTemplate }) {
  const [guideOpen, setGuideOpen] = useState(false)
  return (
    <div data-slot="sc-empty">
      <StatusBlock icon={FlaskConical}
        title={canWrite ? t('scripted.noMonitorsAdmin') : t('scripted.noMonitors')}
        description={canWrite ? t('scripted.f.emptyBody') : undefined}
        actions={<>
          {canWrite && onStartFromTemplate && (
            <Button type="button" className="pointer-coarse:h-10" onClick={onStartFromTemplate} data-slot="sc-empty-template">
              <FileCode2 aria-hidden="true" />{t('scripted.f.startFromTemplate')}
            </Button>
          )}
          <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => setGuideOpen(true)} data-slot="sc-empty-guide">
            <BookOpen aria-hidden="true" />{t('scripted.f.openGuide')}
          </Button>
        </>} />
      <MonitorGuideDialog type="scripted" open={guideOpen} onClose={() => setGuideOpen(false)} />
    </div>
  )
}
