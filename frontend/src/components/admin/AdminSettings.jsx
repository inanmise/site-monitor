import { useState } from 'react'
import { useT } from '../../i18n/index.jsx'
import SmtpSettings from './SmtpSettings'
import LdapSettings from './LdapSettings'
import GeneralSettings from './GeneralSettings'

// Left-menu sections. More land in later phases.
const SECTIONS = [
  { id: 'general', labelKey: 'settings.navGeneral' },
  { id: 'smtp', labelKey: 'settings.navSmtp' },
  { id: 'ldap', labelKey: 'settings.navLdap' },
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
        {active === 'smtp' && <SmtpSettings />}
        {active === 'ldap' && <LdapSettings />}
      </section>
    </div>
  )
}
