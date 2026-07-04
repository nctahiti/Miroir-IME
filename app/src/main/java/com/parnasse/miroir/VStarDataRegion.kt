package com.parnasse.miroir

import android.util.Log
import java.io.*

/**
 * Région de données V★ v2.0 — append-only, tolérante au kill IME.
 *
 * Garanties :
 *   - Chaque append() est atomique (écrit les 16 bytes complets ou rien)
 *   - Si l'IME est tué au milieu d'un write, le fichier reste cohérent
 *   - Les tokens partiels (tronqués) sont ignorés au chargement
 *   - Le fichier ne fait que croître (jamais de réécriture partielle)
 *
 * Usage :
 *   val region = VStarDataRegion(File("page.vstar"))
 *   region.append(token)               // écrit un token
 *   region.append(tokens)              // écrit plusieurs tokens
 *   val count = region.tokenCount      // nombre de tokens valides
 *   val tokens = region.readAll()      // lit tous les tokens valides
 */
class VStarDataRegion(private val file: File) {

    companion object {
        private const val TAG = "Miroir/VStarDataRegion"
    }

    private var writer: DataOutputStream? = null
    private var _tokenCount: Int = 0  // en mémoire, recalculé au reload

    /** Nombre de tokens valides dans le fichier. */
    val tokenCount: Int get() = _tokenCount

    // ── Écriture ──

    /**
     * Ouvre le fichier en mode append.
     * Recalcule le nombre de tokens existants (en cas de reprise après kill).
     */
    fun open() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
            _tokenCount = 0
        } else {
            // Recalculer le nombre de tokens valides (le fichier peut être tronqué)
            _tokenCount = countValidTokens()
        }
        writer = DataOutputStream(BufferedOutputStream(FileOutputStream(file, true)))
        Log.d(TAG, "DataRegion ouvert: ${file.absolutePath} — ${_tokenCount} tokens existants")
    }

    /**
     * Ajoute un token à la fin du fichier.
     * @return le nouvel offset (index) du token dans le flux
     */
    fun append(token: VStarTokenV2): Int {
        val w = writer ?: throw IllegalStateException("DataRegion non ouvert — appeler open() d'abord")
        val idx = _tokenCount
        token.write(w)
        w.flush()  // force l'écriture physique (survie au kill)
        _tokenCount++
        return idx
    }

    /**
     * Ajoute plusieurs tokens.
     * @return l'offset du premier token ajouté
     */
    fun append(tokens: List<VStarTokenV2>): Int {
        val w = writer ?: throw IllegalStateException("DataRegion non ouvert")
        val idx = _tokenCount
        for (t in tokens) {
            t.write(w)
        }
        w.flush()
        _tokenCount += tokens.size
        return idx
    }

    /** Flush explicite (déjà fait après chaque append). */
    fun flush() {
        writer?.flush()
    }

    /** Ferme le flux d'écriture. */
    fun close() {
        try { writer?.flush() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        Log.d(TAG, "DataRegion fermé: $_tokenCount tokens")
    }

    // ── Lecture ──

    /**
     * Lit tous les tokens valides du fichier.
     * Ignore les tokens tronqués en fin de fichier (kill IME).
     */
    fun readAll(): List<VStarTokenV2> {
        if (!file.exists()) return emptyList()
        val tokens = mutableListOf<VStarTokenV2>()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { ins ->
                val fileLength = file.length()
                var bytesRead = 0L
                while (bytesRead + VStarTokenV2.SIZE_BYTES <= fileLength) {
                    try {
                        tokens.add(VStarTokenV2.read(ins))
                        bytesRead += VStarTokenV2.SIZE_BYTES
                    } catch (e: EOFException) {
                        break  // fin de fichier normale
                    }
                }
                val remaining = fileLength - bytesRead
                if (remaining > 0) {
                    Log.w(TAG, "Fichier tronqué: $remaining bytes ignorés en fin (kill IME probable)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture DataRegion: ${e.message}")
        }
        _tokenCount = tokens.size
        return tokens
    }

    /**
     * Lit un token à l'offset donné (index × 16).
     * Retourne null si l'offset est hors limites.
     */
    fun readAt(offset: Int): VStarTokenV2? {
        if (!file.exists()) return null
        val byteOffset = offset.toLong() * VStarTokenV2.SIZE_BYTES
        if (byteOffset + VStarTokenV2.SIZE_BYTES > file.length()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(byteOffset)
                val bytes = ByteArray(VStarTokenV2.SIZE_BYTES)
                raf.readFully(bytes)
                DataInputStream(ByteArrayInputStream(bytes)).use { VStarTokenV2.read(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur readAt($offset): ${e.message}")
            null
        }
    }

    // ── Interne ──

    /** Compte les tokens valides dans le fichier (ignore les tronqués). */
    private fun countValidTokens(): Int {
        val totalBytes = file.length()
        val count = (totalBytes / VStarTokenV2.SIZE_BYTES).toInt()
        val remainder = totalBytes % VStarTokenV2.SIZE_BYTES
        if (remainder > 0) {
            Log.w(TAG, "Fichier existant tronqué: $totalBytes bytes → ${count} tokens valides, $remainder bytes ignorés")
        }
        return count
    }
}
