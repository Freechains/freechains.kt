//import kotlinx.serialization.UnstableDefault
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

data class Block (
    val backs  : Set<Block>,
    val height : Int,
    val author : String,
    val id     : String
)

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}

fun dfs_all (b: Block): Set<Block> {
    return setOf(b) + b.backs.map(::dfs_all).toSet().unionAll()
}

fun commons (b1: Block, b2: Block): Set<Block> {
    val b1s = dfs_all(b1)
    val b2s = dfs_all(b2)
    val int = b1s.intersect(b2s)
    val all = int.map { dfs_all(it)-it }.toSet().unionAll()
    return int - all
}

fun greater (b1: Block, b2: Block): Pair<Block,Block> {
    val b1s = dfs_all(b1)
    val b2s = dfs_all(b2)

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

fun seq (b: Block, stops: Set<Block>): List<Block> {
    val backs = b.backs.toList()
    return when {
        stops.contains(b) -> emptyList()
        else -> when (b.backs.size) {
            0 -> error("TODO-1") //emptyList()
            1 -> seq(backs[0], stops)
            2 -> seqs(b.backs, stops)
            else -> error("TODO")
        } + b
    }
}

fun seqs (bs: Set<Block>, stops: Set<Block>): List<Block> {
    val l = bs.toList()
    return when (l.size) {
        1 -> seq(l[0], stops)
        2 -> {
            val (x1,x2) = greater(l[0],l[1])
            val coms = commons(x1,x2)
            return seqs(coms, stops) + seq(x1,coms) + seq(x2,coms)
        }
        else -> error("TODO")
    }
}

@TestMethodOrder(Alphanumeric::class)
class Consensus {

    @Test
    fun a01_bfs() {
        val gen = Block(emptySet(), 0, "_", "gen")
        val a1  = Block(setOf(gen), 1, "A", "a1")
        val a2  = Block(setOf(a1),  2, "A", "a2")
        val b1  = Block(setOf(a1),  2, "B", "b1")

        // gen <- a1 <- a2
        //          \-- b1

        assert(1 == dfs_all(gen).size)
        assert(2 == dfs_all(a1).size)
        assert(3 == dfs_all(b1).size)
        assert(3 == dfs_all(a2).size)

        val a1_gen = dfs_all(a1) - dfs_all(gen)
        val gen_a1 = dfs_all(gen) - dfs_all(a1)
        assert(a1_gen.contains(a1) && gen_a1.isEmpty())

        val a2s = dfs_all(a2) - dfs_all(b1)
        val b1s = dfs_all(b1) - dfs_all(a2)
        assert(a2s.size==1 && b1s.size==1)
        assert(a2s.contains(a2) && b1s.contains(b1))

        val pres = dfs_all(a2).intersect(dfs_all(b1))
        val counts = pres.groupBy { it.author }.mapValues { it.value.size }
        assert(1 == counts["A"])

        val na2 = counts.filter { pre -> a2s.map { it.author }.contains(pre.key) }.size
        val nb1 = counts.filter { pre -> b1s.map { it.author }.contains(pre.key) }.size
        assert(na2==1 && nb1==0)

        val coms = commons(a2,b1)
        assert(coms.size==1 && coms.contains(a1))

        val (x1,x2) = greater(b1,a2)
        assert(x1==a2 && x2==b1)

        val bs = seqs(setOf(b1,a2), setOf(gen))
        val ret = bs.map { it.id }.joinToString(",")
        //println(ret)
        assert("a1,a2,b1" == ret)
    }

    @Test
    fun a02_commons() {
        val gen = Block(emptySet(), 0, "_", "gen")
        val a1  = Block(setOf(gen), 1, "A", "a1")
        val a2  = Block(setOf(a1),  2, "A", "a2")
        val b2  = Block(setOf(a1),  2, "B", "b2")
        val a3  = Block(setOf(a2,b2),  3, "A", "a3")
        val b3  = Block(setOf(a2,b2),  3, "B", "b3")

        // gen <- a1 <- a2 <\ a3
        //          \-- b2 </ b3

        val coms = commons(a3,b3)
        assert(coms.size==2 && coms.contains(a2) && coms.contains(b2))
    }

    @Test
    fun a03_seqs() {
        val gen = Block(emptySet(),   0, "_", "gen")
        val a1  = Block(setOf(gen),   1, "A", "a1")
        val a2  = Block(setOf(a1),    2, "A", "a2")
        val b2  = Block(setOf(a1),    2, "B", "b2")
        val ab3 = Block(setOf(a2,b2), 3, "B", "ab3")

        // gen <- a1 <- a2 <- ab3
        //          \-- b2 /

        val x = seq(ab3, setOf(gen)).map { it.id }.joinToString(",")
        assert(x == "a1,a2,b2,ab3")
    }

    @Test
    fun a04_seqs() {
        val gen = Block(emptySet(),   0, "_", "gen")
        val a0  = Block(setOf(gen),   1, "A", "a0")
        val a1  = Block(setOf(a0),    2, "A", "a1")
        val b1  = Block(setOf(a0),    2, "B", "b1")
        val a2  = Block(setOf(a1,b1), 3, "A", "a2")
        val c1  = Block(setOf(a0),    2, "C", "c1")
        val a3  = Block(setOf(a2,c1), 4, "A", "a3")

        //          /----- c1 -----\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seq(a3, setOf(gen)).map { it.id }.joinToString(",")
        println(x)
        assert(x == "a0,a1,b1,a2,c1,a3")
    }

    @Test
    fun a05_seqs() {
        val gen = Block(emptySet(),   0, "_", "gen")
        val a0  = Block(setOf(gen),   1, "A", "a0")
        val a1  = Block(setOf(a0),    2, "A", "a1")
        val b1  = Block(setOf(a0),    2, "B", "b1")
        val a2  = Block(setOf(a1,b1), 3, "A", "a2")
        val c1  = Block(setOf(a1),    3, "C", "c1")
        val a3  = Block(setOf(a2,c1), 4, "A", "a3")

        //                /-- c1 --\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seq(a3, setOf(gen)).map { it.id }.joinToString(",")
        println(x)
        assert(x == "a0,a1,b1,a2,c1,a3")
    }
}
