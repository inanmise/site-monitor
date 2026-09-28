import { useId, useState } from 'react'
import {
  Ban, BellRing, Braces, Building2, CalendarClock, ExternalLink, History, ListChecks, ListX, Lock, LockOpen, RefreshCw, Server,
  ServerCrash, ShieldCheck, ShieldOff, ShieldQuestion,
} from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { toUtc } from '../../../utils/localDay.js'
import { relativeTime } from '../../admin/audit/auditFormat.js'
import { CHIP, CHIP_TONE } from '../../certcard/CertCardParts.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CollapsibleSection from '../../ui/CollapsibleSection.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { WHOIS_PROVIDER_LABEL, dnssecOf, fmtExpiry, nsOf, sourceTag } from '../domainCardModel.js'
import {
  blacklistEntriesOf, eppListOf, fmtClock, locksOf, rawJsonOf, relativeDays, sourceFamily, timelineOf,
} from './domainDetailModel.js'

/**
 * "Domain Kaydı" sekmesinin gövdesi (2026-09-28 yeniden tasarım — shadcn, mobil duyarlı). Veri çekme
 * DomainRegistrationTab'da kalır (anlık RDAP/WHOIS → başarısızsa kayıtlı bilgi, hatırlatmalar); bu dosya yalnız sunar.
 * Bölümler (her biri shadcn Card + h3 başlık):
 *   Kayıt kuruluşu (ad · IANA kimliği + IANA listesi bağlantısı · kaynak + açıklaması) ·
 *   Tarihler (kayıt → son güncelleme → bugün → uyarı eşiği → planlanan yenileme → bitiş; mini zaman çizelgesi, METİN
 *   listesidir — ekran okuyucu sırayla okur, noktalar süs) ·
 *   Ad sunucuları ve DNS (NS listesi + toplu çözümleme durumu, DNSSEC, IP ↔ PTR tablosu) ·
 *   Koruma (transfer / güncelleme / silme / yenileme kilitleri EPP'den, sade dilde; kara liste + kanıt + kaldırma
 *   sayfası; son değişim; alarm anahtarları) ·
 *   EPP durum kodları (TÜMÜ, kritik önce; her kodun açıklaması GÖRÜNÜR metin — dokunmatikte de okunur) ·
 *   Hatırlatmalar (eşik çipleri dokun-gör + gönderim listesi, sayfalı) ·
 *   Kayıt verisi (JSON — katlanır, kopyalanabilir).
 * Eski sekmenin gösterdiği her bilgi korunur (neden-boş açıklamaları dahil). Yerleşim KAP genişliğine göre
 * (`@container`): ≥ 48rem iki sütun, altında tek sütun. SOL RENK ŞERİDİ YOK.
 *
 * <p>Test kancaları: kök `domain-registration`; bölüm `domain-reg-section` + `data-section`
 * (registrar|dates|dns|protection|epp|reminders); `domain-timeline` (öğe `data-kind`, `data-state`), `domain-ns-list`,
 * `domain-locks` (öğe `data-op`, `data-state`, `data-tone`), `domain-blacklist`, `domain-epp-list` (öğe `data-code`,
 * `data-tone`), `domain-reminders`, `domain-raw-json`.
 */

const IANA_REGISTRAR_LIST = 'https://www.iana.org/assignments/registrar-ids/registrar-ids.xhtml'
const LOCK_LABEL = { BOTH: 'dom.lockBoth', SERVER: 'dom.lockServer', CLIENT: 'dom.lockClient', NONE: 'dom.lockNone', UNKNOWN: 'dom.lockUnknown' }
/** EPP tonu → çip tonu (kartla aynı: kritik kırmızı, uyarı amber, bilgi mor). */
const EPP_CHIP = { bad: 'bad', warn: 'warn', info: 'plan' }
/** Zaman çizelgesi noktası: ton → kenar/dolgu (geçmiş dolu, gelecek boş halka). */
const DOT = {
  muted: 'border-muted-foreground/60 data-[state=past]:bg-muted-foreground/60',
  info: 'border-primary bg-primary ring-4 ring-primary/15',
  warn: 'border-amber-500 data-[state=past]:bg-amber-500',
  plan: 'border-violet-500 data-[state=past]:bg-violet-500',
  bad: 'border-destructive bg-destructive',
  ok: 'border-success data-[state=past]:bg-success',
  warning: 'border-orange-500 bg-orange-500/20',
  critical: 'border-destructive bg-destructive/20',
  expired: 'border-destructive bg-destructive',
}
/** Hatırlatma çipi durumu → ton (efsane metniyle aynı: yeşil gönderildi, gri kapsandı, kırmızı aşıldı, amber atlandı). */
const REM_TONE = { sent: 'ok', covered: 'muted', skipped: 'warn', due: 'bad', pending: 'muted' }
const REM_DOT = { sent: 'bg-success', covered: 'bg-muted-foreground/50', skipped: 'bg-amber-500' }
const remClass = (status) => (status === 'SENT' ? 'sent' : status === 'COVERED' ? 'covered' : 'skipped')

