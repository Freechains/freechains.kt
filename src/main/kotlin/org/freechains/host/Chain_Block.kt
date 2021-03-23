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

fun Chain.blockState (blk: Block, now: Long) : State {
    val ath = if (blk.sign==null) 0 else this.repsAuthor(blk.sign.pub, now, setOf(blk.hash))
    val (pos,neg) = this.repsPost(blk.hash)
    val unit = blk.hash.toHeight().toReps()

    //println("rep ${blk.hash} = reps=$pos-$neg + ath=$ath // ${blk.immut.time}")
    return when {
        // unchangeable
        (blk.hash.toHeight() == 0)     -> State.ACCEPTED       // genesis block
        this.fromOwner(blk)            -> State.ACCEPTED       // owner signature
        this.name.startsWith('$') -> State.ACCEPTED       // chain with trusted hosts/authors only
        (blk.immut.like != null)       -> State.ACCEPTED       // a like

        // changeable
        (pos==0 && ath<unit) -> State.BLOCKED        // no likes && noob author
        (neg>= LK5_dislikes && LK2_factor *neg>=pos) -> State.HIDDEN   // too much dislikes
        else -> State.ACCEPTED
    }
}

// NEW

fun Chain.blockNew (imm_: Immut, pay0: String, sign: HKey?, pubpvt: Boolean, backs_: Set<Hash> = this.heads.first) : Block {
    assert_(imm_.time == 0.toLong()) { "time must not be set" }
    assert_(imm_.pay.hash == "") { "pay must not be set" }

    assert_(imm_.backs.isEmpty())

    var backs = backs_
    imm_.like.let { liked ->
        if (liked!=null && liked.n>0 && this.heads.second.contains(liked.hash)) {
            backs = backs + liked.hash // TODO: - this.fsLoadBlock(liked.hash).immut.backs
        }
    }

    val imm = imm_.copy (
        time = max (
                getNow(),
            1 + backs.map { this.fsLoadBlock(it).immut.time }.maxOrNull()!!
        ),
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
    val signature=
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

    val new = Block(imm, hash, signature)
    this.blockChain(new,pay1)
    return new
}

fun Chain.blockChain (blk: Block, pay: String) {
    this.blockAssert(blk)

    this.fsSaveBlock(blk)
    this.fsSavePay(blk.hash, pay)

    this.heads = when (this.blockState(blk,blk.immut.time)) {
        State.BLOCKED -> Pair (
            this.heads.first,
            this.heads.second + blk.hash
        )
        else -> Pair (
            this.heads.first - blk.immut.backs + blk.hash,
            this.heads.second - blk.immut.backs
        )
    }

    this.fsSave()
}

fun Chain.blockRemove (hash: Hash) {
    val blk = this.fsLoadBlock(hash)
    assert_(this.heads.second.contains(blk.hash)) { "can only remove blocked block" }
    this.heads = Pair(this.heads.first, this.heads.second - hash)
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
        assert_(!this.heads.second.contains(bk) || (blk.immut.like!=null && blk.immut.like.hash==bk)) {
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
        assert_ (
            this.fromOwner(blk) ||   // owner has infinite reputation
            this.name.startsWith('$') ||   // dont check reps (private chain)
            this.repsAuthor(blk.sign!!.pub, imm.time, imm.backs.toSet()) >= blk.hash.toHeight().toReps()
        ) {
            "like author must have reputation"
        }
    }
}
