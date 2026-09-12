import { useRef, useState } from 'react'
import { useT } from '../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import SmtpSettings from './SmtpSettings'
import LdapSettings from './LdapSettings'
import GeneralSettings from './GeneralSettings'
import MonitorGroups from './MonitorGroups'
import SecretTools from './SecretTools'
import DatabaseInfo from './DatabaseInfo'
import WeeklyAvailabilitySettings from './WeeklyAvailabilitySettings'
import ConfigHealthCard from './ConfigHealthCard.jsx'
import CertInventoryReportSettings from './CertInventoryReportSettings'
import StormSettings from './StormSettings'
import LoginAnomalySettings from './LoginAnomalySettings'
import DomainDiagnostics from './DomainDiagnostics'
import BrandingSettings from './BrandingSettings'
import RetentionSettings from './RetentionSettings'
import UserPushSettings from './UserPushSettings'
import AlertBanner from '../ui/AlertBanner.jsx'

// Left-menu sections. More land in later phases.
const SECTIONS = [
  { id: 'general', labelKey: 'settings.navGeneral' },
  { id: 'branding', labelKey: 'settings.navBranding' },
  { id: 'monitorgroups', labelKey: 'settings.navMonitorGroups' },
  { id: 'smtp', labelKey: 'settings.navSmtp' },
  { id: 'weeklyavail', labelKey: 'settings.navWeeklyAvail' },
  { id: 'certinvreport', labelKey: 'settings.navCertInvReport' },
  { id: 'storm', labelKey: 'settings.navStorm' },
  { id: 'userpush', labelKey: 'settings.navUserPush' },
  { id: 'loginanomaly', labelKey: 'settings.navLoginAnomaly' },
  { id: 'ldap', labelKey: 'settings.navLdap' },
  { id: 'domaindiag', labelKey: 'settings.navDomainDiag' },
  { id: 'retention', labelKey: 'settings.navRetention' },
  { id: 'database', labelKey: 'settings.navDatabase' },
  { id: 'secrets', labelKey: 'settings.navSecrets' },
]

const DEFAULT_SECTION = 'general'
const SECTION_IDS = new Set(SECTIONS.map((s) => s.id))

/** ?sec= yalnız bilinen bölüm anahtarlarını kabul eder (App.jsx'teki VALID_TABS deseni). */
function initialSection() {
  const s = readUrlParam('sec')
  return s && SECTION_IDS.has(s) ? s : DEFAULT_SECTION
}

/**
 * Kapsamlı müdür (AD ADMIN, globalAdmin=false) için KİLİTLİ bölümler: kimlik bilgisi/sır taşıyan
 * dört yüzey. Backend aynı dördü requireNotScopedAdmin ile 403'ler; burada 403 dolu bir ekran yerine
 * "yalnız global yönetici" notu çizilir. Sekme listede kalır (var olduğu görülsün).
 */
const GLOBAL_ONLY_SECTIONS = new Set(['smtp', 'ldap', 'database', 'secrets'])

export default function AdminSettings({ globalAdmin = true }) {
  const t = useT()
  const [active, setActive] = useState(initialSection)
  const tabRefs = useRef({})

  // Derin bağlantı: /?tab=settings&sec=ldap doğrudan LDAP bölümünü açar. Varsayılan bölümde
  // param silinir (URL temiz kalır); yazma replaceState ile — sekmelerin pushState'i bozulmaz.
  useUrlQuerySync({ sec: active === DEFAULT_SECTION ? null : active })

  /**
   * Klavye: oklar/Home/End yalnız ODAĞI taşır, seçimi DEĞİŞTİRMEZ (manuel aktivasyon).
   * Otomatik aktivasyon (odak = seçim) her panelin mount'ta API çağırmasına yol açardı —
   * 13 bölümü ok tuşuyla geçmek onlarca gereksiz istek üretirdi.
   * Yatay oklar da dinleniyor: 760px altında menü yatay diziliyor.
   */
  function onKeyDown(e) {
    const idx = SECTIONS.findIndex((s) => s.id === e.currentTarget.dataset.id)
    let next = null
    if (e.key === 'ArrowDown' || e.key === 'ArrowRight') next = (idx + 1) % SECTIONS.length
    else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') next = (idx - 1 + SECTIONS.length) % SECTIONS.length
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = SECTIONS.length - 1
    if (next == null) return
    e.preventDefault()
    tabRefs.current[SECTIONS[next].id]?.focus()
  }

  return (
    <>
    {/* Yapılandırma sağlığı — 12 bölümün üstünde tek kart (2026-09-12, #25); yalnız global admin ucu */}
    {globalAdmin && <ConfigHealthCard onOpenSection={setActive} />}
    <div className="settings-layout">
      <aside className="settings-menu" role="tablist" aria-orientation="vertical"
             aria-label={t('settings.navHeader')}>
        <div className="settings-menu-header" aria-hidden="true">{t('settings.navHeader')}</div>
        {SECTIONS.map((s) => {
          const selected = active === s.id
          return (
            <button
              key={s.id}
              type="button"
              role="tab"
              id={`settings-tab-${s.id}`}
              data-id={s.id}
              ref={(el) => { tabRefs.current[s.id] = el }}
              aria-selected={selected}
              aria-controls="settings-panel"
              // Roving tabindex: 13 bölüm Tab sırasını doldurmasın — gruba tek Tab ile girilir.
              tabIndex={selected ? 0 : -1}
              className={`settings-menu-item${selected ? ' active' : ''}`}
              onClick={() => setActive(s.id)}
              onKeyDown={onKeyDown}
            >
              {t(s.labelKey)}
            </button>
          )
        })}
      </aside>

      <section className="settings-pane" role="tabpanel" id="settings-panel"
               aria-labelledby={`settings-tab-${active}`} tabIndex={0}>
        {!globalAdmin && GLOBAL_ONLY_SECTIONS.has(active) && (
          <div className="admin-section" data-testid="settings-global-only">
            <AlertBanner tone="warning" title={t('settings.globalOnlyTitle')} role="status">
              {t('settings.globalOnlyBody')}
            </AlertBanner>
          </div>
        )}
        {(globalAdmin || !GLOBAL_ONLY_SECTIONS.has(active)) && <>
        {active === 'general' && <GeneralSettings />}
        {active === 'branding' && <BrandingSettings />}
        {active === 'monitorgroups' && <MonitorGroups />}
        {active === 'smtp' && <SmtpSettings />}
        {active === 'weeklyavail' && <WeeklyAvailabilitySettings />}
        {active === 'certinvreport' && <CertInventoryReportSettings />}
        {active === 'storm' && <StormSettings />}
        {active === 'userpush' && <UserPushSettings />}
        {active === 'loginanomaly' && <LoginAnomalySettings />}
        {active === 'ldap' && <LdapSettings />}
        {active === 'domaindiag' && <DomainDiagnostics />}
        {active === 'retention' && <RetentionSettings />}
        {active === 'database' && <DatabaseInfo />}
        {active === 'secrets' && <SecretTools />}
        </>}
      </section>
    </div>
    </>
  )
}
