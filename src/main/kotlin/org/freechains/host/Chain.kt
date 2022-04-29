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

// internal methods are private but are used in tests

@Serializable
data class Chain (
    var root : String,
    val name : String,
    val keys : List<HKey> // multiple pioneers for public / single shared for private chain / empty otherwise
) {
    val hash : String = (this.name + "/" + keys).calcHash()
    var reps : Map<HKey,Int> = emptyMap()
    var cons : List<Hash>    = listOf(this.genesis())
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

fun Chain.fsLoadBlock (hash: Hash) : Block {
    return File(this.path() + "/blocks/" + hash + ".blk").readText().jsonToBlock()
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
    this.consensus(blk.immut.time, blk)
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

fun Chain.consensus (now: Long=getNow(), nxt: Block?=null) {
    val aaa = getNow()

    val pnds: MutableSet<Block>     = mutableSetOf(this.fsLoadBlock(this.genesis()))
    val reps: MutableMap<HKey,Int>  = mutableMapOf()
    val cons: MutableList<Block>    = mutableListOf()
    val xons: SortedSet<Hash>       = sortedSetOf()
    val negs: MutableSet<Block>     = mutableSetOf()    // new posts still penalized
    val zers: MutableSet<Block>     = mutableSetOf()    // new posts not yet consolidated
    val ones: MutableMap<HKey,Long> = mutableMapOf()    // last time block by pub was consolidated

    val bbb = getNow()
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
    println(">B> ${getNow()-bbb}")

    var iii: Long = 0
    var jjj: Long = 0

    fun negs_zers (now: Long) {
        //negs_zers(nxt.immut.time, xcon.toValue())
        val _iii = getNow()
        val tot = reps.values.sum()
        val rems = mutableSetOf<Block>()
        for (neg in negs) {
            val aft = cons
                .drop(cons.indexOf(neg))    // blocks after myself (including me)
                .map { it.sign!!.pub }      // take their authors
                .toSet()                    // remove duplicates
                .map { reps[it]!! }         // take their reps
                .sum()                      // sum everything
            // 0% -> 0, 50% -> 1
            val dt = (T12h_new * max(0.toDouble(), 1 - aft.toDouble() / tot * 2)).toInt()
            //println("<<< ${this.fsLoadPayRaw(blk.hash).toString(Charsets.UTF_8)} = ${blk.immut.time} <= $now-$dt")
            //println("[${T12h_new}] dt=$dt // 0.5 - $aft/$tot")
            if (neg.immut.time <= now - dt) {
                rems.add(neg)
                reps[neg.sign!!.pub] = min(LK30_max, reps.getXZ(neg.sign.pub) + 1)
            }
        }
        negs.removeAll(rems)
        /*
        val nonegs = negs
            .filter { blk ->
                //println(">>> ${this.fsLoadPayRaw(blk.hash).toString(Charsets.UTF_8)}")
                val tot = reps.values.sum()
                val aft = cons
                    .drop(cons.indexOf(blk))    // blocks after myself (including me)
                    .map { it.sign!!.pub }      // take their authors
                    .toSet()                    // remove duplicates
                    .map { reps[it]!! }         // take their reps
                    .sum()                      // sum everything
                // 0% -> 0, 50% -> 1
                val dt = (T12h_new * max(0.toDouble(), 1 - aft.toDouble() / tot * 2)).toInt()
                //println("<<< ${this.fsLoadPayRaw(blk.hash).toString(Charsets.UTF_8)} = ${blk.immut.time} <= $now-$dt")
                //println("[${T12h_new}] dt=$dt // 0.5 - $aft/$tot")
                blk.immut.time <= now - dt
            }
        negs.removeAll(nonegs)
        nonegs.forEach {
            reps[it.sign!!.pub] = min(LK30_max, reps.getXZ(it.sign.pub) + 1)
            //println("nonneg : +1 : ${it.sign.pub}")
        }
         */
        iii += getNow()-_iii

        val _jjj = getNow()
        val nozers = zers.filter { it.immut.time + T24h_old <= now }
        zers.removeAll(nozers)
        nozers.forEach {
            val one = ones[it.sign!!.pub]
            if (one == null || one + T24h_old <= it.immut.time) {
                ones[it.sign.pub] = it.immut.time
                reps[it.sign.pub] = min(LK30_max, reps.getXZ(it.sign.pub) + 1)
                //println("consol : +1 : ${it.sign.pub}")
            }
        }
        jjj += getNow()-_jjj
    }

    fun Hash.allFronts (): Set<Hash> {
        return setOf(this) + fronts[this]!!.map { it.allFronts() }.flatten().toSet()
    }

    fun auths (hs: Set<Hash>): Int {
        return hs   // sum of reps of all pubs that appear in blocks not in common
            //.let { println(it) ; println(con1.reps) ; it }
            .map    { this.fsLoadBlock(it) }
            .filter { it.sign != null }
            .map    { reps.getZ(it.sign!!.pub) }
            .sum    ()
    }

    val ccc = getNow()
    var x111: Long = 0
    var x222: Long = 0
    var x333: Long = 0
    var x444: Long = 0
    while (!pnds.isEmpty()) {
        // week average of posts in the last 28 days (counting from latest block in cons)
        val y111 = getNow()
        val week_avg = let {
            val ts = cons
                .map { it.immut.time }                   // time of past blocks in cons
                .let { all ->
                    val last = all.maxOrNull()           // latest block in cons
                    all.dropWhile { it < last!!-28*day } // only blocks in the past 28 days
                }
            max(7, (ts.count() / 4))                 // 7 posts/week minimum
        }

        val nxt: Block = pnds                           // find node with more reps inside pnds
            .maxWithOrNull { blk1, blk2 ->
                val h1s = blk1.hash.allFronts()         // all nodes after blk1
                val h2s = blk2.hash.allFronts()
                val h1s_h2s = h1s - h2s                 // all nodes in blk1, not in blk2
                val h2s_h1s = h2s - h1s
                val a1 = auths(h1s_h2s)                 // reps authors sum in blk1, not in blk2
                val a2 = auths(h2s_h1s)
                //println("W: $week_avg, B1: ${h1s.count()}/${blk1.local}, B2: ${h2s.count()}/${blk2.local}")
                when {
                    // both branches have 7 days of posts, the oldest (smaller time) wins (h2-h1)
                    (h1s_h2s.count()>=week_avg && blk1.local<blk2.local) ->  1
                    (h2s_h1s.count()>=week_avg && blk2.local<blk1.local) -> -1
                    // both branches have same reps, the "hashest" wins (h1 vs h2)
                    (a1 == a2) -> blk1.hash.compareTo(blk2.hash)
                    // otherwise, most reps wins (n1-n2)
                    else -> (a1 - a2)
                }
            }!!

        pnds.remove(nxt)  // rem it from sts
        cons.add(nxt)     // add it to consensus list
        xons.add(nxt.hash)
        x111 += getNow()-y111

        // set reps, negs, zers
        val y222 = getNow()
        when {
            // genesis block: set pioneers reps
            (nxt.hash == this.genesis()) -> {
                when {
                    this.name.startsWith("#") -> this.keys.forEach { reps[it] = LK30_max/this.keys.size }
                    this.name.startsWith("@") -> reps[this.atKey()!!] = LK30_max
                }
            }

            // a like: dec reps from author, inc reps to target
            (nxt.immut.like != null) -> {
                if (nxt.sign != null) {
                    reps[nxt.sign.pub] = reps.getXZ(nxt.sign.pub) - nxt.immut.like.n.absoluteValue
                }
                val target = this.fsLoadBlock(nxt.immut.like.hash).let {
                    if (it.sign == null) null else it.sign.pub
                }
                if (target != null) {
                    reps[target] = min(LK30_max, reps.getXZ(target) + nxt.immut.like.n)
                }
            }

            // a signed post: dec reps from author
            (nxt.sign != null) -> {
                reps[nxt.sign.pub] = reps.getXZ(nxt.sign.pub) - 1
                negs.add(nxt)
                zers.add(nxt)
            }
        }
        x222 += getNow()-y222

        val y444 = getNow()
        negs_zers(nxt.immut.time)// nxt may affect previous blks in negs/zers
        x444 += getNow()-y444

        // take next blocks and enqueue those (1) valid and (2) with all backs already in the consensus list
        val y333 = getNow()
        pnds.addAll (
            fronts[nxt.hash]!!
                .map    { this.fsLoadBlock(it) }
                //.filter { (it.immut.backs.toSet() - cons.map{it.hash}.toSet()).isEmpty() }   // (2)
                //.filter { it.immut.backs.all { x -> cons.any { y -> y.hash==x }} } // (2)
                .filter { xons.containsAll(it.immut.backs) } // (2)
                .filter { blk ->                                                    // (1)
                    // block in sequence is a like to my hash?
                    val islk = fronts[blk.hash]!!               // take my fronts
                        .map { this.fsLoadBlock(it) }
                        .any {                                  // (it should be only one, no?)
                            (it.immut.like != null)             // must be a like
                         && (it.immut.like.hash == blk.hash)    // to myself
                         && (it.immut.like.n > 0)               // positive
                         && (this.fromOwner(it) ||              // either from owner
                             reps.getZ(it.sign!!.pub)>0)        //  or from someone with reps
                        }

                    val ok = (blk.hash == this.genesis())       // genesis is always accepted
                          || this.fromOwner(blk)                // owner in identity chain
                          || this.name.startsWith('$')    // private chain
                          || (islk && blk.immut.like==null)     // liked in next block
                          || (blk.sign!=null && reps.getZ(blk.sign.pub)>0)  // has reps
                    ok
                }
        )
        x333 += getNow()-y333
        //xxx = getNow()
        //println(">2>: ${xxx-old}")
        //old = xxx
    }
    println(">1> $x111")
    println(">2> $x222")
    println(">3> $x333")
    println(">4> $x444")
    println(">I> $iii")
    println(">J> $jjj")
    println(">C> ${getNow()-ccc}")

    //xxx = getNow()
    //println(">2>: ${xxx-old}")
    //old = xxx

    val ddd = getNow()
    negs_zers(now)
    this.cons = cons.map {it.hash}
    this.reps = reps.toMap()
    this.fsSave()
    println(">D> ${getNow()-ddd}")
    println(">A> ${getNow()-aaa}")
}