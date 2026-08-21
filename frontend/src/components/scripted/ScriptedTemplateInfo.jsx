import { pickLang } from '../../utils/scriptSourceOptions.js'

/**
 * Seçili şablonun ne yaptığı + kullanım senaryosu + gereken env değişkenleri.
 *
 * Şablon seçicisi uzun süre yalnız ADLARI listeledi; açıklama veriyle birlikte duruyordu ama
 * hiçbir yerde gösterilmiyordu — kullanıcı şablonu yükleyip script'i okumadan hangisinin kendi
 * işine uyduğunu anlayamıyordu.
 *
 * Kapsam rozeti burada da görünür: aynı adda bir Genel ve bir Takım şablonu olabilir, hangisini
 * seçtiğini görmeden ayırt edilemez.
 */
export default function ScriptedTemplateInfo({ tpl, lang, t }) {
  if (!tpl) return null
  const desc = pickLang(tpl.description, tpl.description_en, lang)
  const when = pickLang(tpl.when_to_use, tpl.when_to_use_en, lang)
  const env = tpl.env || []
  return (
    <div className="sc-tpl-info">
      <p className="sc-tpl-info-hdr">
        {tpl.scope === 'team'
          ? <span className="tag-chip">{tpl.team_name || t('scripted.tplScopeTeam')}</span>
          : <span className="tag-chip">{t('scripted.tplScopeGeneral')}</span>}
        {tpl.current_version && <span className="sc-ver-chip">v{tpl.current_version}</span>}
        {/* Köken rozeti: genele açılmış şablonun nereden geldiği (K5) — güven sinyali. */}
        {tpl.source_team_name && !tpl.builtin &&
          <span className="sc-tpl-origin">{t('scripted.tplFromTeam', tpl.source_team_name)}</span>}
      </p>
      {desc && <p>{desc}</p>}
      {when && <p><b>{t('scripted.templateWhen')}</b> {when}</p>}
      {env.length > 0 &&
        <p><b>{t('scripted.templateEnvNeeded')}</b> {env.map(e => e.name).join(', ')}</p>}
    </div>
  )
}
