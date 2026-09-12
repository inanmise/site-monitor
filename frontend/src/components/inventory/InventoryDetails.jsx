import { useState, useEffect } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Check } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import CopyButton from '../ui/CopyButton.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

/** Serbest metin icindeki e-posta belirteci — mail sablonundaki EMAIL_IN_TEXT ile ayni gevseklik. */
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/

/**
 * Sorumlu ekip degeri: "Ad Soyad - ad.soyad@example.com" gibi serbest metin.
 * Icinde e-posta varsa YALNIZ o parca mailto baglantisi olur, gerisi duz metin kalir;
 * ayrica adres tek tikla kopyalanabilir (destege basvururken yazim hatasi olmasin).
 */
function ContactValue({ value }) {
  const raw = (value ?? '').trim()
  if (!raw) return '—'
  const m = raw.match(EMAIL_RE)
  if (!m) return raw
  const addr = m[0]
  const before = raw.slice(0, m.index)
  const after = raw.slice(m.index + addr.length)
  return (
    <>
      {before}
      <a href={`mailto:${addr}`}>{addr}</a>
      {after}
      <CopyButton value={addr} />
    </>
  )
}

function ShowField({ label, value, mono, full }) {
  return (
    <div className={`show-field${full ? ' show-field-full' : ''}`}>
      <span className="show-field-label">{label}</span>
      <span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value ?? '—'}</span>
    </div>
  )
}

/**
 * Envanter kaydının salt-okunur detay gövdesi (.show-body + .show-footer) — Sertifika
 * Envanteri "Göster" ile BİREBİR. Hem InventoryManager'ın Göster modalında hem de
 * CertificateModal'ın "Envanter Bilgileri" tab'ında kullanılır (tek kaynak).
 * teamMap verilirse team_id → ad ondan çözülür (Envanter ekranı); verilmezse sunucunun
 * döndürdüğü team_name kullanılır (kart modalı — USER rolü tüm takım listesini çekemez).
 */
export function InventoryDetails({ record, teamMap }) {
  const t = useT()
  const { theme } = useTheme()
  if (!record) return null
  const teamName = teamMap
    ? (teamMap[String(record.team_id)] ?? record.team_name ?? '—')
    : (record.team_name ?? '—')
  return (
    <>
      <div className="show-body">

        {/* Temel Bilgiler */}
        <div className="show-section-header">{t('inv.sectionBasic')}</div>
        <div className="show-grid-2">
          <ShowField label={t('inv.formDomain')}  value={record.domain} mono />
          <ShowField label={t('inv.formPort')}    value={record.port || 443} />
          <ShowField label={t('inv.formTeam')}    value={teamName} />
          <ShowField label={t('inv.formTier')}    value={
            record.tier
              ? `T${record.tier} — ${t(`inv.tier${record.tier}`)}`
              : t('inv.tierNone')
          } />
          <ShowField label={t('inv.formPurchasedBy')} value={record.purchased_by || '—'} />
          <ShowField label={t('inv.formTlsMode')} value={
            record.tls_mode === 'browser' ? t('inv.tlsModeBrowser')
            : record.tls_mode === 'default' ? t('inv.tlsModeDefault')
            : t('inv.tlsModeInherit')
          } />
          <ShowField label={t('inv.formInterval')} value={
            ({ 1: t('inv.interval1h'), 6: t('inv.interval6h'), 12: t('inv.interval12h'), 24: t('inv.interval24h'), 168: t('inv.interval168h') })[record.check_interval_hours]
            || t('inv.intervalInherit')
          } />
        </div>

        {/* Sorumlu Ekipler — sertifikayi kimin yenileyecegi (yonlendirme DEGIL, bilgilendirme) */}
        <div className="show-section-header">{t('inv.sectionContacts')}</div>
        {CONTACT_FIELDS.some(({ key }) => (record[key] ?? '').trim()) ? (
          <div className="show-grid-2">
            {CONTACT_FIELDS.filter(({ key }) => (record[key] ?? '').trim()).map(({ key, labelKey }) => (
              <ShowField key={key} label={t(labelKey)} value={<ContactValue value={record[key]} />} />
            ))}
          </div>
        ) : (
          // Bos basliktan sonra bos izgara birakmak "veri yuklenmedi" hissi verir; durumu ACIKCA yaz.
          <span className="field-hint">{t('inv.contactsEmpty')}</span>
        )}

        {/* Operasyonel Bilgiler */}
        <div className="show-section-header">{t('inv.sectionOps')}</div>
        <div className="show-yn-grid">
          {INVENTORY_FLAGS.map(({ key, labelKey }) => {
            const val = record[key]
            return (
              <div key={key} className={`show-yn-cell${val ? ' is-yes' : ''}`}>
                <span className="show-yn-label">{t(labelKey)}</span>
                <span className={`show-yn-badge ${val ? 'show-yn-yes' : 'show-yn-no'}`}>
                  {val && <Check size={13} strokeWidth={3} />}
                  {val ? t('inv.yes') : t('inv.no')}
                </span>
              </div>
            )
          })}
        </div>

        {record.change_description && (
          <div className="show-field show-field-full">
            <span className="show-field-label">{t('inv.formChangeDesc')}</span>
            <div className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{record.change_description}</ReactMarkdown>
            </div>
          </div>
        )}

        {/* Gelişmiş */}
        {(record.expected_fingerprint || record.expected_subject) && (
          <>
            <div className="show-section-header">{t('inv.sectionAdv')}</div>
            <div className="show-grid-1">
              {record.expected_fingerprint && (
                <ShowField label={t('inv.formFP')} value={record.expected_fingerprint} mono full />
              )}
              {record.expected_subject && (
                <ShowField label={t('inv.formSubject')} value={record.expected_subject} mono full />
              )}
            </div>
          </>
        )}

      </div>

      {/* Footer — metadata */}
      <div className="show-footer">
        {record.created_at && (
          <span>{t('inv.metaCreated')}: {formatDate(record.created_at)}</span>
        )}
        {record.updated_at && (
          <span>{t('inv.metaUpdated')}: {formatDate(record.updated_at)}</span>
        )}
      </div>
    </>
  )
}

/**
 * CertificateModal'daki "Envanter Bilgileri" tab'ı — domain'e göre envanter kaydını
 * kendisi çeker (AlertHistory/NotesTab deseni). İzin/kapsam backend'de; kayıt yoksa
 * (ya da kapsam dışıysa) boş durum gösterir. .show-body kendi padding'ini taşıdığından
 * içerik .modal-body'ye sarılmaz (çift padding olmasın).
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
  if (!record) {
    return (
      <div className="modal-body">
        <div className="empty-state">{t('modal.inventoryEmpty')}</div>
      </div>
    )
  }
  return <InventoryDetails record={record} />
}
