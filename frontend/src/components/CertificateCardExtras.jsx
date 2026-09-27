import { memo } from 'react'
import { Activity, Fingerprint, Link2, ShieldCheck, Siren, Users, Wrench } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { formatDate } from '../api/client'
import Sparkline from './ui/Sparkline.jsx'
import HintPopover from './ui/HintPopover.jsx'
import { navigateTo } from '../utils/navigate.js'
import { CARD_LAYER } from './monitoring/MonitorCard.jsx'
import { CHIP, CHIP_HOVER, CHIP_TONE, TOUCH_CHIP } from './certcard/CertCardParts.jsx'
import { FINDING_REASON } from './certcard/certCardModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Genel Bakış sertifika kartı — ZENGİN görünüm bölümü (2026-09-27 yeniden tasarım; ilk sürüm 2026-09-19). Veri
 * `/api/certificates/card-extras` satırı; kompakt görünümde hiç çizilmez. Düzen, izleme kartlarının ölçü kutularıyla
 * aynı dil:
 *   - iki kutu: SAĞLIK (temiz/değerlendirilen + bulgu sayısı → Sağlık sekmesi) · AÇIK ALARM (sayı + seviye + onay
 *     durumu → Alarm Geçmişi; yoksa "Açık alarm yok"),
 *   - bulgu çipleri (en fazla 3 + "+N"; kartın gerekçe çipi aynı kusuru zaten gösteriyorsa tekrar çizilmez),
 *   - ERİŞİLEBİLİRLİK (24 sa yüzde + son kontrol + mini grafik → Durum İzleme günlüğü),
 *   - küçük çipler: sertifika değişimi (+ Onayla) · paylaşım ("N alan adında ortak" → pencere) · bakım · sorumlular.
 * Tıklanabilir her öğe gerçek bir shadcn Button, örtünün üstünde (`CARD_LAYER`); adı alan adını taşır. Etkileşimsiz
 * boşluklar örtüye düşer → kartın detayını açar (eski "ölü bölge" sarmalayıcısı yok).
 *
 * Test kancaları: kök `data-slot="cert-extras"`, kutular `cert-extras-health` / `cert-extras-alerts` (`data-tone`,
 * `data-acked`), bulgular `cert-extras-findings`, erişilebilirlik `cert-extras-uptime`, mini grafik `cert-extras-spark`.
 */
const HF_KEYS = ['revocation', 'trust', 'sanMatch', 'chain', 'signature', 'keySize', 'intermediate', 'pinnedFingerprint', 'protocol', 'cipher', 'pfs', 'hsts', 'mixedContent']
const CRITICAL_HF = new Set(['revocation', 'trust', 'sanMatch', 'chain'])
/** Alarm seviyesi → ton. */
const LEVEL_TONE = { critical: 'bad', high: 'high', warning: 'warn', medium: 'warn', low: 'info', info: 'info' }

const stop = (fn) => (e) => { e.stopPropagation(); fn?.(e) }

const TILE = 'min-w-0 rounded-lg border bg-muted/40 px-2.5 py-2 text-left dark:bg-muted/25'
const TILE_TONE = {
  ok: '', info: '',
  warn: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  high: 'border-orange-500/40 bg-orange-500/5 dark:bg-orange-500/10',
  bad: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
}
const VALUE_INK = {
  ok: 'text-success', info: 'text-foreground', warn: 'text-amber-700 dark:text-amber-400',
  high: 'text-orange-700 dark:text-orange-400', bad: 'text-destructive',
}
/** Kutu düğmesi: ghost Button'ın satır içi düzeni kutu düzenine çevrilir; üzerine gelince kenar birincil renge döner. */
const TILE_BUTTON = cn(CARD_LAYER, 'h-auto items-stretch justify-start gap-0.5 font-normal whitespace-normal shadow-none',
  'hover:border-primary/60 hover:bg-muted/70 dark:hover:bg-muted/40')

function TileLabel({ icon: Icon, children }) {
  return (
    <span className="flex w-full min-w-0 items-center gap-1 text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
      <Icon aria-hidden="true" className="size-3 shrink-0" />
      <span className="truncate">{children}</span>
    </span>
  )
}

/**
 * Ölçü kutusu: etiket · büyük değer (+ yanında rozet) · alt satır. `onClick` varsa kutunun tamamı düğmedir. Rozet
 * DEĞERİN yanında: etiket satırında dar kutuda etiketi kesiyordu ("AÇIK ALA… KRİTİK").
 */
