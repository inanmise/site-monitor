import { useState } from 'react'
import { useT } from '../../i18n/index.jsx'
import SmtpSettings from './SmtpSettings'
import LdapSettings from './LdapSettings'
import GeneralSettings from './GeneralSettings'
import MonitorGroups from './MonitorGroups'
import SecretTools from './SecretTools'
import DatabaseInfo from './DatabaseInfo'
import WeeklyAvailabilitySettings from './WeeklyAvailabilitySettings'
import CertInventoryReportSettings from './CertInventoryReportSettings'
import StormSettings from './StormSettings'
import LoginAnomalySettings from './LoginAnomalySettings'
import DomainDiagnostics from './DomainDiagnostics'
import BrandingSettings from './BrandingSettings'
import RetentionSettings from './RetentionSettings'

// Left-menu sections. More land in later phases.
const SECTIONS = [
  { id: 'general', labelKey: 'settings.navGeneral' },
  { id: 'branding', labelKey: 'settings.navBranding' },
  { id: 'monitorgroups', labelKey: 'settings.navMonitorGroups' },
  { id: 'smtp', labelKey: 'settings.navSmtp' },
  { id: 'weeklyavail', labelKey: 'settings.navWeeklyAvail' },
  { id: 'certinvreport', labelKey: 'settings.navCertInvReport' },
  { id: 'storm', labelKey: 'settings.navStorm' },
  { id: 'loginanomaly', labelKey: 'settings.navLoginAnomaly' },
  { id: 'ldap', labelKey: 'settings.navLdap' },
  { id: 'domaindiag', labelKey: 'settings.navDomainDiag' },
  { id: 'retention', labelKey: 'settings.navRetention' },
  { id: 'database', labelKey: 'settings.navDatabase' },
  { id: 'secrets', labelKey: 'settings.navSecrets' },
]

export default function AdminSettings() {
  const t = useT()
  const [active, setActive] = useState('general')

  return (
    <div className="settings-layout">
      <aside className="settings-menu">
        <div className="settings-menu-header">{t('settings.navHeader')}</div>
        {SECTIONS.map((s) => (
          <button
            key={s.id}
            className={`settings-menu-item${active === s.id ? ' active' : ''}`}
            onClick={() => setActive(s.id)}
          >
            {t(s.labelKey)}
          </button>
        ))}
      </aside>

      <section className="settings-pane">
        {active === 'general' && <GeneralSettings />}
        {active === 'branding' && <BrandingSettings />}
        {active === 'monitorgroups' && <MonitorGroups />}
        {active === 'smtp' && <SmtpSettings />}
        {active === 'weeklyavail' && <WeeklyAvailabilitySettings />}
        {active === 'certinvreport' && <CertInventoryReportSettings />}
        {active === 'storm' && <StormSettings />}
        {active === 'loginanomaly' && <LoginAnomalySettings />}
        {active === 'ldap' && <LdapSettings />}
        {active === 'domaindiag' && <DomainDiagnostics />}
        {active === 'retention' && <RetentionSettings />}
        {active === 'database' && <DatabaseInfo />}
        {active === 'secrets' && <SecretTools />}
      </section>
    </div>
  )
}
