package org.freechains.host

import java.util.*

enum class BfsDir {
    BACKS, FRONTS
}

fun Chain.bfsFrontsIsFromTo (from: Hash, to: Hash) : Boolean {
    //println(this.bfsFirst(listOf(from), true) { it.hash == to })
    return this.bfsFrontsFirst(from) { it.hash == to } != null
}

fun Chain.bfsBacksFindAuthor (pub: String) : Block? {
    return this.bfsBacksFirst(this.heads.first) { it.isFrom(pub) }
}

fun Chain.bfsFrontsFirst (start: Hash, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(setOf(start), BfsDir.FRONTS, pred)
}

fun Chain.bfsBacksFirst (heads: Set<Hash>, pred: (Block) -> Boolean) : Block? {
    return this.bfsFirst(heads, BfsDir.BACKS, pred)
}

fun Chain.bfsBacksAuthor (heads: Set<Hash>, pub: String) : List<Block> {
    return this
        .bfsBacksFirst(heads) { it.isFrom(pub) }.let { blk ->
            if (blk == null) {
                emptyList()
            } else {
                fun f (blk: Block) : List<Block> {
                    return listOf(blk) + blk.immut.prev.let {
                        if (it == null) emptyList() else f(this.fsLoadBlock(it))
                    }
                }
                f(blk)
            }
        }
}

private fun Chain.bfsFirst (starts: Set<Hash>, dir: BfsDir, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(starts,true, dir) { !pred(it) }
        .last()
        .let {
            if (pred(it)) it else null
        }
}

fun Chain.bfsFrontsAll (start: Hash = this.getGenesis()) : List<Block> {
    return this.bfsFronts(start,false) { true }
}

fun Chain.bfsBacksAll (heads: Set<Hash>) : List<Block> {
    return this.bfsBacks(heads,false) { true }
}

fun Chain.bfsFronts (start: Hash, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(setOf(start), inc, BfsDir.FRONTS, ok)
}

fun Chain.bfsBacks (starts: Set<Hash>, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    return this.bfs(starts, inc, BfsDir.BACKS, ok)
}

internal fun Chain.bfs (starts: Set<Hash>, inc: Boolean, dir: BfsDir, ok: (Block) -> Boolean) : List<Block> {
    val ret = mutableListOf<Block>()

    val pending =
        if (dir == BfsDir.FRONTS) {
            TreeSet<Block>(compareBy { it.immut.time })
        } else {
            TreeSet<Block>(compareByDescending { it.immut.time })       // TODO: val cmp = ...
        }
    pending.addAll(starts.map { this.fsLoadBlock(it) })

    val visited = starts.toMutableSet()

    while (pending.isNotEmpty()) {
        val blk = pending.first()
        pending.remove(blk)
        if (!ok(blk)) {
            if (inc) {
                ret.add(blk)
            }
            break
        }

        /*
        if (dir == BfsDir.FRONTS) {
            if (this.fronts.get(blk.hash) == null) {
                println("XXXXX ${blk.hash}")
            }
        }
         */

        val list = if (dir == BfsDir.FRONTS) this.fronts.get(blk.hash)!! else blk.immut.backs.toList()
        pending.addAll(list.minus(visited).map { this.fsLoadBlock(it) })
        visited.addAll(list)
        ret.add(blk)
    }

    return ret
}