import { ArrowRight, Check, ExternalLink, FolderOpen, Globe, Info, Link2, Mail, Pencil, Server, Terminal, Trash2 } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { CA_FLOW, GUIDE_PLATFORMS, displayUrl, guideHref, linkKind } from './guideSteps.js'
import { cn } from '@/lib/utils'

/**
 * Değişim Rehberi sunum parçaları. 2026-09-27 yeniden düzen: kaynak kartı (tür simgesi, başlık = yeni sekmede aç,
 * açıklama, kısa adres + kopyala, düzenleyene İşlemler menüsü), CA üzerinden alma akışı (kısa adımlar + dokun-gör
 * ipucu), platform sekmeleri (kendi bölümünde), komut bloğu ve Gelişmiş bölümün adım kartı.
 * Kartlarda sol renk şeridi YOK; durum numara dairesi + `data-state` ile. Uyarı/ipucu = ui/AlertBanner (tam çerçeve).
 */

/** Komut bloğu — başlık + kopyala (ad komut başlığını içerir) + yatay kayan `pre`. */
export function CommandBlock({ t, label, code }) {
  return (
    <figure data-slot="command-block" className="min-w-0 overflow-hidden rounded-lg border bg-muted/40">
      <figcaption className="flex min-w-0 items-center justify-between gap-2 border-b bg-muted/60 py-1 pr-1 pl-3">
        <span className="flex min-w-0 items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <Terminal aria-hidden="true" className="size-3.5 shrink-0" /><span className="truncate">{label}</span>
        </span>
        <CopyButton value={code} variant="ghost" buttonSize="icon-sm" className="pointer-coarse:size-10"
          label={t('a11y.rowAction', label, t('guide.copyCommand'))} copiedLabel={t('guide.copied')} />
      </figcaption>
      <pre className="overflow-x-auto px-3 py-2.5 font-mono text-xs leading-relaxed whitespace-pre"><code>{code}</code></pre>
    </figure>
  )
}

function Commands({ t, commands }) {
  if (!commands?.length) return null
  return (
    <div className="flex min-w-0 flex-col gap-2">
      {commands.map((c) => <CommandBlock key={c.key} t={t} label={t(`guide.cmd.${c.key}`)} code={c.code} />)}
    </div>
  )
}

/**
 * Gelişmiş bölümün adım kartı. `n` 1-tabanlı sıra; `done` işaret durumu; `onShowPlatforms` — `seePlatforms`
 * adımında "Platformunuza yükleme" bölümünü açıp oraya kaydırır (sekmeler artık o bölümde).
 */
export function GuideStepCard({ t, step, n, done, onToggle, onShowPlatforms }) {
  const title = t(`guide.step.${step.id}.title`)
  const Icon = step.icon
  const showPlatforms = step.seePlatforms && onShowPlatforms
  return (
    <li id={`guide-step-${step.id}`} data-slot="guide-step" data-step={step.id} data-state={done ? 'done' : 'todo'} className="scroll-mt-4 list-none">
      <Card className={cn('gap-4 py-5 shadow-none', done && 'bg-muted/30')}>
        <div className="flex items-start gap-3 px-4 sm:px-5">
          <span aria-hidden="true" data-slot="guide-step-num"
            className={cn('flex size-9 shrink-0 items-center justify-center rounded-full text-sm font-bold tabular-nums',
              done ? 'bg-success/15 text-success' : 'bg-primary/10 text-primary')}>
            {done ? <Check className="size-4" /> : n}
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-muted-foreground">{t('guide.stepN', n)}</p>
            <h4 className="flex items-center gap-2 text-base leading-snug font-semibold">
              <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
              <span className={cn('min-w-0', done && 'text-muted-foreground')}>{title}</span>
            </h4>
          </div>
          <Label className="min-h-10 shrink-0 cursor-pointer gap-2 rounded-md px-2 font-normal text-muted-foreground hover:bg-muted/60">
            <Checkbox checked={done} onCheckedChange={(v) => onToggle(step.id, v === true)} aria-label={t('guide.markDone', title)} />
            <span className="hidden sm:inline">{t('guide.done')}</span>
          </Label>
        </div>

        <div className="flex min-w-0 flex-col gap-3 px-4 sm:px-5 @2xl:pl-17">
          <p className="max-w-prose text-sm leading-relaxed">{t(`guide.step.${step.id}.body`)}</p>
          <Commands t={t} commands={step.commands} />

          {(step.callouts || []).map((c) => (
            <AlertBanner key={c.key} tone={c.tone} title={t(`guide.step.${step.id}.${c.key}Title`)} className="mb-0 max-w-prose">
              {t(`guide.step.${step.id}.${c.key}Text`)}
            </AlertBanner>
          ))}

          {(showPlatforms || step.related?.length > 0) && (
            <div className="flex flex-wrap gap-2">
              {showPlatforms && (
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={onShowPlatforms}>
                  <Server aria-hidden="true" />{t('guide.platformsTitle')}<ArrowRight aria-hidden="true" />
                </Button>
              )}
              {(step.related || []).map((r) => (
                <Button key={r.tab} type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => navigateTo(r.tab)}>
                  {t(r.labelKey)}<ArrowRight aria-hidden="true" />
                </Button>
              ))}
            </div>
          )}
        </div>
      </Card>
    </li>
  )
}

