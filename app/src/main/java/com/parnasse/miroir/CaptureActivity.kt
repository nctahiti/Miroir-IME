package com.parnasse.miroir

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * CaptureActivity — surface d'ecriture contextuelle.
 *
 * Deux modes d'invocation :
 *   MODE STANDALONE (sans extras) : bloc auto-genere, surface de capture libre.
 *   MODE CONTEXTUEL (avec extras)  : bloc/page fournis par Parnasse/Flutter.
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "Miroir/Capture"

        const val EXTRA_LIBRARY_ID      = "library_id"
        const val EXTRA_SHELF_ID        = "shelf_id"
        const val EXTRA_BLOCK_ID        = "block_id"
        const val EXTRA_NOTE_ID         = "note_id"
        const val EXTRA_PAGE_N          = "page_n"
        const val EXTRA_MODE            = "mode"
        const val EXTRA_OFFSET_LEFT     = "offset_left"
        const val EXTRA_OFFSET_TOP      = "offset_top"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        const val EXTRA_SESSION_TEXT    = "session_text"
        const val EXTRA_SESSION_ID      = "session_id"
        const val EXTRA_SAMPLE_COUNT    = "sample_count"
        const val EXTRA_TOTAL_COUNT     = "total_count"
        const val EXTRA_PREDICTION      = "prediction"
        const val EXTRA_FLIP_LAYOUT     = "flip_layout"
        const val EXTRA_COEUR_URL       = "coeur_url"
    }

    private val engine = MiroirEngine()
    private var recognizer: DigitalInkWrapper? = null
    private var captureView: CaptureSurfaceView? = null
    private var fontaineOverlay: FontaineOverlay? = null
    private var pageCounter: TextView? = null
    private var eyeButton: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "miroir-capture-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ── Timers ──────────────────────────────────────────────────────────
    private val inferenceRunnable = Runnable { runGroupInference() }
    private val displayRefreshRunnable = Runnable { refreshDisplay() }

    // ── Sync Cœur ───────────────────────────────────────────────────────
    private var lastSyncNotification = 0L
    private val coeurUrl = "http://127.0.0.1:8008"
    private val syncDebounceMs = 3000L  // au plus une notification toutes les 3s

    // ── Cache des blocs Parnasse (chargé en arrière-plan) ───────────────
    private var cachedLibraries: List<LibraryMiroirInfo> = emptyList()
    private var cachedParnasseBlocs: MutableMap<String, List<ParnasseBlocInfo>> = mutableMapOf()  // libraryId → blocs

    // ── Contexte d'invocation ──────────────────────────────────────────
    private var invocBlockId: String? = null
    private var invocPageN: Int = 0
    private var invocNoteId: String? = null
    private var invocMode: String = "bloc"

    /** Résout l'UUID Parnasse d'un bloc vers son nom de dossier Miroir (ex: "standalone").
     *  1. D'abord via le cache (fetchParnasseConfig, asynchrone).
     *  2. Sinon, interroge le Cœur directement (anti-course au premier lancement). */
    private fun resolveMirrorBlockName(blockId: String): String? {
        for ((_, blocs) in cachedParnasseBlocs) {
            for (b in blocs) {
                if (b.id == blockId && b.mirrorName.isNotEmpty()) return b.mirrorName
            }
        }
        // ═══ Fallback synchrone : interroger le Cœur (thread + latch) ═══
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        Thread {
            try {
                for (lib in engine.fetchParnasseConfig(coeurUrl)) {
                    for (b in engine.fetchParnasseBlocs(coeurUrl, lib.libraryId)) {
                        if (b.id == blockId && b.mirrorName.isNotEmpty()) {
                            result = b.mirrorName
                            break
                        }
                    }
                    if (result != null) break
                }
            } catch (_: Exception) {}
            latch.countDown()
        }.start()
        try { latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        return result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        invocBlockId = intent.getStringExtra(EXTRA_BLOCK_ID)
        invocPageN   = intent.getIntExtra(EXTRA_PAGE_N, 0)
        invocNoteId  = intent.getStringExtra(EXTRA_NOTE_ID)
        invocMode    = intent.getStringExtra(EXTRA_MODE) ?: "bloc"

        // ═══ Fallback : contexte via fichier partagé (launchIME ne passe pas d'extras) ═══
        if (invocBlockId == null) {
            try {
                val ctxFile = java.io.File("/storage/emulated/0/Documents/parnasse/miroir/parnasse_context.json")
                if (ctxFile.exists()) {
                    val j = org.json.JSONObject(ctxFile.readText())
                    val bid = j.optString("block_id", "")
                    if (bid.isNotEmpty()) {
                        invocBlockId = bid
                        invocPageN   = j.optInt("page_n", 0)
                        invocNoteId  = j.optString("note_id", null)
                        invocMode    = j.optString("mode", "bloc")
                        Log.i(TAG, "📂 Contexte lu depuis parnasse_context.json: bloc=$bid page=$invocPageN noteId=$invocNoteId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lecture parnasse_context.json: ${e.message}")
            }
        }

        val isContextual = invocBlockId != null
        Log.i(TAG, "=== CAPTURE ACTIVITY === mode=$invocMode contextual=$isContextual blockId=$invocBlockId page=$invocPageN noteId=$invocNoteId")

        // ═══ BASCULE : qui est le maître de la navigation ? ═══
        // Contextuel (ouvert depuis Flutter, bloc de l'étagère) → Parnasse dicte.
        // Standalone (depuis le lanceur) → le Miroir garde la main sur ses blocs.
        engine.coeurUrl = coeurUrl
        if (isContextual) {
            engine.navMode = NavMode.PARNASSE
            engine.parnasseBlockUuid = invocBlockId
            // ═══ L'identité d'abord : la note invoquée, jamais la position ═══
            engine.parnasseNoteId = invocNoteId?.takeIf { it.isNotEmpty() }
        } else {
            engine.navMode = NavMode.LOCAL
        }

        recognizer = DigitalInkWrapper(this).also { it.load() }

        if (isContextual) {
            val mirrorName = resolveMirrorBlockName(invocBlockId!!)
            val blockDirName = mirrorName ?: invocBlockId!!
            engine.openBlockDir(this, blockDirName)
            Log.i(TAG, "📂 Bloc ouvert: $blockDirName (résolu depuis $invocBlockId)")
        } else {
            engine.openBlockDir(this, "standalone")
        }
        engine.initGroupManager(this)
        engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)

        // ═══ BAPTÊME : résoudre la page par note_id (identité) avec repli pageN ═══
        // ⚠️ LOCAL seulement. En PARNASSE le Miroir est viewport : ni scan ni insertion,
        // la position vient de Parnasse (invocPageN) et goToPageFull interrogera le Cœur.
        var targetPage = invocPageN
        if (!invocNoteId.isNullOrEmpty() && engine.navMode != NavMode.PARNASSE) {
            val total = engine.countPages()
            var found = -1
            for (i in 0 until total) {
                if (engine.readPageNoteId(i) == invocNoteId) { found = i; break }
            }
            if (found >= 0) {
                targetPage = found
                Log.i(TAG, "🧭 Page résolue par note_id: $invocNoteId → page $found")
            } else {
                // Nouvelle note → insérer une page à la position indiquée + baptiser
                val newPage = engine.insertPageAt(invocPageN, invocNoteId!!)
                targetPage = newPage
                Log.i(TAG, "⛪ Nouvelle page $newPage insérée et baptisée avec $invocNoteId")
            }
        }

        when (invocMode) {
            else -> buildBlockView()
        }

        if (engine.navMode == NavMode.PARNASSE) {
            // Parnasse est maître : navigue à la position dictée (interroge le Cœur).
            captureView?.post {
                engine.goToPageFull(targetPage) {
                    updatePageCounter()
                    fontaineOverlay?.desactiver()
                    captureView?.invalidate()
                    fontaineOverlay?.activer()
                }
            }
        } else if (isContextual && targetPage > 0 && engine.countPages() > targetPage) {
            engine.goToPageFull(targetPage)
        } else if (engine.countPages() > 0) {
            engine.currentPageIndex = engine.countPages() - 1
            // ⚠️ Posté après le layout pour garantir que onSizeChanged a créé le bitmap
            // ET que la FontaineOverlay ne masque pas le rendu initial sur EPD.
            // Sans ça, redrawBitmapInternal() trouve bitmapCanvas==null → return silencieux
            // → strokes invisibles au démarrage (seuls les blobs apparaissent).
            captureView?.post {
                engine.loadPageFull()
                updatePageCounter()
                // Cycle EPD : désactiver la fontaine → invalider (bitmap + blobs) → réactiver
                // Sans ce cycle, la SurfaceView ZOrderOnTop de la Fontaine masque le nouveau contenu.
                fontaineOverlay?.desactiver()
                captureView?.invalidate()
                fontaineOverlay?.activer()
            }
            return  // onCreate continue, le post fera le reste
        }
        updatePageCounter()
        captureView?.post { captureView?.invalidate() }
    }

    // ═══ singleInstance : les clics suivants passent par onNewIntent (pas onCreate) ═══
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newBlockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        val newPageN   = intent.getIntExtra(EXTRA_PAGE_N, 0)
        val newNoteId  = intent.getStringExtra(EXTRA_NOTE_ID)
        val newMode    = intent.getStringExtra(EXTRA_MODE) ?: "bloc"

        val blocChanged = newBlockId != invocBlockId
        invocBlockId = newBlockId
        invocPageN   = newPageN
        invocNoteId  = newNoteId
        invocMode    = newMode
        Log.i(TAG, "♻ onNewIntent: bloc=$newBlockId page=$newPageN noteId=$newNoteId")

        // ═══ L'identité suit l'invocation — jamais la position ═══
        engine.parnasseNoteId = newNoteId?.takeIf { it.isNotEmpty() }

        if (blocChanged) {
            val mirrorName = resolveMirrorBlockName(newBlockId)
            val blockDirName = mirrorName ?: newBlockId
            engine.openBlockDir(this, blockDirName)
            Log.i(TAG, "♻ Bloc ouvert: $blockDirName (résolu depuis $newBlockId)")
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            buildBlockView()
        }

        // ═══ Résolution par note_id (identité) avec repli pageN — comme onCreate ═══
        // ⚠️ LOCAL seulement. En PARNASSE, la position vient de Parnasse (newPageN).
        var targetPage = newPageN
        if (!newNoteId.isNullOrEmpty() && engine.navMode != NavMode.PARNASSE) {
            val total = engine.countPages()
            var found = -1
            for (i in 0 until total) {
                if (engine.readPageNoteId(i) == newNoteId) { found = i; break }
            }
            if (found >= 0) {
                targetPage = found
                Log.i(TAG, "🧭 onNewIntent: page résolue par note_id $newNoteId → $found")
            } else {
                val newPage = engine.insertPageAt(newPageN, newNoteId!!)
                targetPage = newPage
                Log.i(TAG, "⛪ onNewIntent: nouvelle page $newPage insérée et baptisée avec $newNoteId")
            }
        }

        if (engine.navMode == NavMode.PARNASSE) {
            engine.goToPageFull(targetPage) {
                updatePageCounter()
                captureView?.invalidate()
            }
        } else if (targetPage > 0 && engine.countPages() > targetPage) {
            engine.goToPageFull(targetPage)
        } else {
            engine.currentPageIndex = targetPage.coerceAtLeast(0)
            engine.loadPageFull()
        }
        updatePageCounter()
        captureView?.post { captureView?.invalidate() }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INFERENCE PAR GROUPE (timer)
    // ═══════════════════════════════════════════════════════════════════

    private fun runGroupInference() {
        val gm = engine.groupManager ?: return
        val groups = gm.allGroupsFull()
        Log.i(TAG, "runGroupInference: ${groups.size} groupes, isCorrecting=${captureView?.isCorrecting() ?: false}, correctLetterIndex=${captureView?.correctLetterIndex ?: -1}, insertAtIndex=${captureView?.insertAtIndex ?: -1}")
        if (groups.isEmpty()) return
        val rec = recognizer ?: return
        if (!rec.isLoaded) return

        val registrySnapshot = engine.strokeRegistry.toList()
        for (group in groups) {
            val firstIdx = group.strokeIds.firstOrNull()
                ?.let { engine.inkStrokeIdToRegistryIndex[it] } ?: continue
            if (engine.groupLabels.containsKey(firstIdx)) continue

            val indices = group.strokeIds.mapNotNull { engine.inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) continue

            val groupId = group.id
            inferExecutor.submit {
                val result = rec.recognize(registrySnapshot, indices)
                if (!result.isNullOrBlank()) {
                    uiHandler.post {
                        // ═══ Mode correction → redirect vers CaptureSurfaceView ═══
                        val cv = captureView
                        if (cv != null && cv.isCorrecting() && (cv.correctLetterIndex >= 0 || cv.insertAtIndex >= 0)) {
                            // Avec la désélection à l'entrée, les strokes de correction
                            // créent toujours des groupes SÉPARÉS (firstIdx != correctionGroupFirstIdx)
                            if (firstIdx != cv.correctionGroupFirstIdx) {
                                cv.applyCorrectionResult(result, firstIdx)
                                Log.i(TAG, "Correction appliquée: '$result' (mode correction, firstIdx=$firstIdx)")
                                return@post
                            }
                        }
                        if (!engine.groupLabels.containsKey(firstIdx)) {
                            engine.groupLabels[firstIdx] = result
                            val anchor = registrySnapshot.getOrNull(firstIdx)?.points?.firstOrNull()
                            if (anchor != null) engine.groupAnchor[firstIdx] = anchor
                            Log.i(TAG, "Reconnu: '$result' (groupe ${groupId.take(8)}, ${indices.size} strokes)")
                            // ═══ Notification Cœur : le mot est reconnu, la SD card est déjà à jour ═══
                            notifyCoeur()
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE BLOC
    // ═══════════════════════════════════════════════════════════════════

    private fun buildBlockView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ═══ BARRE D'OUTILS (hauteur fixe, toujours au-dessus) ═══
        // [✕]    [◄ 1/5 ►]    [+]
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 38, 12, 6)
            setBackgroundColor(Color.argb(220, 255, 255, 255))
        }

        // ── Bouton ✕ (fermer) ──
        toolbar.addView(makeToolBtn("\u2715", Color.argb(200, 150, 0, 0)) { view ->
            if (invocBlockId != null) {
                // Lancé depuis Parnasse → retour au Flutter.
                // finish() déclenche onPause/onDestroy → savePageFull + closeBlock (export SD).
                finish()
            } else {
                showCloseMenu(view)
            }
        })

        // ── Espace pousseur gauche ──
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.15f))

        // ── ◄ Navigation gauche (carnet affillié : accepte pages négatives) ──
        toolbar.addView(makeToolBtn("\u25C0", Color.argb(200, 80, 80, 160)) {
            engine.goToPageFull(engine.currentPageIndex - 1, navDelta = -1) {
                updatePageCounter()
                postMiroirState()
                // ═══ REFRESH DE NAVIGATION (28/08) — le rafraîchissement
                // (GC : lavage complet) vient APRÈS le chargement, dans le
                // onSettled : la page est posée d'un seul geste, encre ET
                // labels. Un refresh avant la charge posait une toile nue
                // (labels jamais dessinés — « les nouveaux n'apparaissent
                // qu'après un cycle de la fontaine »). ═══
                refreshAfterNav()
            }
            returnToWriting()
            captureView?.invalidate()
        })

        // ── Compteur de page ──
        pageCounter = TextView(this).apply {
            text = pageLabel()
            textSize = 28f; setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER; setPadding(8, 0, 8, 0)
        }
        toolbar.addView(pageCounter)

        // ── ► Navigation droite (permissive : crée implicitement si page absente) ──
        toolbar.addView(makeToolBtn("\u25B6", Color.argb(200, 0, 80, 160)) {
            engine.goToPageFull(engine.currentPageIndex + 1, navDelta = +1) {
                updatePageCounter()
                postMiroirState()
                // ═══ REFRESH DE NAVIGATION (28/08) — après le chargement,
                // d'un seul geste (voir la nav gauche). ═══
                refreshAfterNav()
            }
            returnToWriting()
            captureView?.invalidate()
        })

        // ── Bouton @ (MDM → Geppetto) ──
        toolbar.addView(makeToolBtn("@", Color.argb(200, 120, 60, 180)) {
            onMdmButtonClick()
        })

        // ── Espace pousseur droit ──
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.15f))

        // ── Bouton + (nouvelle page) ──
        toolbar.addView(makeToolBtn("+", Color.argb(200, 0, 100, 50)) { view ->
            showPlusMenu(view)
        })

        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ═══ LIGNE 2 : Œil de calibration ═══
        val eyeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }
        eyeButton = TextView(this).apply {
            text = if (captureView?.showLabels != false) "\uD83D\uDC41" else "\u2323"  // 👁 / ⌣ (œil fermé)
            textSize = 32f
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                val cv = captureView ?: return@setOnClickListener
                cv.showLabels = !cv.showLabels
                text = if (cv.showLabels) "\uD83D\uDC41" else "\u2323"
                cv.invalidate()
            }
        }
        eyeBar.addView(eyeButton)
        root.addView(eyeBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // ═══ ZONE DE CAPTURE (en dessous de l'interface, occupe tout l'espace restant) ═══
        val captureFrame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // Couche 1 : View standard — blobs, labels, template, strokes
        captureView = CaptureSurfaceView(this, engine).also { cv ->
            cv.onReturnToWriting = { returnToWriting() }
            captureFrame.addView(cv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // Couche 2 : SurfaceView FONTAINE — capture + rendu plume (Boox uniquement)
        if (FontaineOverlay.isAvailable()) {
            fontaineOverlay = FontaineOverlay(this, engine).also { fo ->
                fo.onStrokeFinished = { _ -> scheduleInference() }
                fo.onStrokeBegin = { cancelTimers() }
                fo.onLongPressDetected = { x, y -> handleLongPress(x, y) }
                captureFrame.addView(fo, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            }
        } else {
            fontaineOverlay = null
            Log.i(TAG, "Fontaine indisponible — fallback onDraw standard (CaptureView)")
        }

        captureView?.fontaineOverlay = fontaineOverlay

        root.addView(captureFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f))  // weight=1 → occupe tout l'espace restant

        setContentView(root)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MENUS FLOTTANTS
    // ═══════════════════════════════════════════════════════════════════

    /** Rythme du panneau — LA FORME JUSTE (28/08/2026, écouté au stylet) :
     *  le trait lave en DU (redessin intégral — c'est pour cela qu'un stroke
     *  efface les fantômes de la page d'avant), le relâchement remet REGAL
     *  (releaseTouchHelper — fracture A), la nav doit donc laver en GC (la
     *  page change ENTIÈREMENT — le REGAL doux ne lave pas les noirs d'une
     *  autre image : labels, blobs, traits restaient en fantôme).
     *  Jamais de refreshScreen détaché (runnable qui peut précéder le repaint),
     *  jamais de post de "retour" qui court le chevauchement : c'est le mode
     *  de la vue qui orchestre, et relâcher la plume rend REGAL naturellement. */
    private fun refreshAfterNav() {
        val cv = captureView ?: return
        com.onyx.android.sdk.api.device.epd.EpdController.setViewDefaultUpdateMode(
            cv, com.onyx.android.sdk.api.device.epd.UpdateMode.GC)
        cv.invalidate()
    }

    private fun showCloseMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val currentBlockId = engine.blockDir?.name ?: "—"

        // ── Blocs Parnasse (Cœur → toutes les bibliothèques) ──
        if (cachedLibraries.isNotEmpty()) {
            val parnasseGroup = popup.menu.addSubMenu("Blocs Parnasse")
            for (lib in cachedLibraries) {
                val blocs = cachedParnasseBlocs[lib.libraryId] ?: emptyList()
                if (blocs.isEmpty()) continue
                val libGroup = parnasseGroup.addSubMenu("  ${lib.libraryName}")
                for (bloc in blocs) {
                    val label = "    ${bloc.title}  (${bloc.nbNotes} notes)"
                    libGroup.add(label).setOnMenuItemClickListener {
                        Log.i(TAG, "Bloc Parnasse: ${lib.libraryName} > ${bloc.title} (${bloc.id})")
                        true
                    }
                }
            }
            if (cachedLibraries.all { (cachedParnasseBlocs[it.libraryId] ?: emptyList()).isEmpty() }) {
                parnasseGroup.add("(aucun bloc)").setEnabled(false)
            }
        } else {
            popup.menu.add("Blocs Parnasse (—)").setEnabled(false)
        }

        // ── Blocs Miroir (internes : files/blocks/) ──
        val blocs = engine.listBlocks(this)
        if (blocs.isNotEmpty()) {
            val blocGroup = popup.menu.addSubMenu("Blocs Miroir")
            for (bloc in blocs) {
                val prefix = if (bloc.id == currentBlockId) "✓ " else "  "
                val label = "$prefix${bloc.id.take(20)}  (${bloc.pages} p.)"
                blocGroup.add(label).setOnMenuItemClickListener {
                    if (bloc.id != currentBlockId) {
                        engine.switchBlock(this@CaptureActivity, bloc.id)
                        engine.updateTemplateSpacing(this@CaptureActivity, resources.displayMetrics.heightPixels)
                        captureView?.clearCanvas()
                        updatePageCounter()
                        // Cycle EPD après changement de bloc
                        fontaineOverlay?.desactiver()
                        captureView?.invalidate()
                        fontaineOverlay?.activer()
                    }
                    true
                }
            }
        }

        popup.menu.add("Vider page").setOnMenuItemClickListener {
            engine.clearPage(); captureView?.clearCanvas(); updatePageCounter(); true
        }
        popup.menu.add("Paramètres").setOnMenuItemClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java)); true
        }
        popup.menu.add("Fermer le miroir").setOnMenuItemClickListener {
            engine.savePageFull(); engine.closeBlock()
            val newBlockId = java.util.UUID.randomUUID().toString()
            engine.openBlockDir(this, newBlockId)
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.show()
    }

    private fun showPlusMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Nouvelle page (après)").setOnMenuItemClickListener {
            engine.newPage()  // insère après la page courante
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            returnToWriting()
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.menu.add("Nouvelle page (début)").setOnMenuItemClickListener {
            engine.newPageAtBeginning()
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            returnToWriting()
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.menu.add("Nouvelle page (fin)").setOnMenuItemClickListener {
            engine.newPageAtEnd()
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            returnToWriting()
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.show()
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        // Recharger les paramètres de calibration (blob, template)
        engine.applyCalibrationParams(this)
        // ═══ Le focus suit le Cœur : si Parnasse a déplacé la sélection
        // pendant notre absence, on la suit par identité — jamais par position. ═══
        if (engine.navMode == NavMode.PARNASSE) {
            followCoeurFocus()
        }
        // Précharger les bibliothèques et leurs blocs Parnasse en arrière-plan
        Thread {
            Log.i(TAG, "fetchParnasseConfig: démarrage...")
            val libs = engine.fetchParnasseConfig(coeurUrl)
            cachedLibraries = libs
            Log.i(TAG, "fetchParnasseConfig: ${libs.size} bibliothèques")
            val blocsMap = mutableMapOf<String, List<ParnasseBlocInfo>>()
            for (lib in libs) {
                val blocs = engine.fetchParnasseBlocs(coeurUrl, lib.libraryId)
                blocsMap[lib.libraryId] = blocs
                Log.i(TAG, "  ${lib.libraryName}: ${blocs.size} blocs")
            }
            cachedParnasseBlocs = blocsMap
        }.start()
    }

    /** Re-questionne le Cœur : quel est le focus actuel ? Si Parnasse a changé
     *  la sélection, le Miroir la suit par identité (le focus est partagé). */
    private fun followCoeurFocus() {
        val bid = invocBlockId ?: return
        Thread {
            try {
                val url = java.net.URL("$coeurUrl/api/miroir/state?block_id=$bid")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val stateNoteId = org.json.JSONObject(body).optString("note_id").ifEmpty { null }
                    conn.disconnect()
                    if (stateNoteId != null && stateNoteId != engine.parnasseNoteId) {
                        Log.i(TAG, "🧭 Focus Parnasse a changé: ${stateNoteId.substring(0, 8)} — le suivre")
                        uiHandler.post {
                            engine.parnasseNoteId = stateNoteId
                            engine.goToPageFull(engine.currentPageIndex) {
                                updatePageCounter()
                                captureView?.invalidate()
                            }
                        }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "followCoeurFocus: ${e.message}")
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        // ═══ Export complet au départ : bitmap.png + V★ + groupes + MDM ═══
        engine.savePageFull()
    }

    override fun onDestroy() {
        engine.savePageFull(); engine.closeBlock()
        recognizer?.close()
        super.onDestroy()
    }

    /** Notifie le Cœur de la page courante (navigation bidirectionnelle Miroir → Parnasse).
     *  Parnasse lit cet état (polling) pour ouvrir la note affichée au moment du basculement. */
    private fun postMiroirState() {
        val bid = invocBlockId ?: return
        try {
            val json = org.json.JSONObject().apply {
                put("block_id", bid)
                put("page_n", engine.currentPageIndex)
                put("mode", "capture")
                // ═══ L'identité du FOCUS (parnasseNoteId), pas la lecture locale ═══
                // La page locale peut n'être pas baptisée ; l'identité vient du Cœur.
                val nid = if (engine.navMode == NavMode.PARNASSE) {
                    engine.parnasseNoteId
                } else {
                    engine.readPageNoteId(engine.currentPageIndex)
                }
                if (nid != null) put("note_id", nid)
            }
            Thread {
                try {
                    val url = java.net.URL("$coeurUrl/api/miroir/state")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(json.toString().toByteArray())
                    val code = conn.responseCode
                    conn.disconnect()
                    Log.i(TAG, "📡 État → Parnasse: page=${engine.currentPageIndex} (HTTP $code)")
                } catch (e: Exception) {
                    Log.w(TAG, "postMiroirState: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "postMiroirState: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun makeToolBtn(text: String, bg: Int, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 36f; setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10); setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setOnClickListener { onClick(this) }
        }

    private fun pageLabel(): String {
        val idx = engine.currentPageIndex
        val local = engine.navigationTotal()
        return if (idx >= 0) "${idx + 1} / ${maxOf(local, idx + 1)}"
        else "${idx} [carnet] / ${maxOf(local, -idx)}"
    }

    private fun updatePageCounter() {
        pageCounter?.text = pageLabel()
    }

    /** Bouton @ — envoie le MDM à Geppetto et charge la réponse en strokes. */
    private fun onMdmButtonClick() {
        val mdmSrc = buildMdmFromPage()

        // ═══ TODO: POST vers Geppetto /api/agent/message ═══
        // Pour l'instant, on génère directement depuis le MDM de la page
        // (boucle courte sans Geppetto — vérifie la génération synthétique)
        val count = engine.loadFromMdm(mdmSrc)

        if (count > 0) {
            captureView?.invalidate()
            Toast.makeText(this, "$count groupes générés", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "@→MDM: $count ancres appliquées depuis la page courante")
        } else {
            Toast.makeText(this, "Aucune ancre trouvée", Toast.LENGTH_SHORT).show()
        }
    }

    /** Construit le MDM depuis les labels de la page courante. */
    private fun buildMdmFromPage(): String {
        val sb = StringBuilder()
        // On utilise les labels déjà reconnus (groupLabels)
        // Format simple : un mot par ancre sur la première ligne
        val labels = engine.groupLabels.values.filter { it.isNotBlank() }
        if (labels.isEmpty()) {
            // Test : générer "Bonjour le monde" pour vérifier les strokes synthétiques
            return "@Bonjour @le @monde @test @synthétique"
        }
        for (label in labels) {
            sb.append("@$label ")
        }
        return sb.toString().trim()
    }

    private fun scheduleInference() {
        val inferDelay = CalibrationActivity.getAutoInferDelay(this)
        val displayDelay = CalibrationActivity.getDisplayDelay(this)
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.postDelayed(inferenceRunnable, inferDelay)
        uiHandler.removeCallbacks(displayRefreshRunnable)
        uiHandler.postDelayed(displayRefreshRunnable, displayDelay)
    }

    /** Rafraîchit l'affichage : désactive la fontaine, synchronise le bitmap, réactive. */
    private fun refreshDisplay() {
        if (fontaineOverlay?.modeInteraction == true) return
        engine.groupManager?.evictInactive()
        // 🔬 SÉMATOGRAMME CACHE
        val gm = engine.groupManager
        val cacheGroups = gm?.cacheSize() ?: 0
        val allGroups = gm?.allGroupsFull()?.size ?: 0
        val blobs = engine.groupBlobs.size
        val labels = engine.groupLabels.size
        val strokes = engine.strokeRegistry.count { !it.isDeleted && it.points.isNotEmpty() }
        val totalStrokes = engine.strokeRegistry.size
        val inkMappings = engine.inkStrokeIdToRegistryIndex.size
        Log.i(TAG, "📊 CACHE refresh: strokes=$strokes/$totalStrokes inkMap=$inkMappings " +
            "blobs=$blobs labels=$labels groupes=cache$cacheGroups/all$allGroups")
        Log.i(TAG, "refreshDisplay: desactiver...")
        fontaineOverlay?.desactiver()
        captureView?.invalidate()
        Log.i(TAG, "refreshDisplay: activer... fontaineOverlay=${fontaineOverlay != null}")
        fontaineOverlay?.activer()
        Log.i(TAG, "refreshDisplay: terminé")
    }

    private fun cancelTimers() {
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.removeCallbacks(displayRefreshRunnable)
    }

    private fun handleLongPress(x: Float, y: Float) {
        cancelTimers()
        captureView?.selectGroupAt(x, y)
        fontaineOverlay?.modeInteraction = true
        fontaineOverlay?.desactiver()
        captureView?.armLongPressGesture(x, y)
        fontaineOverlay?.touchForwardTarget = captureView
        Log.d(TAG, "Long-press → bascule franche, blob visible, attente geste")
    }

    private fun returnToWriting() {
        Log.i(TAG, "returnToWriting: modeInteraction=${fontaineOverlay?.modeInteraction} correctionWriteActive=${fontaineOverlay?.correctionWriteActive}")
        fontaineOverlay?.modeInteraction = false
        fontaineOverlay?.touchForwardTarget = null
        fontaineOverlay?.effacerSurface()
        captureView?.invalidate()
        fontaineOverlay?.reactiver()
        Log.i(TAG, "Retour écriture — fontaine réactivée")
    }

    /** Notifie le Cœur que de nouvelles pages sont disponibles dans la SD card.
     *  Debounce 3s pour ne pas spammer pendant l'écriture rapide. */
    private fun notifyCoeur() {
        val now = System.currentTimeMillis()
        if (now - lastSyncNotification < syncDebounceMs) return
        lastSyncNotification = now
        Thread {
            try {
                val url = URL("$coeurUrl/api/miroir/sync")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = """{"library_id":"3225cb96-a14f-4d6d-a965-dd3431489a74","block_name":"standalone"}"""
                conn.outputStream.write(body.toByteArray())
                val code = conn.responseCode
                if (code == 200) {
                    Log.i(TAG, "Coeur sync notifié: HTTP $code")
                } else {
                    Log.w(TAG, "Coeur sync: HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Cœur injoignable — le watcher 15s rattrapera
                Log.d(TAG, "Coeur sync muet: ${e.message}")
            }
        }.start()
    }
}
