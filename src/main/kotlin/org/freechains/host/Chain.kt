package org.freechains.host

import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.common.*
import kotlinx.serialization.Serializable
//import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.ceil

// internal methods are private but are used in tests

@Serializable
data class Chain (
    var root : String,
    val name : String,
    val key  : HKey?    // pioneer for public or shared for private chain
) {
    val hash  : String = this.name.calcHash()
}

// TODO: change to contract/constructor assertion
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

fun Chain.heads (hs: Set<Hash> = this.fsAll(), now: Long = getNow()): Pair<Set<Hash>,Set<Hash>> {
    val blks = hs
        .filter { head -> (hs-head).none { this.all(setOf(it)).contains(head) } }
        .filter { this.isBlocked(this.fsLoadBlock(it),now) }
        .toSet()
    val accs = (hs-blks).let { it
        .filter { head -> (it-head).none { this.all(setOf(it)).contains(head) } }
        .toSet()
    }
    return Pair(accs, blks)
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

fun Chain.fsLoadPay1 (hash: Hash, pubpvt: HKey?) : String {
    val blk = this.fsLoadBlock(hash)
    val pay = this.fsLoadPay0(hash)
    return when {
        !blk.immut.pay.crypt -> pay
        this.name.startsWith('$') -> pay.decrypt(this.key!!)
        (pubpvt == null)     -> pay
        else                 -> pay.decrypt(pubpvt)
    }
}

fun Chain.fsLoadPay0 (hash: Hash) : String {
    return File(this.path() + "/blocks/" + hash + ".pay").readBytes().toString(Charsets.UTF_8)
}

fun Chain.fsExistsBlock (hash: Hash) : Boolean {
    return (this.hash == hash) ||
            File(this.path() + "/blocks/" + hash + ".blk").exists()
}

fun Chain.fsSaveBlock (blk: Block) {
    File(this.path() + "/blocks/" + blk.hash + ".blk").writeText(blk.toJson()+"\n")
}

fun Chain.fsSavePay (hash: Hash, pay: String) {
    File(this.path() + "/blocks/" + hash + ".pay").writeText(pay)
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

fun Chain.all (heads: Set<Hash>): Set<Hash> {
    fun<T> Set<Set<T>>.unionAll (): Set<T> {
        return this.fold(emptySet(), {x,y->x+y})
    }
    return heads + heads.map { this.all(this.fsLoadBlock(it).immut.backs) }.toSet().unionAll()
}

fun Chain.greater (h1: Hash, h2: Hash): Int {
    fun find_heads (bs: Set<Hash>): Set<Hash> {
        return bs.filter { head -> (bs-head).none { this.all(setOf(it)).contains(head) } }.toSet()
    }

    val h1s = this.all(setOf(h1))
    val h2s = this.all(setOf(h2))
    val int = h1s.intersect(h2s)

    // counts the reps of common authors in common blocks
    // counts = {A=2, B=5, C=1, ...}
    val common = let {
        val pioneer = if (this.name.startsWith("#")) setOf(this.key!!) else emptySet()
        val authors = pioneer + int
            .map { this.fsLoadBlock(it) }
            .filter { it.sign!=null }
            .map { it.sign!!.pub }
            .toSet()
        authors
            .map {
                val (reps,inv) = this.seq_invalid(this.seq_order(find_heads(int)))
                assert(inv == null)
                Pair(it,reps[it]!!)
            }
            .toMap()
    }

    // for each branch h1/h2: sum of reputation of these common authors
    fun f (hs: Set<Hash>): Int {
        val pubs = hs
            .map    { this.fsLoadBlock(it) }
            .filter { it.sign != null }
            .map    { it.sign!!.pub }
        return common
            .filter { (aut,_) -> pubs.contains(aut) }
            .map { it.value }
            .sum()
    }

    val n1 = f(h1s - h2s)
    val n2 = f(h2s - h1s)
    //println(common)
    //println(h1s-h2s)
    //println("$n1 // $n2")

    return if (n1 == n2) h1.compareTo(h2) else (n1 - n2)
}

fun Chain.reps (pub: String, now: Long = getNow(), heads: Set<Hash> = this.heads().first) : Int {
    //println(this.seq_order(heads).joinToString(","))
    val (reps,inv) = this.seq_invalid(this.seq_order(heads), now)
    //println(pub)
    //println(reps)
    //println(inv)
    //assert(inv == null)
    return if (inv != null) -1 else reps[pub]!!
}

// receive set of heads, returns total order
fun Chain.seq_order (heads: Set<Hash> = this.heads().first, excluding: Set<Hash> = setOf(this.genesis())): List<Hash> {
    val l = heads.toMutableList()
    assert(l.size > 0)
    val ret = mutableListOf<Hash>()
    var exc = excluding
    while (l.size > 0) {
        var cur = l.maxWithOrNull(::greater)!!
        if (!exc.contains(cur)) {
            ret += seq_order(this.fsLoadBlock(cur).immut.backs, exc) + cur
        }
        exc += this.all(setOf(cur))
        l.remove(cur)
    }
    return ret
}

// find first invalid block in blockchain
fun Chain.seq_invalid (list_: List<Hash>, now: Long = getNow()): Pair<Map<HKey,Int>,Hash?> {
    val list = list_.map { this.fsLoadBlock(it) }
    val negs = mutableSetOf<Block>()
    val zers = mutableSetOf<Block>()
    val reps = list
        .filter { it.sign != null }
        .map    { Pair(it.sign!!.pub,0) }
        .toMap().toMutableMap()
    if (this.name.startsWith("#")) {
        reps[this.key!!] = LK30_max
    }

    fun f (): Hash? {
        for (i in 0..list.size-1) {
            val cur = list[i]

            val nonegs = negs.filter { it.immut.time <= cur.immut.time-12*hour }
            negs -= nonegs
            nonegs.forEach {
                reps[it.sign!!.pub] = reps[it.sign.pub]!! + 1
            }

            val nozers = zers.filter { it.immut.time <= cur.immut.time-24*hour }
            zers -= nozers
            nozers.forEach {
                reps[it.sign!!.pub] = reps[it.sign.pub]!! + 1
            }

            // next block is a like to my hash?
            val lk = (i+1 <= list.size-1) && list[i+1].let { nxt ->
                (nxt.immut.like!=null) && reps[nxt.sign!!.pub]!!>0 && nxt.immut.like.hash==cur.hash && nxt.immut.like.n>0
            }

            when {
                // anonymous or no-reps author

                (cur.sign==null || reps[cur.sign.pub]!! <= 0) -> when {
                    (cur.immut.like != null)  -> return cur.hash        // can't like w/o reps
                    !lk                       -> return cur.hash        // can't post if next !lk
                    else                      -> if (cur.sign!=null) {  // ok, but set -1
                        reps[cur.sign.pub] = reps[cur.sign.pub]!! - 1
                        negs.add(cur)
                        zers.add(cur)
                    }
                }

                // has reps

                // normal post just decrements 1
                (cur.immut.like == null) -> {
                    reps[cur.sign.pub] = reps[cur.sign.pub]!! - 1
                    negs.add(cur)
                    zers.add(cur)
                }

                // like also affects target
                else -> {
                    val target = this.fsLoadBlock(cur.immut.like.hash).let {
                        if (it.sign == null) null else it.sign.pub
                    }
                    reps[cur.sign.pub] = reps[cur.sign.pub]!! - cur.immut.like.n.absoluteValue
                    if (target != null) {
                        reps[target] = reps[target]!! + cur.immut.like.n
                    }
                }
            }
        }
        return null
    }
    val inv = f()

    val nonegs = negs.filter { it.immut.time <= now-12*hour }
    negs -= nonegs
    nonegs.forEach {
        reps[it.sign!!.pub] = reps[it.sign.pub]!! + 1
    }

    val nozers = zers.filter { it.immut.time <= now-24*hour }
    zers -= nozers
    nozers.forEach {
        reps[it.sign!!.pub] = reps[it.sign.pub]!! + 1
    }

    return Pair(reps,inv)
}

// all blocks to remove (in DAG) that lead to the invalid block (in blockchain)
fun Chain.seq_remove (rem: Hash): Set<Hash> {
    return this.all(this.heads().first).filter { this.all(setOf(it)).contains(rem) }.toSet()
}

