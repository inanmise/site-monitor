import { useState, useEffect } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Check, Mail, Inbox } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import CopyButton from '../ui/CopyButton.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { TierBadge, contactInitials } from './InventoryTable.jsx'
import { Avatar, AvatarFallback } from '@/components/shadcn/avatar'

/** Serbest metin icindeki e-posta belirteci — mail sablonundaki EMAIL_IN_TEXT ile ayni gevseklik. */
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/

/**
 * Sorumlu ekip degeri: "Ad Soyad - ad.soyad@example.com" gibi serbest metin.
 * Icinde e-posta varsa YALNIZ o parca mailto baglantisi olur, gerisi duz metin kalir;
 * ayrica adres tek tikla kopyalanabilir (destege basvururken yazim hatasi olmasin).
 */
function ContactValue({ value }) {
  const t = useT()
  const raw = (value ?? '').trim()
  if (!raw) return '—'
  const m = raw.match(EMAIL_RE)
  if (!m) return raw
  const addr = m[0]
  const before = raw.slice(0, m.index)
  const after = raw.slice(m.index + addr.length)
  return (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-1">
      <span className="break-all">{before}<a href={`mailto:${addr}`} className="text-primary underline underline-offset-2">{addr}</a>{after}</span>
      <CopyButton value={addr} label={t('inv.copyEmail')} copiedLabel={t('err.copied')} buttonSize="icon-xs" variant="ghost" className="pointer-coarse:size-10" />
    </span>
  )
}

/** Bölüm başlığı — küçük büyük harfli, alt çizgili (eski .show-section-header'ın shadcn/Tailwind karşılığı). */
export function DetailSection({ title, children, className }) {
  return (
    <section className={cn('flex min-w-0 flex-col gap-2.5', className)}>
      <h3 className="m-0 border-b pb-1.5 text-[11px] font-bold tracking-[.08em] text-muted-foreground uppercase">{title}</h3>
      {children}
    </section>
  )
}

/** Etiket/değer çifti (dl ızgarası). `mono` sabit genişlikli yazı; `full` iki sütunu kaplar. */
export function Fact({ label, value, mono, full }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', full && 'sm:col-span-2')}>
      <dt className="text-[10.5px] font-bold tracking-[.06em] text-muted-foreground uppercase">{label}</dt>
      <dd className={cn('m-0 min-w-0 text-sm leading-snug break-words', mono && 'font-mono text-[12.5px]')}>{value ?? '—'}</dd>
    </div>
  )
}
export function FactGrid({ children, className }) {
  return <dl className={cn('m-0 grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2', className)}>{children}</dl>
}

/**
 * Envanter kaydının salt-okunur detay gövdesi — çekmecenin "Genel bakış" sekmesi ve CertificateModal'ın
 * "Envanter Bilgileri" sekmesi (tek kaynak). 2026-09-27: shadcn/Tailwind — temel bilgiler dl ızgarası, sorumlu ekipler
 * kart (baş harf avatarı + mailto + kopyala), operasyonel bayraklar rozet dizisi (açıklar dolu), notlar markdown kutusu.
 * teamMap verilirse team_id → ad ondan çözülür (Envanter ekranı); verilmezse sunucunun döndürdüğü team_name
 * kullanılır (kart modalı — USER rolü tüm takım listesini çekemez).
 */
