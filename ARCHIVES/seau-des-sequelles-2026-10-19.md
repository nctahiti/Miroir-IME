# ⚱️ Le seau des séquelles — 19/10/2026

Archivées au retrait de la vue clavier (la branche `une-seule-encre`).
Les corps complets vivent aussi dans git : `git show <sha>^:app/src/main/java/com/parnasse/miroir/MiroirIME.kt`.

## Pourquoi elles sont parties

Le clavier ne porte plus le moteur d'encre : il navigue dans le texte (la ligne),
le Miroir (la capture) est le seul écrivain. Ces organes n'avaient plus d'appelant.
On les garde ici « on ne sait jamais » — notamment `makeFmtBtn` qui revivra dans
l'autre vue du clavier (les balises markdown exilées).

---

## vstarWriter · vstarPointCount · vstarDoc (membres)

```kotlin
private var vstarWriter: VStarWriter? = null
private var vstarPointCount = 0
private var vstarDoc: VStarDocumentV2? = null
```

## openVStarDoc

```kotlin
private fun openVStarDoc(dir: java.io.File) {
    val vstarFile = java.io.File(dir, "page.vstar")
    vstarDoc = VStarDocumentV2(vstarFile)
    vstarDoc!!.open()
    Log.i(TAG, "V★ v2.0 ouvert: ${vstarFile.absolutePath}")
}
```

## savePageV2 (le gros — 176 lignes)

Sauvegarde complète V★ v2.0 de la page : écriture bitmap.png, page.vstar
(encodage StrokeRecord→VStarTokenV2, GroupTable avec extents, groupes solo,
labels corrigés), backups horodatés dans debug_saves/, gm_state.json, puis
savePageMdm. Jamais appelée — la sauvegarde réelle vit dans MiroirEngine.savePageFull
(la capture), avec l'écriture atomique page.vstar.neuf (leçon du 19/09).

