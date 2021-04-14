package org.freechains.host

import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.utils.Key
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
    val hash  : String = (this.name + "/" + keys).calcHash()
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

fun Chain.fsLoadTime (hash: Hash) : Long {
    return Date(File(this.path() + "/blocks/" + hash + ".blk").lastModified()).toInstant().toEpochMilli()
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

fun Chain.fsSaveBlock (con: Consensus?, blk: Block, pay: ByteArray) {
    this.blockAssert(con, blk, pay.size)
    File(this.path() + "/blocks/" + blk.hash + ".pay").writeBytes(pay)
    File(this.path() + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.fsAll (): Set<Hash> {
    return File(this.path() + "/blocks/").listFiles().map { it.nameWithoutExtension }.toSet()
}

// SETS

fun Chain.allFroms (hs: Set<Hash>): Set<Hash> {
    return hs.map { this.allFrom(it) }.toSet().unionAll()
}

fun Chain.allFrom (h: Hash): Set<Hash> {
    return setOf(h) + this.allFroms(this.fsLoadBlock(h).immut.backs)
}

fun Chain.find_heads (all: Set<Hash>): Set<Hash> {
    val backs = all.map { this.fsLoadBlock(it) }.map { it.immut.backs }.toSet().unionAll()
    return all - backs
}

// REPUTATION

fun Chain.repsPost (con: Consensus, hash: String) : Pair<Int,Int> {
    val (pos,neg) = con.list
        .map    { this.fsLoadBlock(it) }
        .filter { it.immut.like != null }           // only likes
        .filter { it.immut.like!!.hash == hash }    // only likes to this post
        .map    { it.immut.like!!.n }
        .partition { it > 0 }
    return Pair(pos.sum(),-neg.sum())
}

fun Consensus.repsAuthor (pub: HKey) : Int {
    return this.reps.getZ(pub)
}

fun Chain.heads (con: Consensus, want: Head_State): Set<Hash> {
    val blockeds = (this.fsAll() - con.list)
        .filter { this.fsLoadBlock(it).immut.backs.all { con.list.contains(it) } }
        .toSet()
    return when (want) {
        Head_State.BLOCKED -> blockeds
        Head_State.LINKED  -> this.find_heads(con.list.toSet())
        else -> error("TODO")
    }
}

//////////////////////////////////////////////////////////////////////////////

data class Consensus (
    val reps: Map<HKey,Int>,
    val list: List<Hash>,
    val negs: Set<Hash>,         // new posts still penalized
    val zers: Set<Hash>,         // new posts not yet positive
    val ones: Map<HKey,Long>     // last time block by pub was consolidated
)

data class XConsensus (
    val reps: MutableMap<HKey,Int>,
    val list: MutableList<Hash>,
    val negs: MutableSet<Hash>,
    val zers: MutableSet<Hash>,
    val ones: MutableMap<HKey,Long>
)

fun Consensus.toMutable (): XConsensus {
    return XConsensus (
        this.reps.toMutableMap(),
        this.list.toMutableList(),
        this.negs.toMutableSet(),
        this.zers.toMutableSet(),
        this.ones.toMutableMap()
    )
}

fun XConsensus.toValue (): Consensus {
    return Consensus(this.reps, this.list, this.negs, this.zers, this.ones)
}

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

var cache: HashMap<Pair<Set<Hash>,Hash?>,Consensus> = hashMapOf()

fun Chain.consensus (): Consensus {
    //cache = hashMapOf()
    val con1 = this.consensus_aux(this.find_heads(this.fsAll()),null)
    val con2 = negs_zers(getNow(), con1)
    //println(con.list.map { this.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(","))
    //println(con.list.map { it }.joinToString(","))
    return con2
}

fun Chain.negs_zers (now: Long, con: Consensus): Consensus {
    val x = con.toMutable()

    val nonegs = con.negs
        .map { this.fsLoadBlock(it) }
        .filter { blk ->
            //println(">>> ${this.fsLoadPayRaw(blk.hash).toString(Charsets.UTF_8)}")
            val tot = con.reps.values.sum()
            val aft = con.list
                .drop(con.list.indexOf(blk.hash))   // blocks after myself (including me)
                .map { this.fsLoadBlock(it) }       // take their authors
                .map { it.sign!!.pub }              // take their authors
                .toSet()                            // remove duplicates
                .map { con.reps[it]!! }             // take their reps
                .sum()                              // sum everything
            // 0% -> 0, 50% -> 1
            val dt = (T12h_new * max(0.toDouble(), 1 - aft.toDouble()/tot*2)).toInt()
            //println("<<< ${this.fsLoadPayRaw(blk.hash).toString(Charsets.UTF_8)} = ${blk.immut.time} <= $now-$dt")
            //println("[${T12h_new}] dt=$dt // 0.5 - $aft/$tot")
            blk.immut.time <= now-dt
        }
    x.negs -= nonegs.map { it.hash }
    nonegs.forEach {
        x.reps[it.sign!!.pub] = min(LK30_max,x.reps.getXZ(it.sign.pub) + 1)
        //println("nonneg : +1 : ${it.sign.pub}")
    }

    val nozers = x.zers.map { this.fsLoadBlock(it) }.filter { it.immut.time+T24h_old <= now }
    x.zers -= nozers.map { it.hash }
    nozers.forEach {
        val one = x.ones[it.sign!!.pub]
        if (one==null || one+T24h_old <= it.immut.time) {
            x.ones[it.sign.pub] = it.immut.time
            x.reps[it.sign.pub] = min(LK30_max, x.reps.getXZ(it.sign.pub) + 1)
            //println("consol : +1 : ${it.sign.pub}")
        }
    }

    return x.toValue()
}

fun Chain.consensus_aux (heads: Set<Hash>, nxt: Block?): Consensus {
    val fst = synchronized (cache) {
        cache.filterKeys { heads.equals(it.first) && (nxt==null && it.second==null) || (nxt!=null && nxt.hash==it.second) }.values
    }
    if (fst.size > 0) {
        assert(fst.size == 1)
        //return fst.elementAt(0)
    }
    //println("NO: " + heads)
    val ret = when (heads.size) {
        0    -> Consensus(mapOf(), listOf(), setOf(), setOf(), mapOf())
        1    -> consensus_aux1(heads.single(), nxt)
        else -> consensus_auxN(heads)
    }
    if (fst.size > 0) {
        assert(fst.size == 1)
        //val old = fst.elementAt(0)
        //assert(old.copy(invs=emptySet(),reps=old.reps.filterValues{it!=0}).toString() == ret.copy(invs=emptySet(),reps=ret.reps.filterValues{it!=0}).toString()) { println(heads) ; println(old) ; println(ret) }
        //assert(old.toString() == ret.toString()) { println(heads) ; println(old) ; println(ret) }
    }
    synchronized (cache) {
        cache[Pair(heads,if (nxt==null) null else nxt.hash)] = ret
    }
    return ret
}

fun Chain.consensus_aux1 (head: Hash, nxt: Block?): Consensus {
    val blk = this.fsLoadBlock(head)
    val con = consensus_aux(blk.immut.backs, blk)
    return this.consensus_one(con, blk, nxt)
}

fun Chain.consensus_one (con: Consensus, blk: Block, nxt: Block?): Consensus {
    val xcon = con.toMutable()

    // next block is a like to my hash?
    val lk = (nxt!=null && nxt.immut.like!=null &&
             (this.fromOwner(nxt) || con.reps.getZ(nxt.sign!!.pub)>0) &&
             nxt.immut.like.hash==blk.hash && nxt.immut.like.n>0)

    val ok = blk.hash==this.genesis() || this.fromOwner(blk) || this.name.startsWith('$') ||
             (blk.sign!=null && con.reps.getZ(blk.sign.pub)>0) ||
             (lk && blk.immut.like==null)

    when {
        !ok -> {} //x1.invs += blk.hash     // no reps
        (blk.immut.like != null) -> {   // a like
            xcon.list.add(blk.hash)
            val target = this.fsLoadBlock(blk.immut.like.hash).let {
                if (it.sign == null) null else it.sign.pub
            }
            if (blk.sign != null) {
                xcon.reps[blk.sign.pub] = xcon.reps.getXZ(blk.sign.pub) - blk.immut.like.n.absoluteValue
                //println("source : -${blk.immut.like.n.absoluteValue} : ${blk.sign.pub}")
            }
            if (target != null) {
                xcon.reps[target] = min(LK30_max, xcon.reps.getXZ(target) + blk.immut.like.n)
                //println("target : +${blk.immut.like.n} : $target")
            }
        }
        else -> {                       // a post
            xcon.list.add(blk.hash)
            when {
                (blk.hash == this.genesis()) -> {
                    when {
                        this.name.startsWith("#") -> this.keys.forEach { xcon.reps[it] = LK30_max/this.keys.size }
                        this.name.startsWith("@") -> xcon.reps[this.atKey()!!] = LK30_max
                    }
                }
                (blk.sign != null) -> {
                    xcon.reps[blk.sign.pub] = xcon.reps.getXZ(blk.sign.pub) - 1
                    //println("post   : -1 : ${blk.sign.pub}")
                    xcon.negs.add(blk.hash)
                    xcon.zers.add(blk.hash)
                }
            }
        }
    }

    // must go last b/c the effect of this block on con.reps may affect the call
    return negs_zers(blk.immut.time, xcon.toValue())
}

fun Chain.consensus_auxN (heads: Set<Hash>): Consensus {
    val alls = heads.map { this.allFrom(it) }.toSet()
    val coms = alls.intersectAll()
    var con = consensus_aux(this.find_heads(coms), null)
    val tot = con.reps.values.sum()

    val subs = heads.map { this.consensus_aux1(it,null) }.toMutableList()
    while (subs.size > 0) {
        fun freps (hs: Set<Hash>): Int {
            return hs   // sum of reps of all pubs that appear in blocks not in common
                //.let { println(it) ; println(con1.reps) ; it }
                .map    { this.fsLoadBlock(it) }
                .filter { it.sign != null }
                .map    { con.reps.getZ(it.sign!!.pub) }
                .sum    ()
        }
        val max = subs.maxWithOrNull { con1, con2 ->
            val h1  = con1.list.last()
            val h2  = con2.list.last()
            val h1s = con1.list.toSet()
            val h2s = con2.list.toSet()
            val n1 = freps(h1s - h2s)
            val n2 = freps(h2s - h1s)
            //println(n1.toString() + " vs " + n2)
            when {
                // both branches have +50% reps, the oldest/smaller-time wins (h2-h1)
                (tot<=n1*2 && tot<=n2*2) -> (this.fsLoadTime(h2) - this.fsLoadTime(h1)).toInt()
                // both branches have same reps, the "hashest" wins (h1-h2)
                (n1 == n2)               -> h1.compareTo(h2)
                // otherwise, most reps wins (n1-n2)
                else                     -> (n1 - n2)
            }
        }!!
        val l = max.list - con.list
        for (i in 0..(l.size-1)) {
            con = this.consensus_one(con, this.fsLoadBlock(l[i]), if (i==l.size-1) null else this.fsLoadBlock(l[i+1]))
        }
        subs.remove(max)
    }
    return con
}