/** Neden-boş satırı (eski `.dreg-why`): soluk, kırılabilir. */
function Why({ children, className, ...rest }) {
  return <p className={cn('text-xs text-muted-foreground [overflow-wrap:anywhere]', className)} {...rest}>{children}</p>
}

/** Tonlu durum rozeti (sertifika kartının çip ailesi). */
function ToneBadge({ tone = 'muted', className, children, ...rest }) {
  return (
    <Badge variant="outline" data-tone={tone} className={cn(CHIP, CHIP_TONE[tone] ?? CHIP_TONE.muted, className)} {...rest}>
      {children}
    </Badge>
  )
}

/** Bölüm alt başlığı (bir kart içinde ikinci düzey). */
function SubHead({ children, className }) {
  return <h4 className={cn('mb-1.5 text-[10.5px] font-semibold tracking-[.06em] text-muted-foreground uppercase', className)}>{children}</h4>
}

/** Bölüm: shadcn Card + h3 başlık (+ isteğe bağlı sağ üst öğe). */
function RegSection({ id, icon: Icon, title, action, className, children }) {
  const hid = useId()
  return (
    <Card data-slot="domain-reg-section" data-section={id}
      className={cn('min-w-0 gap-3 py-4 shadow-none', className)}>
      <CardHeader className="flex min-w-0 flex-wrap items-center justify-between gap-2 px-4">
        <h3 id={hid} className="flex min-w-0 items-center gap-2 text-sm leading-tight font-semibold">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{title}
        </h3>
        {action}
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4">{children}</CardContent>
    </Card>
  )
}

