package com.parnasse.miroir

import java.io.*

/**
 * Token V★ v2.0 — 16 bytes alignés (2 mots machine).
 *
 * Structure :
 *   Offset 0-1:   dx (Short) — delta X × scaleFactor (signé)
 *   Offset 2-3:   dy (Short) — delta Y × scaleFactor (signé)
 *   Offset 4-5:   dt (Short) — delta temps ms (signé)
 *   Offset 6:     p  (Byte)  — pression [0-255]
 *   Offset 7:     az (Byte)  — azimuth [0-255]
 *   Offset 8:     i  (Byte)  — tilt [0-255]
 *   Offset 9:     ps (Byte)  — pen state (PS_PENDOWN, PS_PENUP, etc.)
 *   Offset 10:    h  (Byte)  — ornement (-128..127)
 *   Offset 11:    pad (Byte) — padding (réservé, toujours 0)
 *   Offset 12-13: flags (Short) — bitfield FLAG_*
 *   Offset 14-15: ci (Short) — captureIndex (0-65535)
 *
 * TOTAL : 16 bytes = 2 mots machine (alignement parfait)
 */
data class VStarTokenV2(
    val dx: Short,
    val dy: Short,
    val dt: Short,
    val p: Int,               // UByte 0-255
    val az: Int,              // UByte 0-255
    val i: Int,               // UByte 0-255
    val ps: Int,              // UByte 0-255
    val h: Byte,              // signé -128..127
    val flags: Short,         // bitfield FLAG_*
    val captureIndex: Short   // 0-65535
) {
    companion object {
        const val SIZE_BYTES = 16

        // ── Pen states (compatibles v1.1) ──
        const val PS_PENUP = 0
        const val PS_PENDOWN = 1
        const val PS_HOVER = 2
        const val PS_END = 3
        const val PS_GROUP_SEP = 4
        const val PS_GROUP_ANCRE = 5

        // ── Flags ──
        const val FLAG_NONE = 0.toShort()
        const val FLAG_PEN_DOWN = 1.toShort()      // bit 0
        const val FLAG_PEN_UP = 2.toShort()        // bit 1: aussi marqué sur PEN_DOWN si 1 seul point
        const val FLAG_ERASED = 4.toShort()        // bit 2: tombstone
        const val FLAG_GROUP_META = 8.toShort()    // bit 3: métadonnées de groupe
        const val FLAG_END = 16.toShort()          // bit 4: fin de flux
        // bits 5-15 : réservés

        const val AZIMUTH_UNSUPPORTED = 0xFF
        const val TILT_UNSUPPORTED = 0xFF

        // ── Constructeurs nommés ──

        fun penDown(x: Float, y: Float, scaleFactor: Float,
                    p: Int, az: Int, i: Int, ci: Short): VStarTokenV2 {
            return VStarTokenV2(
                dx = (x * scaleFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                dy = (y * scaleFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                dt = 0, p = p.coerceIn(0, 255),
                az = az.coerceIn(0, 255), i = i.coerceIn(0, 255),
                ps = PS_PENDOWN, h = 0,
                flags = (FLAG_PEN_DOWN.toInt() or FLAG_PEN_UP.toInt()).toShort(),
                captureIndex = ci
            )
        }

        fun move(dx: Short, dy: Short, dt: Short, p: Int, az: Int, i: Int, ci: Short): VStarTokenV2 {
            return VStarTokenV2(dx, dy, dt, p.coerceIn(0, 255),
                az.coerceIn(0, 255), i.coerceIn(0, 255),
                PS_PENDOWN, 0, FLAG_NONE, ci
            )
        }

        fun penUp(dx: Short, dy: Short, dt: Short, p: Int, az: Int, i: Int, ci: Short): VStarTokenV2 {
            return VStarTokenV2(dx, dy, dt, p.coerceIn(0, 255),
                az.coerceIn(0, 255), i.coerceIn(0, 255),
                PS_PENUP, 0, FLAG_PEN_UP, ci
            )
        }

        fun groupMeta(anchorX: Float, anchorY: Float, scaleFactor: Float): VStarTokenV2 {
            return VStarTokenV2(
                dx = (anchorX * scaleFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                dy = (anchorY * scaleFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                dt = 0, p = 0, az = AZIMUTH_UNSUPPORTED, i = TILT_UNSUPPORTED,
                ps = PS_GROUP_ANCRE, h = 0, flags = FLAG_GROUP_META, captureIndex = 0
            )
        }

        fun endToken() = VStarTokenV2(0,0,0,0, AZIMUTH_UNSUPPORTED, TILT_UNSUPPORTED, PS_END, 0, FLAG_END, 0)
        fun eraseToken(ci: Short) = VStarTokenV2(0,0,0,0, AZIMUTH_UNSUPPORTED, TILT_UNSUPPORTED, 0, 0, FLAG_ERASED, ci)

        // ── I/O ──

        fun write(out: DataOutputStream, t: VStarTokenV2) {
            out.writeShort(t.dx.toInt()); out.writeShort(t.dy.toInt()); out.writeShort(t.dt.toInt())
            out.writeByte(t.p and 0xFF); out.writeByte(t.az and 0xFF); out.writeByte(t.i and 0xFF)
            out.writeByte(t.ps and 0xFF); out.writeByte(t.h.toInt() and 0xFF)
            out.writeByte(0)  // padding
            out.writeShort(t.flags.toInt()); out.writeShort(t.captureIndex.toInt())
        }

        fun read(ins: DataInputStream): VStarTokenV2 {
            val dx = ins.readShort(); val dy = ins.readShort(); val dt = ins.readShort()
            val p = ins.readUnsignedByte(); val az = ins.readUnsignedByte(); val i = ins.readUnsignedByte()
            val ps = ins.readUnsignedByte(); val h = ins.readByte()
            ins.readByte()  // skip padding
            val flags = ins.readShort(); val ci = ins.readShort()
            return VStarTokenV2(dx, dy, dt, p, az, i, ps, h, flags, ci)
        }

        // ── Helpers flags ──
        fun hasFlag(flags: Short, flag: Short) = (flags.toInt() and flag.toInt()) != 0
        fun isPenDown(flags: Short) = hasFlag(flags, FLAG_PEN_DOWN)
        fun isPenUp(flags: Short) = hasFlag(flags, FLAG_PEN_UP)
        fun isErased(flags: Short) = hasFlag(flags, FLAG_ERASED)
        fun isGroupMeta(flags: Short) = hasFlag(flags, FLAG_GROUP_META)
        fun isEnd(flags: Short) = hasFlag(flags, FLAG_END)
    }

    fun write(out: DataOutputStream) = Companion.write(out, this)
    fun isPenDown() = Companion.isPenDown(flags)
    fun isPenUp() = Companion.isPenUp(flags)
    fun isErased() = Companion.isErased(flags)
    fun isGroupMeta() = Companion.isGroupMeta(flags)
    fun isEnd() = Companion.isEnd(flags)

    override fun toString(): String {
        val sb = StringBuilder("VStarTokenV2(dx=$dx dy=$dy dt=$dt")
        if (p != 0) sb.append(" p=$p")
        if (az != AZIMUTH_UNSUPPORTED) sb.append(" az=$az")
        if (i != TILT_UNSUPPORTED) sb.append(" i=$i")
        sb.append(" ps=$ps")
        if (h != 0.toByte()) sb.append(" h=$h")
        if (flags != FLAG_NONE) {
            val f = mutableListOf<String>()
            if (isPenDown()) f.add("DN"); if (isPenUp()) f.add("UP")
            if (isErased()) f.add("ERASE"); if (isGroupMeta()) f.add("META"); if (isEnd()) f.add("END")
            sb.append(" flags=[${f.joinToString("|")}]")
        }
        sb.append(" ci=$captureIndex)")
        return sb.toString()
    }
}
