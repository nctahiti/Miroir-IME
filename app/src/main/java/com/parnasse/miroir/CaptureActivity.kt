package com.parnasse.miroir

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * CaptureActivity — Interface d'écriture manuscrite simplifiée.
 *
 * Deux modes :
 *   📝 Bloc-notes — écriture libre, reconnaissance mot-à-mot automatique
 *   ✎ Dictée    — un mot affiché, à recopier
 *
 * Layout :
 *   [📝 Bloc-notes] [✕ Fermer]
 *   ┌────────────────────────────┐
 *   │     "kayak" (36sp)        │   ← texte cible / transcription
 *   ├────────────────────────────┤
 *   │                            │
 *   │   CaptureView (fond)       │   ← zone d'écriture stylet
 *   │                            │
 *   ├────────────────────────────┤
 *   │   [↺ Reset]   [✓ Valider] │
 *   └────────────────────────────┘
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "Miroir/Activity"
        private const val CHANNEL_ID = "miroir_overlay"
        private const val NOTIFICATION_ID = 1001

        // Mots pour le mode Dictée
        private val DICTEE_WORDS = arrayOf(
            "le", "la", "les", "un", "une", "de", "du", "et", "ou", "en",
            "chat", "chien", "arbre", "fleur", "ciel", "eau", "feu", "terre",
            "vent", "nuit", "jour", "soleil", "lune", "etoile", "mer", "port",
            "bleu", "noir", "blanc", "rouge", "vert", "jaune", "rose", "gris",
            "petit", "grand", "beau", "bon", "doux", "fort", "vrai", "faux",
            "bonjour", "merci", "silence", "musique", "voyage", "souvenir",
            "kayak", "quartz", "wax", "yoga", "jazz", "puzzle", "boxe", "lynx"
        )
    }

    // ── Vues ──────────────────────────────────────────────────────────
    private var captureView: CaptureView? = null
    private var poemText: TextView? = null
    private var modeBtn: TextView? = null
    private var editModeIndicator: TextView? = null  // 🚢🔦⏳

    // ── Reconnaissance ─────────────────────────────────────────────────
    private var wordRecognizer: DigitalInkWrapper? = null
    private var accumulatedText: String = ""
    private val wordTranscriptions = mutableListOf<String>()  // pour sauvegarde

    // ═══ SOURCE UNIQUE HORIZON ═══
    /** Source unique de vérité pour le texte reconnu (fichier .transcription) */
    private var transcriptionWriter: TranscriptionWriter? = null

    // ── Mode ───────────────────────────────────────────────────────────
    private var isBlocNote = true
    private var currentWord: String = ""
    private var captureMode = CaptureMode.CAPTURE

    // ── Navigation pages ──────────────────────────────────────────────
    private var blocNoteFiles = mutableListOf<java.io.File>()
    private var currentPageIndex = -1  // -1 = nouvelle page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "=== CAPTURE ACTIVITY V4 (VStar conduit + inference asynchrone) ===")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        createNotificationChannel()
        showPersistentNotification()

        // ── Initialisation du reconnaisseur ────────────────────────────
        wordRecognizer = DigitalInkWrapper(this)
        wordRecognizer!!.load()

        // ── StrokeProcessor (thread background dédié) ─────
        val processor = StrokeProcessor(wordRecognizer)

        // ═══ SOURCE UNIQUE HORIZON : fichier .transcription ═══
        val noteDir = java.io.File(filesDir, "blocnote")
        val baseName = "note_${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val tw = TranscriptionWriter(noteDir, baseName, CalibrationActivity.getSpatialDistanceY(this).toFloat())
        transcriptionWriter = tw
        processor.transcriptionWriter = tw

        // ═══ RASTÉRISATION DE CONTRÔLE (V4 Horizon) ═══
        // Rastérise les strokes AVANT inférence ML Kit dans une zone
        // NORMALISÉE par l'interligne (hauteur fixe = 3×IL).
        // → Tous les mots ont la même échelle verticale.
        // → Un point isolé n'est plus reconnu comme grand caractère.
        val rasterDir = java.io.File(filesDir, "debug_rasters")
        // Nettoyer les rasters de la session précédente (évite l'accumulation)
        rasterDir.listFiles()?.forEach { it.delete() }
        processor.debugDir = rasterDir
        processor.enableRasterDebug = false  // désactivé par défaut (debug uniquement)
        processor.maxRasterCount = 0  // illimité si réactivé manuellement
        processor.lineHeight = CalibrationActivity.getSpatialDistanceY(this).toFloat()
        processor.onRasterized = { bitmap, groupIdx ->
            Log.d(TAG, "📸 Raster groupe #$groupIdx reçu: ${bitmap.width}×${bitmap.height}")
        }

        // ── CaptureView ───────────────────────────────────
        captureView = CaptureView(this).also { cv ->
            cv.strokeProcessor = processor
            // Reconnaissance automatique via StrokeProcessor (thread background)
            cv.onWordGroupCompleted = { strokes, group, groupIndex ->
                val firstIdx = group.firstOrNull()
                if (firstIdx != null) {
                    processor.processGroup(
                        strokes = strokes,
                        group = group,
                        groupIndex = groupIndex,
                        onResult = { text ->
                            cv.onGroupInferred(firstIdx, text)
                            onWordRecognized(text)
                        },
                    onError = { err ->
                        Log.e(TAG, "Erreur reconnaissance: $err")
                    }
                )
                }
            }
            // ═══ Synchroniser GroupManager avec la calibration ═══
            cv.syncGroupManagerParams()
            // ═══ Mettre à jour la transcription quand le groupe actif change ═══
            cv.onActiveGroupChanged = {
                runOnUiThread { updatePoemText() }
            // Persistance des groupes pour eviction du cache
            cv.groupManager.persistence = GroupPersistence(GroupPersistence.groupsFile(noteDir, baseName))
            }
            // Indicateur de mode 🚢🔦⏳
            cv.onModeChanged = { mode ->
                runOnUiThread {
                    editModeIndicator?.text = when {
                        cv.currentMode == CaptureMode.EDIT_TEMPORAL -> "⏳"
                        mode == CaptureMode.CAPTURE -> "🚢"
                        mode == CaptureMode.EDIT -> "🔦"
                        else -> "🚢"
                    }
                }
            }
        }

        // ── Layout ─────────────────────────────────────────────────────
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        root.addView(captureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Barre du haut — tout en haut
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 38, 12, 6)
            setBackgroundColor(android.graphics.Color.argb(220, 255, 255, 255))
        }

        // Gauche : 📝 Bloc-notes/Dictée
        modeBtn = makeToolbarButton("📝", android.graphics.Color.argb(180, 100, 180, 255)).apply {
            setOnClickListener { toggleMode() }
        }
        topBar.addView(modeBtn!!)

        // Indicateur mode édition (🚢 CAPTURE / 🔦 EDIT_SPATIAL / ⏳ EDIT_TEMPORAL)
        editModeIndicator = TextView(this).apply {
            text = "🚢"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(14, 8, 14, 8)
            setBackgroundColor(android.graphics.Color.argb(180, 60, 60, 60))
            gravity = Gravity.CENTER
        }
        topBar.addView(editModeIndicator!!)

        // ⚙ Paramètres
        topBar.addView(makeToolbarButton("⚙", android.graphics.Color.argb(180, 80, 80, 80)) {
            val intent = Intent(this@CaptureActivity, CalibrationActivity::class.java)
            startActivity(intent)
        })

        // ✕ Fermer
        topBar.addView(makeToolbarButton("✕", android.graphics.Color.argb(200, 150, 0, 0)) {
            captureView?.saveCurrentNote(mode = "blocnote", transcriptions = transcriptionWriter?.getOrderedWords() ?: wordTranscriptions.toList())
            finish()
        })

        // ⬅ ➡ Navigation entre pages
        topBar.addView(makeToolbarButton("⬅", android.graphics.Color.argb(200, 80, 80, 160)) {
            goToPrevPage()
        })
        topBar.addView(makeToolbarButton("➡", android.graphics.Color.argb(200, 0, 80, 160)) {
            goToNextPage()
        })

        // 👁 Toggle overlays visuels (diagnostic latence)
        val overlayBtn = makeToolbarButton("👁", android.graphics.Color.argb(180, 80, 80, 80)).apply {
            setOnClickListener {
                val cv = captureView ?: return@setOnClickListener
                cv.showVisualOverlays = !cv.showVisualOverlays
                this.text = if (cv.showVisualOverlays) "👁" else "👁‍🗨"
                this.setBackgroundColor(if (cv.showVisualOverlays)
                    android.graphics.Color.argb(180, 80, 80, 80)
                else
                    android.graphics.Color.argb(200, 180, 120, 0))
                cv.postInvalidate()
                Toast.makeText(this@CaptureActivity,
                    if (cv.showVisualOverlays) "👁 Overlays ON" else "👁‍🗨 Overlays OFF (diagnostic)",
                    Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(overlayBtn)

        // Espace flexible
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Droite : 🔄 ✎ ↺ ✓
        // 🔄 Rafraîchir transcriptions (relance l'inférence sur tous les groupes)
        topBar.addView(makeToolbarButton("🔄", android.graphics.Color.argb(200, 0, 80, 160)) {
            refreshAllTranscriptions()
        })

        // 💾 Sauvegarder sans changer la vue
        topBar.addView(makeToolbarButton("💾", android.graphics.Color.argb(200, 0, 100, 50)) {
            val path = captureView?.saveCurrentNote(
                mode = "blocnote",
                transcriptions = transcriptionWriter?.getOrderedWords() ?: wordTranscriptions.toList()
            )
            if (path != null) {
                Toast.makeText(this, "💾 Sauvegardé", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "💾 Sauvegarde manuelle: $path")
            } else {
                Toast.makeText(this, "⚠️ Rien à sauvegarder", Toast.LENGTH_SHORT).show()
            }
        })

        // ✎ Édition
        val editBtn = makeToolbarButton("✎", android.graphics.Color.argb(180, 80, 80, 80)).apply {
            setOnClickListener {
                captureMode = if (captureMode == CaptureMode.CAPTURE) CaptureMode.EDIT else CaptureMode.CAPTURE
                captureView?.currentMode = captureMode
                if (captureMode == CaptureMode.EDIT) captureView?.currentMode = CaptureMode.EDIT_TEMPORAL
                this.text = if (captureMode == CaptureMode.CAPTURE) "✎" else "Ed"
                this.setBackgroundColor(if (captureMode == CaptureMode.CAPTURE)
                    android.graphics.Color.argb(180, 80, 80, 80)
                else
                    android.graphics.Color.argb(200, 100, 80, 0))
                Log.i(TAG, "Mode: ${captureMode.label}")
            }
        }
        topBar.addView(editBtn)

        // ── DEBUG : recalcule et décomposition de groupes ──────────────
        // (section temporaire — à supprimer si inutile après stabilisation)
        topBar.addView(View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(60, 255, 160, 0))
            layoutParams = LinearLayout.LayoutParams(3, 32).apply {
                setMargins(8, 0, 4, 0)
            }
        })
        val decomposeBtn = makeToolbarButton("🪄", android.graphics.Color.argb(180, 80, 80, 80)).apply {
            setOnClickListener {
                val newState = !(captureView?.decomposeMode ?: false)
                captureView?.decomposeMode = newState
                this.text = if (newState) "🪄✓" else "🪄"
                this.setBackgroundColor(if (newState)
                    android.graphics.Color.argb(200, 255, 160, 0)
                else
                    android.graphics.Color.argb(180, 80, 80, 80))
                if (newState) {
                    captureView?.currentMode = CaptureMode.EDIT_TEMPORAL
                    captureView?.currentMode = CaptureMode.EDIT
                    captureMode = CaptureMode.EDIT
                    Toast.makeText(this@CaptureActivity, "🪄 Tapez un groupe pour le décomposer", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topBar.addView(decomposeBtn)
        // 🔗 Bouton fusion séquentielle
        val mergeBtn = makeToolbarButton("🔗", android.graphics.Color.argb(180, 80, 80, 80)).apply {
            setOnClickListener {
                val newState = !(captureView?.mergeMode ?: false)
                captureView?.mergeMode = newState
                captureView?.mergeSourceGroup = null  // reset source
                this.text = if (newState) "🔗✓" else "🔗"
                this.setBackgroundColor(if (newState)
                    android.graphics.Color.argb(200, 100, 180, 255)
                else
                    android.graphics.Color.argb(180, 80, 80, 80))
                    captureView?.currentMode = CaptureMode.EDIT_TEMPORAL
                if (newState) {
                    captureView?.currentMode = CaptureMode.EDIT
                    captureMode = CaptureMode.EDIT
                    Toast.makeText(this@CaptureActivity, "🔗 Tapez deux groupes pour les fusionner", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topBar.addView(mergeBtn)
        topBar.addView(View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(60, 255, 160, 0))
            layoutParams = LinearLayout.LayoutParams(3, 32).apply {
                setMargins(4, 0, 8, 0)
            }
        })
        // ── Fin debug ──────────────────────────────────────────────────

        topBar.addView(makeToolbarButton("↺", android.graphics.Color.argb(200, 120, 80, 0)) {
            onReset()
        })
        topBar.addView(makeToolbarButton("✓", android.graphics.Color.argb(200, 0, 100, 0)) {
            onValidate()
        })

        overlay.addView(topBar)

        // ── Texte affiché — ligne unique défilante avec \\n pour sauts de ligne ──
        poemText = TextView(this).apply {
            text = "📝 Bloc-notes"
            textSize = 28f
            setTextColor(android.graphics.Color.rgb(40, 40, 40))
            setPadding(40, 14, 40, 14)
            setBackgroundColor(android.graphics.Color.rgb(255, 255, 235))
            maxLines = 1
            setHorizontallyScrolling(true)
        }
        val textHeight = (28f * 2.8f + 32).toInt()  // hauteur confortable pour 1 ligne
        overlay.addView(poemText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            textHeight
        ))

        // Espace capture
        overlay.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        // Démarrer en mode Bloc-notes
        applyMode()
        captureView?.startBlocNoteSession()
        scanBlocnoteFiles()

        MiroirService.start(this)
    }

    // ── Modes ──────────────────────────────────────────────────────────

    private fun toggleMode() {
        isBlocNote = !isBlocNote
        applyMode()
    }

    private fun applyMode() {
        if (isBlocNote) {
            modeBtn?.text = "📝"
            modeBtn?.setTextColor(android.graphics.Color.argb(255, 100, 200, 255))
            captureView?.isBlocnoteMode = true
            poemText?.text = if (accumulatedText.isNotEmpty()) accumulatedText else "📝 Bloc-notes"
            poemText?.textSize = 28f
            poemText?.setTextColor(android.graphics.Color.rgb(60, 60, 60))
            Log.i(TAG, "Mode: Bloc-notes")
        } else {
            modeBtn?.text = "✎"
            modeBtn?.setTextColor(android.graphics.Color.argb(255, 255, 180, 30))
            captureView?.isBlocnoteMode = false
            pickWord()
            Log.i(TAG, "Mode: Dictée")
        }
    }

    private fun pickWord() {
        currentWord = DICTEE_WORDS.random()
        poemText?.text = currentWord
        poemText?.textSize = 48f
        poemText?.setTextColor(android.graphics.Color.argb(200, 0, 0, 0))
    }

    // ── Reconnaissance ─────────────────────────────────────────────────

    /** Appelé par StrokeProcessor quand un mot est reconnu (déjà sur UI thread). */
    private fun onWordRecognized(text: String) {
        // Le mot est DÉJÀ écrit dans le .transcription par StrokeProcessor.
        // On recharge simplement depuis le fichier pour afficher.
        if (isBlocNote) {
            reloadFromTranscription()
            poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
        } else {
            // Mode Dictée : comparer au mot cible
            val match = text.equals(currentWord, ignoreCase = true)
            if (match) {
                poemText?.text = "✓ $text"
                poemText?.setTextColor(android.graphics.Color.argb(220, 40, 160, 60))
            } else {
                poemText?.text = "$text ≠ $currentWord"
                poemText?.setTextColor(android.graphics.Color.argb(220, 200, 50, 50))
            }
        }
        Log.i(TAG, "Reconnu: '$text'")
    }

    /**
     * Recharge le texte depuis le fichier .transcription (source unique Horizon).
     * Appelé après chaque mot reconnu, et lors du chargement d'une page existante.
     */
    private fun reloadFromTranscription() {
        val tw = transcriptionWriter ?: return
        accumulatedText = tw.getOrderedText()
        wordTranscriptions.clear()
        wordTranscriptions.addAll(tw.getOrderedWords())
        updatePoemText()
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun onValidate() {
        Log.i(TAG, "✓ Validation")
        captureView?.saveCurrentNote(
            mode = "blocnote",
            transcriptions = transcriptionWriter?.getOrderedWords() ?: wordTranscriptions.toList()
        )

        if (isBlocNote) {
            // Nouvelle page blanche
            captureView?.startBlocNoteSession()
            // ═══ Recréer le .transcription pour la nouvelle page ═══
            transcriptionWriter?.delete()
            val noteDir = java.io.File(filesDir, "blocnote")
            val baseName = "note_${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
            val newTw = TranscriptionWriter(noteDir, baseName, CalibrationActivity.getSpatialDistanceY(this).toFloat())
            transcriptionWriter = newTw
            captureView?.strokeProcessor?.transcriptionWriter = newTw
            // Persistance des groupes pour la nouvelle page
            captureView?.groupManager?.persistence = GroupPersistence(GroupPersistence.groupsFile(noteDir, baseName))
            accumulatedText = ""
            wordTranscriptions.clear()
            currentPageIndex = -1  // nouvelle page
            scanBlocnoteFiles()
            poemText?.text = "📝 Bloc-notes"
            poemText?.textSize = 28f
            poemText?.setTextColor(android.graphics.Color.rgb(60, 60, 60))
        } else {
            // Mot suivant
            captureView?.clear()
            pickWord()
        }
    }

    private fun onReset() {
        Log.i(TAG, "↺ Reset")
        captureView?.clear()
        if (isBlocNote) {
            accumulatedText = ""
            wordTranscriptions.clear()
            poemText?.text = "📝 Bloc-notes"
            poemText?.textSize = 28f
            poemText?.setTextColor(android.graphics.Color.rgb(60, 60, 60))
        } else {
            pickWord()
        }
    }

    // ── Cycle de vie ───────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        captureView?.initTouchHelper()
        // Recharger les paramètres de calibration (modifiés dans CalibrationActivity)
        captureView?.syncGroupManagerParams()
        captureView?.reloadAutoInferDelay()
    }

    override fun onDestroy() {
        Log.i(TAG, "=== CAPTURE ACTIVITY DÉTRUITE ===")
        wordRecognizer?.close()
        wordRecognizer = null
        captureView?.releaseTouchHelper()
        captureView?.clear()
        captureView = null
        super.onDestroy()
    }

    // ── Navigation pages ──────────────────────────────────────────────

    private fun scanBlocnoteFiles() {
        val dir = java.io.File(filesDir, "blocnote")
        if (!dir.exists()) { blocNoteFiles.clear(); return }
        blocNoteFiles = dir.listFiles { f -> f.name.startsWith("note_") && f.name.endsWith(".note") }
            ?.sortedBy { it.name }?.toMutableList() ?: mutableListOf()
        Log.i(TAG, "${blocNoteFiles.size} notes trouvées")
    }

    private fun goToPrevPage() {
        scanBlocnoteFiles()
        if (blocNoteFiles.isEmpty()) return
        
        // Sauver la page courante d'abord
        if (currentPageIndex < 0 && captureView?.hasStrokes() == true) {
            captureView?.saveCurrentNote(mode = "blocnote", transcriptions = transcriptionWriter?.getOrderedWords() ?: wordTranscriptions.toList())
            scanBlocnoteFiles()
        }
        
        val target = if (currentPageIndex < 0) blocNoteFiles.size - 1 else currentPageIndex - 1
        if (target < 0) return
        loadPage(target)
    }

    private fun goToNextPage() {
        if (currentPageIndex < 0) return  // déjà sur nouvelle page
        scanBlocnoteFiles()
        if (currentPageIndex >= blocNoteFiles.size - 1) return
        loadPage(currentPageIndex + 1)
    }

    private fun loadPage(index: Int) {
        if (index < 0 || index >= blocNoteFiles.size) return
        currentPageIndex = index
        val file = blocNoteFiles[index]
        
        // Charger la note
        captureView?.loadNoteFile(file)
        
        // ═══ SOURCE UNIQUE HORIZON : charger le .transcription compagnon ═══
        val noteDir = file.parentFile ?: java.io.File(filesDir, "blocnote")
        val baseName = file.nameWithoutExtension  // ex: "note_20260614-001101"
        val tw = TranscriptionWriter(noteDir, baseName, CalibrationActivity.getSpatialDistanceY(this).toFloat())
        transcriptionWriter = tw
        captureView?.strokeProcessor?.transcriptionWriter = tw
        // Persistance des groupes pour le chargement de page
        captureView?.groupManager?.persistence = GroupPersistence(GroupPersistence.groupsFile(noteDir, baseName))
        
        // Afficher les transcriptions depuis le .transcription (source unique)
        if (tw.exists()) {
            reloadFromTranscription()
        } else {
            // Fallback : charger depuis le .note (ancien format)
            val tx = captureView?.getNoteTranscriptions()
            if (tx != null && tx.isNotEmpty()) {
                // Migrer vers .transcription
                accumulatedText = tx.joinToString(" ")
                wordTranscriptions.clear(); wordTranscriptions.addAll(tx)
                poemText?.text = accumulatedText
            } else {
                accumulatedText = ""
                wordTranscriptions.clear()
                poemText?.text = "📝 Note ${index + 1}/${blocNoteFiles.size}"
            }
        }
        poemText?.textSize = 28f
        poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
        Log.i(TAG, "Page ${index + 1}/${blocNoteFiles.size}: ${file.name} (transcription: ${tw.exists()})")
    }

    // ── Rafraîchir toutes les transcriptions ─────────────────────────

    private fun refreshAllTranscriptions() {
        val recognizer = wordRecognizer ?: return
        if (!recognizer.isLoaded) return
        
        val strokes = captureView?.getStrokeRegistry() ?: return
        val groups = captureView?.computeWordGroupsForSave() ?: return
        if (groups.isEmpty()) return

        // Vider le .transcription et recréer
        val tw = transcriptionWriter
        tw?.delete()
        val newTw = tw ?: run {
            val noteDir = java.io.File(filesDir, "blocnote")
            val baseName = "note_${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
            TranscriptionWriter(noteDir, baseName, CalibrationActivity.getSpatialDistanceY(this).toFloat()).also { transcriptionWriter = it }
        }
        // Mettre à jour le processor
        captureView?.strokeProcessor?.transcriptionWriter = newTw

        accumulatedText = ""
        wordTranscriptions.clear()

        Log.i(TAG, "🔄 Rafraîchissement de ${groups.size} groupes...")
        
        Thread {
            val results = mutableListOf<String>()
            for ((idx, group) in groups.withIndex()) {
                val text = recognizer.recognize(strokes, group)
                results.add(text.ifBlank { "?" })
                // Écrire dans le .transcription avec snapY et ordre
                val snapY = StrokeRenderer.computeSnapY(strokes, group)
                if (text.isNotBlank()) {
                    newTw.writeWord(snapY, text, orderIndex = idx)
                }
            }
            runOnUiThread {
                reloadFromTranscription()
                poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
                Log.i(TAG, "🔄 Transcriptions rafraîchies: $accumulatedText")
                // Peupler les labels d'interligne
                for ((idx, group) in groups.withIndex()) {
                    if (group.isNotEmpty() && idx < results.size) {
                        captureView?.onGroupInferred(group.first(), results[idx])
                    }
                }
                // Sauvegarder dans le fichier .note aussi
                val txWords = newTw.getOrderedWords()
                val path = captureView?.saveCurrentNote(mode = "blocnote", transcriptions = txWords)
                if (path != null) {
                    Toast.makeText(this@CaptureActivity, "💾 Transcriptions sauvegardées", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "💾 Transcriptions sauvegardées dans .note: $path")
                } else {
                    Log.w(TAG, "⚠️ Échec sauvegarde après refresh")
                }
            }
        }.apply { name = "refresh-transcriptions"; start() }
    }

    // ── UI helpers ─────────────────────────────────────────────────────

    /** Affiche le texte accumulé avec le mot du groupe actif encadré.
     *  Les sauts de ligne sont marqués par \\n visible.
     *  Le défilement horizontal est centré sur le mot actif. */
    private fun updatePoemText() {
        val text = accumulatedText
        if (text.isBlank()) {
            poemText?.text = "📝 Bloc-notes"
            poemText?.textSize = 28f
            return
        }
        // Remplacer les vrais \n par " \n " visible dans la ligne unique
        val displayText = text.replace("\n", " \\n ")

        // Trouver le mot actif via GroupManager (SELECTED > LOADED)
        val cv = captureView ?: return
        val gm = cv.groupManager
        val activeGroup = gm.groupsInState(GroupState.SELECTED).firstOrNull()
            ?: gm.groupsInState(GroupState.LOADED).firstOrNull()

        var activeWord: String? = null
        if (activeGroup != null) {
            val tw = transcriptionWriter
            val targetSeq = cv.getActiveGroupSeq()
            if (tw != null && tw.exists() && targetSeq != null) {
                val entries = tw.readAll()  // (snapY, orderIndex, text)
                activeWord = entries.find { it.second == targetSeq }?.third
            }
        }

        if (activeWord != null && activeWord.isNotBlank()) {
            val spannable = android.text.SpannableString(displayText)
            val wordIdx = displayText.indexOf(activeWord, ignoreCase = true)
            if (wordIdx >= 0) {
                spannable.setSpan(
                    android.text.style.BackgroundColorSpan(android.graphics.Color.argb(80, 0, 0, 0)),
                    wordIdx, wordIdx + activeWord.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Centrer horizontalement sur le mot surligné
                val targetIdx = wordIdx
                poemText?.text = spannable
                poemText?.post {
                    try {
                        val layout = poemText?.layout ?: return@post
                        val viewWidth = poemText?.width ?: return@post
                        if (viewWidth <= 0 || layout.lineCount == 0) return@post
                        val horiz = layout.getPrimaryHorizontal(targetIdx)
                        val scrollX = (horiz - viewWidth / 2f).toInt().coerceAtLeast(0)
                            .coerceAtMost(layout.width - viewWidth)
                        poemText?.scrollTo(scrollX.coerceAtLeast(0), 0)
                    } catch (_: Exception) {}
                }
            }
            poemText?.text = spannable
        } else {
            poemText?.text = displayText
        }
        poemText?.textSize = 28f
    }

    private fun makeToolbarButton(
        text: String,
        bgColor: Int,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 36f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(28, 20, 28, 20)
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER
            minWidth = 96
            minHeight = 96
            if (onClick != null) {
                setOnClickListener { onClick() }
            }
        }
    }

    // ── Notification persistante ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Miroir", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Miroir est actif" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showPersistentNotification() {
        val intent = Intent(this, CaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Miroir actif")
            .setContentText("Appuyer pour ouvrir")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
