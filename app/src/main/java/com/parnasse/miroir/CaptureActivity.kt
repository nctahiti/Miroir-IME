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
import android.os.Handler
import android.os.Looper
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
    private var modeBtn: TextView? = null
    private var editModeIndicator: TextView? = null  // 🚢🔦⏳

    // ── Reconnaissance ─────────────────────────────────────────────────
    private var wordRecognizer: DigitalInkWrapper? = null
    private var accumulatedText: String = ""
    private val wordTranscriptions = mutableListOf<String>()  // pour sauvegarde (transitoire, sera supprimé)

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
        val noteDir = java.io.File(filesDir, "blocnote")
        val baseName = "note_${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
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
            }
            // Persistance des groupes pour eviction du cache
            // ⚠️ DOIT être hors du callback — sinon jamais initialisée avant
            // le premier changement de groupe actif → groupes évincés irrécupérables
            cv.groupManager.persistence = GroupPersistence(GroupPersistence.groupsFile(noteDir, baseName))
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

        // ⚙ Paramètres
        topBar.addView(makeToolbarButton("⚙", android.graphics.Color.argb(180, 80, 80, 80)) {
            val intent = Intent(this@CaptureActivity, CalibrationActivity::class.java)
            startActivity(intent)
        })

        // ✕ Fermer
        topBar.addView(makeToolbarButton("✕", android.graphics.Color.argb(200, 150, 0, 0)) {
            Log.i(TAG, "✕ Fermeture — sauvegarde via groupLabels")
            captureView?.saveCurrentNote(mode = "blocnote", transcriptions = captureView?.getOrderedTranscriptions() ?: emptyList())
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
                transcriptions = captureView?.getOrderedTranscriptions() ?: emptyList()
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

        // Indicateur mode édition (🚢 CAPTURE / 🔦 EDIT_SPATIAL / ⏳ EDIT_TEMPORAL)
        // Placé sous le bouton 📝, aligné à gauche
        editModeIndicator = TextView(this).apply {
            text = "🚢"
            textSize = 28f
            setTextColor(android.graphics.Color.argb(220, 40, 40, 40))
            setPadding(18, 12, 18, 12)
            setBackgroundColor(android.graphics.Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            setOnLongClickListener {
                val words = captureView?.getOrderedTranscriptions() ?: emptyList()
                if (words.isEmpty()) {
                    Toast.makeText(this@CaptureActivity, "Aucun texte à copier", Toast.LENGTH_SHORT).show()
                } else {
                    val text = words.joinToString(" ")
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("miroir", text))
                    Toast.makeText(this@CaptureActivity, "📋 ${words.size} mots copiés", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        overlay.addView(editModeIndicator!!, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.START; leftMargin = 16 })

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
    }

    // ── Reconnaissance ─────────────────────────────────────────────────

    /** Appelé par StrokeProcessor quand un mot est reconnu (déjà sur UI thread). */
    private fun onWordRecognized(text: String) {
        // Le label est DÉJÀ dans CaptureView.groupLabels (source unique).
        // Plus de fichier .transcription à synchroniser.
        Log.i(TAG, "Reconnu: '$text' → groupLabels")
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun onValidate() {
        Log.i(TAG, "✓ Validation — source unique groupLabels")
        captureView?.saveCurrentNote(
            mode = "blocnote",
            transcriptions = captureView?.getOrderedTranscriptions() ?: emptyList()
        )
        // Nouvelle page blanche
        captureView?.startBlocNoteSession()
        captureView?.clear()
        wordTranscriptions.clear()
        Log.i(TAG, "✓ Nouvelle page")
    }

    private fun onReset() {
        Log.i(TAG, "↺ Reset")
        captureView?.clear()
        accumulatedText = ""
        wordTranscriptions.clear()
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
            Log.i(TAG, "⬅ Sauvegarde page courante avant navigation")
            captureView?.saveCurrentNote(mode = "blocnote", transcriptions = captureView?.getOrderedTranscriptions() ?: emptyList())
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
        
        // Charger la note — groupLabels est peuplé par loadNoteFile()
        captureView?.loadNoteFile(file)
        
        // Persistance des groupes pour le chargement de page
        val noteDir = file.parentFile ?: java.io.File(filesDir, "blocnote")
        val baseName = file.nameWithoutExtension
        captureView?.groupManager?.persistence = GroupPersistence(GroupPersistence.groupsFile(noteDir, baseName))
        
        Log.i(TAG, "Page ${index + 1}/${blocNoteFiles.size}: ${file.name} (labels: ${captureView?.getOrderedTranscriptions()?.size ?: 0})")
    }

    // ── Rafraîchir toutes les transcriptions ─────────────────────────

    private fun refreshAllTranscriptions() {
        val recognizer = wordRecognizer ?: return
        if (!recognizer.isLoaded) return
        
        val strokes = captureView?.getStrokeRegistry() ?: return
        val groups = captureView?.computeWordGroupsForSave() ?: return
        if (groups.isEmpty()) return

        Log.i(TAG, "🔄 Rafraîchissement de ${groups.size} groupes...")
        
        Thread {
            for ((idx, group) in groups.withIndex()) {
                val text = recognizer.recognize(strokes, group)
                val firstIdx = group.firstOrNull() ?: continue
                if (text.isNotBlank()) {
                    runOnUiThread {
                        captureView?.onGroupInferred(firstIdx, text)
                    }
                }
            }
            runOnUiThread {
                Log.i(TAG, "🔄 Transcriptions rafraîchies: ${captureView?.getOrderedTranscriptions()?.size ?: 0} labels")
                Toast.makeText(this, "🔄 ${groups.size} groupes rafraîchis", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ── UI helpers ─────────────────────────────────────────────────────

    /** Rafraîchit l'interface après un changement de groupe actif. */
    private fun onActiveGroupChanged() {
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
