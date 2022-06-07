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

fun Chain.reset () {
    this.reps  = emptyMap()
    this.cons  = emptyList()
    this.frts  = emptyMap()
    this.fcons = emptyList()
    this.freps = emptyMap()
    this.fnegs = emptySet()
    this.fzers = emptySet()
    this.fones = emptyMap()
}

@Serializable
data class Chain (
    var root : String,
    val name : String,
    val keys : List<HKey> // multiple pioneers for public / single shared for private chain / empty otherwise
) {
    val hash : String = (this.name + "/" + keys).calcHash()

    // Hold consensus() result: many function require cons/reps
    var cons : List<Hash>    = emptyList()
    var reps : Map<HKey,Int> = emptyMap()
    var frts : Map<Hash,Set<Hash>> = emptyMap()

    var fcons : List<Hash>     = emptyList()
    var freps : Map<HKey,Int>  = emptyMap()
    var fnegs : Set<Hash>      = emptySet()    // new posts still penalized
    var fzers : Set<Hash>      = emptySet()    // new posts not yet consolidated
    var fones : Map<HKey,Long> = emptyMap()    // last time block by pub was consolidated

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

fun ByteArray.utf8 () : String {
    return this.toString(Charsets.UTF_8)
}

fun Chain.fsExistsBlock (hash: Hash) : Boolean {
    return (this.hash == hash) ||
            File(this.path() + "/blocks/" + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (blk: Block, pay: ByteArray) {
    this.blockAssert(blk, pay.size)
    File(this.path() + "/blocks/" + blk.hash + ".pay").writeBytes(pay)
    File(this.path() + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
    this.consensus()
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

fun Chain.heads1 (want: Head_State): Set<Hash> {
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

fun Chain.heads2 (want: Head_State): Set<Hash> {
    val blocked = (this.fsAll() - this.cons)
    return when (want) {
        Head_State.LINKED  -> this.cons.filter { (this.frts[it]!! - blocked).isEmpty() }.toSet()
        Head_State.BLOCKED -> blocked
            .filter { this.fsLoadBlock(it).immut.backs.all { this.cons.contains(it) } }
            .toSet()
        else -> error("TODO")
    }
}

fun Chain.heads3 (want: Head_State): Set<Hash> {
    val blocked = (this.fsAll() - this.cons)
    return when (want) {
        Head_State.LINKED  -> this.cons.toSet() //this.cons.filter { (this.frts[it]!! - blocked).isEmpty() }.plus(this.cons).toSet()
        Head_State.BLOCKED -> emptySet() //blocked.minus(blocked)
        else -> error("TODO")
    }
}

fun Chain.heads (want: Head_State): Set<Hash> {
    //this.consensus()
    return when (want) {
        Head_State.LINKED  -> this.heads2(want)
        Head_State.BLOCKED -> this.heads2(want)
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

fun <T> SortedSet<T>.sortedCopy (): SortedSet<T> {
    val ret: SortedSet<T> = sortedSetOf()
    ret.addAll(this)
    return ret
}

fun <T:Comparable<T>> sortedMinus (s1: SortedSet<T>, s2: SortedSet<T>): SortedSet<T> {
    val ret: SortedSet<T> = sortedSetOf()

    while (true) {
        if (s1.isEmpty()) {
            break
        }
        if (s2.isEmpty()) {
            ret.addAll(s1)
            break
        }
        val v1 = s1.first()
        val v2 = s2.first()
        when {
            (v1 < v2) -> {
                ret.add(v1)
                s1.remove(v1)
            }
            (v1 == v2) -> {
                s1.remove(v1)
                s2.remove(v2)
            }
            (v1 > v2) -> {
                s2.remove(v2)
            }
        }
    }
    return ret
}

fun Chain.consensus () {
    /*
    val all = this.fsAll()
    val x1 = all.count()
    val x2 = all.map { this.fsLoadBlock(it) }
        .map { it.immut.backs }
        .groupingBy { it }
        .eachCount()
        .values
        .map { it-1 }
        .sum()
    println(x1)
    println(x2)
    println("-=-=-=-=-=-=-=-")
    */

    val now = getNow()
    val t1 = now
    //println(">>> T1 = $t1")

    // new freezes
    //val last = this.cons.lastOrNull().let { if (it == null) null else this.fsLoadBlock(it).immut.time }
    var n = 0
    val xreps: MutableMap<HKey,Int>       = this.freps.toMutableMap()
    val xnegs: MutableSet<Hash>           = this.fnegs.toMutableSet()
    val xzers: MutableSet<Hash>           = this.fzers.toMutableSet()
    val xones: MutableMap<HKey,Long>      = this.fones.toMutableMap()
    val xfrts: MutableMap<Hash,MutableSet<Hash>> = this.frts.let {
        val ret: MutableMap<Hash,MutableSet<Hash>> = mutableMapOf()
        it.forEach { s, set -> ret[s] = set.toMutableSet() }
        ret
    }
    val xcons: MutableList<Hash>          = this.cons.dropLastWhile {
        val cur = this.fsLoadBlock(it)
        n++
        val isblocked = this.frts[it]!!.any { this.fsLoadBlock(it).immut.like?.hash == cur.hash }
        //println("${cur.immut.time} >= ${last-7*day}")
        //isblocked || (it!=this.genesis() && /*cur.immut.time>now-T7d_fork &&*/ n<=N100_fork)
        isblocked || (it!=this.genesis() && cur.immut.time>now-T7d_fork && n<=N100_fork)
    }.toMutableList()
    val nfrze = n
    //println(">>>")
    //println("111: " + this.cons.map { it.take(5) })
    //println("222: " + xcons.map { it.take(5) })

    //println(">>> " + this.fcons.map { this.fsLoadPayRaw(it).utf8() })
    //println("<<< " + xcons.map { this.fsLoadPayRaw(it).utf8() })
    val t2 = getNow()
    //println(">>> T2 = $t2")

    // xfrts = ...
    sortedMinus(this.fsAll().toSortedSet(), this.cons.toSortedSet()).let {
    //this.fsAll().filter { !this.cons.contains(it) }.let {
        for (h in it) {
            xfrts[h] = mutableSetOf()
        }
        for (h in it) {
            this.fsLoadBlock(h).immut.backs.forEach {
                xfrts[it]!!.add(h)
            }
        }
    }

    val t3 = getNow()
    //println(">>> T3 = $t3")

    var tnegs: Long = 0
    var tnegs1: Long = 0
    var tnegs2: Long = 0
    var tnegs3: Long = 0
    var nnegs1 = 0      // max xnegs.size
    var nnegs2 = 0      // max xcons-neg index
    //println(xnegs)
    fun negs_zers (now: Long) {
        val t0 = getNow()
        val tot = xreps.values.sum()
        val rems = mutableSetOf<Hash>()
        nnegs1 = max(nnegs1, xnegs.size)
        for (neg_ in xnegs) {
            val neg = this.fsLoadBlock(neg_)
            //println(neg_)
            nnegs2 = max(nnegs2, xcons.size-xcons.indexOf(neg_))
            val aft = xcons  // TODO: not calculating blocks before (-1) drop
                .drop(xcons.indexOf(neg_).let { if (it==-1) 0 else it }) // blocks after myself (including me)
                .map { this.fsLoadBlock(it) }
                .filter { it.sign != null }
                .map { it.sign!!.pub }          // take their authors
                .toSet()                        // remove duplicates
                .map { xreps[it] ?: 0 }         // take their reps
                .sum()                          // sum everything
            // 0% -> 0, 50% -> 1
            val dt = (T12h_new * max(0.toDouble(), 1 - aft.toDouble() / tot * 2)).toInt()
            if (neg.immut.time <= now - dt) {
                rems.add(neg_)
                xreps[neg.sign!!.pub] = min(LK30_max, xreps.getXZ(neg.sign.pub) + 1)
            } else {
                break   // must remove in consensus order, not time order
            }
        }
        val t1 = getNow()
        tnegs1 += t1-t0

        xnegs.removeAll(rems)
        val t2 = getNow()
        tnegs2 += t2-t1

        val nozers = xzers
            .map    { this.fsLoadBlock(it) }
            .filter { it.immut.time+T24h_old <= now }
        xzers.removeAll(nozers.map { it.hash })
        nozers.forEach {
            val one = xones[it.sign!!.pub]
            if (one == null || one + T24h_old <= it.immut.time) {
                xones[it.sign.pub] = it.immut.time
                xreps[it.sign.pub] = min(LK30_max, xreps.getXZ(it.sign.pub) + 1)
                //println("consol : +1 : ${it.sign.pub}")
            }
        }
        val t3 = getNow()
        tnegs3 += t3-t2
        tnegs += (t3-t0)
    }

    fun reps (hash: Hash) {
        val blk = this.fsLoadBlock(hash)

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
                xzers.add(hash)
                xreps[blk.sign.pub] = xreps.getXZ(blk.sign.pub) - 1
                xnegs.add(hash)
            }
        }

        negs_zers(blk.immut.time) // this blk may affect previous blks in negs/zers
    }

    for (i in this.fcons.size .. xcons.size-1) {
        reps(xcons[i])
    }

    val t4 = getNow()
    //println(">>> T4 = $t4")

    //println(this.fcons.size)
    //println(fronts)
    //println(xpnds)
    //println(this.freps)
    //println(xreps)

    val t5 = getNow()       // TODO: 110
    //println(">>> T5 = $t5")

    this.fcons = xcons.toList()
    this.freps = xreps.toMap()
    this.fnegs = xnegs.toSet()
    this.fzers = xzers.toSet()
    this.fones = xones.toMap()
    this.frts = xfrts.toMap()

    //println(xfrts)
    //xfrts.forEach { (h,set) -> assert(set.all { it.toHeight() >= h.toHeight() }) }
    val cache: MutableMap<Hash,SortedSet<Hash>> = WeakHashMap()
    fun Hash.allFronts (): SortedSet<Hash> {
        return cache[this] ?:
            (setOf(this) + xfrts[this]!!.map { it.allFronts() }.flatten())
                .toSortedSet()
                .let {
                    cache[this] = it
                    it
                }
    }

    // sum of reps of all authors that appear in blocks not in common
    fun auths (hs: Set<Hash>): Int {
        return hs
            //.let { println(it) ; println(con1.reps) ; it }
            .map    { this.fsLoadBlock(it) }
            .filter { it.sign != null }         // only signed blocks
            .map    { it.sign!!.pub }           // take all public keys
            .toSet  ()                          // remove duplicates
            .map    { xreps.getZ(it) }          // take each reps
            .sum    ()                          // sum everything
    }

    val t6 = getNow()       // t6=0
    //println(">>> T6 = $t6")

    var t61: Long = 0
    var t62: Long = 0
    var t63: Long = 0
    var nxpnds = 0      // max number of simultaneous nodes in xpnds
    var nforks = 0      // max number of uncommon nodes in forks
    //println("FRONTS: " + fronts["52_C78E1AE73C801526BDB4D81C781E7078C808E98501266566CD6B39EBE38DBABE"])
    //println("REJECTED")

    // GUARDAR FRONTS EM XPNDS, colocar em ordem
    // otimizar h1s-h2s

    fun cmp (x1: Pair<Hash,SortedSet<Hash>>, x2: Pair<Hash,SortedSet<Hash>>): Int {
        val (h1,fr1) = x1
        val (h2,fr2) = x2
        //println("<<<")
        val h1s_h2s = sortedMinus(fr1.sortedCopy(), fr2.sortedCopy())  // all nodes in blk1, not in blk2
        val h2s_h1s = sortedMinus(fr2.sortedCopy(), fr1.sortedCopy())
        //assert(h1s_h2s.size!=0 && h2s_h1s.size!=0)
        nforks = max(nforks, h1s_h2s.size)
        nforks = max(nforks, h2s_h1s.size)

        val (b1,c1) = h1.hashSplit()
        val (b2,c2) = h2.hashSplit()

        val a1 = auths(h1s_h2s.toSet())     // reps authors sum in blk1, not in blk2
        val a2 = auths(h2s_h1s.toSet())
        //println(">>> ${h1s_h2s.size} / ${h2s_h1s.size}")
        //println(">>> ${h1.take(5)}: $a1")
        //println(">>> ${h2.take(5)}: $a2")

        return when {
            (h1s_h2s.size == 0) -> -1
            (h2s_h1s.size == 0) -> 1
            (a1 != a2) -> (a1 - a2)
            (b1 != b2) -> -(b1 - b2)
            else -> -c1.compareTo(c2)
        }
    }

    // xpnds will hold the blocks outside stable consensus w/o incoming edges
    val xpnds: MutableSet<Pair<Hash,SortedSet<Hash>>> = mutableSetOf()
    val xord = xcons.toSortedSet()
    if (!xord.contains(this.genesis())) {
        val gen = this.genesis()
        xpnds.add(Pair(gen, gen.allFronts().sortedCopy()))
    }
    for (h in xcons) {
        xpnds.addAll (  // add all of xcons fronts
            xfrts[h]!!
                .filter { !xord.contains(it) }
                .filter { new -> xpnds.none { it.second.contains(new) } }
                .map    { Pair(it, it.allFronts().sortedCopy()) }
        )
    }
    //println(xcons)
    //println(xpnds)

    while (!xpnds.isEmpty()) {
        nxpnds = max(nxpnds, xpnds.size)
        val x61 = getNow()
        // find node with more reps inside pnds
        //println("-=-=-=-")
        val nxt = xpnds.maxWithOrNull(::cmp)!!    //.maxWithOrNull { (h1,fr1), (h2,fr2) -> ... }!!.first
        //if (nxt.toHeight() <= 25) {
            //println(">>> ${xpnds.sorted().map { it.take(5) }}")
            //println("<<< $nxt")
        //}

        assert(xpnds.remove(nxt))  // rem it from sts
        //println(">>> $nxt: ${xpnds.sorted()}")
        t61 += getNow()-x61

        //if (nxt == "1321_8FEB85E73D1EBE9DE62D84F5B5F1C781FA32A0323B4002A7723321837CFBC2FA") {
        //    System.err.println(xpnds.sorted().map { it.take(10) })
        //}

        /*
        assert(!xcons.contains(nxt)) {
            (nxt.first + " // " +
            //xpnds.sorted().map { it.take(10) } + " // " +
            "!!! ERRO !!!")
        }
         */

        xcons.add(nxt.first)     // add it to consensus list
        //println("    >>> $nxt")
        xord.add(nxt.first)
        val x62 = getNow()
        reps(nxt.first)
        t62 += getNow()-x62

        // take next blocks and enqueue those (1) valid and (2) with all backs already in the consensus list
        val x63 = getNow()
        xpnds.addAll (
            xfrts[nxt.first]!!
                .filter { new -> xpnds.none { it.second.contains(new) } }
                .map    { this.fsLoadBlock(it) }
                .filter { xord.containsAll(it.immut.backs) }   // (2)
                .filter { blk ->                                    // (1)
                    // block in sequence is a like to my hash?
                    val islk = xfrts[blk.hash]!!               // take my fronts
                        .map { this.fsLoadBlock(it) }
                        .any {                                  // (like must have blk as single back, no?)
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
                    //println("? ${blk.hash}, $islk, $ok")
                    //if (!ok) println(blk.hash)
                    ok
                }
                .map { Pair(it.hash, it.hash.allFronts().sortedCopy()) }
                .let {
                    nxpnds += it.size
                    it
                }
        )
        t63 += getNow()-x63
    }

    val t7 = getNow()       //  t7=543 (0,404,85)
    //println(">>> T7 = $t7")

    negs_zers(now)
    this.cons = xcons.toList()
    //println("333: " + this.cons.map { it.take(5) })
    //println("<<<")
    this.reps = xreps.toMap()

    val t8 = getNow()
    //println(">>> T8 = $t8")
    //println("TIMES=${t8-t1} | t2=${t2-t1} | t3=${t3-t2} | t4=${t4-t3} | t5=${t5-t4} | t6=${t6-t5} | t7=${t7-t6} ($t61,$t62,$t63) | t8=${t8-t7} | tnegs=$tnegs")
    //println("SIZES | fz=${xcons.size}/$nfrze | negs=${nnegs1}x${nnegs2}=${nnegs1*nnegs2} | xpnds=$nxpnds/$nforks")
    //println("<<< " + this.cons.map { this.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(","))
    this.fsSave()
}