function Tile({ slot, tone, icon, label, badge, value, sub, onClick, name, className, ...rest }) {
  const body = (
    <>
      <TileLabel icon={icon}>{label}</TileLabel>
      <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
        <span className={cn('text-lg leading-tight font-semibold tabular-nums', VALUE_INK[tone])}>{value}</span>
        {badge}
      </span>
      {sub && <span className="text-[11px] leading-snug text-muted-foreground">{sub}</span>}
    </>
  )
  const cls = cn(TILE, 'flex flex-col', TILE_TONE[tone], className)
  if (!onClick) return <div data-slot={slot} data-tone={tone} className={cn(cls, 'gap-0.5')} {...rest}>{body}</div>
  return (
    <Button type="button" variant="ghost" data-slot={slot} data-tone={tone} aria-label={name} onClick={stop(onClick)}
      className={cn(cls, TILE_BUTTON)} {...rest}>
      {body}
    </Button>
  )
}

/** Küçük çip: `onClick` varsa ghost Button (dokunmatikte 40 px alan), yoksa Badge. */
function Chip({ tone = 'muted', onClick, name, disabled, className, children, ...rest }) {
  if (onClick) {
    return (
      <Button type="button" variant="ghost" size="xs" data-tone={tone} aria-label={name} disabled={disabled} onClick={stop(onClick)}
        className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, CHIP_TONE[tone], CHIP_HOVER[tone], className)} {...rest}>
        {children}
      </Button>
    )
  }
  return <Badge variant="outline" data-tone={tone} className={cn(CHIP, CHIP_TONE[tone], className)} {...rest}>{children}</Badge>
}

function levelText(level, t) {
  const key = `notify.level.${String(level || '').toUpperCase()}`
  const v = t(key)
  return v === key ? String(level || '') : v
}

