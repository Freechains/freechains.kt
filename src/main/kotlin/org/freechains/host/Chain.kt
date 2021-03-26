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

fun Chain.heads (state: Head_State): Set<Hash> {
    val all = this.fsAll()
    val hs = all.filter { head -> (all-head).none { this.allFrom(it).contains(head) } }

    fun blockeds (): Set<Hash> {
        return hs
            .filter { head -> (hs-head).none { this.allFrom(it).contains(head) } }
            .filter {
                val blk = this.fsLoadBlock(it)
                when {
                    // immutable
                    (blk.hash.toHeight() == 0)     -> false       // genesis block
                    this.fromOwner(blk)            -> false       // owner signature
                    this.name.startsWith('$') -> false       // chain with trusted hosts/authors only
                    (blk.immut.like != null)       -> false       // a like

                    // mutable
                    else -> {
                        val rep = if (blk.sign==null) 0 else {
                            this.reps(blk.sign.pub, setOf(blk.hash))
                        }
                        (rep < 0)
                    }
                }
            }
            .toSet()
    }

    return when (state) {
        Head_State.ALL     -> hs.toSet()
        Head_State.BLOCKED -> blockeds()
        Head_State.LINKED  -> (all-blockeds()).let { it
            .filter { head -> (it-head).none { this.allFrom(it).contains(head) } }
            .toSet()
        }
    }
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

fun Chain.fsSaveBlock (blk: Block, pay: String) {
    this.blockAssert(blk)
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

fun Chain.repsAuthor (pub: HKey) : Int {
    val (reps,_,_) = this.consensus().let { println(it) ; it }
    return reps[pub].let { if (it == null) 0 else it }
}

fun Chain.allFroms (hs: Set<Hash>): Set<Hash> {
    return hs.map { this.allFrom(it) }.toSet().unionAll()
}

fun Chain.allFrom (h: Hash): Set<Hash> {
    return setOf(h) + this.allFroms(this.fsLoadBlock(h).immut.backs)
}

fun Chain.greater (h1: Hash, h2: Hash): Int {
    fun find_heads (bs: Set<Hash>): Set<Hash> {
        return bs.filter { head -> (bs-head).none { this.allFrom(it).contains(head) } }.toSet()
    }

    val h1s = this.allFrom(h1)
    val h2s = this.allFrom(h2)
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

fun Chain.reps (pub: String, heads: Set<Hash> = this.heads(Head_State.LINKED)) : Int {
    val (reps,inv) = this.seq_invalid(this.seq_order(heads))
    return if (inv != null) -1 else reps[pub]!!
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
    val negs: MutableSet<Block>,
    val zers: MutableSet<Block>
)

fun Chain.consensus (): Consensus {
    val con = Consensus(mutableMapOf(), mutableListOf(), mutableSetOf(), mutableSetOf(), mutableSetOf())
    println(this.fsAll())
    println(this.find_heads(this.fsAll()))
    this.consensus_aux(this.find_heads(this.fsAll()),null,con)
    negs_zers(getNow(), con)
    return con
}

fun negs_zers (now: Long, con: Consensus) {
    val nonegs = con.negs.filter { it.immut.time <= now-12*hour }
    con.negs -= nonegs
    nonegs.forEach {
        con.reps[it.sign!!.pub] = con.reps[it.sign.pub]!! + 1
    }
    val nozers = con.zers.filter { it.immut.time <= now-24*hour }
    con.zers -= nozers
    nozers.forEach {
        con.reps[it.sign!!.pub] = con.reps[it.sign.pub]!! + 1
    }
}

fun Chain.consensus_aux (heads: Set<Hash>, nxt: Block?, con: Consensus) {
    when (heads.size) {
        0    -> consensus_aux0(con)
        1    -> consensus_aux1(heads.single(), nxt, con)
        else -> consensus_auxN(heads, con)
    }
}

fun Chain.consensus_aux0 (con: Consensus) {
    assert(con.reps.isEmpty() && con.list.isEmpty() && con.invs.isEmpty() && con.negs.isEmpty() && con.zers.isEmpty())
    if (this.name.startsWith("#")) {
        con.reps[this.key!!] = 30
    }
    con.list.add(this.genesis())
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
             (blk.sign!=null && con.reps[blk.sign.pub]!!>0) ||
             (lk && blk.immut.like==null)

    when {
        !ok -> con.invs += blk.hash     // no reps
        (blk.immut.like != null) -> {   // a like
            val target = this.fsLoadBlock(blk.immut.like.hash).let {
                if (it.sign == null) null else it.sign.pub
            }
            if (blk.sign != null) {
                con.reps[blk.sign.pub] = con.reps[blk.sign.pub]!! - blk.immut.like.n.absoluteValue
            }
            if (target != null) {
                con.reps[target] = con.reps[target]!! + blk.immut.like.n
            }
        }
        else -> {                       // a post
            if (blk.sign != null) {
                con.reps[blk.sign.pub] = con.reps[blk.sign.pub]!! - 1
                con.negs.add(blk)
                con.zers.add(blk)
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
        val max = l.maxWithOrNull { h1,h2 ->
            val h1s = this.allFrom(h1)
            val h2s = this.allFrom(h2)
            fun freps (hs: Set<Hash>): Int {
                return hs
                    .map    { this.fsLoadBlock(it) }
                    .filter { it.sign != null }
                    .map    { it.sign!!.pub }
                    .filter { con.reps.containsKey(it) }
                    .toSet  ()  // all pubs that appear in blocks not in common and have reps
                    .map    { con.reps[it]!! }
                    .sum    ()
            }
            val n1 = freps(h1s - h2s)
            val n2 = freps(h2s - h1s)
            if (n1 == n2) h1.compareTo(h2) else (n1 - n2)
        }!!
        this.consensus_aux(setOf(max), null, con)
        l.remove(max)
    }
}

//////////////////////////////////////////////////////////////////////////////

// receive set of heads, returns total order
fun Chain.seq_order (heads: Set<Hash> = this.heads(Head_State.ALL), excluding: Set<Hash> = setOf(this.genesis())): List<Hash> {
    val l = heads.toMutableList()
    assert(l.size > 0)
    val ret = mutableListOf<Hash>()
    var exc = excluding
    while (l.size > 0) {
        var cur = l.maxWithOrNull(::greater)!!
        if (!exc.contains(cur)) {
            ret += seq_order(this.fsLoadBlock(cur).immut.backs, exc) + cur
        }
        exc += this.allFrom(cur)
        l.remove(cur)
    }
    return ret
}

// find first invalid block in blockchain
fun Chain.seq_invalid (list_: List<Hash>): Pair<Map<HKey,Int>,Hash?> {
    val now  = getNow()
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

            // owner/private chain has infinite reputation
            val ok = this.fromOwner(cur) || this.name.startsWith('$')

            when {
                // anonymous or no-reps author

                !ok && (cur.sign==null || reps[cur.sign.pub]!!<=0) -> when {
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
                    if (cur.sign != null) {
                        reps[cur.sign.pub] = reps[cur.sign.pub]!! - 1
                        negs.add(cur)
                        zers.add(cur)
                    }
                }

                // like also affects target
                else -> {
                    val target = this.fsLoadBlock(cur.immut.like.hash).let {
                        if (it.sign == null) null else it.sign.pub
                    }
                    if (cur.sign != null) {
                        reps[cur.sign.pub] = reps[cur.sign.pub]!! - cur.immut.like.n.absoluteValue
                    }
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
    return this.allFroms(this.heads(Head_State.LINKED)).filter { this.allFrom(it).contains(rem) }.toSet()
}

