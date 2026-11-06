package com.crko.storage

/**
 * SHA-256 (FIPS 180-4), HMAC (RFC 2104) and PBKDF2 (RFC 8018) in pure Kotlin. The cloud sync needs
 * byte-identical cryptography on every platform, so the primitives live in common code instead of
 * trusting each platform's provider zoo; the platform port only supplies random bytes.
 */

/** Fractional parts of the cube roots of the first 64 primes, and the initial state. */
private val SHA256_K = longArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
).map { it.toInt() }.toIntArray()
private val SHA256_INIT = longArrayOf(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
).map { it.toInt() }.toIntArray()
private const val SHA256_BLOCK = 64

/** One streaming SHA-256 computation: [update] with the message, then a single [finish]. */
internal class Sha256 {
    private val state = SHA256_INIT.copyOf()
    private val block = ByteArray(SHA256_BLOCK)
    private var buffered = 0
    private var totalBytes = 0L

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        totalBytes += length
        var position = offset
        var remaining = length
        if (buffered > 0) {
            val take = minOf(SHA256_BLOCK - buffered, remaining)
            data.copyInto(block, buffered, position, position + take)
            buffered += take
            position += take
            remaining -= take
            if (buffered == SHA256_BLOCK) {
                compress(block, 0)
                buffered = 0
            }
        }
        while (remaining >= SHA256_BLOCK) {
            compress(data, position)
            position += SHA256_BLOCK
            remaining -= SHA256_BLOCK
        }
        if (remaining > 0) {
            data.copyInto(block, 0, position, position + remaining)
            buffered = remaining
        }
    }

    /** Pads the message and returns the 32-byte digest. The instance is spent afterwards. */
    fun finish(): ByteArray {
        val bitLength = totalBytes * 8
        append(0x80.toByte())
        while (buffered != 56) append(0)
        for (index in 0 until 8) {
            append((bitLength ushr (8 * (7 - index))).toByte())
        }
        val digest = ByteArray(32)
        state.forEachIndexed { index, word ->
            digest[4 * index] = (word ushr 24).toByte()
            digest[4 * index + 1] = (word ushr 16).toByte()
            digest[4 * index + 2] = (word ushr 8).toByte()
            digest[4 * index + 3] = word.toByte()
        }
        return digest
    }

    /** Feeds one padding byte straight into the block buffer without counting it as message. */
    private fun append(value: Byte) {
        block[buffered++] = value
        if (buffered == SHA256_BLOCK) {
            compress(block, 0)
            buffered = 0
        }
    }

    private fun compress(input: ByteArray, offset: Int) {
        val w = IntArray(64)
        for (t in 0 until 16) {
            w[t] = ((input[offset + 4 * t].toInt() and 0xFF) shl 24) or
                ((input[offset + 4 * t + 1].toInt() and 0xFF) shl 16) or
                ((input[offset + 4 * t + 2].toInt() and 0xFF) shl 8) or
                (input[offset + 4 * t + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val x = w[t - 15]
            val y = w[t - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
}

internal fun sha256(vararg chunks: ByteArray): ByteArray = Sha256().apply { chunks.forEach(::update) }.finish()

/** HMAC-SHA256 over [chunks]; the key is hashed once when longer than the block size. */
internal fun hmacSha256Bytes(key: ByteArray, vararg chunks: ByteArray): ByteArray {
    val blockKey = if (key.size > 64) sha256(key) else key.copyOf(64)
    val inner = ByteArray(64) { index -> (blockKey[index].toInt() xor 0x36).toByte() }
    val outer = ByteArray(64) { index -> (blockKey[index].toInt() xor 0x5c).toByte() }
    val innerHash = Sha256().apply {
        update(inner)
        chunks.forEach(::update)
    }.finish()
    return Sha256().apply {
        update(outer)
        update(innerHash)
    }.finish()
}

/** PBKDF2-HMAC-SHA256 (RFC 8018 §5.2), byte-exact across platforms by construction. */
internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, rounds: Int, keyLengthBytes: Int): ByteArray {
    require(rounds > 0 && keyLengthBytes > 0)
    val hLen = 32
    val blocks = (keyLengthBytes + hLen - 1) / hLen
    val derived = ByteArray(blocks * hLen)
    val counter = ByteArray(salt.size + 4)
    salt.copyInto(counter)
    for (block in 1..blocks) {
        counter[salt.size] = (block ushr 24).toByte()
        counter[salt.size + 1] = (block ushr 16).toByte()
        counter[salt.size + 2] = (block ushr 8).toByte()
        counter[salt.size + 3] = block.toByte()
        var u = hmacSha256Bytes(password, counter)
        val accumulator = u.copyOf()
        repeat(rounds - 1) {
            u = hmacSha256Bytes(password, u)
            for (index in accumulator.indices) {
                accumulator[index] = (accumulator[index].toInt() xor u[index].toInt()).toByte()
            }
        }
        accumulator.copyInto(derived, (block - 1) * hLen)
    }
    return derived.copyOf(keyLengthBytes)
}
