import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import LdapSettings from '../components/admin/LdapSettings.jsx'

vi.mock('../api/client', () => ({
  api: {
    admin: {
      getLdapSettings: vi.fn(),
      saveLdapSettings: vi.fn(),
      testLdap: vi.fn(() => Promise.resolve({ success: true, message: 'Bind başarılı' })),
      queryLdapUser: vi.fn(),
    },
  },
}))
import { api } from '../api/client'

const base = {
  enabled: true, host: 'ldap.example.com', port: 636, use_ldaps: true, start_tls: false,
  skip_cert_verification: false, ca_cert_pem: '', bind_dn: 'CN=svc', bind_password_set: true,
  base_dn: 'DC=example', user_search_filter: '(objectclass=person)', user_attribute: 'sAMAccountName',
  email_attribute: 'mail', display_attribute: 'displayName', group_filter: '(objectclass=group)',
  skip_member_of: false, default_role: 'USER', role_mappings: [],
}

describe('LdapSettings — sertifika doğrulama uyarısı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getLdapSettings.mockResolvedValue({ success: true, data: base, secret_key_set: true })
  })

  it('doğrulama açıkken uyarı ve "CA ile doğrulayarak test et" butonu YOK', async () => {
    render(<LdapSettings />)
    await waitFor(() => expect(api.admin.getLdapSettings).toHaveBeenCalled())
    await screen.findByDisplayValue('ldap.example.com')
    expect(screen.queryByText(/doğrulanmıyor|not verified/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /CA ile doğrulayarak|Test with CA/i })).toBeNull()
  })

  it('"atla" açıkken MITM uyarısı + CA PEM yüklüyse "kullanılmıyor" vurgusu görünür', async () => {
    api.admin.getLdapSettings.mockResolvedValue({
      success: true,
      data: { ...base, skip_cert_verification: true, ca_cert_pem: '-----BEGIN CERTIFICATE-----' },
      secret_key_set: true,
    })
    render(<LdapSettings />)
    await waitFor(() => expect(api.admin.getLdapSettings).toHaveBeenCalled())
    await screen.findByDisplayValue('ldap.example.com')
    expect(screen.getByText(/doğrulanmıyor|not verified/i)).toBeInTheDocument()
    expect(screen.getByText(/KULLANILMIYOR|NOT used/i)).toBeInTheDocument()
  })

  it('"CA ile doğrulayarak test et" kaydedilmiş ayarı değiştirmeden verify=true ile test eder', async () => {
    api.admin.getLdapSettings.mockResolvedValue({
      success: true, data: { ...base, skip_cert_verification: true }, secret_key_set: true,
    })
    render(<LdapSettings />)
    await waitFor(() => expect(api.admin.getLdapSettings).toHaveBeenCalled())
    await screen.findByDisplayValue('ldap.example.com')

    fireEvent.click(screen.getByRole('button', { name: /CA ile doğrulayarak|Test with CA/i }))
    await waitFor(() => expect(api.admin.testLdap).toHaveBeenCalledWith(true))
    expect(api.admin.saveLdapSettings).not.toHaveBeenCalled()   // ayar DEĞİŞMEZ
  })
})
