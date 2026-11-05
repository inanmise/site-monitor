import { describe, it, expect } from 'vitest'
import {
  CONNECTION_EXAMPLES, EXAMPLE_FIELD_ORDER, RULES, examplesFor, placeholdersFor, valueFitsRule,
} from '../components/dbquery/connectionExamples.js'
import { parseConnectionString, FILLABLE } from '../components/dbquery/connectionString.js'
import { DB_TYPES, DEFAULT_PORT, ORACLE_TCPS_PORT, PG_SSL_MODES, ORACLE_MODES } from '../components/dbquery/connectionModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Bağlantı örnekleri (2026-11-05) — örnekler gerçek ayrıştırıcıdan AYNI alanlarla geçer, sunucu kurallarına uyar, parola
 * taşımaz; her örneğin TR + EN başlığı / açıklaması vardır.
 */
describe('bağlantı örnekleri', () => {
  it('her tür en az iki örnek taşır; kimlikler benzersiz', () => {
    const ids = []
    for (const type of DB_TYPES) {
      expect(examplesFor(type).length, type).toBeGreaterThanOrEqual(2)
      for (const ex of examplesFor(type)) ids.push(ex.id)
    }
    expect(new Set(ids).size).toBe(ids.length)
    expect(examplesFor('MYSQL')).toEqual([])
  })

  it('her örnek dizesi ayrıştırıcıdan geçer: tür doğru, örneğin alanlarının hepsi aynı değerle dolar, parola yok', () => {
    for (const type of DB_TYPES) {
      for (const ex of examplesFor(type)) {
        const r = parseConnectionString(ex.conn)
        expect(r.ok, `${ex.id}: ${r.error}`).toBe(true)
        expect(r.db_type, ex.id).toBe(type)
        expect(r.hadPassword, ex.id).toBe(false)
        expect(r.notes, ex.id).toEqual([])
        for (const [k, v] of Object.entries(ex.fields)) {
          expect(r.fields[k], `${ex.id}.${k}`).toEqual(v)
        }
        // Dize örnekte gösterilmeyen bir alan doldurmaz (örnek ekranı ile dizenin söylediği aynı)
        for (const k of Object.keys(r.fields)) {
          if (k === 'oracle_mode' && ex.fields.oracle_mode == null) continue
          if (k === 'oracle_tcps' && r.fields[k] === false && ex.fields.oracle_tcps == null) continue
          expect(Object.keys(ex.fields), `${ex.id}: dize "${k}" dolduruyor ama örnekte yok`).toContain(k)
        }
      }
    }
  })

  it('alanlar formun doldurulabilir alanlarından ve gösterim sırasındaki alanlardan oluşur', () => {
    for (const type of DB_TYPES) {
      for (const ex of examplesFor(type)) {
        for (const k of Object.keys(ex.fields)) {
          expect(FILLABLE, `${ex.id}.${k}`).toContain(k)
          expect(EXAMPLE_FIELD_ORDER, `${ex.id}.${k}`).toContain(k)
        }
      }
    }
  })

  it('değerler sunucu kurallarına uyar; kipler / SSL bilinen değerler; varsayılan portlar tutarlı', () => {
    for (const type of DB_TYPES) {
      for (const ex of examplesFor(type)) {
        for (const [k, v] of Object.entries(ex.fields)) expect(valueFitsRule(k, v), `${ex.id}.${k}=${v}`).toBe(true)
        expect(ex.fields.host).toMatch(/\.example\.com$/)
        if (ex.fields.ssl_mode) expect(PG_SSL_MODES).toContain(ex.fields.ssl_mode)
        if (ex.fields.oracle_mode) expect(ORACLE_MODES).toContain(ex.fields.oracle_mode)
      }
      expect(examplesFor(type)[0].fields.port, `${type} ilk örnek varsayılan port`).toBe(DEFAULT_PORT[type])
    }
    expect(CONNECTION_EXAMPLES.ORACLE.find((e) => e.fields.oracle_tcps).fields.port).toBe(ORACLE_TCPS_PORT)
    // SQL Server adlandırılmış örnek: kendi portu (sürücü port verilince örnek adını kullanmaz)
    const named = CONNECTION_EXAMPLES.MSSQL.find((e) => e.fields.mssql_instance)
    expect(named.fields.port).not.toBe(DEFAULT_PORT.MSSQL)
    // Kural yardımcısı yanlış değeri reddeder
    expect(valueFitsRule('database_name', 'odeme;drop')).toBe(false)
    expect(valueFitsRule('username', 'a;b')).toBe(false)
    expect(valueFitsRule('port', 70000)).toBe(false)
    expect(valueFitsRule('host', 'https://x')).toBe(false)
    expect(RULES.nameMax).toBe(120)
  })

  it('hiçbir örnek parola ya da gerçek alan adı taşımaz', () => {
    const text = JSON.stringify(CONNECTION_EXAMPLES)
    expect(text).not.toMatch(/password|pwd|parola/i)
    for (const type of DB_TYPES) {
      for (const ex of examplesFor(type)) {
        // anahtar=değer parolası, URI kullanıcı:parola@ ve Oracle kullanıcı/parola@ biçimleri yok
        expect(ex.conn, ex.id).not.toMatch(/password=|pwd=|:\/\/[^/@]+:[^/@]+@|thin:[^@/]+\/[^@]+@/i)
      }
    }
  })

  it('yer tutucular türe ve Oracle kipine göre', () => {
    expect(placeholdersFor('POSTGRESQL')).toMatchObject({ host: 'pg-rapor-01.example.com', database_name: 'odeme', username: 'sitemonitor_ro' })
    expect(placeholdersFor('MSSQL')).toMatchObject({ host: 'sql-prod-01.example.com', database_name: 'OdemeDB', mssql_instance: 'SQL01' })
    expect(placeholdersFor('ORACLE', 'SERVICE').database_name).toBe('ORCLPDB1.example.com')
    expect(placeholdersFor('ORACLE', 'SID').database_name).toBe('ORCL')
    expect(placeholdersFor('X')).toEqual({})
  })

  it('her örneğin TR + EN başlığı ve açıklaması var', () => {
    for (const type of DB_TYPES) {
      for (const ex of examplesFor(type)) {
        for (const dict of [TR, EN]) {
          expect(dict[`dbq.conn.ex.${ex.id}.title`], `${ex.id} başlık`).toBeTruthy()
          expect(dict[`dbq.conn.ex.${ex.id}.note`], `${ex.id} açıklama`).toBeTruthy()
        }
      }
    }
  })
})
