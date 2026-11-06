package com.crko.storage

/**
 * AES-GCM (NIST SP 800-38D) over the pure-Kotlin AES block cipher. Only the 96-bit nonce of the
 * cloud sync is supported, which keeps the counter derivation to `IV ‖ 0x00000001`. The sealed
 * layout is `ciphertext ‖ tag(16)`; [open] answers null when the tag does not verify.
 */
internal object Gcm {
    private const val TAG_BYTES = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == 12) { "GCM nonce must be 96 bits" }
        val cipher = AesEncryptor(key)
        val ciphertext = ctrCipher(cipher, nonce, plaintext)
        val tag = tag(cipher, nonce, aad, ciphertext)
        return concat(ciphertext, tag)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        require(nonce.size == 12) { "GCM nonce must be 96 bits" }
        if (sealed.size < TAG_BYTES) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_BYTES)
        val expected = tag(AesEncryptor(key), nonce, aad, ciphertext)
        var diff = 0
        for (index in expected.indices) {
            diff = diff or (expected[index].toInt() xor sealed[sealed.size - TAG_BYTES + index].toInt())
        }
        if (diff != 0) return null
        return ctrCipher(AesEncryptor(key), nonce, ciphertext)
    }

    /** CTR mode whose first data counter is `inc32(J0)`; used for both directions. */
    private fun ctrCipher(cipher: AesEncryptor, nonce: ByteArray, data: ByteArray): ByteArray {
        val counter = ByteArray(16)
        nonce.copyInto(counter)
        counter[12] = 0
        counter[13] = 0
        counter[14] = 0
        counter[15] = 1
        val keystream = ByteArray(16)
        val out = data.copyOf()
        var offset = 0
        while (offset < out.size) {
            incrementCounter(counter)
            cipher.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(16, out.size - offset)
            for (index in 0 until take) {
                out[offset + index] = (out[offset + index].toInt() xor keystream[index].toInt()).toByte()
            }
            offset += take
        }
        return out
    }

    private fun incrementCounter(counter: ByteArray) {
        for (index in 15 downTo 12) {
            val value = (counter[index].toInt() and 0xFF) + 1
            counter[index] = value.toByte()
            if (value <= 0xFF) return
        }
    }

    /** The authentication tag: `AES(K, J0) ⊕ GHASH_H(A ‖ C ‖ len(A) ‖ len(C))`. */
    private fun tag(cipher: AesEncryptor, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val h = ByteArray(16)
        cipher.encryptBlock(ByteArray(16), 0, h, 0)
        val ghash = ByteArray(16)
        feedBlock(ghash, h, aad)
        feedBlock(ghash, h, ciphertext)
        val lengths = ByteArray(16)
        putLong(lengths, 0, aad.size.toLong() * 8)
        putLong(lengths, 8, ciphertext.size.toLong() * 8)
        feedBlock(ghash, h, lengths)
        val j0 = ByteArray(16)
        nonce.copyInto(j0)
        j0[12] = 0
        j0[13] = 0
        j0[14] = 0
        j0[15] = 1
        val mask = ByteArray(16)
        cipher.encryptBlock(j0, 0, mask, 0)
        for (index in 0 until 16) ghash[index] = (ghash[index].toInt() xor mask[index].toInt()).toByte()
        return ghash
    }

    /**
     * Feeds [data] into the GHASH state, one multiply per 16-byte chunk. A partial final chunk is
     * zero-padded by leaving the accumulator's untouched bytes in place, exactly as the XOR with a
     * zero-padded block behaves.
     */
    private fun feedBlock(state: ByteArray, h: ByteArray, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val take = minOf(16, data.size - offset)
            for (index in 0 until take) {
                state[index] = (state[index].toInt() xor data[offset + index].toInt()).toByte()
            }
            multiply(state, h)
            offset += 16
        }
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr (8 * (7 - index))).toByte()
        }
    }

    /**
     * GF(2^128) multiplication per the GCM spec: bit by bit from the leftmost bit of [x], doubling
     * [v] and reducing by R (0xe1 in the first byte) whenever the shifted-out bit was set.
     */
    private fun multiply(state: ByteArray, h: ByteArray) {
        val z = ByteArray(16)
        val v = h.copyOf()
        for (index in 0 until 16) {
            val bits = state[index].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                if (bits and (1 shl bit) != 0) {
                    for (byte in 0 until 16) z[byte] = (z[byte].toInt() xor v[byte].toInt()).toByte()
                }
                val lsbSet = (v[15].toInt() and 1) != 0
                shiftRight(v)
                if (lsbSet) v[0] = (v[0].toInt() xor 0xe1).toByte()
            }
        }
        z.copyInto(state)
    }

    private fun shiftRight(block: ByteArray) {
        var carry = 0
        for (index in block.indices) {
            val value = block[index].toInt() and 0xFF
            block[index] = ((value ushr 1) or carry).toByte()
            carry = (value and 1) shl 7
        }
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        a.copyInto(out)
        b.copyInto(out, a.size)
        return out
    }
}
