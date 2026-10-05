import { Fragment, useId } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { CircleCheck } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { formatDate } from '../../api/client'
import { toUtc } from '../../utils/localDay.js'
import { relativeTime } from '../admin/audit/auditFormat.js'
import CopyButton from '../ui/CopyButton.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Item, ItemContent, ItemMedia } from '@/components/shadcn/item'
import { Avatar, AvatarFallback } from '@/components/shadcn/avatar'
import { cn } from '@/lib/utils'
import { FLAG_ICON, contactInitials } from './InventoryTable.jsx'
import { filledContactFields, safeHttpUrl, splitEmail, splitFlags, splitLinks } from './inventoryDetailModel.js'

/**
 * Envanter kaydı detayının yapı taşları (2026-09-28 yeniden tasarım — shadcn, mobil duyarlı). Kardeş yüzeyle
 * (Alan Adı penceresi `DomainRegistrationPanel`) aynı dağarcık: bölüm = shadcn Card + ikonlu h3; değerler `dl`
 * (telefonda tek, kap ≥ 28rem iki sütun — `@container`); teknik değer mono ve kırpılmadan sarılır; boş değer soluk "—".
 * SOL RENK ŞERİDİ YOK. Çekmecenin "Sertifika" sekmesi de `DetailSection`/`Fact`/`FactGrid`'i kullanır.
 */

/** Ton → çip sınıfı (sertifika kartının çip ailesiyle aynı değerler; yarı saydam kenar+zemin — palet gamı globals.css'te). */
export const TONE = {
  ok: 'border-success/30 bg-success/10 text-success dark:bg-success/20',
  warn: 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  bad: 'border-destructive/30 bg-destructive/10 text-destructive dark:bg-destructive/20',
  info: 'border-primary/25 bg-primary/10 text-primary dark:bg-primary/20',
  muted: 'border-border bg-muted text-muted-foreground',
}
/** Çip: uzun etiket telefonda sarar (whitespace-normal), en az 24 px. */
export const CHIP = "h-auto min-h-6 min-w-0 max-w-full gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold whitespace-normal shadow-none [&_svg:not([class*='size-'])]:size-3"

/** Kart içi ikinci düzey başlık. */
export function SubHead({ children, className }) {
  return <h4 className={cn('m-0 mb-1.5 text-[11px] font-semibold tracking-[.06em] text-muted-foreground uppercase', className)}>{children}</h4>
}

/**
 * Bölüm kartı: shadcn Card + ikonlu h3 (+ sağda isteğe bağlı `meta`, ör. sayaç rozeti). `role="region"` + başlığa bağlı
 * ad → ekran okuyucu bölümler arasında gezinir. Test kancası `data-slot="inv-section"` + `data-section`.
 * Geriye uyum: yalnız `title` + `children` ile de çağrılır (çekmecenin Sertifika sekmesi).
 */
