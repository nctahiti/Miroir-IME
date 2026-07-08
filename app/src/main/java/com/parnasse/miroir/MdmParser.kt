package com.parnasse.miroir

/**
 * MDM — MarkDownMiroir — Parser minimal
 * ======================================
 * Extrait les ancres @mot et la structure de colonnes (;*).
 * 
 * Syntaxe supportée (MVP) :
 *   @mot           → ancre simple
 *   @mot ;* @mot   → colonnes
 *   
 * Format de sortie : liste de MdmAnchor(label, lineIndex, colIndex, align)
 * où align ∈ {START, CENTER, END}
 */
data class MdmAnchor(
    val label: String,
    val lineIndex: Int,
    val colIndex: Int,
    val align: MdmAlign = MdmAlign.START
)

enum class MdmAlign { START, CENTER, END }

object MdmParser {

    /** Parse une source MDM et retourne la liste des ancres. */
    fun parse(src: String): List<MdmAnchor> {
        val anchors = mutableListOf<MdmAnchor>()
        val lines = src.split('\n')
        
        var lineIdx = 0
        var gridCols = 0  // nombre de colonnes détecté (>0 si grille active)
        var align: MdmAlign = MdmAlign.START
        
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            
            // Détecter alignement
            align = when {
                line.startsWith("<*") -> MdmAlign.START
                line.startsWith("|*") -> MdmAlign.CENTER
                line.startsWith(">*") -> MdmAlign.END
                else -> align // garde l'alignement précédent
            }
            
            // Split par ;* pour les colonnes
            if (";*" in line) {
                val cells = line.split(";*").map { it.trim() }
                if (gridCols == 0) gridCols = cells.size
                
                var colIdx = 0
                for (cell in cells) {
                    extractWords(cell, anchors, lineIdx, colIdx, align)
                    colIdx++
                }
                lineIdx++
            } else {
                // Ligne simple (pas de colonne)
                extractWords(line, anchors, lineIdx, 0, align)
                if (anchors.isNotEmpty() && anchors.last().lineIndex == lineIdx) {
                    lineIdx++ // incrémenter seulement si on a trouvé au moins un mot
                }
            }
        }
        
        return anchors
    }
    
    private fun extractWords(
        text: String,
        anchors: MutableList<MdmAnchor>,
        lineIdx: Int,
        colIdx: Int,
        align: MdmAlign
    ) {
        // Extraire tous les @mot
        val wordRegex = Regex("@(\\w+)")
        for (match in wordRegex.findAll(text)) {
            val word = match.groupValues[1]
            anchors.add(MdmAnchor(label = word, lineIndex = lineIdx, colIndex = colIdx, align = align))
        }
    }
    
    /** Génère du MDM depuis une liste d'ancres (pour sauvegarde). */
    fun generate(anchors: List<MdmAnchor>): String {
        if (anchors.isEmpty()) return ""
        
        val sb = StringBuilder()
        var currentLine = -1
        var currentCol = -1
        
        for ((i, a) in anchors.withIndex()) {
            if (a.lineIndex != currentLine) {
                if (currentLine >= 0) sb.append("\n")
                currentLine = a.lineIndex
                currentCol = -1
                // Alignement de la ligne
                sb.append(when (a.align) {
                    MdmAlign.CENTER -> "|* "
                    MdmAlign.END -> ">* "
                    else -> "<* "
                })
            }
            
            if (currentCol >= 0 && a.colIndex != currentCol) {
                sb.append(" ;* ")
            }
            currentCol = a.colIndex
            
            sb.append("@${a.label}")
        }
        
        if (sb.isNotEmpty()) {
            // Fermer la dernière balise d'alignement
            sb.append(when (anchors.last().align) {
                MdmAlign.CENTER -> " *|"
                MdmAlign.END -> " *>"
                else -> " *>"
            })
        }
        
        return sb.toString()
    }
}