/**
 * Platform sekmeleri (NetScaler / IIS / Kubernetes-OpenShift / Nginx-Apache) — "Platformunuza yükleme" bölümünde.
 * Sekmede not + komutlar; kaynaklarda bu platforma uyan kategori varsa "NetScaler kaynakları (2)" düğmesi
 * (`onGoCategory(ad)` → Kaynaklar'da o kategori süzülür).
 */
export function PlatformTabs({ t, platform, onPlatform, categories = [], onGoCategory }) {
  return (
    <Tabs value={platform} onValueChange={onPlatform} className="min-w-0">
      {/* Sekmeler sarar (kaydırma değil): telefonda dört platform adı tek satıra sığmaz */}
      <TabsList aria-label={t('guide.platform')} className="h-auto w-fit max-w-full flex-wrap justify-start">
        {GUIDE_PLATFORMS.map((p) => <TabsTrigger key={p.id} value={p.id} className="flex-none px-3 pointer-coarse:min-h-10">{p.label}</TabsTrigger>)}
      </TabsList>
      {GUIDE_PLATFORMS.map((p) => {
        const cats = categories.filter((c) => !c.placeholder && p.match.test(c.name))
        return (
          <TabsContent key={p.id} value={p.id} className="flex min-w-0 flex-col gap-2 pt-1">
            <p className="max-w-prose text-sm text-muted-foreground">{t(`guide.plat.${p.id}.note`)}</p>
            <Commands t={t} commands={p.commands} />
            {cats.length > 0 && onGoCategory && (
              <div className="flex flex-wrap gap-2">
                {cats.map((c) => (
                  <Button key={c.id} type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onGoCategory(c.name)}>
                    <FolderOpen aria-hidden="true" />{t('guide.platformResources', c.name, c.count)}<ArrowRight aria-hidden="true" />
                  </Button>
                ))}
              </div>
            )}
          </TabsContent>
        )
      })}
    </Tabs>
  )
}

/**
 * CA üzerinden satın alma / yenileme — kısa adımlı akış kartı (uzun metin YOK: adım başına tek satır + isteğe bağlı
 * dokun-gör ipucu). Izgara KAP genişliğine bağlı: dar kapta tek sütun, geniş kapta 2–3 sütun (1-2-3 / 4-5-6).
 * `caCount` CA portalı kategorisindeki bağlantı sayısı; `onGoCa` / `onShowPlatforms` sayfa içi geçişler.
 */