export function DetailSection({ id, icon: Icon, title, meta, className, contentClassName, children }) {
  const hid = useId()
  return (
    <Card data-slot="inv-section" data-section={id} role="region" aria-labelledby={hid}
      className={cn('min-w-0 gap-3 py-4 shadow-none', className)}>
      <CardHeader className="flex min-w-0 flex-wrap items-center justify-between gap-2 px-4">
        <h3 id={hid} className="m-0 flex min-w-0 items-center gap-2 text-sm leading-tight font-semibold">
          {Icon && <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
          <span className="min-w-0">{title}</span>
        </h3>
        {meta}
      </CardHeader>
      <CardContent className={cn('@container flex min-w-0 flex-col gap-3 px-4', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

const isEmpty = (v) => v == null || v === ''

/**
 * Etiket/değer çifti. `mono`: sabit genişlikli; uzun belirteç (IP, parmak izi, host) kırpılmadan, yalnız gerektiğinde
 * herhangi bir noktadan sarılır (`overflow-wrap:anywhere` — boşluklu değer önce boşlukta kırılır). `full`: iki sütunu
 * kaplar. `hint`: değerin altında soluk açıklama (ikinci `dd`). Boş değer soluk "—".
 */
export function Fact({ label, value, mono, full, hint, className, ...rest }) {
  const empty = isEmpty(value)
  return (
    <div className={cn('flex min-w-0 flex-col gap-1', full && '@md:col-span-2', className)} {...rest}>
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-sm leading-snug [overflow-wrap:anywhere]',
        mono && !empty && 'font-mono text-[13px]', empty && 'text-muted-foreground')}>
        {empty ? '—' : value}
      </dd>
      {hint && <dd className="m-0 min-w-0 text-xs leading-snug text-muted-foreground [overflow-wrap:anywhere]">{hint}</dd>}
    </div>
  )
}

/** `dl` ızgarası — telefonda tek sütun, kap ≥ 28rem iki sütun (üst öğede `@container`: DetailSection içeriği). */
export function FactGrid({ children, className }) {
  return <dl className={cn('m-0 grid min-w-0 grid-cols-1 gap-x-6 gap-y-3.5 @md:grid-cols-2', className)}>{children}</dl>
}

/** Yeni sekmede açılan dış bağlantı: yalnız http(s) (`safeHttpUrl` süzgecinden geçmiş `href`). */
function ExternalAnchor({ href, children, className }) {
  const t = useT()
  return (
    <a href={href} target="_blank" rel="noopener noreferrer"
      className={cn('break-all text-primary underline underline-offset-2 hover:text-primary/80', className)}>
      {children}<span className="sr-only"> ({t('domdet.newTab')})</span>
    </a>
  )
}

/**
 * Serbest metin: içindeki http(s) adresleri dış bağlantı, gerisi düz metin. `javascript:` vb. ASLA bağlantı olmaz.
 * Bağlantısız metin tek bir metin düğümü olarak döner (metin aramaları bölünmez).
 */
export function LinkifiedText({ text }) {
  const parts = splitLinks(text)
  if (parts.length === 1 && parts[0].type === 'text') return parts[0].value
  return parts.map((p, i) => (p.type === 'url'
    ? <ExternalAnchor key={i} href={p.href}>{p.value}</ExternalAnchor>
    : <Fragment key={i}>{p.value}</Fragment>))
}

/**
 * Sorumlu değeri ("Ad Soyad - ad.soyad@example.com"): YALNIZ e-posta parçası `mailto:` bağlantısı, ad düz metin
 * (tamamını linklemek "ada tıkla" yanılgısı yaratır). Adres yoksa düz metin.
 */
export function ContactText({ value }) {
  const raw = String(value ?? '').trim()
  const e = splitEmail(raw)
  if (!e) return raw
  return (
    <>{e.before}<a href={`mailto:${e.addr}`} className="break-all text-primary underline underline-offset-2 hover:text-primary/80">{e.addr}</a>{e.after}</>
  )
}

/**
 * Sorumlu Ekipler listesi — yalnız DOLU roller (boş rolün etiketi hiç çizilmez; sayaç başlıkta "n/4"). Her satır shadcn
 * Item: baş harf avatarı · rol · değer (mailto) · adresi kopyala (dokunmatikte 40 px, ad adresi taşır, 2 sn "Kopyalandı").
 * Dördü de boşsa açık bir not — boş başlık altında boş ızgara "yüklenmedi" hissi verirdi.
 */
export function ContactList({ record }) {
  const t = useT()
  const contacts = filledContactFields(record)
  if (contacts.length === 0) return <p className="m-0 text-sm text-muted-foreground">{t('inv.contactsEmpty')}</p>
  return (
    <ul data-slot="inv-contacts-list" className="m-0 flex list-none flex-col gap-2 p-0">
      {contacts.map(({ key, labelKey }) => {
        const value = record[key]
        const addr = splitEmail(value)?.addr
        return (
          <Item key={key} asChild variant="outline" size="sm" className="flex-nowrap items-start gap-3 bg-muted/20 px-3 py-2.5">
            <li data-slot="inv-contact" data-contact={key}>
              <ItemMedia className="pt-0.5">
                <Avatar><AvatarFallback className="bg-sky-100 text-xs font-bold text-sky-800 dark:bg-sky-900 dark:text-sky-100">{contactInitials(value)}</AvatarFallback></Avatar>
              </ItemMedia>
              <ItemContent className="min-w-0 gap-0.5">
                <span className="text-xs font-medium text-muted-foreground">{t(labelKey)}</span>
                <span className="text-sm leading-snug [overflow-wrap:anywhere]"><ContactText value={value} /></span>
              </ItemContent>
              {addr && (
                <CopyButton value={addr} label={t('inv.det.copyAddr', addr)} copiedLabel={t('err.copied')} variant="ghost" buttonSize="icon-sm"
                  className="-my-0.5 shrink-0 text-muted-foreground hover:text-primary pointer-coarse:size-10" />
              )}
            </li>
          </Item>
        )
      })}
    </ul>
  )
}

/** Bayrak çipinin tonu: "eylem gerekli" kırmızı, "kullanımda" yeşil, diğer açıklar birincil (tablo ikon renkleriyle aynı). */
const FLAG_ON_TONE = { action_required: 'bad', in_use: 'ok' }

/**
 * Operasyonel bayraklar — 13'ü de görünür: "Geçerli olanlar" dolu tonlu çip (ikonlu), "Geçerli olmayanlar" soluk.
 * Hiçbiri açık değilse açık bir not. Test kancası `data-slot="inv-flag-badges"`, çip `data-flag` + `data-on`.
 */
export function FlagBoard({ record }) {
  const t = useT()
  const { on, off } = splitFlags(record)
  return (
    <div data-slot="inv-flag-badges" className="flex min-w-0 flex-col gap-3">
      <div className="min-w-0">
        <SubHead>{t('inv.det.flagsOnTitle')}</SubHead>
        {on.length > 0 ? (
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
            {on.map(({ key, labelKey }) => {
              const Icon = FLAG_ICON[key] || CircleCheck
              return (
                <li key={key} className="min-w-0">
                  <Badge variant="outline" data-flag={key} data-on="true" className={cn(CHIP, TONE[FLAG_ON_TONE[key] || 'info'])}>
                    <Icon aria-hidden="true" />{t(labelKey)}
                  </Badge>
                </li>
              )
            })}
          </ul>
        ) : <p className="m-0 text-sm text-muted-foreground">{t('inv.flagsNone')}</p>}
      </div>
      {off.length > 0 && (
        <div className="min-w-0">
          <SubHead>{t('inv.det.flagsOffTitle')}</SubHead>
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
            {off.map(({ key, labelKey }) => (
              <li key={key} className="min-w-0">
                <Badge variant="outline" data-flag={key} data-on="false" className={cn(CHIP, 'border-dashed bg-transparent font-normal text-muted-foreground')}>
                  {t(labelKey)}
                </Badge>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

/** Markdown içindeki bağlantı: http(s) → yeni sekme; mailto → olduğu gibi; başka her şey düz metin. */
function MarkdownLink({ href, children }) {
  const safe = safeHttpUrl(href)
  if (safe) return <ExternalAnchor href={safe}>{children}</ExternalAnchor>
  if (/^mailto:/i.test(String(href || '')) && splitEmail(String(href).slice(7))) {
    return <a href={href} className="text-primary underline underline-offset-2">{children}</a>
  }
  return <span>{children}</span>
}
const MD_COMPONENTS = { a: MarkdownLink }

/** Notlar (değişiklik açıklaması) — markdown; bağlantılar güvenli. Test kancası `data-slot="inv-notes"`. */
export function MarkdownNotes({ text }) {
  const { isDark } = useTheme()
  return (
    <div data-slot="inv-notes" className="show-markdown min-w-0 [overflow-wrap:anywhere]" data-color-mode={isDark ? 'dark' : 'light'}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={MD_COMPONENTS}>{text}</ReactMarkdown>
    </div>
  )
}

/** Zaman damgası: göreli ("3 gün önce") + tam tarih (ekran okuyucu ve fare için `time`). */
export function TimeAgo({ iso, className }) {
  const t = useT()
  if (!iso) return null
  const rel = relativeTime(iso, t)
  return (
    <time dateTime={toUtc(iso)} title={formatDate(iso)} className={className}>{rel || formatDate(iso)}</time>
  )
}
