import MDEditor, { commands } from '@uiw/react-md-editor/nohighlight'
import { createMdEditor } from './createMdEditor.jsx'

/**
 * Vurgusuz editör (tembel parça). `highlightEnable={false}` kullanan editörler (ui/MarkdownEditor, haftalık rapor
 * MdField) yazma alanında zaten vurgu çizmiyordu; `nohighlight` girişi yalnız önizlemedeki Prism dil paketini
 * (refractor, ~600 KB) ve ham HTML çözücüsünü bırakır. Önizlemedeki kod blokları renksiz çizilir.
 */
export default createMdEditor(MDEditor, commands)