function Row({ label, children }) {
  return (
    <>
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

/** Kayıt kuruluşu: ad · IANA kimliği (+ IANA'nın registrar listesi — sabit adres, alan adı eklenmez) · kaynak. */
function RegistrarSection({ d }) {
  const t = useT()
  const src = d.source && d.source !== 'NONE' ? sourceTag(d.source, d.whois_provider) : null
  const fam = sourceFamily(d.source)
  const provider = d.whois_provider && WHOIS_PROVIDER_LABEL[d.whois_provider]
  return (
    <RegSection id="registrar" icon={Building2} title={t('domdet.sec.registrar')}>
      <dl className="grid min-w-0 grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-4 gap-y-2">
        <Row label={t('dreg.registrarName')}>
          {d.registrar ? <span className="font-medium">{d.registrar}</span> : <span className="text-muted-foreground">{t('domdet.noRegistrar')}</span>}
        </Row>
        <Row label={t('dreg.ianaId')}>
          {d.registrar_iana_id ? (
            <span className="inline-flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-mono">{d.registrar_iana_id}</span>
              <Button asChild variant="link" size="sm" className="h-auto p-0 text-xs pointer-coarse:min-h-10">
                <a href={IANA_REGISTRAR_LIST} target="_blank" rel="noopener noreferrer">
                  {t('domdet.ianaList')}<ExternalLink aria-hidden="true" className="size-3" />
                  <span className="sr-only"> ({t('domdet.newTab')})</span>
                </a>
              </Button>
            </span>
          ) : '—'}
        </Row>
        <Row label={t('dreg.source')}>
          {src ? <span className="font-mono text-[13px]">{src}</span> : '—'}
          {fam && <Why className="mt-0.5">{t(`domcard.src.${fam}`)}</Why>}
          {provider && <Why>{t('domcard.src.via', provider)}</Why>}
        </Row>
      </dl>
    </RegSection>
  )
}

const TL_LABEL = {
  registered: 'domdet.tl.registered', updated: 'domdet.tl.updated', today: 'domdet.tl.today', warning: 'domdet.tl.warning',
  plan: 'domdet.tl.plan', expiry: 'domdet.tl.expiry',
}

/**
 * Mini zaman çizelgesi — sıralı liste (`<ol>`): her öğe ad + tarih (+ RDAP saati) + göreli süre METİN olarak; ekran
 * okuyucu için başka bir alternatif gerekmez, nokta ve çizgiler `aria-hidden`. Dar kapta dikey, ≥ 42rem yatay
 * (eşit aralıklı adımlar — yıllara yayılan kaydı orantılı çizmek son olayları üst üste bindirirdi).
 */
function DatesSection({ d }) {
  const t = useT()
  const items = timelineOf(d)
  const onlyToday = items.every((it) => it.kind === 'today')
  return (
    <RegSection id="dates" icon={CalendarClock} title={t('domdet.sec.dates')} className="@3xl:col-span-2">
      {onlyToday ? <Why>{d.source === 'NONE' || !d.source ? t('dreg.whyNoData') : t('domdet.tl.empty')}</Why> : (
        <div className="@container min-w-0">
          <ol data-slot="domain-timeline" aria-label={t('domdet.tl.label')}
            className="m-0 grid min-w-0 list-none gap-0 p-0 @2xl:auto-cols-fr @2xl:grid-flow-col">
            {items.map((it, i) => {
              const last = i === items.length - 1
              const label = it.kind === 'expiry' && it.state === 'past' ? t('domdet.tl.expired')
                : it.kind === 'plan' && it.overdue ? t('domdet.tl.planOverdue') : t(TL_LABEL[it.kind])
              const clock = it.kind === 'expiry' ? fmtClock(it.iso) : null
              const extra = it.kind === 'warning' ? t('domdet.tl.warningDays', it.days)
                : it.kind === 'plan' && it.before > 0 ? t('domdet.planBefore', it.before) : null
              return (
                <li key={`${it.kind}-${it.key}`} data-kind={it.kind} data-state={it.state} data-tone={it.tone}
                  className={cn('relative flex min-w-0 gap-3 @2xl:flex-col @2xl:gap-2 @2xl:pr-3', !last && 'pb-4 @2xl:pb-0')}>
                  {!last && (
                    <span aria-hidden="true"
                      className="absolute top-5 bottom-0 left-[7px] w-px bg-border @2xl:top-[7px] @2xl:right-0 @2xl:bottom-auto @2xl:left-5 @2xl:h-px @2xl:w-auto" />
                  )}
                  <span aria-hidden="true" data-state={it.state}
                    className={cn('relative z-[1] mt-0.5 size-[15px] shrink-0 rounded-full border-2 bg-card @2xl:mt-0', DOT[it.tone] ?? DOT.muted)} />
                  <div className="min-w-0">
                    <p className={cn('text-xs font-semibold', it.kind === 'today' ? 'text-primary' : it.tone === 'bad' || it.tone === 'expired' ? 'text-destructive' : 'text-foreground')}>
                      {label}
                    </p>
                    <p className="text-sm tabular-nums">
                      <time dateTime={it.key}>{fmtExpiry(it.iso)}</time>
                      {clock && <span className="text-xs text-muted-foreground"> · {clock}</span>}
                    </p>
                    {it.kind !== 'today' && (
                      <p className="text-xs text-muted-foreground">
                        {relativeDays(it.diff)}{extra ? ` · ${extra}` : ''}
                      </p>
                    )}
                  </div>
                </li>
              )
            })}
          </ol>
        </div>
      )}
    </RegSection>
  )
}

/** Ad sunucuları (+ toplu çözümleme), DNSSEC, IP ↔ PTR. Ad sunucusu BAŞINA çözümleme verisi yok — uydurulmaz. */
function DnsSection({ d }) {
  const t = useT()
  const ns = nsOf(d)
  const sec = dnssecOf(d)
  const ips = csv(d.resolved_ips), hosts = csv(d.hostnames)
  const nsBadge = ns.resolves === true ? <ToneBadge tone="ok" data-slot="domain-ns-resolves"><Server aria-hidden="true" />{t('domdet.nsOk')}</ToneBadge>
    : ns.resolves === false ? <ToneBadge tone="bad" data-slot="domain-ns-resolves"><ServerCrash aria-hidden="true" />{t('dom.dashNsFail')}</ToneBadge>
      : null
  return (
    <RegSection id="dns" icon={Server} title={t('domdet.sec.dns')} action={nsBadge}>
      <div className="min-w-0">
        <SubHead>{t('dreg.nameservers')}{ns.count ? ` (${ns.count})` : ''}</SubHead>
        {ns.count ? (
          <ul data-slot="domain-ns-list" className="m-0 flex list-none flex-col divide-y rounded-lg border p-0">
            {ns.list.map((n) => (
              <li key={n} className="flex min-w-0 items-center gap-2 px-3 py-2 font-mono text-[13px] [overflow-wrap:anywhere]">
                <Server aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />{n}
              </li>
            ))}
          </ul>
        ) : <Why>— {d.source === 'NONE' || !d.source ? t('dreg.whyNoData') : t('dreg.whyNoNs')}</Why>}
        {ns.count > 0 && ns.resolves != null && <Why className="mt-1.5">{t('domdet.nsAggregate')}</Why>}
        {ns.resolves === false && <Why className="mt-1 text-destructive">{t('domcard.nsFailHint')}</Why>}
      </div>
      <div className="min-w-0">
        <SubHead>DNSSEC</SubHead>
        {sec ? (
          <div className="flex min-w-0 flex-col gap-1">
            <ToneBadge tone={sec === 'signed' ? 'ok' : 'muted'} data-slot="domain-dnssec" className="self-start">
              {sec === 'signed' ? <ShieldCheck aria-hidden="true" /> : <ShieldOff aria-hidden="true" />}
              {sec === 'signed' ? t('dreg.dnssecSigned') : t('dreg.dnssecUnsigned')}
            </ToneBadge>
            <Why>{t(`domcard.dnssec.${sec}`)}</Why>
          </div>
        ) : <Why>{t('dreg.dnssecUnknown')} {d.source === 'WHOIS' ? t('dreg.whyDnssecWhois') : t('dreg.whyNoData')}</Why>}
      </div>
      <div className="min-w-0">
        <SubHead>{t('dreg.ips')}</SubHead>
        {ips.length ? (
          // IP ↔ ters kayıt (PTR) — shadcn Table (dar ekranda kendi kabında yatay kayar)
          <Table data-slot="dreg-iptable" className="text-[13px]">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="h-8 pl-0">{t('domdet.colIp')}</TableHead>
                <TableHead className="h-8">{t('domdet.colPtr')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ips.map((ip, i) => (
                <TableRow key={ip} className="hover:bg-transparent">
                  <TableCell className="py-1.5 pr-4 pl-0 font-mono">{ip}</TableCell>
                  <TableCell className="py-1.5 whitespace-normal [overflow-wrap:anywhere]">
                    {hosts[i] || <span className="text-xs text-muted-foreground">{t('dreg.whyNoPtr')}</span>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : <Why>— {t('dreg.whyNoIp', d.domain || '')}</Why>}
      </div>
    </RegSection>
  )
}

const LOCK_ICON = { ok: Lock, bad: LockOpen, warn: Lock, muted: ShieldQuestion }

/** Koruma: kilit matrisi (sade dil), kara liste (+ kanıt, kaldırma sayfası), son değişim, alarm anahtarları. */
function ProtectionSection({ d }) {
  const t = useT()
  const locks = locksOf(d)
  const unknownWhy = d.source === 'WHOIS' ? t('dreg.whyLockWhois') : t('domcard.lock.UNKNOWN')
  const bl = String(d.blacklist_status || 'UNKNOWN').toUpperCase()
  const entries = bl === 'LISTED' ? blacklistEntriesOf(d.blacklist_detail) : []
  const noIp = csv(d.resolved_ips).length === 0
  return (
    <RegSection id="protection" icon={ShieldCheck} title={t('domdet.sec.protection')}>
      <ul data-slot="domain-locks" aria-label={t('domdet.locks')} className="m-0 flex list-none flex-col divide-y rounded-lg border p-0">
        {locks.map((l) => {
          const Icon = LOCK_ICON[l.tone] ?? ShieldQuestion
          const label = l.op === 'renew' && l.state === 'NONE' ? t('domdet.renewFree') : t(LOCK_LABEL[l.state])
          // Satır işlemin ne işe yaradığını söyler (transfer bilinen durumda durumun anlamını); "neden doğrulanamadı" dört
          // kez tekrarlanmaz — listenin altında BİR kez yazılır.
          const hint = l.op === 'transfer' && l.state !== 'UNKNOWN' ? t(`domcard.lock.${l.state}`) : t(`domdet.opHint.${l.op}`)
          return (
            <li key={l.op} data-op={l.op} data-state={l.state} data-tone={l.tone}
              className="flex min-w-0 flex-col gap-1.5 px-3 py-2.5 @sm:flex-row @sm:items-start @sm:justify-between @sm:gap-3">
              <div className="min-w-0">
                <p className="text-sm font-medium">{t(`domdet.op.${l.op}`)}</p>
                <Why>{hint}</Why>
              </div>
              <ToneBadge tone={l.tone} className="shrink-0 self-start"><Icon aria-hidden="true" />{label}</ToneBadge>
            </li>
          )
        })}
      </ul>
      {locks.some((l) => l.state === 'UNKNOWN') && <Why data-slot="domain-locks-unknown" className="-mt-1.5">{unknownWhy}</Why>}
      <div data-slot="domain-blacklist" data-status={bl} className="min-w-0">
        <SubHead>{t('dom.blacklist')}</SubHead>
        {bl === 'CLEAN' && <div className="flex flex-col gap-1"><ToneBadge tone="ok" className="self-start"><ShieldCheck aria-hidden="true" />{t('dom.blClean')}</ToneBadge><Why>{t('domcard.bl.CLEAN')}</Why></div>}
        {bl === 'SKIPPED' && <div className="flex flex-col gap-1"><ToneBadge tone="muted" className="self-start">{t('dom.blSkipped')}</ToneBadge><Why>{t('domdet.blSkippedHint')}</Why></div>}
        {bl === 'LISTED' && (
          <div className="flex flex-col gap-1.5">
            <ToneBadge tone="bad" className="self-start"><Ban aria-hidden="true" />{t('dom.blListed').replace('{n}', String(entries.length || '?'))}</ToneBadge>
            <Why>{t('dom.dashBlacklistedHint')}</Why>
            {entries.length > 0 && (
              <ul className="m-0 flex list-none flex-col divide-y rounded-lg border p-0">
                {entries.map((e) => (
                  <li key={`${e.list}-${e.target}`} className="flex min-w-0 flex-col gap-0.5 px-3 py-2 @sm:flex-row @sm:items-center @sm:justify-between @sm:gap-3">
                    <span className="min-w-0 [overflow-wrap:anywhere]">
                      <span className="font-mono text-[13px] font-medium">{e.list}</span>
                      {e.target && <span className="text-xs text-muted-foreground"> · {e.target}</span>}
                    </span>
                    {e.delist && (
                      <Button asChild variant="link" size="sm" className="h-auto shrink-0 self-start p-0 text-xs pointer-coarse:min-h-10">
                        <a href={e.delist} target="_blank" rel="noopener noreferrer">
                          {t('domdet.delist')}<ExternalLink aria-hidden="true" className="size-3" />
                          <span className="sr-only"> ({e.list}, {t('domdet.newTab')})</span>
                        </a>
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        {bl !== 'CLEAN' && bl !== 'SKIPPED' && bl !== 'LISTED' && (
          <div className="flex flex-col gap-1">
            <ToneBadge tone="muted" className="self-start"><ListX aria-hidden="true" />{t('dom.blUnknown')}</ToneBadge>
            <Why>{noIp ? t('dreg.whyBlNoIp') : t('dreg.whyBlNoAnswer')}</Why>
          </div>
        )}
      </div>
      {d.change_detail && (
        <div data-slot="domain-last-change" className="min-w-0">
          <SubHead className="flex items-center gap-1"><History aria-hidden="true" className="size-3" />{t('dom.lastChange')}</SubHead>
          <p className="text-sm [overflow-wrap:anywhere]">{d.change_detail}</p>
        </div>
      )}
      <div className="min-w-0">
        <SubHead>{t('dom.alarmSettings')}</SubHead>
        <dl className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-1 text-sm">
          {[['transfer_lock_alert', 'dom.transferLockAlert', d.transfer_lock_alert !== false],
            ['blacklist_enabled', 'dom.blacklistEnabled', d.blacklist_enabled === true],
            ['change_alert', 'dom.changeAlert', d.change_alert !== false]].map(([k, key, on]) => (
            <div key={k} className="contents">
              <dt className="min-w-0 text-muted-foreground">{t(key)}</dt>
              <dd className={cn('text-right font-medium', on ? 'text-success' : 'text-muted-foreground')}>{on ? t('domdet.on') : t('domdet.off')}</dd>
            </div>
          ))}
        </dl>
      </div>
    </RegSection>
  )
}

/** EPP kodlarının TAMAMI, kritik önce; her kodun sade dilde açıklaması görünür, kaynağın yazımı ayrıca (farklıysa). */
function EppSection({ d }) {
  const t = useT()
  const list = eppListOf(d.status_codes)
  const desc = (c) => { const k = `epp.${c.key}`; const v = t(k); return v !== k ? v : null }
  return (
    <RegSection id="epp" icon={ListChecks} title={t('domdet.sec.epp')}
      action={list.length ? <Badge variant="secondary" className="tabular-nums">{list.length}</Badge> : null}>
      {list.length ? (
        <ul data-slot="domain-epp-list" className="m-0 flex list-none flex-col divide-y rounded-lg border p-0">
          {list.map((c) => (
            <li key={c.code} data-code={c.key} data-tone={c.tone} className="flex min-w-0 flex-col gap-1 px-3 py-2.5">
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <ToneBadge tone={EPP_CHIP[c.tone]} data-slot="domain-epp-code">{c.label}</ToneBadge>
                <span className="text-[10.5px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t(`domdet.eppTone.${c.tone}`)}</span>
              </div>
              <Why className="text-foreground/80">{desc(c) || t('domdet.eppNoDesc')}</Why>
              {c.code !== c.label && <p className="font-mono text-[11px] text-muted-foreground [overflow-wrap:anywhere]">{c.code}</p>}
            </li>
          ))}
        </ul>
      ) : <Why>— {d.source === 'WHOIS' ? t('dreg.whyNoEppWhois') : t('dreg.whyNoData')}</Why>}
    </RegSection>
  )
}

/** Hatırlatmalar: eşik çipleri (durumu dokun-gör balonunda) + efsane + gönderim listesi (sayfalı). */
function RemindersSection({ d, rem, remError, remPager }) {
  const t = useT()
  const items = rem?.items || []
  return (
    <RegSection id="reminders" icon={BellRing} title={t('dreg.reminders')} className="@3xl:col-span-2">
      {!rem ? <Why>{remError ? t('domdet.remError') : t('domdet.remLoading')}</Why> : (
        <div data-slot="domain-reminders" className="flex min-w-0 flex-col gap-3">
          <ul aria-label={t('domdet.remThresholds')} className="m-0 flex min-w-0 list-none flex-wrap gap-2 p-0">
            {(rem.thresholds || []).map((th) => {
              const hit = items.find((x) => x.threshold_days === th && x.expiry_date === d.expiry_date)
              const cls = hit ? remClass(hit.status) : (d.days_remaining != null && d.days_remaining <= th ? 'due' : 'pending')
              const state = hit ? `${t('dreg.remStatus_' + hit.status)} · ${formatDateSec(hit.sent_at)}`
                : cls === 'due' ? t('domdet.remDue') : t('dreg.remPending')
              const text = th === 1 ? t('domdet.oneDay') : `${th} ${t('card.daysUnit')}`
              return (
                <li key={th}>
                  <HintPopover content={state} aria-label={t('a11y.rowAction', text, state)}
                    triggerClassName="rounded-full pointer-coarse:min-h-10">
                    <Badge variant="outline" data-state={cls} className={cn(CHIP, CHIP_TONE[REM_TONE[cls]], cls === 'pending' && 'border-dashed bg-transparent')}>
                      {text}
                    </Badge>
                  </HintPopover>
                </li>
              )
            })}
          </ul>
          <Why>{t('dreg.remLegend')}</Why>
          {items.length > 0 ? (
            <>
              <ul className="m-0 flex list-none flex-col divide-y rounded-lg border p-0 text-sm">
                {remPager.pageItems.map((x) => (
                  <li key={x.id} className="flex min-w-0 items-start gap-2.5 px-3 py-2">
                    <span aria-hidden="true" className={cn('mt-1.5 size-2 shrink-0 rounded-full', REM_DOT[remClass(x.status)])} />
                    <div className="min-w-0">
                      <p className="tabular-nums">
                        {formatDateSec(x.sent_at)} · {t('dreg.remRow', x.threshold_days, x.days_remaining ?? '—')} · <span className="font-medium">{t('dreg.remStatus_' + x.status)}</span>
                      </p>
                      {(x.recipients || x.push_queued != null) && (
                        <Why>
                          {x.recipients ? `→ ${x.recipients}` : ''}{x.recipients && x.push_queued != null ? ' · ' : ''}{x.push_queued != null ? `push ${x.push_queued}` : ''}
                        </Why>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
              <PaginationBar {...remPager} />
            </>
          ) : <Why>{t('dreg.remNone')}</Why>}
        </div>
      )}
    </RegSection>
  )
}

/** Kayıt verisi (JSON): SiteMonitor'ün sakladığı normalleştirilmiş kayıt — katlanır, kopyalanabilir, klavyeyle kayar. */
function RawSection({ d }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const json = rawJsonOf(d)
  return (
    <CollapsibleSection open={open} onOpenChange={setOpen} icon={Braces} label={t('domdet.sec.raw')} hint={t('domdet.rawHintShort')}
      data-slot="domain-raw" contentClassName="pt-2">
      <div className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3">
        <div className="flex min-w-0 items-start justify-between gap-2">
          <Why>{t('domdet.rawHint')}</Why>
          <CopyButton value={json} label={t('domdet.rawCopy')} copiedLabel={t('domdet.copied')} buttonSize="icon-sm"
            className="shrink-0 pointer-coarse:size-10" />
        </div>
        <pre data-slot="domain-raw-json" tabIndex={0} aria-label={t('domdet.sec.raw')}
          className="max-h-80 min-w-0 overflow-auto rounded-md bg-muted/50 p-3 font-mono text-xs leading-relaxed">
          {json}
        </pre>
      </div>
    </CollapsibleSection>
  )
}

function csv(v) {
  if (Array.isArray(v)) return v
  if (v == null || v === '') return []
  return String(v).split(',').map((s) => s.trim()).filter(Boolean)
}

/**
 * @param {object}   reg        GET /monitoring/domain/{id}/registration yanıtı (enrichDomain biçimi)
 * @param {object}   rem        hatırlatmalar `{ thresholds, items }` (yüklenene kadar null)
 * @param {boolean}  remError   hatırlatmalar alınamadı ("gönderilmedi" demek yanlış olurdu — ayrı metin)
 * @param {object}   remPager   usePagination (modal ön ayarı) — liste sayfalanır
 * @param {boolean}  stale      anlık sorgu başarısız, kayıtlı bilgi gösteriliyor
 * @param {boolean}  loading    anlık sorgu sürüyor (Yenile düğmesi döner)
 * @param {Function} onRefresh  anlık sorguyu yeniden çalıştır
 */
export default function DomainRegistrationPanel({ reg: d, rem, remError = false, remPager, stale = false, loading = false, onRefresh }) {
  const t = useT()
  const at = d.checked_at
  return (
    <div data-slot="domain-registration" className="@container flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 @md:flex-row @md:items-center @md:justify-between">
        {stale ? (
          <AlertBanner tone="warning" className="mb-0 min-w-0 flex-1">{t('dreg.stale').replace('{0}', formatDateSec(at))}</AlertBanner>
        ) : (
          <p data-slot="domain-reg-lookup" className="min-w-0 text-xs text-muted-foreground">
            {at ? <>{t('domdet.lookedUp')} <time dateTime={toUtc(at)} className="font-medium text-foreground/80">{relativeTime(at, t) || formatDateSec(at)}</time> · <span className="tabular-nums">{formatDateSec(at)}</span></> : t('domdet.neverChecked')}
          </p>
        )}
        <Button type="button" variant="outline" size="sm" onClick={onRefresh} disabled={loading} aria-busy={loading || undefined}
          title={t('domdet.refreshHint')} className="shrink-0 self-end pointer-coarse:h-10 @md:self-auto">
          {loading ? <Spinner size={13} inline decorative /> : <RefreshCw aria-hidden="true" />}{t('dreg.refresh')}
        </Button>
      </div>
      {/* items-start: kısa bölüm (ör. boş EPP) komşusunun boyuna uzayıp boş kutu çizmesin */}
      <div className="grid min-w-0 items-start gap-3 @3xl:grid-cols-2">
        <DatesSection d={d} />
        <RegistrarSection d={d} />
        <DnsSection d={d} />
        <ProtectionSection d={d} />
        <EppSection d={d} />
        <RemindersSection d={d} rem={rem} remError={remError} remPager={remPager} />
      </div>
      <RawSection d={d} />
    </div>
  )
}
