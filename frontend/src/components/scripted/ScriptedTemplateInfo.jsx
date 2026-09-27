import { pickLang } from '../../utils/scriptSourceOptions.js'
import { VersionChip } from './VersionTimeline.jsx'
import { Badge } from '@/components/shadcn/badge'

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
        {/* Kapsam rozeti (shadcn Badge): takım ya da genel. */}
        <Badge variant="outline" data-slot="template-scope" className="font-semibold">
          {tpl.scope === 'team' ? (tpl.team_name || t('scripted.tplScopeTeam')) : t('scripted.tplScopeGeneral')}
        </Badge>
        {tpl.current_version && <VersionChip className="ml-2">v{tpl.current_version}</VersionChip>}
        {/* Köken rozeti: genele açılmış şablonun nereden geldiği (K5) — güven sinyali. */}
        {tpl.source_team_name && !tpl.builtin &&
          <Badge variant="ghost" data-slot="template-origin" className="ml-2 px-0 text-[11px] font-semibold text-muted-foreground">
            {t('scripted.tplFromTeam', tpl.source_team_name)}
          </Badge>}
      </p>
      {desc && <p>{desc}</p>}
      {when && <p><b>{t('scripted.templateWhen')}</b> {when}</p>}
      {env.length > 0 &&
        <p><b>{t('scripted.templateEnvNeeded')}</b> {env.map(e => e.name).join(', ')}</p>}
    </div>
  )
}
