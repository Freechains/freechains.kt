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

fun Chain.isHidden (con: Consensus, blk: Block) : Boolean {
    return when {
        // immutable
        (blk.hash.toHeight() == 0)     -> false       // genesis block
        this.fromOwner(blk)            -> false       // owner signature
        this.name.startsWith('$') -> false       // chain with trusted hosts/authors only
        (blk.immut.like != null)       -> false       // a like
        con.list.any {
            val blk2 = this.fsLoadBlock(it)
            val lk2 = blk2.immut.like
            (lk2!=null && lk2.hash==blk.hash && blk.sign!=null && blk.sign.pub==blk2.sign!!.pub)
        }                              -> true        // dislike from itself

        // mutable
        else -> {
            val (pos,neg) = this.repsPost(con, blk.hash)
            (neg>=LK3_dislikes && neg>=pos) // too many dislikes
        }
    }
}

// NEW

fun Chain.blockNew (con: Consensus, sign: HKey?, like: Like?, pay: ByteArray, crypt: Boolean, backs: Set<Hash>?) : Hash {
    val backs_ = when {
        (backs != null) -> backs
        (like!=null && like.n>0 && this.heads(con,Head_State.BLOCKED).contains(like.hash)) -> setOf(like.hash)
        else -> this.heads(con,Head_State.LINKED)
    }

    val pay_ = when {
        this.name.startsWith('$') -> pay.encryptShared(this.keys[0]!!)
        crypt -> pay.encryptPublic(this.atKey()!!)
        else  -> pay
    }

    val imm = Immut (
        time = max (getNow(), 1+backs_.map{ this.fsLoadBlock(it).immut.time }.maxOrNull()!!),
        pay = Payload (
            crypt = this.name.startsWith('$') || crypt,
            hash  = pay_.calcHash()
        ),
        like  = like,
        backs = backs_
    )

    val hash = imm.toHash()

    // signs message if requested (pvt provided or in pvt chain)
    val signature = if (sign == null) null else {
        val sig = ByteArray(Sign.BYTES)
        val msg = lazySodium.bytes(hash)
        val pvt = Key.fromHexString(sign).asBytes
        lazySodium.cryptoSignDetached(sig, msg, msg.size.toLong(), pvt)
        val sig_hash = LazySodium.toHex(sig)
        Signature(sig_hash, sign.pvtToPub())
    }

    this.fsSaveBlock(con, Block(imm, hash, signature),pay_)
    return hash
}

fun Chain.blockAssert (con: Consensus?, blk: Block, size: Int) {
    val imm = blk.immut
    val now = getNow()

    // backs exist and are older
    for (bk in blk.immut.backs) {
        assert_(this.fsExistsBlock(bk)) { "back must exist" }
        assert_(this.fsLoadBlock(bk).immut.time <= blk.immut.time) { "back must be older" }
        assert_(!this.heads(con!!,Head_State.BLOCKED).contains(bk) || (blk.immut.like!=null && blk.immut.like.hash==bk)) {
            "backs must be accepted"
        }
    }

    if (blk.hash.toHeight() == 0) {
        assert_(blk.hash == this.genesis()) { "invalid genesis" }
    } else {
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
        if (this.fsExistsBlock(imm.like.hash)) {
            this.fsLoadBlock(imm.like.hash).let {
                // can dislike, but not like itself (could give +inf to all posts)
                assert_(imm.like.n<0 || !it.isFrom(blk.sign!!.pub)) { "like must not target itself" }
            }
        }
    }

    // payload size
    if (this.fromOwner(blk) || this.name.startsWith('$')) {
        // ok
    } else {
        assert_(size <= S128_pay) { "post is too large" }
    }
}
