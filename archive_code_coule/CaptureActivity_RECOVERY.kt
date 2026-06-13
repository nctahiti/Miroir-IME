package com.parnasse.miroir

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * CaptureActivity — Interface complète d'écriture de haïkus.
 *
 * Toolbar revisitée :
 *   [Niveau: ▼]  [➕ Série]  [✕ Fermer]
 *   ┌─────────────────────────────────────┐
 *   │         haïku à recopier            │
 *   │  (3 lignes centrées, fond translucide)│
 *   │                                     │
 *   │          (zone d'écriture)          │
 *   │        CaptureView plein écran      │
 *   │                                     │
 *   ├─────────────────────────────────────┤
 *   │   [✓ Valider]    [↺ Reset]          │
 *   └─────────────────────────────────────┘
 *
 * Le flux V* est écrit en continu.
 * À chaque validation : POST → Cœur + haïku aléatoire suivant.
 *
 * Niveaux :
 *   - Niveau 1 (Base) : les 8 haïkus classiques
 *   - Niveau 2 (Haïkus libres) : à définir (vide par défaut)
 *   - Personnalisé : séries ajoutées via ➕ Série
 */
class CaptureActivity : Activity() {

    companion object {
        /** Intent extra : chemin d'un fichier .vstar a charger dans l'editeur */
        const val EXTRA_VSTAR_PATH = "com.parnasse.miroir.EXTRA_VSTAR_PATH"

        private const val TAG = "Miroir/Activity"
        private const val CHANNEL_ID = "miroir_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val VSTAR_DIR = "vstar"
        private const val BLOCNOTE_DIR = "blocnote"
        private const val RAW_DIR = "raw_capture"
        private const val INGEST_URL = "http://127.0.0.1:8005/api/miroir/ingest-vstar"
        private const val CORPUS_URL = "http://127.0.0.1:8005/api/training/texts"
        private const val TAG_API = "MiroirAPI"
        private const val TAG_CORPUS = "Miroir/Corpus"
        private const val DEVICE_NAME = "Boox Note Air 5C"
        private const val RESOLUTION = "1860x2480"
        private const val PREFS_NAME = "miroir_haikus"
        private const val PREFS_CUSTOM = "custom_series"
    }

    private var captureView: CaptureView? = null
    private var currentFile: File? = null
    private var editingVStarPath: String? = null  // fichier charge en mode edition
    private var statsText: TextView? = null
    private var poemText: TextView? = null
    private var poemCount: TextView? = null
    private var levelText: TextView? = null
    private var currentLevelIdx: Int = 0
    private var currentHaiku: String = ""
    private var haikusWritten: Int = 0
    private var levelNames: Array<String> = arrayOf()
    private var levelMap: MutableMap<String, MutableList<String>> = linkedMapOf()
    private var curriculumSections: List<CurriculumSection> = emptyList()
    private var recentIndices: MutableList<Int> = mutableListOf()

    // ── Mode system ────────────────────────────────────────────────
    private var modeButtons = mutableListOf<TextView>()
    private var deleteButton: TextView? = null
    private var isBlocnoteMode = false
    private var blocnoteButton: TextView? = null
    private var prevLevelBtn: TextView? = null
    private var nextLevelBtn: TextView? = null
    private var plusButton: TextView? = null
    private var refreshButton: TextView? = null
    private var resetButton: TextView? = null
    
    // ── Bloc-note stack ────────────────────────────────────────────
    /** Liste triee des fichiers bloc-notes existants (par ordre chronologique) */
    private var blocNoteFiles: MutableList<File> = mutableListOf()
    /** Index dans la pile (-1 = nouvelle page blanche en cours d'ecriture) */
    private var blocNoteIndex: Int = -1
    /** Texte affichant "page X/Y" dans la navBar */
    private var pageCounter: TextView? = null
    /** Boutons ⬅➡ pour la navigation */
    private var prevNoteButton: TextView? = null
    private var nextNoteButton: TextView? = null
    
    // ── Bigram trainer ─────────────────────────────────────────────
    private var bigramTrainer: BigramTrainer? = null
    private var currentBigramEntry: BigramEntry? = null
    
    // ── Word trainer ────────────────────────────────────────────────
    private var wordTrainer: WordTrainer? = null
    private var currentWordEntry: WordEntry? = null
    
    // ── UI training (unifié : marche autant pour bigrammes que mots) ─
    private var trainingProgressText: TextView? = null
    private var trainingConfirmText: TextView? = null

    // ── Review mode (navigation paires) → réserve pour render futur ──
    // CaptureMode.REVIEW défini dans StrokeRecord.kt
    
    // ── ONNX inference engine ─────────────────────────────────────
    private var onnxEngine: OnnxEngine? = null
    
    // ── Transcription cumulative (bloc-note) ────────────────────────
    /** Accumulation des transcriptions ONNX, affiche dans poemText */
    private var accumulatedTranscription: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "=== CAPTURE ACTIVITY V2 CRÉÉE ===")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Notification persistante pour retrouver Miroir
        createNotificationChannel()
        showPersistentNotification()

        // Charger les niveaux depuis le curriculum unifié
        loadCurriculum()
        
        // Initialiser BigramTrainer (singleton, persistant)
        bigramTrainer = BigramTrainer.getInstance(this)
        
        // Initialiser WordTrainer (singleton, persistant)
        wordTrainer = WordTrainer.getInstance(this)

        // Dossier V*
        val vstarDir = File(filesDir, VSTAR_DIR).also { it.mkdirs() }

        // CaptureView
        captureView = CaptureView(this).also { cv ->
            cv.onWordGroupCompleted = { strokes, group ->
                onAutoInfer(strokes, group)
            }
        }
        // Ne PAS ouvrir de fichier vstar ici — on demarre directement en bloc-note
        closeVStarFile(save = false)

        // Scanner les fichiers bloc-notes existants
        scanBlocnoteFiles()

        // Layout racine
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        // CaptureView → fond
        root.addView(captureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Layout superposé pour les contrôles
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Barre du haut : actions ──
        // Layout : [✕ ↺ ➕] gauche | [◀ section ▶] centre | [In Ed ✓] droite
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 38, 12, 6)
            setBackgroundColor(android.graphics.Color.argb(220, 255, 255, 255))
        }

        // ── Nouvel ordre : 📝 ✕ ➕ | ◀ section ▶ | 🗑 ✎ Ed In ↻ ✓ ↺ ──
        // 📝 Bloc-notes (tout à gauche)
        blocnoteButton = makeToolbarButton("📝", android.graphics.Color.argb(180, 80, 80, 80)).apply {
            setOnClickListener { onBlocnoteToggle() }
        }
        topBar.addView(blocnoteButton!!)
        // ✕ Fermer
        topBar.addView(makeToolbarButton("✕", android.graphics.Color.argb(200, 150, 0, 0)))
        // ➕ Série
        plusButton = makeToolbarButton("➕", android.graphics.Color.argb(200, 80, 80, 80))
        topBar.addView(plusButton!!)

        // ── Centre : ◀ section ▶ (flex weight 1) ──
        levelText = TextView(this).apply {
            text = levelNames.getOrElse(0) { "Niveau" }
            textSize = 11f
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(8, 4, 8, 4)
            gravity = Gravity.CENTER
        }
        val prevLevel = makeToolbarButton("◀", android.graphics.Color.TRANSPARENT,
            android.graphics.Color.argb(180, 80, 80, 80)) {
            currentLevelIdx = ((currentLevelIdx - 1) + levelNames.size) % levelNames.size
            levelText?.text = levelNames.getOrElse(currentLevelIdx) { "?" }
            pickRandomHaiku()
        }
        val nextLevel = makeToolbarButton("▶", android.graphics.Color.TRANSPARENT,
            android.graphics.Color.argb(180, 80, 80, 80)) {
            currentLevelIdx = (currentLevelIdx + 1) % levelNames.size
            levelText?.text = levelNames.getOrElse(currentLevelIdx) { "?" }
            pickRandomHaiku()
        }

        prevLevelBtn = prevLevel
        nextLevelBtn = nextLevel
        topBar.addView(prevLevel)
        topBar.addView(levelText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        topBar.addView(nextLevel)

        // 🗑 Delete (caché par défaut, visible en mode EDIT)
        deleteButton = makeToolbarButton("🗑", android.graphics.Color.argb(200, 180, 30, 30)).apply {
            visibility = View.GONE
            setOnClickListener {
                captureView?.deleteSelectedStroke()
                visibility = View.GONE
            }
        }
        topBar.addView(deleteButton!!)

        val drawBtn = makeToolbarButton("✎", android.graphics.Color.argb(200, 0, 100, 150))
        val editBtn = makeToolbarButton("Ed", android.graphics.Color.argb(180, 80, 80, 80))
        val insBtn = makeToolbarButton("In", android.graphics.Color.argb(180, 80, 80, 80))
        modeButtons = mutableListOf(drawBtn, editBtn, insBtn)

        drawBtn.setOnClickListener { setMode(CaptureMode.CAPTURE) }
        editBtn.setOnClickListener { setMode(CaptureMode.EDIT) }
        insBtn.setOnClickListener { setMode(CaptureMode.INSERT) }

        topBar.addView(drawBtn)
        topBar.addView(editBtn)
        topBar.addView(insBtn)
        // ↻ Rafraîchir
        refreshButton = makeToolbarButton("↻", android.graphics.Color.argb(200, 0, 80, 120))
        topBar.addView(refreshButton!!)
        // ✓ Valider
        topBar.addView(makeToolbarButton("✓", android.graphics.Color.argb(200, 0, 100, 0)))
        // ↺ Reset (tout à droite)
        resetButton = makeToolbarButton("↺", android.graphics.Color.argb(200, 120, 80, 0))
        topBar.addView(resetButton!!)

        overlay.addView(topBar)
        
        // Demarrer en mode bloc-notes par defaut (apres creation des boutons)
        isBlocnoteMode = true
        applyBlocnoteState()

        // ── Barre de navigation : ⬅ page X/Y ➡ (sous la barre du haut) ──
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setPadding(12, 2, 12, 2)
            setBackgroundColor(android.graphics.Color.argb(180, 255, 255, 255))
        }
        // ⬅ Retour (note précédente)
        prevNoteButton = makeToolbarButton("⬅", android.graphics.Color.argb(200, 80, 80, 80)).apply {
            setOnClickListener { goToPrevBlocNote() }
        }
        navBar.addView(prevNoteButton!!)
        // Compteur de pages
        pageCounter = TextView(this).apply {
            text = "0/0"
            textSize = 14f
            setTextColor(android.graphics.Color.argb(160, 0, 0, 0))
            gravity = Gravity.CENTER
            setPadding(20, 4, 20, 4)
        }
        navBar.addView(pageCounter, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        // ➡ Suivant (note suivante)
        nextNoteButton = makeToolbarButton("➡", android.graphics.Color.argb(200, 0, 80, 160)).apply {
            setOnClickListener { goToNextBlocNote() }
        }
        navBar.addView(nextNoteButton!!)
        overlay.addView(navBar)

        // ── Texte de transcription ──
        poemText = TextView(this).apply {
            textSize = 36f
            setTextColor(android.graphics.Color.rgb(60, 60, 60))
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1.0f)
            setPadding(40, 12, 40, 12)
            // Fond solide (pas de transparence — l'écran E-Ink ne la gère pas bien)
            setBackgroundColor(android.graphics.Color.rgb(255, 255, 220))
            maxLines = 3
            text = "📝 Bloc-notes"
        }
        overlay.addView(poemText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── Compteur ──
        poemCount = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.argb(100, 0, 0, 0))
            gravity = Gravity.CENTER
        }
        overlay.addView(poemCount, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ── Training progress (bigrammes + mots, unifié) ──
        trainingProgressText = TextView(this).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.argb(180, 0, 100, 0))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        overlay.addView(trainingProgressText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ── Bouton ✓ Écrit ! (bigrammes + mots) ──
        trainingConfirmText = TextView(this).apply {
            text = "✓ Écrit !"
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(48, 24, 48, 24)
            setBackgroundColor(android.graphics.Color.argb(200, 40, 160, 60))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { onConfirmCurrent(); }
        }
        val trainingConfirmLayout = FrameLayout(this)
        trainingConfirmLayout.addView(trainingConfirmText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = Gravity.CENTER })
        overlay.addView(trainingConfirmLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Espace pour la capture
        overlay.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        // Stats en bas (infos)
        statsText = TextView(this).apply {
            text = "V* v2.0"
            setTextColor(android.graphics.Color.argb(60, 0, 0, 0))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        overlay.addView(statsText)

        // ── Callbacks CaptureView ──
        captureView?.onModeChanged = { mode -> onModeChanged(mode) }
        captureView?.onSelectionChanged = { idx -> onStrokeSelected(idx) }

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        // Charger le moteur ONNX (mode Bloc-notes : reconnaissance cursive)
        onnxEngine = OnnxEngine(this)
        val onnxOk = onnxEngine!!.load()
        if (!onnxOk) {
            Log.w(TAG, "⚠️ ONNX engine non chargé — reconnaissance indisponible")
            onnxEngine = null
            poemText?.text = "⚠️ ONNX: échec chargement"
            poemText?.setTextColor(android.graphics.Color.rgb(200, 50, 50))
        } else {
            Log.i(TAG, "✅ ONNX engine prêt")
            poemText?.text = "✅ ONNX prêt"
            poemText?.setTextColor(android.graphics.Color.rgb(40, 160, 60))

            // Charger le LM optionnel pour shallow fusion
            try {
                val lm = CharLM(this)
                onnxEngine!!.charLM = lm
                onnxEngine!!.setLmWeight(0.1f)
                Log.i(TAG, "✅ CharLM chargé, weight=0.1")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ CharLM non disponible, greedy pur: ${e.message}")
            }
        }

        // Afficher le premier haïku ou charger un fichier VStar
        handleVStarIntent(intent)

        MiroirService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        handleVStarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (captureView != null && !captureView!!.touchHelperAttempted) {
            Log.i(TAG, "Init TouchHelper depuis onResume")
            captureView!!.initTouchHelper()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "=== CAPTURE ACTIVITY DÉTRUITE ===")
        onnxEngine?.close()
        onnxEngine = null
        closeVStarFile(save = false)
        captureView?.releaseTouchHelper()
        captureView?.clear()
        captureView = null
        super.onDestroy()
    }

    // ── Traitement intent VStar (éditeur) ───────────────────────────────

    /**
     * Charge un fichier .vstar depuis l'intent, ou reprend la capture normale.
     * Sauvegarde automatiquement la session de capture en cours avant.
     */
    private fun handleVStarIntent(intent: Intent?) {
        val vstarPath = intent?.getStringExtra(EXTRA_VSTAR_PATH) ?: ""
        if (vstarPath.isEmpty()) {
            editingVStarPath = null
            pickRandomHaiku()
            return
        }

        val vstarFile = File(vstarPath)
        if (!vstarFile.exists()) {
            Log.w(TAG, "Fichier VStar introuvable: $vstarPath")
            editingVStarPath = null
            pickRandomHaiku()
            return
        }

        // Sauvegarder la session de capture en cours avant de charger le fichier
        closeVStarFile(save = true)
        editingVStarPath = vstarPath

        Log.i(TAG, "Chargement fichier: $vstarPath")
        val loaded = if (vstarPath.endsWith(".note"))
            captureView?.loadNoteFile(vstarFile) == true
        else
            captureView?.loadVStarFile(vstarFile)
        poemText?.text = "✎ Edition: ${vstarFile.name}"
        poemText?.textSize = 32f
        trainingProgressText?.visibility = View.GONE
        trainingConfirmText?.visibility = View.GONE

        // Nouveau fichier de capture pour les ajouts (INSERT mode)
        // ATTENTION : openNewVStarFile appelle closeVStarFile qui nullifie editingVStarPath
        // => le restaurer immediatement apres
        openNewVStarFile()
        editingVStarPath = vstarPath
    }

    // ── Niveaux ──────────────────────────────────────────────────────────

    private fun loadCurriculum() {
        curriculumSections = CurriculumLoader.load(this)
        levelNames = curriculumSections.map { it.name }.toTypedArray()

        levelMap.clear()
        for (section in curriculumSections) {
            when (section.type) {
                SectionType.LIST -> levelMap[section.name] = section.items.toMutableList()
                SectionType.TRAINING -> levelMap[section.name] = mutableListOf("${section.trainer}:placeholder")
                SectionType.HISTORY -> levelMap[section.name] = mutableListOf("history:placeholder")
            }
        }

        // Charger les séries personnalisées depuis SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customJson = prefs.getString(PREFS_CUSTOM, "") ?: ""
        if (customJson.isNotEmpty()) {
            try {
                val lines = customJson.split("\n").filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    val customSection = CurriculumSection(
                        name = "Personnalisé",
                        type = SectionType.LIST,
                        items = lines
                    )
                    curriculumSections = curriculumSections + customSection
                    levelMap["Personnalisé"] = lines.toMutableList()
                    levelNames = curriculumSections.map { it.name }.toTypedArray()
                }
            } catch (_: Exception) {}
        }

        recentIndices.clear()
    }

    private fun saveCustomSeries(haikus: List<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_CUSTOM, haikus.joinToString("\n")).apply()
        // Recharger
        loadCurriculum()
        // Passer au niveau Personnalisé
        val customIdx = levelNames.indexOf("Personnalisé")
        if (customIdx >= 0) {
            currentLevelIdx = customIdx
            levelText?.text = levelNames.getOrElse(customIdx) { "?" }
            pickRandomHaiku()
        }
    }

    // ── Sélection aléatoire ──────────────────────────────────────────────

    private fun pickRandomHaiku() {
        if (currentLevelIdx < 0 || currentLevelIdx >= levelNames.size) return
        val levelName = levelNames[currentLevelIdx]
        
        // ── Mode Bigrammes / Mots (training unifié) ──
        // Vérifier d'abord les trainers legacy (noms exacts)
        if (levelName == "Bigrammes" || levelName == "Curriculum Mots") {
            pickFromTrainerLegacy(levelName)
            return
        }
        
        // Dispatch par type de section
        val section = curriculumSections.getOrNull(currentLevelIdx) ?: return
        
        when (section.type) {
            SectionType.TRAINING -> pickFromTrainer(section)
            SectionType.HISTORY -> pickFromHistory()
            SectionType.LIST -> pickFromList(section)
        }
    }

    // ── Confirmer l'écriture courante (TRAINING ou LIST) ──
    
    private fun onConfirmCurrent() {
        val section = curriculumSections.getOrNull(currentLevelIdx)
        if (section?.type == SectionType.LIST) {
            // Mode LIST : sauvegarder la capture AVEC son label, puis avancer
            onValidate()
            advanceToNext()
        } else {
            // Mode TRAINING : via le trainer existant (bigram/word)
            onTrainingComplete()
            advanceToNext()
        }
    }

    // ── Sélection depuis une section LIST ──

    // ── Avancer à la paire suivante ──────────────────────────────
    
    private fun advanceToNext() {
        // Nettoyer les entries → forcer le chargement d'une nouvelle paire
        currentBigramEntry = null
        currentWordEntry = null
        pickRandomHaiku()
    }

    // ── Revenir à la paire précédente ──────────────────────────────

    private fun goBack() {
        // Pour les trainers (bigram/word) → revenir à la paire précédente
        if (currentBigramEntry != null || currentWordEntry != null) {
            val isBigram = currentBigramEntry != null
            if (isBigram) {
                bigramTrainer?.rewind(1)
            } else {
                wordTrainer?.rewind(1)
            }
            currentBigramEntry = null
            currentWordEntry = null
            pickRandomHaiku()
            return
        }
        // Pour les listes/historique → section précédente
        currentLevelIdx = ((currentLevelIdx - 1) + levelNames.size) % levelNames.size
        levelText?.text = levelNames.getOrElse(currentLevelIdx) { "?" }
        pickRandomHaiku()
    }

    private fun pickFromList(section: CurriculumSection) {
        val haikus = levelMap[section.name] ?: return
        
        trainingProgressText?.visibility = View.GONE
        trainingConfirmText?.visibility = View.VISIBLE
        
        if (haikus.isEmpty()) {
            poemText?.text = "Aucun texte — ajoutez-en avec ➕ Série"
            poemText?.textSize = 18f
            poemCount?.text = ""
            currentHaiku = ""
            return
        }

        // Éviter les 3 derniers
        val available = haikus.indices.filter { it !in recentIndices }
        val pickFrom = if (available.isEmpty()) haikus.indices.toList() else available

        val idx = pickFrom.random()
        recentIndices.add(idx)
        if (recentIndices.size > 4) recentIndices.removeAt(0)

        currentHaiku = haikus[idx]
        val lines = currentHaiku.split(" / ")
        poemText?.text = lines.joinToString("\n")
        poemText?.textSize = 18f
        poemText?.setTextColor(android.graphics.Color.argb(200, 60, 60, 60))

        val total = haikus.size
        val written = haikusWritten
        val subtitle = if (section.subtitle.isNotEmpty()) " — ${section.subtitle}" else ""
        poemCount?.text = "$written écrits$subtitle ($total items)"
        poemCount?.setTextColor(android.graphics.Color.argb(100, 0, 0, 0))
        
        // Lancer une session avec le nouveau haiku (fix: label correct des l'affichage)
        captureView?.startSession(currentHaiku)
    }

    // ── Legacy : sélection depuis un trainer par nom ──

    private fun pickFromTrainerLegacy(levelName: String) {
        val isBigram = levelName == "Bigrammes"
        val trainer = if (isBigram) bigramTrainer else wordTrainer
        val trainerProgress = if (isBigram) bigramTrainer?.progress else wordTrainer?.progress
        val trainerStats = if (isBigram) bigramTrainer?.getStats() else wordTrainer?.getStats()
        
        val next = if (isBigram) bigramTrainer?.getNext() else wordTrainer?.getNext()
        val isComplete = if (isBigram) bigramTrainer?.isComplete == true else wordTrainer?.isComplete == true
        val doneMsg = if (isBigram) "✅ Bigrammes terminés !" else "✅ Mots terminés !"
        
        if (next == null || isComplete) {
            poemText?.text = doneMsg
            poemText?.textSize = 32f
            poemCount?.text = ""
            trainingProgressText?.visibility = View.GONE
            trainingConfirmText?.visibility = View.GONE
            currentHaiku = ""
            currentBigramEntry = null
            currentWordEntry = null
            return
        }
        
        val displayText: String
        val tierLabel: String
        val tierColor: Int
        val note: String
        
        if (isBigram) {
            val entry = next as BigramEntry
            currentBigramEntry = entry
            currentWordEntry = null
            displayText = entry.pair
            tierLabel = "Tier ${entry.tier}"
            note = ""
            tierColor = when (entry.tier) {
                "T1" -> android.graphics.Color.argb(200, 200, 160, 0)
                "T2" -> android.graphics.Color.argb(200, 0, 120, 180)
                "T3" -> android.graphics.Color.argb(200, 40, 140, 60)
                else -> android.graphics.Color.argb(100, 0, 0, 0)
            }
        } else {
            val entry = next as WordEntry
            currentWordEntry = entry
            currentBigramEntry = null
            displayText = entry.word
            tierLabel = "Palier ${entry.tier}"
            note = if (entry.note.isNotEmpty()) " (${entry.note})" else ""
            tierColor = when (entry.tier) {
                "P1" -> android.graphics.Color.argb(200, 200, 100, 0)
                "P2" -> android.graphics.Color.argb(200, 0, 130, 180)
                "P3" -> android.graphics.Color.argb(200, 40, 150, 60)
                "P4" -> android.graphics.Color.argb(200, 160, 40, 140)
                "P5" -> android.graphics.Color.argb(200, 180, 30, 30)
                else -> android.graphics.Color.argb(100, 0, 0, 0)
            }
        }
        
        currentHaiku = displayText
        poemText?.text = displayText
        poemText?.textSize = 48f
        poemText?.setTextColor(android.graphics.Color.argb(200, 0, 0, 0))
        poemCount?.text = "$tierLabel$note — \"$displayText\""
        poemCount?.setTextColor(tierColor)

        // Demarrer une nouvelle session de capture avec le label correct
        // (fix: le label est celui affiche a l'ecran, pas celui du precedent complete)
        captureView?.startSession(displayText)

        val pct = ((trainerProgress ?: 0f) * 100).toInt()
        val stats = trainerStats ?: ""
        trainingProgressText?.text = "$pct% — $stats"
        trainingProgressText?.visibility = View.VISIBLE
        trainingConfirmText?.visibility = View.VISIBLE
    }

    // ── Sélection depuis une section TRAINING ──

    private fun pickFromTrainer(section: CurriculumSection) {
        val trainerName = section.trainer ?: return
        when (trainerName) {
            "bigram" -> pickFromTrainerLegacy("Bigrammes")
            "word" -> pickFromTrainerLegacy("Curriculum Mots")
            else -> {
                poemText?.text = "Trainer inconnu: $trainerName"
                poemText?.textSize = 32f
            }
        }
    }

    // ── Sélection depuis une section HISTORY ──

    private fun pickFromHistory() {
        trainingProgressText?.visibility = View.GONE
        trainingConfirmText?.visibility = View.GONE
        
        showHistoryDialog()
        
        poemText?.text = "📂 Historique — Choisissez un fichier"
        poemText?.textSize = 32f
        poemCount?.text = ""
        currentHaiku = ""
    }

    // ── Gestion fichier ────────────────────────────────────────────────

    private fun openNewVStarFile() {
        closeVStarFile(save = false)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val vstarDir = File(filesDir, VSTAR_DIR)
        vstarDir.mkdirs()
        currentFile = File(vstarDir, "capture_$timestamp.csv")
        // Le writer brut est gere par CaptureView via startSession()
    }

    private fun closeVStarFile(save: Boolean) {
        // CaptureView ferme son propre rawWriter dans clear()
        currentFile = null
    }

    /** Sauvegarde les modifications du fichier edite (EDIT mode) sans effacer la vue */
    private fun closeVStarFileSaveEditOnly() {
        val cv = captureView
        val editingFile = editingVStarPath?.let { File(it) }
        if (cv == null || editingFile == null || !editingFile.exists()) return
        try {
            captureView?.saveCurrentNote(mode = "edit")
            Log.i(TAG, "Editeur: sauvegarde .note effectuee")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sauvegarde edition: ${e.message}")
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun makeButton(text: String, bgColor: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(28, 12, 28, 12)
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER
            setOnClickListener { onButtonClick(text) }
        }
    }

    /** Bouton toolbar standard — taille confortable pour le doigt (48dp+) */
    private fun makeToolbarButton(
        text: String,
        bgColor: Int,
        textColor: Int = android.graphics.Color.WHITE,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 36f  // ×1.8 de 20f ≈ 200%
            setTextColor(textColor)
            setPadding(28, 20, 28, 20)  // +75%
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER
            minWidth = 96  // ×1.7 de 56
            minHeight = 96  // ×1.7 de 56
            if (onClick != null) {
                setOnClickListener { onClick() }
            } else {
                setOnClickListener { onButtonClick(text) }
            }
        }
    }

    // ── Gestion des modes ──────────────────────────────────────────

    private fun setMode(mode: CaptureMode) {
        captureView?.currentMode = mode
        val colors = listOf(
            android.graphics.Color.argb(200, 0, 100, 150),
            android.graphics.Color.argb(200, 100, 80, 0),
            android.graphics.Color.argb(200, 0, 120, 80)
        )
        val inactive = android.graphics.Color.argb(180, 80, 80, 80)
        for (i in 0 until 3) {
            modeButtons.getOrNull(i)?.setBackgroundColor(
                if (mode.ordinal == i) colors[i] else inactive
            )
        }
        Log.i(TAG, "Mode: ${mode.label}")
    }

    private fun onModeChanged(mode: CaptureMode) {
        if (mode != CaptureMode.EDIT) {
            deleteButton?.visibility = View.GONE
        }
    }

    // =========================================================================
    // BLOC-NOTE STACK MANAGEMENT
    // =========================================================================

    /** Scanne le dossier blocnote/ et trie les fichiers par date */
    private fun scanBlocnoteFiles() {
        val dir = File(filesDir, BLOCNOTE_DIR)
        if (!dir.exists()) {
            blocNoteFiles.clear()
            return
        }
        blocNoteFiles = dir.listFiles { f -> f.name.startsWith("note_") && f.name.endsWith(".note") }
            ?.sortedBy { it.name }
            ?.toMutableList() ?: mutableListOf()
        // index ne change pas pendant le scan
        updatePageCounter()
    }

    /** Met a jour le compteur de pages */
    private fun updatePageCounter() {
        val total = blocNoteFiles.size
        val current = if (blocNoteIndex < 0) total + 1 else blocNoteIndex + 1
        pageCounter?.text = "$current / ${total + 1}"
        // Activer/desactiver les boutons ⬅➡ selon la position
        prevNoteButton?.isEnabled = (blocNoteIndex > 0 || (blocNoteIndex < 0 && total > 0))
        nextNoteButton?.isEnabled = false  // desactive en mode nouvelle page
        prevNoteButton?.alpha = if (prevNoteButton?.isEnabled == true) 1f else 0.3f
        nextNoteButton?.alpha = 0.3f
    }

    /** Va a la note precedente dans la pile bloc-notes */
    private fun goToPrevBlocNote() {
        if (!isBlocnoteMode) return
        if (blocNoteFiles.isEmpty()) return

        val targetIdx = if (blocNoteIndex < 0) blocNoteFiles.size - 1 else blocNoteIndex - 1
        if (targetIdx < 0) return

        if (blocNoteIndex < 0) {
            // Ne sauvegarder que s'il y a des strokes
            val hasStrokes = captureView?.hasStrokes() ?: false
            if (hasStrokes) {
                captureView?.saveCurrentNote(mode = "blocnote")
                scanBlocnoteFiles()
                val newTargetIdx = blocNoteFiles.size - 1
                if (newTargetIdx < 0) return
                loadBlocNotePage(newTargetIdx)
            } else {
                // Page vierge, pas de sauvegarde
                loadBlocNotePage(targetIdx)
            }
        } else {
            loadBlocNotePage(targetIdx)
        }
    }

    /** Va a la note suivante dans la pile bloc-notes */
    private fun goToNextBlocNote() {
        if (!isBlocnoteMode) return
        if (blocNoteIndex < 0) return  // deja sur la nouvelle page
        if (blocNoteIndex >= blocNoteFiles.size - 1) {
            startNewBlocNotePage()
            return
        }
        loadBlocNotePage(blocNoteIndex + 1)
    }

    /** Charge une page bloc-note existante */
    private fun loadBlocNotePage(index: Int) {
        if (index < 0 || index >= blocNoteFiles.size) return
        blocNoteIndex = index
        val file = blocNoteFiles[index]
        captureView?.loadNoteFile(file)
        updatePageCounter()
        // Mettre a jour les etats des boutons
        prevNoteButton?.isEnabled = (blocNoteIndex > 0)
        prevNoteButton?.alpha = if (blocNoteIndex > 0) 1f else 0.3f
        nextNoteButton?.isEnabled = (blocNoteIndex < blocNoteFiles.size - 1)
        nextNoteButton?.alpha = if (blocNoteIndex < blocNoteFiles.size - 1) 1f else 0.3f
        // Afficher les transcriptions de la note chargee si disponibles
        val noteTranscriptions = captureView?.getNoteTranscriptions()
        if (noteTranscriptions != null && noteTranscriptions.isNotEmpty()) {
            poemText?.text = noteTranscriptions.joinToString(" ")
            poemText?.textSize = 24f
            poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
        } else {
            poemText?.text = "📝 Note ${index + 1}"
            poemText?.textSize = 28f
            poemText?.setTextColor(android.graphics.Color.argb(180, 100, 180, 255))
        }
    }

    /** Demarre une nouvelle page vierge dans le bloc-notes */
    private fun startNewBlocNotePage() {
        blocNoteIndex = -1
        captureView?.startBlocNoteSession()
        updatePageCounter()
        // Afficher la transcription accumulée (ne pas l'écraser)
        if (accumulatedTranscription.isNotEmpty()) {
            poemText?.text = accumulatedTranscription
            poemText?.textSize = 24f
            poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
        } else {
            poemText?.text = "📝 Bloc-notes (en attente d'écriture)"
            poemText?.textSize = 24f
            poemText?.setTextColor(android.graphics.Color.rgb(120, 120, 120))
        }
        prevNoteButton?.isEnabled = (blocNoteFiles.size > 0)
        prevNoteButton?.alpha = if (blocNoteFiles.size > 0) 1f else 0.3f
        nextNoteButton?.isEnabled = false
        nextNoteButton?.alpha = 0.3f
    }

    /** Bascule mode bloc-notes (appele par le bouton) */
    private fun onBlocnoteToggle() {
        isBlocnoteMode = !isBlocnoteMode
        applyBlocnoteState()
    }
    
    /** Applique l'etat bloc-note a l'UI et au CaptureView */
    private fun applyBlocnoteState() {
        blocnoteButton?.setTextColor(if (isBlocnoteMode)
            android.graphics.Color.argb(255, 100, 200, 255)
        else
            android.graphics.Color.argb(180, 80, 80, 80))
        captureView?.isBlocnoteMode = isBlocnoteMode

        // En mode bloc-notes : cacher les controles d'entrainement
        val trainingVisible = if (isBlocnoteMode) View.GONE else View.VISIBLE
        levelText?.visibility = trainingVisible
        prevLevelBtn?.visibility = trainingVisible
        nextLevelBtn?.visibility = trainingVisible
        plusButton?.visibility = trainingVisible
        modeButtons.forEach { it.visibility = trainingVisible }
        refreshButton?.visibility = if (isBlocnoteMode) View.GONE else View.VISIBLE

        if (isBlocnoteMode) {
            // Afficher la transcription accumulée ou le placeholder
            if (accumulatedTranscription.isNotEmpty()) {
                poemText?.text = accumulatedTranscription
                poemText?.textSize = 24f
                poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
            } else {
                poemText?.text = "📝 Bloc-notes"
                poemText?.textSize = 28f
                poemText?.setTextColor(android.graphics.Color.argb(180, 100, 180, 255))
            }
            updatePageCounter()
            // Demarrer session bloc-note si pas deja fait
            if (blocNoteIndex < 0) {
                startNewBlocNotePage()
            }
        } else {
            poemText?.text = ""
        }
        Log.i(TAG, "Bloc-notes: ${if (isBlocnoteMode) "ON" else "OFF"}")
    }

    private fun onStrokeSelected(idx: Int?) {
        if (idx != null && captureView?.currentMode == CaptureMode.EDIT) {
            deleteButton?.visibility = View.VISIBLE
        } else {
            deleteButton?.visibility = View.GONE
        }
    }

    private fun onButtonClick(text: String) {
        when (text) {
            "✓" -> onValidate()
            "✓ Valider" -> onValidate()
            "↺" -> onReset()
            "↺ Reset" -> onReset()
            "✕" -> onClose()
            "✕ Fermer" -> onClose()
            "➕ Série" -> onAddSeries()
            "↻" -> onRefreshCorpus()
        }
    }

    /** Inference automatique quand un groupe de mots est complete (mode Bloc-notes) */
    private fun onAutoInfer(strokes: List<StrokeRecord>, group: List<Int>) {
        val engine = onnxEngine ?: return
        // Lancer sur thread background pour ne pas bloquer l'ecriture du trait suivant
        Thread {
            try {
                val text = engine.recognize(strokes, group)
                if (text.isNotBlank()) {
                    runOnUiThread {
                        if (accumulatedTranscription.isNotEmpty()) accumulatedTranscription += " "
                        accumulatedTranscription += text
                        poemText?.text = accumulatedTranscription
                        poemText?.textSize = 24f
                        poemText?.setTextColor(android.graphics.Color.rgb(40, 120, 200))
                        Log.i(TAG, "Auto-infer: '$text'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-infer erreur: ${e.message}")
            }
        }.apply { name = "auto-infer"; start() }
    }

    private fun onValidate() {
        Log.i(TAG, "✓ Validation")

        // -- Mode Bloc-notes : finaliser la page courante, nouvelle page blanche --
        if (isBlocnoteMode) {
            Log.i(TAG, "Bloc-note: validation + reconnaissance ONNX")
            
            // Exécuter la reconnaissance ONNX avant d'effacer la page
            val strokes = captureView?.getStrokeRegistry()
            val wordGroups = captureView?.computeWordGroupsForSave()
            val transcriptions = if (onnxEngine != null && strokes != null && wordGroups != null && strokes.isNotEmpty()) {
                val results = wordGroups.mapNotNull { group ->
                    onnxEngine?.recognize(strokes, group)
                }
                val text = results.joinToString(" ")
                if (text.isNotBlank()) {
                    // Accumuler la transcription, ne pas remplacer
                    if (accumulatedTranscription.isNotEmpty()) accumulatedTranscription += " "
                    accumulatedTranscription += text
                    poemText?.text = accumulatedTranscription
                    poemText?.textSize = 24f
                    poemText?.setTextColor(android.graphics.Color.argb(220, 40, 120, 200))
                }
                results
            } else null
            
            // Forcer un nouveau fichier : le ✓ crée une nouvelle page, écrase pas l'ancienne
            captureView?.currentNotePath = null
            captureView?.saveCurrentNote(mode = "blocnote", transcriptions = transcriptions)
            startNewBlocNotePage()
            // Nouvelle page → vider la transcription du champ texte
            accumulatedTranscription = ""
            poemText?.text = "📝 Bloc-notes (en attente d'écriture)"
            poemText?.textSize = 24f
            poemText?.setTextColor(android.graphics.Color.rgb(120, 120, 120))
            scanBlocnoteFiles()
            return
        }
        
        // ── Mode Training (bigrammes / mots) : fonction unique ──
        if (currentBigramEntry != null || currentWordEntry != null) {
            onTrainingComplete()
            return
        }
        
        // ── Mode Edition : sauvegarder le fichier VStar sans effacer la vue ──
        if (captureView?.currentMode == CaptureMode.EDIT && editingVStarPath != null) {
            Log.i(TAG, "Mode EDIT: sauvegarde du fichier edite")
            captureView?.saveCurrentNote(mode = "edit")
            editingVStarPath = null
            return
        }
        
        // Dictee: sauvegarder au format .note avec le label du haiku courant
        captureView?.saveCurrentNote(
            label = currentHaiku,
            mode = "dictee"
        )
        openNewVStarFile()
        haikusWritten++
        Log.i(TAG, "Capture dictee: label=${currentHaiku.take(40)}")
    }
    
    // ── Bigram : valider la paire écrite ──────────────────────────────
    
    private fun onTrainingComplete() {
        // Détermine si on est en bigram ou mot
        val bigramEntry = currentBigramEntry
        val wordEntry = currentWordEntry
        val isBigram = bigramEntry != null
        val entry = (bigramEntry ?: wordEntry) ?: return
        
        val label: String
        val prefix: String
        val display: String
        val tier: String
        
        if (isBigram) {
            val e = bigramEntry
            label = "bigram:${e.pair}"
            prefix = "vstar_${e.pair}"
            display = e.pair
            tier = e.tier
            Log.i(TAG, "V Bigram complété: $display ($tier)")
        } else {
            val e = wordEntry!!
            label = "word:${e.word}"
            prefix = "word_${e.word}"
            display = e.word
            tier = e.tier
            Log.i(TAG, "V Mot complété: $display ($tier)")
        }
        
        // 1) Fermer le writer (écrit PS_END)
        // 1) Sauvegarder au format .note avec le label d'entrainement
        val notePath = captureView?.saveCurrentNote(mode = "train", label = label)
        val noteFileName = if (notePath != null) File(notePath).name else ""
        closeVStarFile(save = true)
        
        // 2) Marquer comme complété dans le trainer
        if (isBigram) {
            bigramTrainer?.completePair(display, tier, noteFileName)
        } else {
            wordTrainer?.completeWord(display, tier, noteFileName)
        }
        haikusWritten++
        
        // 3) Nettoyer les entries, garder le texte affiché (méditation)
        currentBigramEntry = null
        currentWordEntry = null
        
        captureView?.clear()
        openNewVStarFile()
        
        poemText?.text = display
        poemText?.textSize = 48f
        poemText?.setTextColor(android.graphics.Color.argb(200, 0, 0, 0))
        trainingConfirmText?.visibility = View.VISIBLE
        trainingProgressText?.visibility = View.GONE
    }

    private fun onReset() {
        Log.i(TAG, "↺ Reset")
        closeVStarFile(save = false)
        captureView?.startSession(currentHaiku)
        openNewVStarFile()
        // En mode training : réafficher le texte (l'entrée courante n'est pas touchée)
        if (currentBigramEntry != null || currentWordEntry != null) {
            val text = currentBigramEntry?.pair ?: currentWordEntry?.word ?: ""
            if (text.isNotEmpty()) {
                poemText?.text = text
                trainingConfirmText?.visibility = View.VISIBLE
                return
            }
        }
        // Mode normal : nouvelle entrée
        pickRandomHaiku()

        // Rétablir le mode Bloc-notes (le toggle OFF + Reset le laissait à false)
        // → permet à checkAutoInfer() de déclencher la reconnaissance
        isBlocnoteMode = true
        captureView?.isBlocnoteMode = true
    }

    private fun onClose() {
        Log.i(TAG, "✕ Fermeture")
        
        // ── Training : sauvegarder avant de fermer ──
        if (currentBigramEntry != null || currentWordEntry != null) {
            onTrainingComplete()
            finish()
            return
        }
        
        // Sauvegarder au format .note avant de fermer
        closeVStarFile(save = true)
        captureView?.saveCurrentNote(label = currentHaiku, mode = "dictee")
        finish()
    }

    private fun onAddSeries() {
        Log.i(TAG, "➕ Ajout de série")
        val input = EditText(this).apply {
            hint = "Un haïku par ligne\n(utilisez / pour les retours à la ligne)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
            setLines(8)
            gravity = Gravity.TOP
            setTextColor(android.graphics.Color.BLACK)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ajouter une série de haïkus")
            .setMessage("Entrez les haïkus, un par ligne.\nEx: pruniers en fleurs / sous la pluie fine d'avril / le chemin sent bon")
            .setView(input)
            .setPositiveButton("Ajouter") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val haikus = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (haikus.isNotEmpty()) {
                        saveCustomSeries(haikus)
                        Toast.makeText(this@CaptureActivity, "${haikus.size} haïkus ajoutés !", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
    }

    // ── Historique CSV ─────────────────────────────────────────────────

    private fun showHistoryDialog() {
        val vstarDir = File(filesDir, VSTAR_DIR)
        if (!vstarDir.exists()) {
            Toast.makeText(this, "Aucun historique — ${VSTAR_DIR}/ n'existe pas", Toast.LENGTH_SHORT).show()
            return
        }
        val noteFiles = vstarDir.listFiles()
            ?.filter { it.name.endsWith(".csv") || it.name.endsWith(".note") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (noteFiles.isEmpty()) {
            Toast.makeText(this, "Aucune capture dans ${VSTAR_DIR}/ — commencez par écrire !", Toast.LENGTH_SHORT).show()
            return
        }

        // Construire les items du dialogue : label + nom fichier + date
        val items = noteFiles.map { file ->
            val label = readLabelFor(file)
            val dateStr = try {
                val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                sdf.format(Date(file.lastModified()))
            } catch (_: Exception) { "" }
            CSVItem(display = if (label.isNotEmpty()) "$label — $dateStr" else "${file.name} — $dateStr", file = file, label = label)
        }

        val displayStrings = items.map { it.display }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📂 Historique des captures")
            .setItems(displayStrings) { _, which ->
                val item = items[which]
                loadCSVForHistory(item.file, item.label)
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private data class CSVItem(val display: String, val file: File, val label: String)

    private fun readLabelFor(file: File): String {
        // Pour les .note, lire le label directement dans le JSON
        if (file.name.endsWith(".note")) {
            return try {
                val json = org.json.JSONObject(file.readText())
                json.optString("label", "")
            } catch (_: Exception) { "" }
        }
        // Legacy: label dans un fichier .txt à côté
        val txtFile = File(file.parent, "${file.name}.txt")
        return try {
            txtFile.readText().trim()
        } catch (_: Exception) { "" }
    }

    private fun loadCSVForHistory(file: File, label: String) {
        try {
            Log.i(TAG, "Historique: reprise de ${file.name}, label=$label")
            
            // Afficher le label
            currentHaiku = label
            val labelLines = label.split(" / ")
            poemText?.text = labelLines.joinToString("\n")
            poemText?.textSize = 18f
            poemText?.setTextColor(android.graphics.Color.argb(200, 60, 60, 60))
            poemCount?.text = "📂 ${file.name}"
            
            // Charger le fichier (.note ou .csv legacy)
            val loaded = if (file.name.endsWith(".note"))
                captureView?.loadNoteFile(file) == true
            else
                captureView?.let { it.loadVStarFile(file) || it.loadCSV(file) } == true
            
            if (loaded) {
                // En mode édition : on garde la capture chargée, pas de nouveau writer
                currentHaiku = label
                captureView?.startSession(label)
                openNewVStarFile()
            }
            
            Toast.makeText(this, "Reprise: ${label.take(30)}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement historique: ${e.message}")
            Toast.makeText(this, "Erreur: ${e.message?.take(40)}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Notification persistante ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Miroir Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Miroir est actif en superposition"
            }
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

    private fun hidePersistentNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
    }

    // ── Rafraîchir le corpus ──────────────────────────────────────────────

    private fun onRefreshCorpus() {
        Thread {
            try {
                Log.i(TAG_CORPUS, "Rafraîchissement du corpus...")
                val url = URL(CORPUS_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG_CORPUS, "Erreur HTTP $code")
                    conn.disconnect()
                    runOnUiThread {
                        Toast.makeText(this@CaptureActivity, "Erreur serveur ($code)", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                // Parser le JSON : {"texts":["...",...],"count":N}
                // Extraction manuelle robuste (sans org.json)
                val texts = parseJsonStringArray(json) ?: mutableListOf()
                val count = texts.size
                Log.i(TAG_CORPUS, "Corpus reçu: $count textes")
                
                if (count > 0) {
                    runOnUiThread {
                        // Créer ou mettre à jour le niveau "Serveur"
                        levelMap["Serveur"] = texts.toMutableList()
                        val serverSection = CurriculumSection(
                            name = "Serveur",
                            type = SectionType.LIST,
                            items = texts
                        )
                        curriculumSections = curriculumSections + serverSection
                        levelNames = curriculumSections.map { it.name }.toTypedArray()
                        // Basculer vers le niveau Serveur
                        val serverIdx = levelNames.indexOf("Serveur")
                        if (serverIdx >= 0) {
                            currentLevelIdx = serverIdx
                            levelText?.text = levelNames.getOrElse(serverIdx) { "?" }
                            pickRandomHaiku()
                        }
                        Toast.makeText(this@CaptureActivity, "$count textes chargés !", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@CaptureActivity, "Corpus vide", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG_CORPUS, "Erreur rafraîchissement: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@CaptureActivity, "Erreur: ${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }



    // ── Parseur JSON simple ───────────────────────────────────────────
    // Parse ["...","..."] en list de string. Retourne null si invalide.
    private fun parseJsonStringArray(json: String): List<String>? {
        val start = json.indexOf('[')
        val end = json.lastIndexOf(']')
        if (start < 0 || end <= start + 1) return null
        val arr = json.substring(start + 1, end)
        val result = mutableListOf<String>()
        var i = 0
        while (i < arr.length) {
            // Chercher l'ouverture d'une chaîne
            while (i < arr.length && arr[i] != '"') i++
            if (i >= arr.length) break
            i++ // saute le " ouvrant
            val sb = StringBuilder()
            while (i < arr.length) {
                val ch = arr[i]
                if (ch == '"') { i++; break } // fin de chaîne
                if (ch == '\\' && i + 1 < arr.length) {
                    i++
                    when (arr[i]) {
                        'n' -> sb.append('\n')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        else -> sb.append(arr[i])
                    }
                } else {
                    sb.append(ch)
                }
                i++
            }
            val text = sb.toString().trim()
            if (text.length > 3) result.add(text)
        }
        return result
    }

    // ── API ──────────────────────────────────────────────────────────────

    private fun sendVStarToServer(filename: String, data: ByteArray, label: String) {
        try {
            val b64 = Base64.getEncoder().encodeToString(data)
            val escapedLabel = label.replace("\\", "\\\\").replace("\"", "\\\"")
            val jsonBody = """{"session_id":"${filename}","vstar_base64":"${b64}","label":"${escapedLabel}"}"""

            val url = URL(INGEST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            conn.outputStream.write(jsonBody.toByteArray())
            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Log.i(TAG_API, "V* envoyé: code=$code, ${filename} (${data.size} octets)")
        } catch (e: Exception) {
            Log.w(TAG_API, "Erreur envoi: ${e.message}")
        }
    }
}
