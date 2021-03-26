package org.freechains.host

import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.common.*
import kotlinx.serialization.Serializable
//import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.absoluteValue

// internal methods are private but are used in tests

@Serializable
data class Chain (
    var root : String,
    val name : String,
    val key  : HKey?    // pioneer for public or shared for private chain
) {
    val hash  : String = this.name.calcHash()
}

fun Chain.validate () : Chain {
    val rest = when (this.name.first()) {
        '#' -> this.name.drop(1)
        '$' -> this.name.drop(1)
        '@' -> this.name.drop( if (this.name.drop(1).first() == '!') 2 else 1)
        else -> null
    }
    assert_(rest != null && rest.all { it.isLetterOrDigit() || it == '.' }) {
        "invalid chain name: $this"
    }
    when (this.name.first()) {
        '$'  -> assert_(this.key != null) { "expected shared key" }
        '#'  -> assert_(this.key != null) { "expected public key" }
        else -> assert_(this.key == null) { "unexpected key" }
    }
    return this
}

fun Chain.atKey () : HKey? {
    return when {
        this.name.startsWith("@!") -> this.name.drop(2)
        this.name.startsWith('@')   -> this.name.drop(1)
        else -> null
    }
}

fun Chain.path () : String {
    return this.root + "/chains/" + this.name + "/"
}

// JSON

fun Chain.toJson () : String {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.encodeToString(Chain.serializer(), this)
}

fun String.fromJsonToChain () : Chain {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.decodeFromString(Chain.serializer(), this)
}

// GENESIS, HEADS

fun Chain.genesis () : Hash {
    return "0_" + this.hash
}

// HASH

val zeros = ByteArray(GenericHash.BYTES)
fun String.calcHash () : String {
    return lazySodium.cryptoGenericHash(this, Key.fromBytes(zeros))
}

fun Immut.toHash () : Hash {
    fun Set<Hash>.backsToHeight () : Int {
        return when {
            this.isEmpty() -> 0
            else -> 1 + this.map { it.toHeight() }.maxOrNull()!!
        }
    }
    return this.backs.backsToHeight().toString() + "_" + this.toJson().calcHash()
}

// FILE SYSTEM

internal fun Chain.fsSave () {
    val dir = File(this.path() + "/blocks/")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    File(this.path() + "/" + "chain").writeText(this.toJson())
}

fun Chain.fsLoadBlock (hash: Hash) : Block {
    return File(this.path() + "/blocks/" + hash + ".blk").readText().jsonToBlock()
}

fun Chain.fsLoadPayCrypt (hash: Hash, pubpvt: HKey?) : String {
    val blk = this.fsLoadBlock(hash)
    val pay = this.fsLoadPayRaw(hash)
    return when {
        !blk.immut.pay.crypt -> pay
        this.name.startsWith('$') -> pay.decrypt(this.key!!)
        (pubpvt == null)     -> pay
        else                 -> pay.decrypt(pubpvt)
    }
}

fun Chain.fsLoadPayRaw (hash: Hash) : String {
    return File(this.path() + "/blocks/" + hash + ".pay").readBytes().toString(Charsets.UTF_8)
}