function CertificateCardExtras({ cert, extra, reasons = [], onOpenHealth, onConfirmRenewal, onEditContacts, confirming = false, onOpenShared }) {
  const t = useT()
  if (!extra) return null
  const { health, alerts, uptime, change, shared, maintenance, contacts } = extra
  const domain = cert.domain
  const named = (label) => t('a11y.rowAction', domain, label)
  const hasUptime = !!(uptime && (uptime.pct24 != null || uptime.last_status))
  const hasShared = !!(shared && (shared.count > 0 || shared.san_count > 1))
  const hasChips = !!(change || hasShared || maintenance || contacts)
  if (!health && !alerts && !hasUptime && !hasChips) return null

  // Sağlık: bulgular (bilinen kurallar); kartın gerekçe çipi aynı kusuru gösteriyorsa bulgu çipi tekrar çizilmez.
  const failed = (health?.failed || []).filter((k) => HF_KEYS.includes(k))
  const covered = new Set(reasons.map((r) => r.key))
  const findings = failed.filter((k) => !(FINDING_REASON[k] || []).some((rk) => covered.has(rk)))
  const healthTone = failed.length ? (failed.some((k) => CRITICAL_HF.has(k)) ? 'bad' : 'warn') : 'ok'
  const healthValue = health ? `${health.ok}/${health.evaluated}` : null
  const healthSub = !health ? null
    : failed.length === 0 ? t('certcard.x.allClear')
    : failed.length === 1 ? t('certcard.x.finding1') : t('certcard.x.findings', failed.length)

  // Açık alarm
  const alertCount = alerts?.count || 0
  const alertTone = alertCount ? (LEVEL_TONE[String(alerts.level || 'warning').toLowerCase()] ?? 'warn') : 'ok'
  const alertLevel = alertCount ? levelText(alerts.level, t) : null

  // Erişilebilirlik (24 sa)
  const pct = uptime?.pct24
  const upTone = pct == null ? 'info' : pct < 95 ? 'bad' : pct < 99 ? 'warn' : 'ok'
  const lastUp = uptime?.last_status === 'up'
  const lastDown = !!uptime?.last_status && !lastUp
  const spark = Array.isArray(uptime?.points) && uptime.points.some((p) => p != null)

  const contactList = contacts ? [['app_dev', contacts.app_dev], ['iis_admin', contacts.iis_admin], ['svc_mgmt', contacts.svc_mgmt], ['waf_admin', contacts.waf_admin]].filter(([, v]) => v) : []
  const contactsText = contactList.length === 1 ? t('certcard.x.contact1') : t('ccx.contacts', contactList.length)
  const contactsTip = contactList.map(([k, v]) => `${t(`ccx.contact.${k}`)}: ${v}`).join('\n')

  return (
    <div data-slot="cert-extras" className="mb-3 flex min-w-0 flex-col gap-2 border-t border-dashed pt-3">
      {(health || alerts) && (
        <div className="grid min-w-0 grid-cols-2 gap-2">
          {health && (
            <Tile slot="cert-extras-health" tone={healthTone} icon={ShieldCheck} label={t('certcard.x.health')}
              value={healthValue} sub={healthSub} title={t('ccx.healthTip', health.ok, health.evaluated)}
              onClick={onOpenHealth ? () => onOpenHealth(domain) : undefined}
              name={named(`${t('certcard.x.health')}: ${healthValue} · ${healthSub}`)} />
          )}
          <Tile slot="cert-extras-alerts" tone={alertTone} icon={Siren} label={t('certcard.x.alerts')}
            className={health ? undefined : 'col-span-2'}
            data-acked={alertCount > 0 && alerts.all_acked ? 'true' : undefined}
            title={alertCount ? (alerts.types || []).join(', ') || undefined : undefined}
            badge={alertLevel && (
              <Badge variant="outline" className={cn('h-4 shrink-0 rounded px-1 text-[9.5px] font-bold tracking-[.04em] uppercase', CHIP_TONE[alertTone])}>
                {alertLevel}
              </Badge>
            )}
            value={alertCount}
            sub={alertCount ? (alerts.all_acked ? t('certcard.x.acked') : t('certcard.x.unacked')) : t('certcard.x.noAlerts')}
            onClick={alertCount ? () => navigateTo('alerthistory', alerts.first_id ? { incident: alerts.first_id } : undefined) : undefined}
            name={alertCount ? named(`${t('ccx.openAlerts', alertCount)} · ${alertLevel}`) : undefined} />
        </div>
      )}

      {findings.length > 0 && (
        <div data-slot="cert-extras-findings" className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-2">
          {findings.slice(0, 3).map((k) => (
            <Chip key={k} tone={CRITICAL_HF.has(k) ? 'bad' : 'muted'} title={t(`hlth.row.${k}.title`)}
              onClick={onOpenHealth ? () => onOpenHealth(domain) : undefined} name={named(t(`today.hf.${k}`))}>
              {t(`today.hf.${k}`)}
            </Chip>
          ))}
          {findings.length > 3 && <span className="text-[11px] text-muted-foreground">+{findings.length - 3}</span>}
        </div>
      )}

      {hasUptime && (
        // Satırın tamamı Durum İzleme günlüğüne (?q=alan) gider; mini grafik düğmenin içinde (svg `size-` sınıflı).
        <Button type="button" variant="ghost" data-slot="cert-extras-uptime" data-tone={upTone}
          title={`${t('ccx.uptimeTip', uptime.checks24 ?? 0, uptime.last_at ? formatDate(uptime.last_at) : '—')}\n${t('ccx.uptimeGo')}`}
          onClick={stop(() => navigateTo('uptime', { q: domain }))}
          className={cn(TILE, TILE_TONE[upTone], TILE_BUTTON, 'flex w-full flex-row items-center justify-between gap-3')}>
          <span className="flex min-w-0 flex-col gap-0.5">
            <TileLabel icon={Activity}>{t('certcard.x.availability')}</TileLabel>
            <span className="flex min-w-0 flex-wrap items-baseline gap-x-2">
              <span className={cn('text-lg leading-tight font-semibold tabular-nums', VALUE_INK[upTone])}>
                {pct == null ? '—' : t('ccx.pct', pct)}
              </span>
              {(lastUp || lastDown) && (
                <span className="inline-flex items-center gap-1 text-[11px] text-muted-foreground tabular-nums">
                  <span aria-hidden="true" className={cn('size-1.5 shrink-0 rounded-full', lastUp ? 'bg-success' : 'bg-destructive')} />
                  {lastDown ? <span className="font-semibold text-destructive">{t('live.down')}</span>
                    : uptime.last_ms != null ? `${uptime.last_ms} ms` : t('live.up')}
                </span>
              )}
            </span>
          </span>
          {spark && (
            <span data-slot="cert-extras-spark" className="flex shrink-0 items-center">
              <Sparkline data={uptime.points.map((p) => (p == null ? 0 : p))} width={88} height={24}
                className="size-auto h-6 w-[88px]" color={pct != null && pct < 95 ? '#dc2626' : '#059669'} />
            </span>
          )}
        </Button>
      )}

      {hasChips && (
        <div data-slot="cert-extras-chips" className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-2">
          {change && (
            <>
              <Chip tone={change.mismatch ? 'bad' : 'warn'} title={change.changed_at ? t('ccx.changedAt', formatDate(change.changed_at)) : undefined}>
                <Fingerprint aria-hidden="true" /> {change.mismatch ? t('ccx.pinMismatch') : t('ccx.changed')}
              </Chip>
              {!change.mismatch && onConfirmRenewal && (
                <Button type="button" variant="outline" size="xs" disabled={confirming} title={t('ccx.confirmTip')}
                  aria-label={named(t('ccx.confirm'))} onClick={stop(() => onConfirmRenewal(domain))}
                  className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, 'border-primary/50 bg-transparent text-primary hover:bg-primary/10 hover:text-primary dark:border-primary/50 dark:bg-transparent dark:hover:bg-primary/15')}>
                  {t('ccx.confirm')}
                </Button>
              )}
            </>
          )}
          {shared && shared.count > 0 && (
            // Tıklanınca paylaşılan sertifika penceresi (alanlar takım/platform/kalan gün ile).
            <Chip tone="info" onClick={onOpenShared ? () => onOpenShared(domain) : undefined}
              name={named(`${t('certcard.x.shared', shared.count)}${shared.san_count > 1 ? ` · ${t('ccx.san', shared.san_count)}` : ''}`)}
              title={`${t('ccx.sharedTip')}\n${(shared.domains || []).join('\n')}${shared.count > (shared.domains || []).length ? '\n…' : ''}${onOpenShared ? `\n\n${t('ccx.sharedClick')}` : ''}`}>
              <Link2 aria-hidden="true" />
              <span className="truncate">{t('certcard.x.shared', shared.count)}{shared.san_count > 1 ? ` · ${t('ccx.san', shared.san_count)}` : ''}</span>
            </Chip>
          )}
          {shared && !(shared.count > 0) && shared.san_count > 1 && (
            <Chip tone="muted" title={t('ccx.sanTip')}>{t('ccx.san', shared.san_count)}</Chip>
          )}
          {maintenance && (
            <Chip tone={maintenance.active ? 'warn' : 'muted'} title={maintenance.name || undefined}>
              <Wrench aria-hidden="true" />
              <span className="truncate">
                {maintenance.active ? t('ccx.maintActive', maintenance.until ? formatDate(maintenance.until) : '—')
                  : t('ccx.maintSoon', maintenance.next_start ? formatDate(maintenance.next_start) : '—')}
              </span>
            </Chip>
          )}
          {contacts && (contacts.missing ? (
            // Tıklanınca envanter formu Sorumlu Ekipler bölümünde açılır (yetki yoksa salt bilgi).
            <Chip tone="warn" title={t('ccx.contactsMissingTip') + (onEditContacts ? `\n${t('ccx.contactsAdd')}` : '')}
              onClick={onEditContacts ? () => onEditContacts(domain) : undefined} name={named(t('ccx.contactsMissing'))}>
              <Users aria-hidden="true" /> {t('ccx.contactsMissing')}
            </Chip>
          ) : onEditContacts ? (
            <Chip tone="muted" title={`${contactsTip}\n${t('ccx.contactsEdit')}`} onClick={() => onEditContacts(domain)}
              name={named(contactsText)}>
              <Users aria-hidden="true" /> {contactsText}
            </Chip>
          ) : (
            // Düzenleme yetkisi yok: kişi listesi dokununca da açılan açıklamada (yalnız-hover bilgi yok).
            <HintPopover content={contactsTip} aria-label={named(contactsText)}
              triggerClassName={cn(CARD_LAYER, TOUCH_CHIP, 'rounded-full pointer-coarse:min-h-0')}>
              <Badge variant="outline" data-tone="muted" className={cn(CHIP, CHIP_TONE.muted)}>
                <Users aria-hidden="true" /> {contactsText}
              </Badge>
            </HintPopover>
          ))}
        </div>
      )}
    </div>
  )
}

export default memo(CertificateCardExtras)
