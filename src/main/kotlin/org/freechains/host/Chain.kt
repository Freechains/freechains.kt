package org.freechains.host

import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.utils.Key
import org.freechains.common.*
import kotlinx.serialization.Serializable
//import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.Integer.max
import java.lang.Integer.min
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
    var heads : Pair<Set<Hash>,Set<Hash>> = Pair(emptySet(), emptySet())
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

// GENESIS

fun Chain.getGenesis () : Hash {
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

// REPUTATION

fun Int.toReps () : Int {
    return ceil(this.toFloat() / 10).toInt()
}

fun Chain.repsPost (hash: String) : Pair<Int,Int> {
    val likes = this
        .bfsAll(this.heads.first)
        .filter { it.immut.like != null }           // only likes
        .filter { it.immut.like!!.hash == hash }    // only likes to this post
        .map    { it.immut.like!!.n * it.hash.toHeight().toReps() }

    val pos = likes.filter { it > 0 }.map { it }.sum()
    val neg = likes.filter { it < 0 }.map { it }.sum()

    //println("$hash // chk=$chkRejected // pos=$pos // neg=$neg")
    return Pair(pos,-neg)
}

fun Chain.repsAuthor (pub: String, now: Long, heads: Set<Hash>) : Int {
    //println("REPS_AUTHOR FROM HEADS $heads")
    val gen = if (this.key==pub) LK30_max else 0
    val mines = this.bfsAll(this.heads.first).filter { it.isFrom(pub) }

    val posts = mines                                    // mines
        .filter { it.immut.like == null }                     // not likes
        .let { list ->
            val pos = list
                .filter { now >= it.immut.time + T24h_old }   // posts older than 1 day
                .map    { it.hash.toHeight().toReps() }       // get reps of each post height
                .sum    ()                                    // sum everything
            val neg = list
                .filter { now <  it.immut.time + T12h_new }   // posts newer than 1 day
                .map    { it.hash.toHeight().toReps() }       // get reps of each post height
                .sum    ()                                    // sum everything
            //println("gen=$gen // pos=$pos // neg=$neg // now=$now")
            max(gen,min(LK30_max,pos)) - neg
        }

    val recv = this.bfsAll(heads)                     // all pointing from heads to genesis
        .filter { it.immut.like != null }                       // which are likes
        .filter {                                               // and are to me
            this.fsLoadBlock(it.immut.like!!.hash).let {
                //println("${it.hash}")
                (it.sign!=null && it.sign.pub==pub)
            }
        }
        .map    { it.immut.like!!.n * it.hash.toHeight().toReps() } // get likes N
        .sum()                                                      // likes I received

    val gave = mines
        .filter { it.immut.like != null }                       // likes I gave
        //.let { println(it) ; it }
        .map { it.hash.toHeight().toReps() }                    // doesn't matter the signal
        .sum()

    //println("posts=$posts + recv=$recv - gave=$gave")
    return posts + recv - gave
}

// BFS

fun Chain.bfsIsFromTo (from: Hash, to: Hash) : Boolean {
    return this.bfsFirst(setOf(to)) { it.hash == from } != null
}

fun Chain.bfsFirst (heads: Set<Hash>, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(heads,true) { !pred(it) }
        .last()
        .let {
            if (pred(it)) it else null
        }
}

fun Chain.bfsAll (heads: Set<Hash>) : List<Block> {
    return this.bfs(heads,false) { true }
}

internal fun Chain.bfs (heads: Set<Hash>, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    val ret = mutableListOf<Block>()

    val pending = TreeSet<Block>(compareByDescending { it.immut.time })       // TODO: val cmp = ...
    pending.addAll(heads.map { this.fsLoadBlock(it) })

    val visited = heads.toMutableSet()

    while (pending.isNotEmpty()) {
        val blk = pending.first()
        pending.remove(blk)
        if (!ok(blk)) {
            if (inc) {
                ret.add(blk)
            }
            break
        }

        val list = blk.immut.backs.toList()
        pending.addAll(list.minus(visited).map { this.fsLoadBlock(it) })
        visited.addAll(list)
        ret.add(blk)
    }

    return ret
}

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}

fun Chain.all (heads: Set<Hash>): Set<Hash> {
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
        val hs  = find_heads(int)
        val now = hs.map { this.fsLoadBlock(it).immut.time }.maxOrNull()
        authors.map { Pair(it,this.reps(it,now!!,hs)) }.toMap()
    }

    // for each branch h1/h2: sum of reputation of these common authors
    fun f (hs: Set<Hash>): Int {
        val pubs = hs
            .map    { this.fsLoadBlock(it) }
            .filter { it.sign != null }
            .map    { it.sign!!.pub }
        return common
            .filter { (aut,rep) -> pubs.contains(aut) }
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

fun Chain.reps (pub: String, now: Long = getNow(), heads: Set<Hash> = this.heads.first) : Int {
    //println("REPS_AUTHOR FROM HEADS $heads")
    val ngen = if (this.key==pub) LK30_max else 0
    val all = this.all(heads).map { this.fsLoadBlock(it) }

    val mines = all.filter   { it.isFrom(pub) }
    val posts = mines.filter { it.immut.like == null }
    val likes = mines.filter { it.immut.like != null }

    val nold = if (posts.size == 0) 0 else {
        val olds = posts.filter { now >= it.immut.time + T24h_old }.size
        val first = posts.minByOrNull{ it.immut.time }!!.immut.time
        min(olds, ((now-first)/day).toInt())
    }
    val nnew = posts.filter { now <  it.immut.time + T12h_new }.size
    val nlks = likes.map { it.immut.like!!.n }.sum()

    val nrecv = (all - mines)  // (dis)likes to me
        .filter { it.immut.like != null }                           // likes
        .map { Pair(it.immut.like!!.n,
                    this.fsLoadBlock(it.immut.like.hash)) }         // to
        .filter { it.second.sign.let { it!=null && it.pub==pub } }  // me
        .map    { it.first }                                        // +/- N
        .sum()                                                      // total

    return ngen + nold - nnew - nlks + nrecv
}

// receive set of heads, returns total order
fun Chain.seq_order (heads: Set<Hash> = this.heads.first, excluding: Set<Hash> = setOf(this.getGenesis())): List<Hash> {
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
fun Chain.seq_invalid (list_: List<Hash>): Hash? {
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
