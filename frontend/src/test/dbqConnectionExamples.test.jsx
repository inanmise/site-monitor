import { describe, it, expect, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ConnectionForm from '../components/dbquery/ConnectionForm.jsx'
import { emptyForm } from '../components/dbquery/connectionModel.js'
import { CONNECTION_EXAMPLES } from '../components/dbquery/connectionExamples.js'

/**
 * Bağlantı formunda örnekler (2026-11-05) — kapalı başlar; seçili türün senaryoları alan alan değer + kopyalanabilir dize
 * gösterir; tür / Oracle kipi değişince örnekler ve "Alan alan ne yazılır" listesi değişir; alan yer tutucuları türe göre;
 * örnekler formu DEĞİŞTİRMEZ; parola hiçbir yerde yok.
 */
function FormHarness({ initial, showPaste = true }) {
  const [f, setF] = useState(initial)
  return <ConnectionForm form={f} onChange={setF} showPaste={showPaste} />
}

const examples = () => document.querySelector('[data-slot="dbq-conn-examples"]')
const openExamples = () => fireEvent.click(document.querySelector('[data-slot="dbq-conn-examples-toggle"]'))

beforeEach(() => { localStorage.setItem(LANG_STORAGE_KEY, 'tr') })

describe('bağlantı formu — örnekler', () => {
  it('kapalı başlar; açılınca PostgreSQL senaryoları, alan değerleri, dize ve kopyala düğmesi', () => {
    render(<FormHarness initial={emptyForm(1)} />)
    const toggle = document.querySelector('[data-slot="dbq-conn-examples-toggle"]')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveTextContent('Bağlantı örnekleri — PostgreSQL')
    expect(document.querySelector('[data-slot="dbq-conn-example"]')).toBeNull()
    openExamples()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const cards = examples().querySelectorAll('[data-slot="dbq-conn-example"]')
    expect([...cards].map((c) => c.getAttribute('data-id'))).toEqual(CONNECTION_EXAMPLES.POSTGRESQL.map((e) => e.id))
    const first = within(cards[0])
    expect(first.getByText('SSL zorunlu (önerilen)')).toBeInTheDocument()
    const host = cards[0].querySelector('[data-slot="dbq-conn-example-field"][data-field="host"]')
    expect(host).toHaveTextContent('Sunucu')
    expect(host).toHaveTextContent('pg-rapor-01.example.com')
    expect(cards[0].querySelector('[data-field="ssl_mode"]')).toHaveTextContent('require')
    expect(cards[0].querySelector('[data-slot="dbq-conn-example-string"]')).toHaveTextContent(CONNECTION_EXAMPLES.POSTGRESQL[0].conn)
    expect(first.getByRole('button', { name: 'Bağlantı dizesini kopyala: SSL zorunlu (önerilen)' })).toBeInTheDocument()
    expect(examples().querySelector('[data-slot="dbq-conn-examples-paste"]')).not.toBeNull()
    // Alan alan ne yazılır
    const guide = examples().querySelector('[data-slot="dbq-conn-guide"]')
    expect(guide).toHaveTextContent('Alan alan ne yazılır')
    expect(guide.querySelector('[data-field="port"]')).toHaveTextContent('PostgreSQL için varsayılan 5432')
    expect(guide.querySelector('[data-field="database_name"]')).toHaveTextContent('Örn. odeme')
    expect(guide.querySelector('[data-field="mssql_instance"]')).toBeNull()
    expect(document.body.textContent).not.toMatch(/password=|pwd=/i)
  })

  it('tür değişince örnekler değişir: SQL Server adlandırılmış örnek kendi portuyla; açık anahtar "Açık"', () => {
    render(<FormHarness initial={emptyForm(1)} />)
    openExamples()
    fireEvent.click(screen.getByRole('button', { name: 'SQL Server' }))
    expect(examples()).toHaveAttribute('data-type', 'MSSQL')
    const named = examples().querySelector('[data-slot="dbq-conn-example"][data-id="mssql-named"]')
    expect(named.querySelector('[data-field="mssql_instance"]')).toHaveTextContent('SQL01')
    expect(named.querySelector('[data-field="port"]')).toHaveTextContent('14331')
    expect(named).toHaveTextContent('KENDİ TCP portunu')
    expect(named.querySelector('[data-field="mssql_encrypt"]')).toHaveTextContent('Açık')
    expect(examples().querySelector('[data-slot="dbq-conn-guide"] [data-field="mssql_instance"]')).not.toBeNull()
  })

  it('Oracle: SID kipinde veritabanı alanı ve rehber SID der; TCPS örneği 2484', () => {
    render(<FormHarness initial={emptyForm(1, 'ORACLE')} />)
    openExamples()
    const sid = examples().querySelector('[data-id="ora-sid"]')
    expect(sid.querySelector('[data-field="database_name"]')).toHaveTextContent('SID')
    expect(sid.querySelector('[data-field="oracle_mode"]')).toHaveTextContent('SID')
    expect(examples().querySelector('[data-id="ora-tcps"] [data-field="port"]')).toHaveTextContent('2484')
    expect(examples().querySelector('[data-slot="dbq-conn-guide"] [data-field="database_name"]')).toHaveTextContent('ORCLPDB1.example.com')
    fireEvent.click(screen.getByRole('button', { name: 'SID' }))
    expect(examples().querySelector('[data-slot="dbq-conn-guide"] [data-field="database_name"]')).toHaveTextContent('Örn. ORCL')
  })

  it('alan yer tutucuları ve ipuçları türe göre; örnekler formu değiştirmez', () => {
    render(<FormHarness initial={emptyForm(1)} />)
    expect(screen.getByRole('textbox', { name: /^Sunucu/ })).toHaveAttribute('placeholder', 'pg-rapor-01.example.com')
    expect(screen.getByRole('textbox', { name: /^Veritabanı/ })).toHaveAttribute('placeholder', 'odeme')
    expect(screen.getByRole('textbox', { name: /^Kullanıcı adı/ })).toHaveAttribute('placeholder', 'sitemonitor_ro')
    expect(screen.getByText('Varsayılan 5432')).toBeInTheDocument()
    expect(screen.getByText('Yalnız harf, rakam ve _ . $ # -')).toBeInTheDocument()
    openExamples()
    expect(screen.getByRole('textbox', { name: /^Sunucu/ })).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'SQL Server' }))
    expect(screen.getByRole('textbox', { name: /^Sunucu/ })).toHaveAttribute('placeholder', 'sql-prod-01.example.com')
    expect(screen.getByRole('textbox', { name: /^Örnek adı/ })).toHaveAttribute('placeholder', 'SQL01')
    expect(screen.getByText('Varsayılan 1433')).toBeInTheDocument()
  })

  it('yapıştırma kutusu yoksa "yukarıdaki kutuya yapıştırın" ipucu da yok', () => {
    render(<FormHarness initial={emptyForm(1)} showPaste={false} />)
    openExamples()
    expect(examples().querySelector('[data-slot="dbq-conn-examples-paste"]')).toBeNull()
  })

  it('İngilizce arayüzde başlık ve rehber İngilizce', () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<FormHarness initial={emptyForm(1)} />)
    expect(document.querySelector('[data-slot="dbq-conn-examples-toggle"]')).toHaveTextContent('Connection examples — PostgreSQL')
    openExamples()
    expect(examples().querySelector('[data-slot="dbq-conn-guide"]')).toHaveTextContent('What goes in each field')
  })
})