fun Chain.fsExistsBlock (hash: Hash) : Boolean {
    return (this.hash == hash) ||
            File(this.path() + "/blocks/" + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (con: Consensus?, blk: Block, pay: String) {
    this.blockAssert(con, blk)
    File(this.path() + "/blocks/" + blk.hash + ".pay").writeText(pay)
    File(this.path() + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.fsAll (): Set<Hash> {
    return File(this.path() + "/blocks/").listFiles().map { it.nameWithoutExtension }.toSet()
}

// REPUTATION

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val (pos,neg) = this
        .fsAll()
        .map    { this.fsLoadBlock(it) }
        .filter { it.immut.like != null }           // only likes
        .filter { it.immut.like!!.hash == hash }    // only likes to this post
        .map    { it.immut.like!!.n }
        .partition { it > 0 }
    return Pair(pos.sum(),-neg.sum())
}

fun Chain.allFroms (hs: Set<Hash>): Set<Hash> {
    return hs.map { this.allFrom(it) }.toSet().unionAll()
}

fun Chain.allFrom (h: Hash): Set<Hash> {
    return setOf(h) + this.allFroms(this.fsLoadBlock(h).immut.backs)
}

fun Chain.find_heads (all: Set<Hash>): Set<Hash> {
    fun isHead (h: Hash): Boolean {
        return (all-h).none { this.allFrom(it).contains(h) }
    }
    return all.filter { isHead(it) }.toSet()
}

//////////////////////////////////////////////////////////////////////////////

data class Consensus (
    val reps: MutableMap<HKey,Int>,
    val list: MutableList<Hash>,
    val invs: MutableSet<Hash>,
    val negs: MutableSet<Hash>,
    val zers: MutableSet<Hash>
)

fun MutableMap<HKey,Int>.getZ (pub: HKey): Int {
    if (!this.containsKey(pub)) {
        this[pub] = 0
    }
    return this[pub]!!
}

fun Consensus.repsAuthor (pub: HKey) : Int {
    return this.reps.getZ(pub)
}

fun Chain.consensus (): Consensus {
    val con = Consensus(mutableMapOf(), mutableListOf(), mutableSetOf(), mutableSetOf(), mutableSetOf())
    this.consensus_aux(this.find_heads(this.fsAll()),null,con)
    negs_zers(getNow(), con)
    return con
}

fun Chain.negs_zers (now: Long, con: Consensus) {
    val nonegs = con.negs.map { this.fsLoadBlock(it) }.filter { it.immut.time <= now-12*hour }
    con.negs -= nonegs.map { it.hash }
    nonegs.forEach {
        con.reps[it.sign!!.pub] = con.reps[it.sign.pub]!! + 1
    }
    val nozers = con.zers.map { this.fsLoadBlock(it) }.filter { it.immut.time <= now-24*hour }
    con.zers -= nozers.map { it.hash }
    nozers.forEach {
        con.reps[it.sign!!.pub] = con.reps[it.sign.pub]!! + 1
    }
}

fun Chain.consensus_aux (heads: Set<Hash>, nxt: Block?, con: Consensus) {
    when (heads.size) {
        0    -> {}
        1    -> consensus_aux1(heads.single(), nxt, con)
        else -> consensus_auxN(heads, con)
    }
}

fun Chain.consensus_aux1 (head: Hash, nxt: Block?, con: Consensus) {
    if (con.list.contains(head) || this.allFrom(head).intersect(con.invs).isNotEmpty()) {
        return // stop if reaches a node in list or node that leads to invs
    }

    val blk = this.fsLoadBlock(head)
    consensus_aux(blk.immut.backs, blk, con)

    negs_zers(blk.immut.time, con)

    // next block is a like to my hash?
    val lk = (nxt!=null && nxt.immut.like!=null && con.reps[nxt.sign!!.pub]!!>0 &&
             nxt.immut.like.hash==blk.hash && nxt.immut.like.n>0)

    val ok = head==this.genesis() || this.fromOwner(blk) || this.name.startsWith('$') ||
             (blk.sign!=null && con.reps.getZ(blk.sign.pub)>0) ||
             (lk && blk.immut.like==null)

    when {
        !ok -> con.invs += blk.hash     // no reps
        (blk.immut.like != null) -> {   // a like
            con.list.add(blk.hash)
            val target = this.fsLoadBlock(blk.immut.like.hash).let {
                if (it.sign == null) null else it.sign.pub
            }
            if (blk.sign != null) {
                con.reps[blk.sign.pub] = con.reps.getZ(blk.sign.pub) - blk.immut.like.n.absoluteValue
            }
            if (target != null) {
                con.reps[target] = con.reps.getZ(target) + blk.immut.like.n
            }
        }
        else -> {                       // a post
            con.list.add(blk.hash)
            when {
                (this.name.startsWith("#") && blk.hash == this.genesis()) -> con.reps[this.key!!] = 30
                (blk.sign != null) -> {
                    con.reps[blk.sign.pub] = con.reps.getZ(blk.sign.pub) - 1
                    con.negs.add(blk.hash)
                    con.zers.add(blk.hash)
                }
            }
        }
    }
}

fun Chain.consensus_auxN (heads: Set<Hash>, con: Consensus) {
    val alls = heads.map { this.allFrom(it) }.toSet()
    val coms = alls.intersectAll()
    consensus_aux(this.find_heads(coms), null, con)

    val l = heads.toMutableList()
    assert(l.size > 0)
    while (l.size > 0) {
        fun freps (hs: Set<Hash>): Int {
            return hs   // sum of reps of all pubs that appear in blocks not in common
                .map    { this.fsLoadBlock(it) }
                .filter { it.sign != null }
                .map    { con.reps.getZ(it.sign!!.pub) }
                .sum    ()
        }
        val max = l.maxWithOrNull { h1,h2 ->
            val h1s = this.allFrom(h1)
            val h2s = this.allFrom(h2)
            val n1 = freps(h1s - h2s)
            val n2 = freps(h2s - h1s)
            if (n1 == n2) h1.compareTo(h2) else (n1 - n2)
        }!!
        this.consensus_aux(setOf(max), null, con)
        l.remove(max)
    }
}
