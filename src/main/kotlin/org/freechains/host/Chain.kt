package org.freechains.host

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.utils.Key
import org.freechains.common.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

@Serializable
data class Chain (
    var root : String,
    val name : String,
    val keys : List<HKey> // multiple pioneers for public / single shared for private chain / empty otherwise
) {
    val hash : String = (this.name + "/" + keys).calcHash()

    // Hold consensus() result: many function require cons/reps
    var reps : Map<HKey,Int> = emptyMap()
    var cons : List<Hash>    = listOf(this.genesis())
    // ...
    // But they cannot be used "as is" in incremental consensus:
    //  - a fork may be introduced in an arbitrary place, which invalidates both cons/reps
    // The idea is to
    //  1. clear reps entirely
    //  2. recalculate reps/negs/ones blindly from current cons while blocks are freezed
    //  3. use the algorithm for the remaining blocks
}

fun Chain.validate () : Chain {
    val rest = when (this.name.first()) {
        '#' -> this.name.drop(1)
        '$' -> this.name.drop(1)
        '@' -> this.name.drop( if (this.name.drop(1).first() == '!') 2 else 1)
        else -> null
    }
    assert_(rest != null && rest.all { it.isLetterOrDigit() || it=='_' || it=='-' || it=='.' }) {
        "invalid chain name: $this"
    }
    when (this.name.first()) {
        '$'  -> assert_(this.keys.size == 1) { "expected shared key" }
        '#'  -> assert_(this.keys.size >= 1) { "expected public key" }
        else -> assert_(this.keys.size == 0) { "unexpected key" }
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
fun ByteArray.calcHash () : String {
    val out = ByteArray(GenericHash.BYTES)
    assert(lazySodium.cryptoGenericHash(out,GenericHash.BYTES, this,this.size.toLong(), zeros,zeros.size))
    return LazySodium.toHex(out)
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

private val cache: MutableMap<Hash,Block> = WeakHashMap()

fun Chain.fsLoadBlock (hash: Hash) : Block {
    val path = this.path() + "/blocks/" + hash + ".blk"
    return cache[path] ?: File(path).readText().jsonToBlock().let {
        cache[path] = it
        it
    }
}

fun Chain.fsLoadPayCrypt (hash: Hash, pubpvt: HKey?): ByteArray {
    val blk = this.fsLoadBlock(hash)
    val pay = this.fsLoadPayRaw(hash)
    return when {
        !blk.immut.pay.crypt -> pay
        this.name.startsWith('$') -> pay.decrypt(this.keys[0])
        (pubpvt == null)     -> pay
        else                 -> pay.decrypt(pubpvt)
    }
}

fun Chain.fsLoadPayRaw (hash: Hash) : ByteArray {
    return File(this.path() + "/blocks/" + hash + ".pay").readBytes()
}

fun Chain.fsExistsBlock (hash: Hash) : Boolean {
    return (this.hash == hash) ||
            File(this.path() + "/blocks/" + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (blk: Block, pay: ByteArray) {
    this.blockAssert(blk, pay.size)
    File(this.path() + "/blocks/" + blk.hash + ".pay").writeBytes(pay)
    File(this.path() + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
    this.consensus_one(blk)
}

fun Chain.fsAll (): Set<Hash> {
    return File(this.path() + "/blocks/").listFiles().map { it.nameWithoutExtension }.toSet()
}

// REPUTATION

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val (pos,neg) = this.cons
        .map    { this.fsLoadBlock(it) }
        .filter { it.immut.like != null }           // only likes
        .filter { it.immut.like!!.hash == hash }    // only likes to this post
        .map    { it.immut.like!!.n }
        .partition { it > 0 }
    return Pair(pos.sum(),-neg.sum())
}

fun Chain.heads (want: Head_State): Set<Hash> {
    fun Chain.find_heads (all: Set<Hash>): Set<Hash> {
        val backs = all.map { this.fsLoadBlock(it) }.map { it.immut.backs }.toSet().unionAll()
        return all - backs
    }

    val blockeds = (this.fsAll() - this.cons)
        .filter { this.fsLoadBlock(it).immut.backs.all { this.cons.contains(it) } }
        .toSet()
    return when (want) {
        Head_State.BLOCKED -> blockeds
        Head_State.LINKED  -> this.find_heads(this.cons.toSet())
        else -> error("TODO")
    }
}

//////////////////////////////////////////////////////////////////////////////

fun Map<HKey,Int>.getZ (pub: HKey): Int {
    if (!this.containsKey(pub)) {
        return 0
    }
    return this[pub]!!
}

fun MutableMap<HKey,Int>.getXZ (pub: HKey): Int {
    if (!this.containsKey(pub)) {
        this[pub] = 0
    }
    return this[pub]!!
}

fun Chain.consensus_one (nxt: Block) {
    this.consensus(nxt.immut.time)
}

fun Chain.consensus_all () {
    this.consensus(getNow())
}

fun Chain.consensus (now: Long) {
    // find the newest freezed block in the current consensus
    assert(this.cons.size >= 1)
    var freeze = 1  // genesis block is freezed
    for (i in 1..this.cons.size-1) {                        // drop i from end of list
        val ts = this.cons.dropLast(i)                      // work from the end of the list
            .map { this.fsLoadBlock(it) }
            .map { it.immut.time }                          // time of past blocks in cons
            .let { all ->
                val last = all.maxOrNull()                  // latest block in cons
                all.dropWhile { it < last!! - 28 * day }    // only blocks in the past 28 days
            }
        val week_avg = max(7, (ts.count() / 4))          // 7 posts/week minimum
        if (i >= week_avg) {
            freeze = this.cons.size-i                       // more posts then the week avg starting at i
            break                                           // stop on first (newest) freeze
        }
    }
    val frzs: List<Hash> = this.cons.take(freeze)

    val fronts: Map<Hash,Set<Hash>> = this.fsAll().let {
        val ret: MutableMap<Hash,MutableSet<Hash>> = mutableMapOf()
        for (h in it) {
            ret[h] = mutableSetOf()
        }
        for (h in it) {
            this.fsLoadBlock(h).immut.backs.forEach {
                ret[it]!!.add(h)
            }
            //ret[h] = it.filter { this.fsLoadBlock(it).immut.backs.contains(h) }.toSet()
        }
        ret.toMap()
    }

    val xcons: MutableList<Hash>     = mutableListOf()
    val xreps: MutableMap<HKey,Int>  = mutableMapOf()
    val xnegs: MutableSet<Hash>      = mutableSetOf()    // new posts still penalized
    val xzers: MutableSet<Hash>      = mutableSetOf()    // new posts not yet consolidated
    val xones: MutableMap<HKey,Long> = mutableMapOf()    // last time block by pub was consolidated
    val xpnds: MutableSet<Hash>      = mutableSetOf()

    fun negs_zers (now: Long) {
        val tot = xreps.values.sum()
        val rems = mutableSetOf<Hash>()
        for (neg_ in xnegs) {
            val neg = this.fsLoadBlock(neg_)
            val aft = xcons
                .drop(xcons.indexOf(neg_))       // blocks after myself (including me)
                .map { this.fsLoadBlock(it) }
                .map { it.sign!!.pub }          // take their authors
                .toSet()                        // remove duplicates
                .map { xreps[it]!! }            // take their reps
                .sum()                          // sum everything
            // 0% -> 0, 50% -> 1
            val dt = (T12h_new * max(0.toDouble(), 1 - aft.toDouble() / tot * 2)).toInt()
            if (neg.immut.time <= now - dt) {
                rems.add(neg_)
                xreps[neg.sign!!.pub] = min(LK30_max, xreps.getXZ(neg.sign.pub) + 1)
            }
        }
        xnegs.removeAll(rems)

        val nozers = xzers
            .map    { this.fsLoadBlock(it) }
            .filter { it.immut.time + T24h_old <= now }
        xzers.removeAll(nozers.map { it.hash })
        nozers.forEach {
            val one = xones[it.sign!!.pub]
            if (one == null || one + T24h_old <= it.immut.time) {
                xones[it.sign.pub] = it.immut.time
                xreps[it.sign.pub] = min(LK30_max, xreps.getXZ(it.sign.pub) + 1)
                //println("consol : +1 : ${it.sign.pub}")
            }
        }
    }

    fun add (hash: Hash) {
        val blk = this.fsLoadBlock(hash)
        xcons.add(hash)     // add it to consensus list

        // set reps, negs, zers
        when {
            // genesis block: set pioneers reps
            (hash == this.genesis()) -> {
                when {
                    this.name.startsWith("#") -> this.keys.forEach { xreps[it] = LK30_max/this.keys.size }
                    this.name.startsWith("@") -> xreps[this.atKey()!!] = LK30_max
                }
            }

            // a like: dec reps from author, inc reps to target
            (blk.immut.like != null) -> {
                if (blk.sign != null) {
                    xreps[blk.sign.pub] = xreps.getXZ(blk.sign.pub) - blk.immut.like.n.absoluteValue
                }
                val target = this.fsLoadBlock(blk.immut.like.hash).let {
                    if (it.sign == null) null else it.sign.pub
                }
                if (target != null) {
                    xreps[target] = min(LK30_max, xreps.getXZ(target) + blk.immut.like.n)
                }
            }

            // a signed post: dec reps from author
            (blk.sign != null) -> {
                xreps[blk.sign.pub] = xreps.getXZ(blk.sign.pub) - 1
                xnegs.add(hash)
                xzers.add(hash)
            }
        }

        negs_zers(blk.immut.time) // nxt may affect previous blks in negs/zers
    }

    // xpnds will hold the blocks outside stable consensus w/o incoming edges
    frzs.forEach {
        xpnds.remove(it)
        add(it)
        xpnds.addAll(fronts[it]!!.minus(frzs))
    }

    fun Hash.allFronts (): Set<Hash> {
        return setOf(this) + fronts[this]!!.map { it.allFronts() }.flatten().toSet()
    }

    fun auths (hs: Set<Hash>): Int {
        return hs   // sum of reps of all pubs that appear in blocks not in common
            //.let { println(it) ; println(con1.reps) ; it }
            .map    { this.fsLoadBlock(it) }
            .filter { it.sign != null }
            .map    { xreps.getZ(it.sign!!.pub) }
            .sum    ()
    }

    while (!xpnds.isEmpty()) {
        val nxt: Hash = xpnds                           // find node with more reps inside pnds
            .maxWithOrNull { h1, h2 ->
                val h1s = h1.allFronts()         // all nodes after blk1
                val h2s = h2.allFronts()
                val h1s_h2s = h1s - h2s                 // all nodes in blk1, not in blk2
                val h2s_h1s = h2s - h1s
                val a1 = auths(h1s_h2s)                 // reps authors sum in blk1, not in blk2
                val a2 = auths(h2s_h1s)
                when {
                    // both branches have same reps, the "hashest" wins (h1 vs h2)
                    (a1 == a2) -> h1.compareTo(h2)
                    // otherwise, most reps wins (n1-n2)
                    else -> (a1 - a2)
                }
            }!!

        xpnds.remove(nxt)  // rem it from sts
        add(nxt)

        // take next blocks and enqueue those (1) valid and (2) with all backs already in the consensus list
        xpnds.addAll (
            fronts[nxt]!!
                .map    { this.fsLoadBlock(it) }
                .filter { it.immut.backs.minus(xcons).isEmpty() }   // (2)
                .filter { blk ->                                    // (1)
                    // block in sequence is a like to my hash?
                    val islk = fronts[blk.hash]!!               // take my fronts
                        .map { this.fsLoadBlock(it) }
                        .any {                                  // (it should be only one, no?)
                            (it.immut.like != null)             // must be a like
                         && (it.immut.like.hash == blk.hash)    // to myself
                         && (it.immut.like.n > 0)               // positive
                         && (this.fromOwner(it) ||              // either from owner
                             xreps.getZ(it.sign!!.pub)>0)        //  or from someone with reps
                        }

                    val ok = (blk.hash == this.genesis())       // genesis is always accepted
                          || this.fromOwner(blk)                // owner in identity chain
                          || this.name.startsWith('$')    // private chain
                          || (islk && blk.immut.like==null)     // liked in next block
                          || (blk.sign!=null && xreps.getZ(blk.sign.pub)>0)  // has reps
                    ok
                }
                .map { it.hash }
        )
    }
    negs_zers(now)
    this.cons = xcons.toList()
    this.reps = xreps.toMap()
}