package com.darkyen.ud

import com.darkyen.ucbor.ByteRead
import com.darkyen.ucbor.ByteWrite
import com.darkyen.ucbor.doubleToFloat

/**
 * CBOR serialization provided by [com.darkyen.ucbor.CborSerializer] can turn, in principle,
 * any object into a binary string. Binary strings can be compared, however the ordering of such
 * serialized objects is different from the ordering of the original objects.
 * Serialization that is stable in regard to ordering must be implemented more carefully
 * and is hard to do generically.
 * Instances of this class provide such serialization scheme.
 */
interface KeySerializer<K:Any> {
    /** Encode [value] into [w], such that the ordering of [value]
     * matches the ordering of the binary strings produced. */
    fun serialize(w: ByteWrite, value: K)

    /** Decode the whole content of [r] as [K] */
    fun deserialize(r: ByteRead): K
}

/** A boolean key */
object BooleanKeySerializer : KeySerializer<Boolean> {
    override fun serialize(w: ByteWrite, value: Boolean) {
        w.writeRawBE(if (value) 1L else 0L, 1)
    }
    override fun deserialize(r: ByteRead): Boolean {
        return r.readRawBE(1) != 0L
    }
}

/** Treats [Long] as unsigned. */
object UnsignedLongKeySerializer : KeySerializer<Long> {
    override fun serialize(w: ByteWrite, value: Long) {
        w.writeRawBE(value, 8)
    }

    override fun deserialize(r: ByteRead): Long {
        return r.readRawBE(8)
    }
}
/** Treats [Int] as unsigned. */
object UnsignedIntKeySerializer : KeySerializer<Int> {
    override fun serialize(w: ByteWrite, value: Int) {
        w.writeRawBE((value).toLong(), 4)
    }

    override fun deserialize(r: ByteRead): Int {
        return r.readRawBE(4).toInt()
    }
}

/**
 * Binary strings are compared like unsigned values,
 * but all common primitives are signed. So we add a bias.
 */
private const val BIAS32:Int = Int.MIN_VALUE // 0x8000_0000
private const val BIAS64:Long = Long.MIN_VALUE // 0x8000_0000_0000_0000

object IntKeySerializer : KeySerializer<Int> {
    override fun serialize(w: ByteWrite, value: Int) {
        w.writeRawBE((value + BIAS32).toLong(), 4)
    }

    override fun deserialize(r: ByteRead): Int {
        return r.readRawBE(4).toInt() - BIAS32
    }
}
object LongKeySerializer : KeySerializer<Long> {
    override fun serialize(w: ByteWrite, value: Long) {
        w.writeRawBE(value + BIAS64, 8)
    }

    override fun deserialize(r: ByteRead): Long {
        return r.readRawBE(8) - BIAS64
    }
}

private const val MASK32 = 0x7FFF_FFFF
private const val MASK64 = 0x7FFF_FFFF_FFFF_FFFFL
// https://en.wikipedia.org/wiki/IEEE_754-1985#Comparing_floating-point_numbers
object FloatKeySerializer : KeySerializer<Float> {
    override fun serialize(w: ByteWrite, value: Float) {
        val bits = value.toRawBits()
        val masked = bits and MASK32
        val biasedBits = if ((bits ushr 31) == 1) {
            // Negative, must be flipped
            MASK32 - masked
        } else {
            // Positive, must be added to bias
            BIAS32 + masked
        }
        w.writeRawBE(biasedBits.toLong(), 4)
    }

    override fun deserialize(r: ByteRead): Float {
        val bits = r.readRawBE(4).toInt()
        val masked = bits and MASK32
        val floatBits: Int = if ((bits ushr 31) == 0) {
            // Negative, unflip, add sign
            (MASK32 - masked) or BIAS32
        } else {
            // Positive, unbias (=masked)
            masked
        }
        return doubleToFloat(Float.fromBits(floatBits).toDouble())
    }
}

object DoubleKeySerializer : KeySerializer<Double> {
    override fun serialize(w: ByteWrite, value: Double) {
        val bits = value.toRawBits()
        val masked = bits and MASK64
        val biasedBits = if ((bits ushr 63).toInt() == 1) {
            // Negative, must be flipped
            MASK64 - masked
        } else {
            // Positive, must be added to bias
            BIAS64 + masked
        }
        w.writeRawBE(biasedBits, 8)
    }

    override fun deserialize(r: ByteRead): Double {
        val bits = r.readRawBE(8)
        val masked = bits and MASK64
        val floatBits: Long = if ((bits ushr 63).toInt() == 0) {
            // Negative, unflip, add sign
            (MASK64 - masked) or BIAS64
        } else {
            // Positive, unbias (=masked)
            masked
        }
        return Double.fromBits(floatBits)
    }
}

class EnumKeySerializer<T:Enum<T>>(private val values: Array<T>) : KeySerializer<T> {
    // https://stackoverflow.com/questions/1823346/whats-the-limit-to-the-number-of-members-you-can-have-in-a-java-enum
    // suggests that the max amount of enum members can't ever be more than 16bit and the actual limit is much smaller (but more than 256)
    // so using unsigned short seems fine
    override fun serialize(w: ByteWrite, value: T) {
        w.writeRawBE(value.ordinal.toLong(), 2)
    }

    override fun deserialize(r: ByteRead): T {
        return values[r.readRawBE(2).toInt()]
    }
}

