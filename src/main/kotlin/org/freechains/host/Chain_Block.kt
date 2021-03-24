package org.freechains.host

import org.freechains.common.*
import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import kotlin.math.max

fun Chain.fromOwner (blk: Block) : Boolean {
    return this.atKey().let { it!=null && blk.isFrom(it) }
}

// STATE

fun Chain.isBlocked (blk: Block, now: Long) : Boolean {
    return when {
        // immutable
        (blk.hash.toHeight() == 0)     -> false       // genesis block
        this.fromOwner(blk)            -> false       // owner signature
        this.name.startsWith('$') -> false       // chain with trusted hosts/authors only
        (blk.immut.like != null)       -> false       // a like

        // mutable
        else -> {
            val rep = if (blk.sign==null) 0 else this.reps(blk.sign.pub, now, setOf(blk.hash))
            (rep < 0)
        }
    }
}

fun Chain.isHidden (blk: Block) : Boolean {
    return when {
        // immutable
        (blk.hash.toHeight() == 0)     -> false       // genesis block
        this.fromOwner(blk)            -> false       // owner signature
        this.name.startsWith('$') -> false       // chain with trusted hosts/authors only
        (blk.immut.like != null)       -> false       // a like

        // mutable
        else -> {
            val (pos,neg) = this.repsPost(blk.hash)
            (neg>=LK5_dislikes && LK2_factor*neg>=pos) // too many dislikes
        }
    }
}

// NEW

fun Chain.blockNew (imm_: Immut, pay0: String, sign: HKey?, pubpvt: Boolean, backs_: Set<Hash>? = null) : Hash {
    assert_(imm_.time == 0.toLong()) { "time must not be set" }
    assert_(imm_.pay.hash == "") { "pay must not be set" }
    assert_(imm_.backs.isEmpty())

    val backs = backs_ ?: this.heads() + (
        if (imm_.like != null && imm_.like.n > 0 && this.blockeds().contains(imm_.like.hash)) {
            setOf(imm_.like.hash) // include blocked liked block in backs
        } else {
            emptySet()
        }
    )

    val imm = imm_.copy (
        time = max (getNow(), 1+backs.map{ this.fsLoadBlock(it).immut.time }.maxOrNull()!!),
        pay = imm_.pay.copy (
            crypt = this.name.startsWith('$') || pubpvt,
            hash  = pay0.calcHash()
        ),
        backs = backs
    )
    val pay1 = when {
        this.name.startsWith('$') -> pay0.encryptShared(this.key!!)
        pubpvt -> pay0.encryptPublic(this.atKey()!!)
        else   -> pay0
    }
    val hash = imm.toHash()

    // signs message if requested (pvt provided or in pvt chain)
    val signature =
        if (sign == null)
            null
        else {
            val sig = ByteArray(Sign.BYTES)
            val msg = lazySodium.bytes(hash)
            val pvt = Key.fromHexString(sign).asBytes
            lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), pvt)
            val sig_hash = LazySodium.toHex(sig)
            Signature(sig_hash, sign.pvtToPub())
        }

    this.fsSaveBlock(Block(imm, hash, signature),pay1)
    return hash
}

fun Chain.blockRemove (hash: Hash) {
    val blk = this.fsLoadBlock(hash)
    assert_(this.blockeds().contains(blk.hash)) { "can only remove blocked block" }
    this.fsSave()
}

fun Chain.blockAssert (blk: Block) {
    val imm = blk.immut
    val now = getNow()
    //println(">>> ${blk.hash} vs ${imm.toHash()}")

    // backs exist and are older
    for (bk in blk.immut.backs) {
        //println("$it <- ${blk.hash}")
        assert_(this.fsExistsBlock(bk)) { "back must exist" }
        assert_(this.fsLoadBlock(bk).immut.time <= blk.immut.time) { "back must be older" }
        assert_(!this.blockeds().contains(bk) || (blk.immut.like!=null && blk.immut.like.hash==bk)) {
            "backs must be accepted"
        }
    }

    if (blk.hash.toHeight() > 0) {
        assert_(blk.hash == imm.toHash()) { "hash must verify" }
        assert_(imm.time >= now - T120D_past) { "too old" }
        if (this.name.startsWith("@!")) {
            assert_(this.fromOwner(blk)) { "must be from owner" }
        }
    }
    assert_(imm.time <= now + T30M_future) { "from the future" }

    if (blk.sign != null) {                 // sig.hash/blk.hash/sig.pubkey all match
        val sig = LazySodium.toBin(blk.sign.hash)
        val msg = lazySodium.bytes(blk.hash)
        val key = Key.fromHexString(blk.sign.pub).asBytes
        assert_(lazySodium.cryptoSignVerifyDetached(sig, msg, msg.size, key)) { "invalid signature" }
    }

    if (imm.like != null) {
        assert_(blk.sign != null) { "like must be signed" }
        // may receive out of order // may point to rejected post
        //assert_(this.fsExistsBlock(imm.like.hash)) { "like must have valid target" }
        if (this.fsExistsBlock(imm.like.hash)) {
            this.fsLoadBlock(imm.like.hash).let {
                assert_(!it.isFrom(blk.sign!!.pub)) { "like must not target itself" }
            }
        }
    }
}
