//import kotlinx.serialization.UnstableDefault
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

data class Block (
    val backs  : Set<Block>,
    val author : String,
    val id     : String
)

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}

fun bfs_all (b: Block): Set<Block> {
    return setOf(b) + b.backs.map(::bfs_all).toSet().unionAll()
}

fun bfs_first (b: Block, f: (Block)->Boolean): Block? {
    return when {
        f(b) -> b
        b.backs.isEmpty() -> null
        else -> b.backs.first(f)
    }
}

fun bfs_commonBlock (b1: Block, b2: Block): Block {
    val b1s = bfs_all(b1)
    return bfs_first(b2, {b -> b1s.contains(b)})!!
}

fun greater (b1: Block, b2: Block): Pair<Block,Block> {
    val b1s = bfs_all(b1)
    val b2s = bfs_all(b2)

    // author counts in common prefix
    // counts = {A=2, B=5, C=1, ...}
    val counts = b1s.intersect(b2s).groupBy { it.author }.mapValues { it.value.size }

    // number of authors in counts that appear in each side
    val n_b1s = counts.filter { pre -> (b1s-b2s).map { it.author }.contains(pre.key) }.size
    val n_b2s = counts.filter { pre -> (b2s-b1s).map { it.author }.contains(pre.key) }.size

    return when {
        (n_b1s > n_b2s) -> Pair(b1,b2)
        (n_b1s < n_b2s) -> Pair(b2,b1)
        else -> error("TODO: order tie")
    }
}

fun seq (b: Block, stop: Block?): List<Block> {
    val backs = b.backs.toList()
    return when {
        (b.id == "gen") -> listOf(b)
        (b == stop) -> emptyList()
        else -> when (b.backs.size) {
            0 -> error("TODO") //emptyList()
            1 -> seq(backs[0], stop)
            2 -> seqs(backs[0], backs[1], stop)
            else -> error("TODO")
        } + b
    }
}

fun seqs (b1: Block, b2: Block, stop: Block?): List<Block> {
    val (x1,x2) = greater(b1,b2)
    val common = bfs_commonBlock(x1,x2)
    return seq(common, stop) + seq(x1,common) + seq(x2,common)
}

@TestMethodOrder(Alphanumeric::class)
class Consensus {

    @Test
    fun a01_bfs() {
        val gen = Block(emptySet(), "_", "gen")
        val a1  = Block(setOf(gen), "A", "a1")
        val a2  = Block(setOf(a1), "A", "a2")
        val b1  = Block(setOf(a1), "B", "b1")

        // gen <- a1 <- a2
        //          \-- b1

        assert(1 == bfs_all(gen).size)
        assert(2 == bfs_all(a1).size)
        assert(3 == bfs_all(b1).size)
        assert(3 == bfs_all(a2).size)

        assert(a1 == bfs_first(a2, {b -> b.id=="a1"}))
        assert(a1 == bfs_commonBlock(a2,b1))
        assert(gen == bfs_commonBlock(a1,gen))
        assert(gen == bfs_commonBlock(b1,gen))


        val a1_gen = bfs_all(a1) - bfs_all(gen)
        val gen_a1 = bfs_all(gen) - bfs_all(a1)
        assert(a1_gen.contains(a1) && gen_a1.isEmpty())

        val a2s = bfs_all(a2) - bfs_all(b1)
        val b1s = bfs_all(b1) - bfs_all(a2)
        assert(a2s.size==1 && b1s.size==1)
        assert(a2s.contains(a2) && b1s.contains(b1))

        val pres = bfs_all(a2).intersect(bfs_all(b1))
        val counts = pres.groupBy { it.author }.mapValues { it.value.size }
        assert(1 == counts["A"])

        val na2 = counts.filter { pre -> a2s.map { it.author }.contains(pre.key) }.size
        val nb1 = counts.filter { pre -> b1s.map { it.author }.contains(pre.key) }.size
        assert(na2==1 && nb1==0)

        val (x1,x2) = greater(b1,a2)
        assert(x1==a2 && x2==b1)

        val bs = seqs(b1, a2, null)
        assert("gen,a1,a2,b1" == bs.map { it.id }.joinToString(","))
    }

    @Test
    fun a02_seqs() {
        val gen = Block(emptySet(), "_", "gen")
        val a1  = Block(setOf(gen), "A", "a1")
        val a2  = Block(setOf(a1), "A", "a2")
        val b2  = Block(setOf(a1), "B", "b2")
        val ab3 = Block(setOf(a2,b2), "B", "ab3")

        // gen <- a1 <- a2 <- ab3
        //          \-- b2 /

        val x = seq(ab3,null).map { it.id }.joinToString(",")
        assert(x == "gen,a1,a2,b2,ab3")
    }

    @Test
    fun a03_seqs() {
        val gen = Block(emptySet(),   "_", "gen")
        val a0  = Block(setOf(gen),   "A", "a0")
        val a1  = Block(setOf(a0),    "A", "a1")
        val b1  = Block(setOf(a0),    "B", "b1")
        val a2  = Block(setOf(a1,b1), "A", "a2")
        val c1  = Block(setOf(a0),    "C", "c1")
        val a3  = Block(setOf(a2,c1), "A", "a3")

        //          /----- c1 -----\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seq(a3,null).map { it.id }.joinToString(",")
        println(x)
        assert(x == "gen,a0,a1,b1,a2,c1,a3")
    }

    @Test
    fun a04_seqs() {
        val gen = Block(emptySet(),   "_", "gen")
        val a0  = Block(setOf(gen),   "A", "a0")
        val a1  = Block(setOf(a0),    "A", "a1")
        val b1  = Block(setOf(a0),    "B", "b1")
        val a2  = Block(setOf(a1,b1), "A", "a2")
        val c1  = Block(setOf(a1),    "C", "c1")
        val a3  = Block(setOf(a2,c1), "A", "a3")

        //                /-- c1 --\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seq(a3,null).map { it.id }.joinToString(",")
        println(x)
        assert(x == "gen,a0,a1,b1,a2,c1,a3")
    }
}
