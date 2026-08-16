import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { useTeamOptions } from '../hooks/useTeamOptions.js'

/**
 * TAKIM FİLTRESİ SEÇENEKLERİ — altı izleme sayfasında birebir kopyalanmış bloktu ve
 * <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce ölçüldü: bloktan {@code __none__} dalını
 * silmek 780 testin HİÇBİRİNİ kırmadı. Yani "takımsız monitörleri göster" seçeneği altı sayfada
 * birden sessizce kaybolabilirdi ve kimse fark etmezdi — takımsız monitörler (envanter-türevi
 * port/dns kayıtları buna dahil) filtrelenemez hâle gelirdi.
 *
 * Çıkarımın asıl kazancı bu: tek dosyadaki bu testler artık altı sayfayı birden kilitliyor.
 */
const wrapper = ({ children }) => <LangProvider>{children}</LangProvider>
const run = (monitors) => renderHook(() => useTeamOptions(monitors), { wrapper }).result.current

describe('useTeamOptions', () => {
  it('ilk seçenek DAİMA "tümü" — filtre sıfırlama yolu kaybolmasın', () => {
    const { teamOptions } = run([{ team_name: 'SY-B' }])
    expect(teamOptions[0].value).toBe('all')
  })

  it('takım adları localeCompare ile sıralanır (Türkçe harf sırası)', () => {
    const { teamOptions } = run([
      { team_name: 'Zeta' }, { team_name: 'Çınar' }, { team_name: 'Ada' }, { team_name: 'Zeta' },
    ])
    // mükerrer ad tekilleşir; 'Çınar' ASCII'de 'Z'den sonra gelirdi — localeCompare şart
    expect(teamOptions.map(o => o.value)).toEqual(['all', 'Ada', 'Çınar', 'Zeta'])
  })

  it('TAKIMSIZ monitör varsa __none__ seçeneği EN SONA eklenir', () => {
    const { teamOptions } = run([{ team_name: 'SY-A' }, { /* takımsız */ }, { team_name: 'SY-B' }])
    expect(teamOptions.map(o => o.value)).toEqual(['all', 'SY-A', 'SY-B', '__none__'])
  })

  it('takımsız monitör YOKSA __none__ seçeneği hiç çıkmaz (boş filtre üretme)', () => {
    const { teamOptions } = run([{ team_name: 'SY-A' }])
    expect(teamOptions.map(o => o.value)).toEqual(['all', 'SY-A'])
  })

  it('hasTeamOptions: GERÇEK takım varsa true — all/__none__ sayılmaz', () => {
    expect(run([{ team_name: 'SY-A' }]).hasTeamOptions).toBe(true)
    // yalnız takımsız monitörler varsa filtre çubuğu gösterilmemeli
    expect(run([{}, {}]).hasTeamOptions).toBe(false)
    expect(run([]).hasTeamOptions).toBe(false)
  })

  it('boş liste çökmez ve yalnız "tümü" döner', () => {
    const { teamOptions } = run([])
    expect(teamOptions).toHaveLength(1)
    expect(teamOptions[0].value).toBe('all')
  })

  it('boş/null takım adı TAKIMSIZ sayılır (boş etiketli seçenek üretilmez)', () => {
    const { teamOptions } = run([{ team_name: '' }, { team_name: null }, { team_name: 'SY-A' }])
    expect(teamOptions.map(o => o.value)).toEqual(['all', 'SY-A', '__none__'])
  })
})
