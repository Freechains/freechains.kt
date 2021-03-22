//import kotlinx.serialization.UnstableDefault
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.math.absoluteValue

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

fun dag_all (heads: Set<Block>): Set<Block> {
    return heads.map(::dfs_all).toSet().unionAll()
}

fun dag_heads (bs: Set<Block>): Set<Block> {
    return bs.filter { head -> (bs-head).none { dfs_all(it).contains(head) } }.toSet()
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

// receive set of heads, returns total order
fun seq_order (heads: Set<Block>, excluding: Set<Block>): List<Block> {
    val l = heads.toMutableList()
    assert(l.size > 0)
    val ret = mutableListOf<Block>()
    var exc = excluding
    while (l.size > 0) {
        var cur = l.maxWithOrNull(::greater)!!
        if (!exc.contains(cur)) {
            ret += seq_order(cur.backs, exc) + cur
        }
        exc += dfs_all(cur)
        l.remove(cur)
    }
    return ret
}

// find first invalid block in blockchain
fun seq_invalid (pioneer: String, list: List<Block>): Block? {
    val map = list.map { Pair(it.author,0) }.toMap().toMutableMap()
    map[pioneer] = 30
    for (i in 0..list.size-1) {
        val cur = list[i]
        val lk = (i+1 <= list.size-1) && list[i+1].let { nxt ->
            (nxt.like != null) && map[nxt.author]!!>0 && nxt.like.first==cur && nxt.like.second>0
        }
        when {
            (map[cur.author]!! <= 0) -> when {
                (cur.like != null)  -> return cur
                !lk                 -> return cur
                else                -> map[cur.author] = map[cur.author]!! - 1
            }
            (cur.like == null) -> map[cur.author] = map[cur.author]!! - 1
            else -> {
                val target = cur.like.first.author
                map[cur.author] = map[cur.author]!! - cur.like.second.absoluteValue
                map[target]     = map[target]!!     + cur.like.second
            }
        }
    }
    return null
}

// all blocks to remove (in DAG) that lead to the invalid block (in blockchain)
fun dag_remove (heads: Set<Block>, rem: Block): Set<Block> {
    return dag_all(heads).filter { dfs_all(it).contains(rem) }.toSet()
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

        val bs = seq_order(setOf(b1,a2), setOf(gen))
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

        val x = seq_order(setOf(ab3), setOf(gen)).map { it.id }.joinToString(",")
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

        val x = seq_order(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
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

        val x = seq_order(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
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

        val x = seq_order(setOf(a3), setOf(gen)).map { it.id }.joinToString(",")
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

        val x = seq_order(setOf(a2), setOf(gen)).map { it.id }.joinToString(",")
        //println(x)
        assert(x == "a0,a1,b1,c1,a2")

        val hs =  dag_heads(dag_all(setOf(a1,b1,c1)))
        assert(hs.size==3 && hs.contains(a1) && hs.contains(b1) && hs.contains(c1))
    }

    @Test
    fun c01_likes() {
        val gen = Block(emptySet(), "_", "gen", null)
        val a0  = Block(setOf(gen), "A", "a0",  null)

        // gen <- a0

        val bs = listOf(a0)
        assert(null == seq_invalid("A", bs))
        assert(a0   == seq_invalid("_", bs))
    }
    @Test
    fun c02_likes() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)

        // gen <- a0 <- b1

        val bs = listOf(a0,b1)
        assert(b1 == seq_invalid("A", bs))
    }

    @Test
    fun d01_likes_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val a2  = Block(setOf(b1),    "A", "a2", null)

        // gen <- a0 <- b1 <- a2

        val bs = seq_order(setOf(a2), setOf(gen))
        assert(b1 == seq_invalid("A", bs))
    }
    @Test
    fun d02_likes_seqs() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val a2  = Block(setOf(b1),    "A", "a2", Pair(b1,1))

        // gen <- a0 <- b1 <- a2

        val bs = seq_order(setOf(a2), setOf(gen))
        assert(null == seq_invalid("A", bs))
    }


    @Test
    fun e01_remove() {
        val gen = Block(emptySet(),   "_", "gen", null)
        val a0  = Block(setOf(gen),   "A", "a0", null)
        val b1  = Block(setOf(a0),    "B", "b1", null)
        val a2  = Block(setOf(b1),    "A", "a2", null)

        // gen <- a0 <- b1 <- a2

        val bs = seq_order(setOf(a2), setOf(gen))

        val inv = seq_invalid("A", bs)
        assert(inv == b1)

        val rem = dag_remove(setOf(a2), inv!!)
        assert(rem.size==2 && rem.contains(a2) && rem.contains(b1))

        val old_all = dag_all(setOf(a2))
        val new_all = old_all - rem
        val new_hs  = dag_heads(new_all)
        assert(new_hs.size==1 && new_hs.contains(a0))

        val new_bs  = seq_order(new_hs, setOf(gen))
        val new_inv = seq_invalid("A", new_bs)
        assert(new_inv == null)
    }
    @Test
    fun e02_remove() {
        val gen = Block(emptySet(), "_", "gen", null)
        val a0  = Block(setOf(gen), "A", "a0",  null)
        val b1  = Block(setOf(a0),  "B", "b1",  null)
        val a2  = Block(setOf(b1),  "A", "a2",  Pair(b1,2))
        val c3  = Block(setOf(a2),  "C", "c3",  null)
        val a4  = Block(setOf(c3),  "A", "a4",  Pair(c3,10))
        val cx  = Block(setOf(a4),  "C", "cx",  null)
        val b5  = Block(setOf(cx),  "B", "b5",  null)
        val b6  = Block(setOf(b5),  "B", "b6",  null)
        val a7  = Block(setOf(b6),  "A", "a7",  null)
        val c5  = Block(setOf(cx),  "C", "c5",  null)

        //                                          /- b5 <- b6 <- a7
        // gen <- a0 <- b1 <- +a2 <- c3 <- +a4 <- cx
        //                                          \- c5

        val bs = seq_order(setOf(a7,c5), setOf(gen))
        val x1 = bs.map { it.id }.joinToString(",")
        println(x1)
        assert(x1 == "a0,b1,a2,c3,a4,cx,b5,b6,a7,c5")

        val inv = seq_invalid("A", bs)
        assert(inv == b6)

        val rem = dag_remove(setOf(a7,c5), inv!!)
        assert(rem.size==2 && rem.contains(b6) && rem.contains(a7))

        val old_all = dag_all(setOf(a7,c5))
        val new_all = old_all - rem
        val new_hs  = dag_heads(new_all)
        assert(new_hs.size==2 && new_hs.contains(b5) && new_hs.contains(c5))

        val new_bs  = seq_order(new_hs, setOf(gen))
        val x2 = new_bs.map { it.id }.joinToString(",")
        println(x2)
        assert(x2 == "a0,b1,a2,c3,a4,cx,c5,b5")
        val new_inv = seq_invalid("A", new_bs)
        assert(new_inv == null)
    }
}