export function InventoryDetails({ record, teamMap }) {
  const t = useT()
  const { theme } = useTheme()
  if (!record) return null
  const teamName = teamMap
    ? (teamMap[String(record.team_id)] ?? record.team_name ?? null)
    : (record.team_name ?? null)
  const contacts = CONTACT_FIELDS.filter(({ key }) => (record[key] ?? '').trim())
  const flagsOn = INVENTORY_FLAGS.filter(({ key }) => !!record[key])
  const flagsOff = INVENTORY_FLAGS.filter(({ key }) => !record[key])
  return (
    <div data-slot="inv-details" className="flex min-w-0 flex-col gap-5">
      {/* Temel bilgiler */}
      <DetailSection title={t('inv.drawerKeyFacts')}>
        <FactGrid>
          <Fact label={t('inv.formDomain')} value={record.domain} mono />
          <Fact label={t('inv.formPort')} value={record.port || 443} />
          <Fact label={t('inv.formTeam')} value={teamName ? <TeamBadge teamId={record.team_id} teamName={teamName} /> : <span className="text-amber-700 dark:text-amber-400">{t('inv.hy.no_team')}</span>} />
          <Fact label={t('inv.formTier')} value={record.tier
            ? <span className="inline-flex items-center gap-1.5"><TierBadge tier={record.tier} /> {t(`inv.tier${record.tier}`)}</span>
            : t('inv.tierNone')} />
          <Fact label={t('inv.formPlatform')} value={record.platform ? `${record.platform}${record.platform_detail ? ' · ' + record.platform_detail : ''}` : '—'} />
          <Fact label={t('inv.formPurchasedBy')} value={record.purchased_by || '—'} />
          <Fact label={t('inv.formGroup')} value={record.group_name ? <Badge variant="outline" className="font-normal">{record.group_name}</Badge> : '—'} />
          <Fact label={t('inv.formTags')} value={record.tags
            ? <span className="flex flex-wrap gap-1">{String(record.tags).split(',').map((x) => x.trim()).filter(Boolean).map((tag) => <Badge key={tag} variant="secondary" className="font-normal">{tag}</Badge>)}</span>
            : '—'} />
          <Fact label={t('inv.formTlsMode')} value={
            record.tls_mode === 'browser' ? t('inv.tlsModeBrowser')
            : record.tls_mode === 'default' ? t('inv.tlsModeDefault')
            : t('inv.tlsModeInherit')
          } />
          <Fact label={t('inv.formInterval')} value={
            ({ 1: t('inv.interval1h'), 6: t('inv.interval6h'), 12: t('inv.interval12h'), 24: t('inv.interval24h'), 168: t('inv.interval168h') })[record.check_interval_hours]
            || t('inv.intervalInherit')
          } />
          {record.description && <Fact label={t('inv.formDesc')} value={record.description} full />}
        </FactGrid>
      </DetailSection>

      {/* Sorumlu Ekipler — sertifikayi kimin yenileyecegi (yonlendirme DEGIL, bilgilendirme) */}
      <DetailSection title={t('inv.sectionContacts')}>
        {contacts.length > 0 ? (
          <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 sm:grid-cols-2">
            {contacts.map(({ key, labelKey }) => (
              <li key={key}>
                <Card data-slot="inv-contact-card" className="flex-row items-start gap-3 rounded-lg px-3 py-2.5 shadow-none">
                  <Avatar className="mt-0.5"><AvatarFallback className="bg-sky-100 text-xs font-bold text-sky-800 dark:bg-sky-900 dark:text-sky-100">{contactInitials(record[key])}</AvatarFallback></Avatar>
                  <div className="min-w-0 flex-1">
                    <div className="text-[10.5px] font-bold tracking-[.06em] text-muted-foreground uppercase">{t(labelKey)}</div>
                    <div className="text-sm leading-snug"><ContactValue value={record[key]} /></div>
                  </div>
                  <Mail aria-hidden="true" className="mt-1 size-4 shrink-0 text-muted-foreground/60" />
                </Card>
              </li>
            ))}
          </ul>
        ) : (
          // Bos basliktan sonra bos izgara birakmak "veri yuklenmedi" hissi verir; durumu ACIKCA yaz.
          <p className="m-0 text-sm text-muted-foreground">{t('inv.contactsEmpty')}</p>
        )}
      </DetailSection>

      {/* Operasyonel Bilgiler — açık bayraklar dolu rozet, kapalılar soluk kenarlı (13'ü de görünür, ne olmadığı da okunur) */}
      <DetailSection title={t('inv.sectionOps')}>
        <div data-slot="inv-flag-badges" className="flex flex-wrap gap-1.5">
          {flagsOn.map(({ key, labelKey }) => (
            <Badge key={key} data-flag={key} data-on="true" className="gap-1 bg-success/15 text-success dark:bg-success/20"><Check aria-hidden="true" strokeWidth={3} />{t(labelKey)}</Badge>
          ))}
          {flagsOff.map(({ key, labelKey }) => (
            <Badge key={key} data-flag={key} data-on="false" variant="outline" className="font-normal text-muted-foreground">{t(labelKey)}</Badge>
          ))}
        </div>
        {flagsOn.length === 0 && <p className="m-0 text-xs text-muted-foreground">{t('inv.flagsNone')}</p>}
      </DetailSection>

      {/* Notlar (değişiklik açıklaması, markdown) */}
      <DetailSection title={t('inv.drawerNotes')}>
        {record.change_description ? (
          <div data-slot="inv-notes" className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{record.change_description}</ReactMarkdown>
          </div>
        ) : <p className="m-0 text-sm text-muted-foreground">{t('inv.drawerNoNotes')}</p>}
      </DetailSection>

      {/* Gelişmiş */}
      {(record.expected_fingerprint || record.expected_subject) && (
        <DetailSection title={t('inv.sectionAdv')}>
          <FactGrid>
            {record.expected_fingerprint && <Fact label={t('inv.formFP')} value={record.expected_fingerprint} mono full />}
            {record.expected_subject && <Fact label={t('inv.formSubject')} value={record.expected_subject} mono full />}
          </FactGrid>
        </DetailSection>
      )}

      {/* Künye */}
      <p className="m-0 flex flex-wrap gap-x-5 gap-y-1 border-t pt-3 text-[11px] text-muted-foreground">
        {record.created_at && <span>{t('inv.metaCreated')}: {formatDate(record.created_at)}</span>}
        {record.updated_at && <span>{t('inv.metaUpdated')}: {formatDate(record.updated_at)}{record.updated_by_name ? ` · ${record.updated_by_name}` : ''}</span>}
      </p>
    </div>
  )
}

/**
 * CertificateModal'daki "Envanter Bilgileri" tab'ı — domain'e göre envanter kaydını
 * kendisi çeker (AlertHistory/NotesTab deseni). İzin/kapsam backend'de; kayıt yoksa
 * (ya da kapsam dışıysa) boş durum gösterir (ui/StatusBlock).
 */
export function InventoryTab({ domain }) {
  const t = useT()
  const [record, setRecord] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!domain) return
    let alive = true
    setLoading(true)
    setRecord(null)
    api.admin.getInventoryByDomain(domain)
      .then((res) => {
        if (!alive) return
        setRecord(res?.success ? (res.data ?? null) : null)
        setLoading(false)
      })
      .catch(() => { if (alive) { setRecord(null); setLoading(false) } })
    return () => { alive = false }
  }, [domain])

  if (loading) return <LoadingBlock label={t('modal.loading')} fullWidth />
  if (!record) return <StatusBlock tone="neutral" icon={Inbox} title={t('modal.inventoryEmpty')} />
  return <div className="p-4 sm:p-5"><InventoryDetails record={record} /></div>
}
