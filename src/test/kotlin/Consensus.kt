//import kotlinx.serialization.UnstableDefault
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

data class Block (
    val backs  : Set<Block>,
    val author : String,
    val id     : String,
    val like   : Pair<Block,Int>?
)

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}

fun dfs_all (b: Block): Set<Block> {
    return setOf(b) + b.backs.map(::dfs_all).toSet().unionAll()
}

fun greater (b1: Block, b2: Block): Int {
    val b1s = dfs_all(b1)
    val b2s = dfs_all(b2)

    // counts the number of common authors in common blocks
    // counts = {A=2, B=5, C=1, ...}
    val counts = b1s.intersect(b2s).groupBy { it.author }.mapValues { it.value.size }

    // counts the sum of common authors that appear in uncommon blocks in each side
    val n_b1s = counts.filter { com -> (b1s-b2s).map { it.author }.contains(com.key) }.map { it.value }.sum()
    val n_b2s = counts.filter { com -> (b2s-b1s).map { it.author }.contains(com.key) }.map { it.value }.sum()

    return if (n_b1s == n_b2s) -b1.id.compareTo(b2.id) else (n_b1s - n_b2s)
}

fun seqs (bs: Set<Block>, excluding: Set<Block>): List<Block> {
    val l = bs.toMutableList()
    assert(l.size > 0)
    val ret = mutableListOf<Block>()
    var exc = excluding
    while (l.size > 0) {
        var cur = l.maxWithOrNull(::greater)!!
        if (!exc.contains(cur)) {
            ret += seqs(cur.backs, exc) + cur
        }
        exc += dfs_all(cur)
        l.remove(cur)
    }
    return ret
}

fun check (pioneer: String, list: List<Block>): Block? {
    val map = mutableMapOf(Pair(pioneer,30))
    for (b in list) {
        if (map[b.author]==null || map[b.author]!!<=0) {
            return b
        } else {
            map[b.author] = map[b.author]!! - 1
        }
    }
    return null
}

@TestMethodOrder(Alphanumeric::class)
class Consensus {

    @Test
    fun a01_bfs() {
        val gen = Block(emptySet(), "_", "gen", null)
        val a1  = Block(setOf(gen), "A", "a1", null)
        val a2  = Block(setOf(a1),  "A", "a2", null)
        val b1  = Block(setOf(a1),  "B", "b1", null)

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

        assert(greater(b1,a2) < 0)

        val bs = seqs(setOf(b1,a2), setOf(gen))
        val ret = bs.map { it.id }.joinToString(",")
        //println(ret)
        assert("a1,a2,b1" == ret)
    }

    @Test
    fun b01_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a1  = Block(setOf(gen),   "A", "a1", null)
        val a2  = Block(setOf(a1),    "A", "a2", null)
        val b2  = Block(setOf(a1),    "B", "b2", null)
        val ab3 = Block(setOf(a2,b2), "B", "ab3", null)

        // gen <- a1 <- a2 <- ab3
        //          \-- b2 /

        val x = seqs(setOf(ab3), setOf(gen)).map { it.id }.joinToString(",")
        assert(x == "a1,a2,b2,ab3")
    }

    @Test
    fun b02_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val a1  = Block(setOf(a0),    "A", "a1", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val a2  = Block(setOf(a1,b1), "A", "a2", null)
        val c1  = Block(setOf(a0),    "C", "c1", null)
        val a3  = Block(setOf(a2,c1), "A", "a3", null)

        //          /----- c1 -----\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seqs(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
        //println(x)
        assert(x == "a0,a1,b1,a2,c1,a3")
    }

    @Test
    fun b03_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val a1  = Block(setOf(a0),    "A", "a1", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val a2  = Block(setOf(a1,b1), "A", "a2", null)
        val c2  = Block(setOf(a1),    "C", "c2", null)
        val a3  = Block(setOf(a2,c2), "A", "a3", null)

        //                /-- c2 --\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seqs(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
        //println(x)
        assert(x == "a0,a1,b1,a2,c2,a3")
    }

    @Test
    fun b04_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val a1  = Block(setOf(a0),    "A", "a1", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val c1  = Block(setOf(a0),    "C", "c1", null)
        val a2  = Block(setOf(a1,b1), "A", "a2", null)
        val c2  = Block(setOf(a1,c1), "C", "c2", null)
        val a3  = Block(setOf(a2,c2), "A", "a3", null)

        //          /-- c1 </ c2 <\
        // gen <- a0 <- a1 <- a2 <- a3
        //          \-- b1 --/

        val x = seqs(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
        //println(x)
        assert(x == "a0,a1,b1,a2,c1,c2,a3")
    }

    @Test
    fun b05_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val a1  = Block(setOf(a0),    "A", "a1", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val c1  = Block(setOf(a0),    "C", "c1", null)
        val a2  = Block(setOf(a1,b1,c1), "A", "a2", null)

        //          /-- c1 --\
        // gen <- a0 <- a1 <- a2
        //          \-- b1 --/

        val x = seqs(setOf(a2), setOf(gen)).map { it.id }.joinToString(",")
        //println(x)
        assert(x == "a0,a1,b1,c1,a2")
    }

    @Test
    fun c01_likes() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val bs = seqs(setOf(a0), setOf(gen))
        assert(null == check("A", bs))
        assert(a0   == check("_", bs))
    }
}