export function CaFlowCard({ t, caCount, onGoCa, onShowPlatforms }) {
  const actionsFor = (step) => {
    const cls = 'pointer-coarse:h-10'
    if (step.go === 'ca') {
      return (
        <Button type="button" variant="outline" size="sm" className={cls} onClick={onGoCa}>
          <FolderOpen aria-hidden="true" />{t('guide.flowGoCa', caCount)}
        </Button>
      )
    }
    if (step.go === 'platforms') {
      return (
        <Button type="button" variant="outline" size="sm" className={cls} onClick={onShowPlatforms}>
          <Server aria-hidden="true" />{t('guide.platformsTitle')}
        </Button>
      )
    }
    if (step.go === 'verify') {
      return (
        <>
          <Button type="button" variant="outline" size="sm" className={cls} onClick={() => navigateTo('all')}>
            {t('nav.all')}<ArrowRight aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" size="sm" className={cls} onClick={() => navigateTo('renewal')}>
            {t('nav.renewal')}<ArrowRight aria-hidden="true" />
          </Button>
        </>
      )
    }
    return null
  }
  return (
    <Card data-slot="guide-ca-flow" className="gap-4 py-5 shadow-none">
      <CardHeader className="gap-1 px-4 sm:px-5">
        <h3 id="guide-ca-flow-title" className="text-lg leading-snug font-semibold">{t('guide.flowTitle')}</h3>
        <CardDescription className="max-w-prose">{t('guide.flowIntro')}</CardDescription>
      </CardHeader>
      <CardContent className="px-4 sm:px-5">
        <ol data-slot="guide-flow" className="grid list-none grid-cols-1 gap-3 @2xl:grid-cols-2 @5xl:grid-cols-3">
          {CA_FLOW.map((step, i) => {
            const title = t(`guide.flow.${step.id}.title`)
            const Icon = step.icon
            const actions = actionsFor(step)
            return (
              <li key={step.id} data-slot="guide-flow-step" data-step={step.id} className="flex min-w-0 gap-3 rounded-lg border bg-muted/30 p-3">
                <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary tabular-nums">
                  {i + 1}
                </span>
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <div className="flex min-w-0 items-start gap-1">
                    <h4 className="flex min-w-0 flex-1 items-center gap-1.5 pt-1.5 text-sm leading-snug font-semibold">
                      <Icon aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                      <span className="min-w-0">{title}</span>
                    </h4>
                    {step.hint && (
                      <HintPopover content={t(`guide.flow.${step.id}.hint`)} align="end" aria-label={t('guide.flowMore', title)}
                        triggerClassName="size-8 shrink-0 text-muted-foreground hover:text-foreground pointer-coarse:size-10">
                        <Info aria-hidden="true" className="size-4" />
                      </HintPopover>
                    )}
                  </div>
                  <p className="text-sm leading-snug text-muted-foreground">{t(`guide.flow.${step.id}.text`)}</p>
                  {actions && <div className="mt-1 flex flex-wrap gap-2">{actions}</div>}
                </div>
              </li>
            )
          })}
        </ol>
      </CardContent>
    </Card>
  )
}

const KIND_ICON = { web: Globe, unc: FolderOpen, mail: Mail, other: Link2 }

/**
 * Kaynak (bağlantı) kartı: tür simgesi (web / ağ klasörü / e-posta), başlık = yeni sekmede açan bağlantı, açıklama,
 * altta kısa adres (tek satır, tam adres `title`'da) + kopyala; ağ klasörü için "yolu kopyalayın" ipucu. Düzenleyen
 * kullanıcıda Düzenle/Sil tek İşlemler menüsünde (ui/KebabMenu — adı kaynağı içerir). Bağlantı penceresinin canlı
 * önizlemesi de bu kartı `isAdmin={false}` ile çizer.
 */
export function GuideLinkCard({ t, link, isAdmin, onEdit, onDelete }) {
  const { kind } = linkKind(link.url)
  const KindIcon = KIND_ICON[kind] || Link2
  // Şema beyaz listesi (BD1): `javascript:` / `data:` gibi adreslerde başlık bağlantı DEĞİL, düz metin.
  const href = guideHref(link.url)
  return (
    <Card data-slot="guide-item" data-kind={kind}
      className="h-full gap-3 p-4 shadow-none transition-colors hover:border-primary/50 motion-reduce:transition-none">
      <div className="flex min-w-0 items-start gap-3">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <KindIcon className="size-4" />
        </span>
        <div className="min-w-0 flex-1">
          {href ? (
            <a href={href} target="_blank" rel="noopener noreferrer"
              className="inline-flex max-w-full items-start gap-1.5 leading-snug font-medium text-primary no-underline [overflow-wrap:anywhere] hover:underline">
              <span className="min-w-0">{link.title}</span>
              <ExternalLink aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            </a>
          ) : (
            <span data-slot="guide-link-inert" className="inline-flex max-w-full leading-snug font-medium [overflow-wrap:anywhere]">{link.title}</span>
          )}
          {link.description && <p className="mt-1 text-sm leading-snug text-muted-foreground">{link.description}</p>}
        </div>
        {isAdmin && (
          <KebabMenu label={t('guide.actions')} rowLabel={link.title} items={[
            { label: t('guide.edit'), icon: <Pencil aria-hidden="true" />, onClick: () => onEdit(link) },
            { label: t('guide.delete'), icon: <Trash2 aria-hidden="true" />, danger: true, onClick: () => onDelete(link) },
          ]} />
        )}
      </div>
      <div className="mt-auto flex min-w-0 items-center gap-1 rounded-md border bg-muted/40 py-0.5 pr-0.5 pl-2.5">
        <span data-slot="guide-url" title={link.url} className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">
          {displayUrl(link.url)}
        </span>
        <CopyButton value={link.url} variant="ghost" buttonSize="icon-sm" className="shrink-0 pointer-coarse:size-10"
          label={t('a11y.rowAction', link.title, t('guide.copyUrl'))} copiedLabel={t('guide.copied')} />
      </div>
      {kind === 'unc' && <p className="-mt-1 text-xs text-muted-foreground">{t('guide.uncHint')}</p>}
    </Card>
  )
}
