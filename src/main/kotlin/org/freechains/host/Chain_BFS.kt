package org.freechains.host

import java.util.*

fun Chain.bfsIsFromTo (from: Hash, to: Hash) : Boolean {
    return this.bfsFirst(setOf(to)) { it.hash == from } != null
}

fun Chain.bfsFirst (starts: Set<Hash>, pred: (Block) -> Boolean) : Block? {
    return this
        .bfs(starts,true) { !pred(it) }
        .last()
        .let {
            if (pred(it)) it else null
        }
}

fun Chain.bfsAll (heads: Set<Hash>) : List<Block> {
    return this.bfs(heads,false) { true }
}

internal fun Chain.bfs (starts: Set<Hash>, inc: Boolean, ok: (Block) -> Boolean) : List<Block> {
    val ret = mutableListOf<Block>()

    val pending = TreeSet<Block>(compareByDescending { it.immut.time })       // TODO: val cmp = ...
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

        val list = blk.immut.backs.toList()
        pending.addAll(list.minus(visited).map { this.fsLoadBlock(it) })
        visited.addAll(list)
        ret.add(blk)
    }

    return ret
}