```kotlin
private fun savePageV2() {
    // ═══ Création paresseuse du bloc au premier stroke ═══
    if (blockDir == null && strokeRegistry.any { !it.isDeleted && it.points.isNotEmpty() }) {
        ensureBlockDir(currentFieldKey.ifEmpty { "standalone" }, System.currentTimeMillis())
        Log.i(TAG, "Bloc créé paresseusement: ${blockDir?.name}")
    }
    val bd = blockDir ?: run {
        Log.w(TAG, "savePageV2: pas de bloc actif")
        return
    }
    val hasLiveStrokes = strokeRegistry.any { !it.isDeleted && it.points.isNotEmpty() && it.timestamps.isNotEmpty() }
    val dir = java.io.File(bd, "page_$currentPageIndex")
    if (!hasLiveStrokes) {
        if (dir.exists()) { dir.deleteRecursively(); Log.i(TAG, "savePageV2: page vide — dossier supprimé") }
        return
    }
    try {
        dir.mkdirs()
        bitmap?.let {
            java.io.FileOutputStream(java.io.File(dir, "bitmap.png")).use { out ->
                it.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
        // ═══ DEBUG : sauvegarde horodatée avant écrasement ═══
        val vstarFile = java.io.File(dir, "page.vstar")
        val groupsFile = java.io.File(dir, "page.groups.json")
        val debugDir = java.io.File(bd, "debug_saves")
        debugDir.mkdirs()
        val ts = java.text.SimpleDateFormat("HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
        val backupDir = java.io.File(debugDir, "page${currentPageIndex}_$ts")
        backupDir.mkdirs()
        if (vstarFile.exists()) { vstarFile.copyTo(java.io.File(backupDir, "page.vstar"), true) }
        if (groupsFile.exists()) { groupsFile.copyTo(java.io.File(backupDir, "page.groups.json"), true) }
        // Sauvegarder aussi l'état GroupManager (JSON lisible)
        val gmState = org.json.JSONObject()
        val gm = groupManager
        if (gm != null) {
            val groupsArr = org.json.JSONArray()
            for (g in gm.allGroups()) {
                val gObj = org.json.JSONObject()
                gObj.put("id", g.id.take(8))
                gObj.put("strokeIds", org.json.JSONArray(g.strokeIds))
                gObj.put("state", g.state.toString())
                groupsArr.put(gObj)
            }
            gmState.put("groups", groupsArr)
            gmState.put("cacheSize", gm.cacheSize())
        }
        java.io.FileWriter(java.io.File(backupDir, "gm_state.json")).use { it.write(gmState.toString(2)) }
        Log.i(TAG, "V★ v2.0 DEBUG: backup → ${backupDir.name}")

        // Ouvrir le document V2 (écrase l'ancien)
        if (vstarFile.exists()) vstarFile.delete()
        if (groupsFile.exists()) groupsFile.delete()  // ⚠️ éviter accumulation groupes
        val doc = VStarDocumentV2(vstarFile)
        doc.open()

        // ═══ Encoder les strokes vivants en V★ v2.0 ═══
        val allLiveIndices = strokeRegistry.indices
            .filter { !strokeRegistry[it].isDeleted && strokeRegistry[it].points.isNotEmpty() && strokeRegistry[it].timestamps.isNotEmpty() }
            .toList()

        // Map: registryIndex → captureIndex + offset dans le fichier
        val registryToCI = mutableMapOf<Int, Short>()
        val ciToOffset = mutableMapOf<Short, Int>()
        val ciToCount = mutableMapOf<Short, Int>()

        for (ri in allLiveIndices) {
            val sr = strokeRegistry[ri]
            val ci = doc.beginStroke()
            registryToCI[ri] = ci
            val startOffset = doc.activeStrokeOffset()
            val tokens = strokeRecordToTokensV2(sr, ci)
            for (t in tokens) doc.writeToken(t)
            doc.endStroke(ci)
            ciToOffset[ci] = startOffset
            ciToCount[ci] = tokens.size
        }

        // ═══ Construire les groupes directement dans la GroupTable ═══
        val allGroups = (groupManager?.allGroupsFull() ?: emptyList())
            .filter { it.strokeIds.isNotEmpty() }
        groupManager?.persistence?.deleteAll()

        val groupToRIs = mutableMapOf<String, MutableList<Int>>()
        for (group in allGroups) {
            val ris = mutableListOf<Int>()
            for (sid in group.strokeIds) {
                val ri = inkStrokeIdToRegistryIndex[sid] ?: continue
                if (ri in registryToCI) ris.add(ri)
            }
            if (ris.isNotEmpty()) groupToRIs[group.id] = ris
        }

        var groupCount = 0
        for ((oldGid, ris) in groupToRIs) {
            val firstRI = ris.first()
            val firstCI = registryToCI[firstRI] ?: continue
            val firstOffset = ciToOffset[firstCI] ?: continue
            val firstCount = ciToCount[firstCI] ?: continue
            val label = groupLabels[firstRI]
            val (ax, ay) = computeGroupAnchor(ris)
            val newGid = doc.createGroupWithExtent(anchorX = ax, anchorY = ay, offset = firstOffset, count = firstCount, label = label)
            if (newGid.isEmpty()) continue
            groupCount++
            for (ri in ris) {
                if (ri == firstRI) continue
                val ci = registryToCI[ri] ?: continue
                val offset = ciToOffset[ci] ?: continue
                val count = ciToCount[ci] ?: continue
                doc.getGroupTable()?.addExtent(newGid, VStarGroupTable.Extent(offset, count))
            }
            val origLabel = originalLabels[firstRI]
            if (origLabel != null && origLabel != label) {
                doc.updateGroupMeta(newGid, labelCorrected = label)
            }
        }

        for (ri in allLiveIndices) {
            if (groupToRIs.values.any { ri in it }) continue
            val ci = registryToCI[ri] ?: continue
            val offset = ciToOffset[ci] ?: continue
            val count = ciToCount[ci] ?: continue
            val label = groupLabels[ri]
            val gid = doc.createGroupWithExtent(0f, 0f, offset, count, label)
            if (gid.isNotEmpty()) groupCount++
        }

        doc.flush()
        doc.close()
        Log.i(TAG, "V★ v2.0 sauvegardé: ${allLiveIndices.size} strokes, $groupCount groupes → ${vstarFile.length()}B")
        savePageMdm(dir)
    } catch (e: Exception) {
        Log.e(TAG, "savePageV2 erreur: ${e.message}", e)
    }
}
```

## makeFmtBtn (revivra dans l'autre vue du clavier)

```kotlin
fun makeFmtBtn(label: String, markdown: String): android.widget.Button {
    return android.widget.Button(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.argb(255, 200, 200, 200))
        background = borderedBg(Color.argb(100, 60, 60, 70))
        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        setOnClickListener { injectMarkdown(markdown) }
    }
}
```

## isEditing (vue interne)

```kotlin
fun isEditing(): Boolean = editMode != EditMode.NONE
```

## handleToolbarTap (vue interne — l'ancienne toolbar stylet)

```kotlin
private fun handleToolbarTap(x: Float, y: Float): Boolean {
    if (y > toolbarHeightPx) return false  // pas dans la toolbar
    val btnWidth = width / 4f
    val index = (x / btnWidth).toInt().coerceIn(0, 3)
    Log.i(TAG, "Tap stylet bouton #$index (x=$x, y=$y)")
    when (index) {
        0 -> { savePage(); currentInputConnection?.commitText("\n", 1); requestHideSelf(0) }
        1 -> {
            val intent = android.content.Intent(this@MiroirIME, CalibrationActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        2 -> { showOverlays = !showOverlays; refreshAll() }
        3 -> {
            currentInputConnection?.let { ic ->
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            }
            clearPage(); refreshAll(); requestHideSelf(0)
        }
    }
    return true
}
```

## getSpatialBounds

```kotlin
private fun getSpatialBounds(): List<android.graphics.RectF> {
    getSpatialGroups()
    return cachedSpatialBounds ?: emptyList()
}
```

## cancelAllGroupTimers

```kotlin
private fun cancelAllGroupTimers() {
    for ((_, timer) in groupTimers) {
        timer.cancel(false)
    }
    groupTimers.clear()
}
```
