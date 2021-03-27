import com.goterl.lazycode.lazysodium.LazySodium
import com.goterl.lazycode.lazysodium.LazySodiumJava
import com.goterl.lazycode.lazysodium.SodiumJava
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.KeyPair
import kotlinx.serialization.Serializable
//import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import org.freechains.cli.main_cli
import org.freechains.cli.main_cli_assert
import org.freechains.common.*
import org.freechains.host.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.Alphanumeric
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.time.Instant
import java.util.*
import kotlin.concurrent.thread

private const val PVT0 = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
private const val PUB0 = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
private const val PVT1 = "6A416117B8F7627A3910C34F8B35921B15CF1AC386E9BB20E4B94AF0EDBE24F4E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
private const val PUB1 = "E14E4D7E152272D740C3CA4298D19733768DF7E74551A9472AAE384E8AB34369"
private const val PVT2 = "320B59D3B1C969E20BD10D1349CEFECCD31B8FB84827369DCA644E780F004EA6146754D26A9B803138D47B62C92D9542343C22EB67BFFA9C429028985ED56C40"
private const val PUB2 = "146754D26A9B803138D47B62C92D9542343C22EB67BFFA9C429028985ED56C40"
private const val SHA0 = "64976DF4946F45D6EF37A35D06A1D9A1099768FBBC2B4F95484BA390811C63A2"

private const val PORT0 = PORT_8330 +0
private const val PORT1 = PORT_8330 +1
private const val PORT2 = PORT_8330 +2

private const val P0 = "--port=${PORT0}"
private const val P1 = "--port=${PORT1}"
private const val P2 = "--port=${PORT2}"

private const val H0 = "--host=localhost:$PORT0"
private const val H1 = "--host=localhost:$PORT1"
private const val H2 = "--host=localhost:$PORT2"
private const val S0 = "--sign=$PVT0"
private const val S1 = "--sign=$PVT1"

fun B (s: String): ByteArray {
    return s.toByteArray()
}

@TestMethodOrder(Alphanumeric::class)
class Tests {

    companion object {
        @BeforeAll
        @JvmStatic
        internal fun reset () {
            assert_(File("/tmp/freechains/tests/").deleteRecursively())
        }
    }

    @BeforeEach
    fun stop () {
        main_host(arrayOf("stop", P0))
        main_host(arrayOf("stop", P1))
        main_host(arrayOf("stop", P2))
    }

    /*
    @Test
    fun test () {
        thread { main_host(arrayOf("start", "/data/freechains/data/")) }
        Thread.sleep(100)
        main_cli_assert(arrayOf("chain", "\$sync.A2885F4570903EF5EBA941F3497B08EB9FA9A03B4284D9B27FF3E332BA7B6431", "get", "7_E5DF707ADBB9C4CB86B902C9CD2F5E85E062BFB8C3DC895FDAE9C2E796271DDE", "--decrypt=699299132C4C9AC3E7E78C5C62730AFDD68574D0DFA444D372FFBB51DF1BF1E0"))
    }

    @Test
    fun a0 () {
        // proof that data-class synchronized is "by ref" not "by value"
        // we need "by value" because same chain might be loaded by different threads/sockets
        data class XXX (val a: Int, val b: Int)
        val v1 = XXX(10,20)
        val v2 = XXX(10,20)
        val t1 = thread {
            synchronized(v1) {
                Thread.sleep(10000)
            }
        }
        Thread.sleep(100)
        println("antes")
        synchronized(v2) {
            println("dentro")
        }
        println("depois")
    }
     */

    @Test
    fun a1_json() {
        @Serializable
        data class MeuDado(val v: String)

        val bs: MutableList<Byte> = mutableListOf()
        for (i in 0..255) {
            bs.add(i.toByte())
        }
        val x = bs.toByteArray().toString(Charsets.ISO_8859_1)
        //println(x)
        val s = MeuDado(x)
        //println(s)
        //@OptIn(UnstableDefault::class)
        val json = Json { prettyPrint = true }
        val a = json.encodeToString(MeuDado.serializer(), s)
        val b = json.decodeFromString(MeuDado.serializer(), a)
        val c = b.v.toByteArray(Charsets.ISO_8859_1)
        assert_(bs.toByteArray().contentToString() == c.contentToString())
    }
    @Test
    fun a2_shared() {
        val str = "Alo 1234"
        val enc = str.toByteArray().encryptShared(SHA0)
        val dec = enc.decryptShared(SHA0).toString(Charsets.UTF_8)
        //println(str.length.toString() + " vs " + dec.length)
        assert(str == dec)
    }
    @Test
    fun a3_pubpvt() {
        val str = "Alo 1234"
        val enc = str.toByteArray().encryptPublic(PUB0)
        val dec = enc.decryptPrivate(PVT0).toString(Charsets.UTF_8)
        //println(str.length.toString() + " vs " + dec.length)
        assert(str == dec)
    }

    @Test
    fun b1_chain() {
        val h = Host_load("/tmp/freechains/tests/local/")
        val c1 = h.chainsJoin("#uerj", PUB0)

        val c2 = h.chainsLoad(c1.name)
        assert_(c1.hashCode().equals(c2.hashCode()))

        val hash = c2.blockNew(c2.consensus(), null, null, B(""), false, null)
        val blk2 = c2.fsLoadBlock(hash)
        assert_(c2.fsLoadBlock(hash).hashCode().equals(blk2.hashCode()))
    }

    @Test
    fun c1_post() {
        val loc = Host_load("/tmp/freechains/tests/local/")
        val chain = loc.chainsJoin("@$PUB0", null)
        val n1 = chain.blockNew(chain.consensus(), PVT0, null, B(""), false, null)
        val n2 = chain.blockNew(chain.consensus(), PVT0, null, B(""), false, null)
        val n3 = chain.blockNew(chain.consensus(), null, null, B(""), false, null)

        var ok = false
        try {
            val b3 = chain.fsLoadBlock(n3)
            val n = b3.copy(immut=b3.immut.copy(pay=b3.immut.pay.copy(hash="xxx")))
            chain.blockAssert(null,n)
        } catch (e: Throwable) {
            ok = true
        }
        assert_(ok)

        assert_(chain.fsExistsBlock(chain.genesis()))
        //println(n1.toHeightHash())
        assert_(chain.fsExistsBlock(n1))
        assert_(chain.fsExistsBlock(n2))
        assert_(chain.fsExistsBlock(n3))
        assert_(!chain.fsExistsBlock("2_........"))
    }
    @Test
    fun c02_blocked() {
        val loc = Host_load("/tmp/freechains/tests/C02/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        val n1 = chain.blockNew(chain.consensus(), PVT0, null,   B("1"), false, null)
        val n2 = chain.blockNew(chain.consensus(), PVT1, null, B("2.1"), false, null)
        val n3 = chain.blockNew(chain.consensus(), PVT1, null, B("2.2"), false, null)
        //println(chain.heads)
        val con = chain.consensus()
        assert(chain.heads(con,Head_State.LINKED).let { it.size==1 && it.contains(n1) })
        assert(chain.heads(con,Head_State.BLOCKED).let { it.size==2 && it.contains(n2) && it.contains(n3) })
    }
    @Test
    fun c03_all() {
        val loc = Host_load("/tmp/freechains/tests/C03/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val n1 = chain.blockNew(chain.consensus(), PVT0, null, B("1"), false, null)
        val n2 = chain.blockNew(chain.consensus(), PVT0, null, B("2"), false, null)
        val n3 = chain.blockNew(chain.consensus(), PVT0, null, B("3"), false, null)
        val con1 = chain.consensus()
        val all = chain.allFroms(chain.heads(con1,Head_State.LINKED))
        assert(all.size==4 && all.contains(n3))
        val rep1 = con1.repsAuthor(PUB0)
        assert(rep1 == 27)
        setNow(12*hour+100)
        val rep2 = chain.consensus().repsAuthor(PUB0)
        assert(rep2 == 30)
    }
    @Test
    fun c04_all() {
        val loc = Host_load("/tmp/freechains/tests/C04/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        assert(30 == chain.consensus().repsAuthor(PUB0))
        val n1 = chain.blockNew(chain.consensus(), PVT0, null, B("1"), false, null)
        assert(29 == chain.consensus().repsAuthor(PUB0))
        setNow(12*hour+100)
        assert(30 == chain.consensus().repsAuthor(PUB0))
        val n2 = chain.blockNew(chain.consensus(), PVT0, null, B("2"), false, null)
        assert(29 == chain.consensus().repsAuthor(PUB0))
        setNow(24*hour+200)
        assert(31 == chain.consensus().repsAuthor(PUB0))
        val n3 = chain.blockNew(chain.consensus(), PVT0, null, B("3"), false, null)
        assert(30 == chain.consensus().repsAuthor(PUB0))
    }
    @Test
    fun c05_seq() {
        val loc = Host_load("/tmp/freechains/tests/C05/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val a1 = chain.blockNew(chain.consensus(), PVT0, null, B("a1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, null, B("a2"), false, null)
        val b2 = chain.blockNew(chain.consensus(), PVT1, null, B("b2"), false, setOf(a1))

        // gen <- a1 <- a2
        //          \-- b2

        val con1 = chain.consensus()
        assert(chain.heads(con1,Head_State.LINKED).let  { it.size==1 && it.contains(a2) })
        assert(chain.heads(con1,Head_State.BLOCKED).let { it.size==1 && it.contains(b2) })
        assert(28 == con1.repsAuthor(PUB0))

        val a3 = chain.blockNew(chain.consensus(), PVT0, Like(1,b2), B("a3"), false, null)

        // gen <- a1 <- a2
        //          \-- b2 <- a3

        val con2 = chain.consensus()
        assert(chain.heads(con2,Head_State.LINKED).let  { it.size==2 && it.contains(a3) && it.contains(a2)})
        assert(chain.heads(con2,Head_State.BLOCKED).let { it.size==0 })
        assert(27 == con2.repsAuthor(PUB0))
        assert( 0 == con2.repsAuthor(PUB1))

        setNow(12*hour+100)
        val con3 = chain.consensus()
        assert(29 == con3.repsAuthor(PUB0))
        assert( 1 == con3.repsAuthor(PUB1))

        setNow(24*hour+200)
        val con4 = chain.consensus()
        assert(31 == con4.repsAuthor(PUB0))
        assert( 2 == con4.repsAuthor(PUB1))

        val str = con2.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        println(str)
        assert(str == ",a1,b2,a3,a2")
    }
    @Test
    fun c06_ord1() {
        val loc = Host_load("/tmp/freechains/tests/C06/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null, B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1), B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null, B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(1,c3), B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        val con1 = chain.consensus()
        assert(chain.heads(con1,Head_State.LINKED).let  { it.size==1 && it.contains(a4) })
        assert(chain.heads(con1,Head_State.BLOCKED).let { it.size==0 })
        assert(28 == con1.repsAuthor(PUB0))
        assert( 0 == con1.repsAuthor(PUB1))
        assert( 0 == con1.repsAuthor(PUB2))

        setNow(13*hour)
        val con2 = chain.consensus()
        assert(28 == con2.repsAuthor(PUB0))
        assert( 1 == con2.repsAuthor(PUB1))
        assert( 1 == con2.repsAuthor(PUB2))

        val a5 = chain.blockNew(chain.consensus(), PVT0, null, B("a5"), false, setOf(a4))
        val c5 = chain.blockNew(chain.consensus(), PVT2, null, B("c5"), false, setOf(a4))
        val b5 = chain.blockNew(chain.consensus(), PVT1, null, B("b5"), false, setOf(a4))
        val a6 = chain.blockNew(chain.consensus(), PVT0, null, B("a6"), false, setOf(a5,b5))
        val a7 = chain.blockNew(chain.consensus(), PVT0, null, B("a7"), false, setOf(a6,c5))
        val con3 = chain.consensus()
        assert(chain.heads(con3,Head_State.LINKED).size==1 && chain.heads(con3,Head_State.BLOCKED).size==0)
        assert(chain.heads(con3,Head_State.LINKED).contains(a7))
        assert(25 == con3.repsAuthor(PUB0))
        assert( 0 == con3.repsAuthor(PUB1))
        assert( 0 == con3.repsAuthor(PUB2))

        //                            /----- c5 -----\
        // gen <- b1 <- a2 <- c3 <- a4 <- a5 <- a6 <- a7
        //                            \-- b5 --/

        val str = con3.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        //println(str)
        assert(str == ",b1,a2,c3,a4,a5,b5,a6,c5,a7")
    }
    @Test
    fun c07_ord2() {
        val loc = Host_load("/tmp/freechains/tests/C07/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null,B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1),B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null, B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(1,c3),B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        setNow(13*hour)
        val a5 = chain.blockNew(chain.consensus(), PVT0, null, B("a5"), false, setOf(a4))
        val b5 = chain.blockNew(chain.consensus(), PVT1, null, B("b5"), false, setOf(a4))
        val a6 = chain.blockNew(chain.consensus(), PVT0, null, B("a6"), false, setOf(a5,b5))
        val c6 = chain.blockNew(chain.consensus(), PVT2, null, B("c6"), false, setOf(a5))
        val a7 = chain.blockNew(chain.consensus(), PVT0, null, B("a7"), false, setOf(a6,c6))

        //                                  /-- c6 --\
        // gen <- b1 <- a2 <- c3 <- a4 <- a5 <- a6 <- a7
        //                            \-- b5 --/

        val con = chain.consensus()
        val str = con.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        //println(str)
        assert(str == ",b1,a2,c3,a4,a5,b5,a6,c6,a7")
    }
    @Test
    fun c08_ord3() {
        val loc = Host_load("/tmp/freechains/tests/C08/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null, B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1),B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null, B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(2,c3),B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        setNow(13*hour)
        val a5 = chain.blockNew(chain.consensus(), PVT0, null,B("a5"), false, setOf(a4))
        val b5 = chain.blockNew(chain.consensus(), PVT1, null,B("b5"), false, setOf(a4))
        val c5 = chain.blockNew(chain.consensus(), PVT2, null,B("c5"), false, setOf(a4))
        val c6 = chain.blockNew(chain.consensus(), PVT2, null,B("c6"), false, setOf(a5,c5))
        val a6 = chain.blockNew(chain.consensus(), PVT0, null,B("a6"), false, setOf(a5,b5))
        val a7 = chain.blockNew(chain.consensus(), PVT0, null,B("a7"), false, setOf(a6,c6))

        //                            /-- c5 -/ c6 --\
        // gen <- b1 <- a2 <- c3 <- a4 <- a5 <- a6 <- a7
        //                            \-- b5 --/

        val con = chain.consensus()
        val str = con.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        //println(str)
        assert(str == ",b1,a2,c3,a4,a5,b5,a6,c5,c6,a7")
    }
    @Test
    fun c09_ord4() {
        val loc = Host_load("/tmp/freechains/tests/C09/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null,       B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1), B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null,       B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(2,c3), B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        setNow(13*hour)
        val a5 = chain.blockNew(chain.consensus(), PVT0, null, B("a5"), false, setOf(a4))
        val b5 = chain.blockNew(chain.consensus(), PVT1, null, B("b5"), false, setOf(a4))
        val c5 = chain.blockNew(chain.consensus(), PVT2, null, B("c5"), false, setOf(a4))
        val a6 = chain.blockNew(chain.consensus(), PVT0, null, B("a6"), false, setOf(a5,b5,c5))

        //                            /-- c5 --\
        // gen <- b1 <- a2 <- c3 <- a4 <- a5 <- a6
        //                            \-- b5 --/

        val con = chain.consensus()
        val str = con.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        //println(str)
        assert(str == ",b1,a2,c3,a4,a5,c5,b5,a6")
    }
    @Test
    fun c10_inv1() {
        val loc = Host_load("/tmp/freechains/tests/C10/")
        val chain = loc.chainsJoin("#xxx", PUB0)
        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null,        B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1), B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null,        B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(2,c3), B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        val con = chain.consensus()
        val str = con.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        //println(str)
        assert(str == ",b1,a2,c3,a4")
        assert(con.invs.isEmpty())
    }
    @Test
    fun c11_inv2() {
        val loc = Host_load("/tmp/freechains/tests/C11/")
        val chain = loc.chainsJoin("#xxx", PUB0)

        setNow(0)
        val b1 = chain.blockNew(chain.consensus(), PVT1, null,       B("b1"), false, null)
        val a2 = chain.blockNew(chain.consensus(), PVT0, Like(1,b1), B("a2"), false, null)
        val c3 = chain.blockNew(chain.consensus(), PVT2, null,       B("c3"), false, null)
        val a4 = chain.blockNew(chain.consensus(), PVT0, Like(2,c3), B("a4"), false, null)

        // gen <- b1 <- a2 <- c3 <- a4

        setNow(13*hour)

        val con1 = chain.consensus()
        assert(2 == con1.repsAuthor(PUB2))
        assert(1 == con1.repsAuthor(PUB1))

        setNow(25*hour)

        val con2 = chain.consensus()
        assert(3 == con2.repsAuthor(PUB2))
        assert(2 == con2.repsAuthor(PUB1))

        val c5 = chain.blockNew(chain.consensus(), PVT2, null, B("c5"), false, setOf(a4))
        val c6 = chain.blockNew(chain.consensus(), PVT2, null, B("c6"), false, setOf(c5))

        val con3 = chain.consensus()
        assert(1 == con3.repsAuthor(PUB2))

        val c7 = chain.blockNew(chain.consensus(), PVT2, null,        B("c7"), false, setOf(c6))
        val b5 = chain.blockNew(chain.consensus(), PVT1, null,        B("b5"), false, setOf(a4))
        val a6 = chain.blockNew(chain.consensus(), PVT0, Like(-2,c3), B("a6"), false, setOf(b5))

        //                            /-- c5 -- c6 -- c7
        // gen <- b1 <- a2 <- c3 <- a4
        //                            \-- b5 -- a6

        val con4 = chain.consensus()
        val str = con4.list.map { chain.fsLoadPayRaw(it).toString(Charsets.UTF_8) }.joinToString(",")
        println(str)
        assert(str == ",b1,a2,c3,a4,b5,a6,c5")
        //println(inv)
        assert(con4.invs.let { it.size==2 && it.contains(c6) && it.contains(c7) })
    }

    @Test
    fun d1_proto() {
        // SOURCE
        val src = Host_load("/tmp/freechains/tests/src/")
        val srcChain = src.chainsJoin("@$PUB1", null)
        srcChain.blockNew(srcChain.consensus(), PVT1, null, B(""), true, null)
        srcChain.blockNew(srcChain.consensus(), PVT1, null, B(""), true, null)
        thread { Daemon(src).daemon() }

        // DESTINY
        val dst = Host_load("/tmp/freechains/tests/dst/", PORT1)
        dst.chainsJoin("@$PUB1", null)
        thread { Daemon(dst).daemon() }
        Thread.sleep(200)

        main_cli_assert(arrayOf("peer", "localhost:$PORT1", "ping")).let {
            //println(">>> $it")
            assert_(it.toInt() < 50)
        }
        main_cli(arrayOf("peer", "localhost:11111", "ping")).let {
            assert_(!it.first && it.second == "! connection refused")
        }
        main_cli_assert(arrayOf("peer", "localhost:$PORT1", "chains")).let {
            assert_(it == "@$PUB1")
        }

        main_cli(arrayOf("peer", "localhost:$PORT1", "send", "@$PUB1"))
        Thread.sleep(200)

        main_cli(arrayOf(H1, "local", "stop"))
        main_cli(arrayOf("local", "stop"))
        Thread.sleep(200)

        // TODO: check if dst == src
        // $ diff -r /tmp/freechains/tests/dst/ /tmp/freechains/tests/src/
    }

    @Test
    fun f1_peers() {
        val h1 = Host_load("/tmp/freechains/tests/h1/", PORT0)
        val h1Chain = h1.chainsJoin("@$PUB1", null)
        h1Chain.blockNew(h1Chain.consensus(), PVT1, null,  B(""), false, null)
        h1Chain.blockNew(h1Chain.consensus(), PVT1, null,  B(""), false, null)

        val h2 = Host_load("/tmp/freechains/tests/h2/", PORT1)
        val h2Chain = h2.chainsJoin("@$PUB1", null)
        h2Chain.blockNew(h2Chain.consensus(), PVT1, null,  B(""), false, null)
        h2Chain.blockNew(h2Chain.consensus(), PVT1, null,  B(""), false, null)

        Thread.sleep(200)
        thread { Daemon(h1).daemon() }
        thread { Daemon(h2).daemon() }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "peer", "localhost:$PORT1", "recv", "@$PUB1"))
        Thread.sleep(200)
        main_cli(arrayOf(H1, "local", "stop"))
        main_cli(arrayOf("local", "stop"))
        Thread.sleep(200)

        // TODO: check if 8332 (h2) < 8331 (h1)
        // $ diff -r /tmp/freechains/tests/h1 /tmp/freechains/tests/h2/
    }

    @Test
    fun m00_chains() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/M0/"))
        }
        Thread.sleep(200)
        main_host(arrayOf("path")).let {
            assert_(it.first && it.second == "//tmp/freechains/tests/M0//")
        }
        main_cli_assert(arrayOf("chains", "list")).let {
            assert_(it == "")
        }
        main_cli_assert(arrayOf("chains", "leave", "#xxx")).let {
            assert_(it == "false")
        }
        main_cli_assert(arrayOf("chains", "join", "#xxx", PUB0)).let {
            assert_(it.isNotEmpty())
        }
        main_cli_assert(arrayOf("chains", "join", "#yyy", PUB1)).let {
            assert_(it.isNotEmpty())
        }
        main_cli_assert(arrayOf("chains", "list")).let {
            assert_(it == "#yyy #xxx")
        }
        main_cli_assert(arrayOf("chains", "leave", "#xxx")).let {
            assert_(it == "true")
        }
    }

    @Test
    fun m01_args() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/M1/"))
        }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "#xxx", PUB0))

        main_cli(arrayOf("xxx", "yyy")).let {
            assert_(!it.first)
        }
        main_cli(arrayOf()).let {
            assert_(!it.first && it.second.contains("Usage:"))
        }
        main_cli(arrayOf("--help")).let {
            assert_(it.first && it.second.contains("Usage:"))
        }

        assert_(main_cli_assert(arrayOf("chain", "#xxx", "genesis")).startsWith("0_"))
        assert_(main_cli_assert(arrayOf("chain", "#xxx", "heads")).startsWith("0_"))

        main_cli(arrayOf("chain", "#xxx", "post", "inline", "aaa", S0))
        assert_(main_cli_assert(arrayOf("chain", "#xxx", "heads")).startsWith("1_"))
        main_cli_assert(arrayOf("chain", "#xxx", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf("chain", "#xxx", "heads")).let { list ->
            list.split(' ').toTypedArray().let {
                assert_(it.size == 1)
                assert_(it[0].startsWith("1_"))
            }
        }

        main_cli(arrayOf("chain", "#xxx", "get", "block", "0_B5E21297B8EBEE0CFA0FA5AD30F21B8AE9AE9BBF25F2729989FE5A092B86B129", "inline")).let {
            println(it)
            assert_(!it.first && it.second.equals("! block not found"))
        }

        /*val h2 =*/ main_cli(arrayOf(S0, "chain", "#xxx", "post", "file", "/tmp/freechains/tests/M1/chains/#xxx/chain"))

        //org.freechains.core.cli(arrayOf(S0, "chain", "/xxx", "post", "file", "/tmp/20200504192434-0.eml"))

        // h0 -> h1 -> h2

        assert_(main_cli_assert(arrayOf("chain", "#xxx", "heads")).startsWith("2_"))
        assert_(main_cli_assert(arrayOf("chain", "#xxx", "heads", "blocked")).isEmpty())

        //org.freechains.core.cli(arrayOf("chain", "#xxx", "post", "file", "base64", "/bin/cat"))
        main_host(arrayOf("stop"))
        // TODO: check genesis 2x, "aaa", "host"
        // $ cat /tmp/freechains/tests/M1/chains/xxx/blocks/*
    }

    @Test
    fun m01_blocked() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/M1B/"))
        }
        Thread.sleep(200)
        main_cli_assert(arrayOf("chains", "join", "#xxx", PUB0))
        main_cli_assert(arrayOf("chain", "#xxx", "post", "inline", "1.1", "--sign=$PVT1"))
        main_cli_assert(arrayOf("chain", "#xxx", "post", "inline", "1.2", "--sign=$PVT1"))
        val x = main_cli_assert(arrayOf("chain", "#xxx", "heads"))
        assert_(!x.contains(" "))
    }

    @Test
    fun m01_trav() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/trav/"))
        }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "#", PUB0))
        val gen = main_cli_assert(arrayOf("chain", "#", "genesis"))
        main_cli(arrayOf("chain", "#", "post", "inline", "aaa", S0))
        main_cli_assert(arrayOf("chain", "#", "traverse", gen)).let {
            it.split(" ").let {
                assert_(it.size == 1 && it[0].startsWith("1_"))
            }
        }
    }

    @Test
    fun m01_listen() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/listen/"))
        }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "#", PUB0))

        var ok = 0
        val t1 = thread {
            val socket = Socket("localhost", PORT0)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            writer.writeLineX("$PRE chain # listen")
            val n = reader.readLineX().toInt()
            assert_(n == 1) { "error 1" }
            ok++
        }

        val t2 = thread {
            val socket = Socket("localhost", PORT0)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            writer.writeLineX("$PRE chains listen")
            val x = reader.readLineX()
            assert_(x == "1 #") { "error 2" }
            ok++
        }

        Thread.sleep(200)
        main_cli(arrayOf("chain", "#", "post", "inline", "aaa", S0))
        t1.join()
        t2.join()
        assert_(ok == 2)
    }

    @Test
    fun m02_crypto() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(200)
        val lazySodium = LazySodiumJava(SodiumJava())
        val kp: KeyPair = lazySodium.cryptoSignKeypair()
        val pk: Key = kp.publicKey
        val sk: Key = kp.secretKey
        assert_(lazySodium.cryptoSignKeypair(pk.asBytes, sk.asBytes))
        //println("TSTTST: ${pk.asHexString} // ${sk.asHexString}")
        main_cli(arrayOf("crypto", "shared", "senha secreta"))
        main_cli(arrayOf("crypto", "pubpvt", "senha secreta"))

        val msg = "mensagem secreta"
        val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
        val key = Key.fromHexString("B07CFFF4BE58567FD558A90CD3875A79E0876F78BB7A94B78210116A526D47A5")
        val encrypted = lazySodium.cryptoSecretBoxEasy(msg, nonce, key)
        //println("nonce=${lazySodium.toHexStr(nonce)} // msg=$encrypted")
        val decrypted = lazySodium.cryptoSecretBoxOpenEasy(encrypted, nonce, key)
        assert_(msg == decrypted)
    }
    @Test
    fun m02_crypto_passphrase() {
        thread {
            main_host(arrayOf("start", "/tmp/freechains/tests/M2/"))
        }
        Thread.sleep(200)

        val s0 = main_cli_assert(arrayOf("crypto", "shared", "senha"))
        val s1 = main_cli_assert(arrayOf("crypto", "shared", "senha secreta"))
        val s2 = main_cli_assert(arrayOf("crypto", "shared", "senha super secreta"))
        assert_(s0 != s1 && s1 != s2)

        val k0 = main_cli_assert(arrayOf("crypto", "pubpvt", "senha"))
        val k1 = main_cli_assert(arrayOf("crypto", "pubpvt", "senha secreta"))
        val k2 = main_cli_assert(arrayOf("crypto", "pubpvt", "senha super secreta"))
        assert_(k0 != k1 && k1 != k2)
    }
    @Test
    fun m02_crypto_pubpvt() {
        val ls = LazySodiumJava(SodiumJava())
        val bobKp = ls.cryptoBoxKeypair()
        val message = "A super secret message".toByteArray()
        val cipherText =
            ByteArray(message.size + Box.SEALBYTES)
        ls.cryptoBoxSeal(cipherText, message, message.size.toLong(), bobKp.publicKey.asBytes)
        val decrypted = ByteArray(message.size)
        val res = ls.cryptoBoxSealOpen(
            decrypted,
            cipherText,
            cipherText.size.toLong(),
            bobKp.publicKey.asBytes,
            bobKp.secretKey.asBytes
        )

        if (!res) {
            println("Error trying to decrypt. Maybe the message was intended for another recipient?")
        }

        println(String(decrypted)) // Should print out "A super secret message"


        val lazySodium = LazySodiumJava(SodiumJava())
        //val kp = ls.cryptoBoxKeypair()

        val pubed = Key.fromHexString("4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB").asBytes
        val pvted =
            Key.fromHexString("70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB")
                .asBytes
        val pubcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
        val pvtcu = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)

        assert_(lazySodium.convertPublicKeyEd25519ToCurve25519(pubcu, pubed))
        assert_(lazySodium.convertSecretKeyEd25519ToCurve25519(pvtcu, pvted))

        val dec1 = "mensagem secreta".toByteArray()
        val enc1 = ByteArray(Box.SEALBYTES + dec1.size)
        lazySodium.cryptoBoxSeal(enc1, dec1, dec1.size.toLong(), pubcu)
        //println(LazySodium.toHex(enc1))

        val enc2 = LazySodium.toBin(LazySodium.toHex(enc1))
        //println(LazySodium.toHex(enc2))
        assert_(Arrays.equals(enc1, enc2))
        val dec2 = ByteArray(enc2.size - Box.SEALBYTES)
        lazySodium.cryptoBoxSealOpen(dec2, enc2, enc2.size.toLong(), pubcu, pvtcu)
        assert_(dec2.toString(Charsets.UTF_8) == "mensagem secreta")
    }

    @Test
    fun m03_crypto_post() {
        val loc = Host_load("/tmp/freechains/tests/M2/")
        val c1 = loc.chainsJoin("\$sym", "password".toShared())
        c1.blockNew(c1.consensus(), null, null, B(""), true, null)
        val c2 = loc.chainsJoin("@$PUB0", null)
        c2.blockNew(c2.consensus(), PVT0, null, B(""), true, null)
    }
    @Test
    fun m04_crypto_encrypt() {
        val loc = Host_load("/tmp/freechains/tests/M2/")
        val c1 = loc.chainsLoad("\$sym")
        val n1 = c1.blockNew(c1.consensus(),null, null, B("aaa"), true, null)
        val n2 = c1.fsLoadPayCrypt(n1, null)
        assert_(n2.toString(Charsets.UTF_8) == "aaa")
        val n3 = c1.fsLoadPayRaw(n1)
        assert_(n3.toString(Charsets.UTF_8) != "aaa")
    }

    @Test
    fun m05_crypto_encrypt_sym() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M50/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M51/", P1)) }
        Thread.sleep(200)
        main_cli(
                arrayOf(
                        "chains",
                        "join",
                        "#xxx"
                )
        )
        main_cli(
                arrayOf(
                        H1,
                        "chains",
                        "join",
                        "#xxx"
                )
        )

        main_cli(arrayOf("chain", "#xxx", "post", "inline", "aaa", "--encrypt"))
        main_cli(arrayOf("peer", "localhost:$PORT1", "send", "#xxx"))
    }
    @Test
    fun m06_crypto_encrypt_asy() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M60/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M61/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "@$PUB0"))
        main_cli(arrayOf(H1, "chains", "join", "@$PUB0"))
        val hash = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "aaa", S0, "--encrypt"))

        val pay = main_cli_assert(arrayOf("chain", "@$PUB0", "get", "payload", hash, "inline", "--decrypt=$PVT0"))
        assert_(pay == "aaa")

        main_cli(arrayOf("peer", "localhost:$PORT1", "send", "@$PUB0"))
        val json2 = main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "get", "block", hash, "inline"))
        val blk2 = json2.jsonToBlock_()
        assert_(blk2.pay.crypt)

        val h2 = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "bbbb", S1))
        val pay2 = main_cli_assert(arrayOf("chain", "@$PUB0", "get", "payload", h2, "inline"))
        assert_(pay2 == "bbbb")
    }
    @Test
    fun m06x_crypto_encrypt_asy() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M60x/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M61x/", P1)) }
        Thread.sleep(200)
        main_cli_assert(arrayOf("chains", "join", "@!$PUB0"))
        main_cli_assert(arrayOf(H1, "chains", "join", "@!$PUB0"))
        val hash = main_cli_assert(arrayOf("chain", "@!$PUB0", "post", "inline", "aaa", S0, "--encrypt"))

        val pay = main_cli_assert(arrayOf("chain", "@!$PUB0", "get", "payload", hash, "inline", "--decrypt=$PVT0"))
        assert_(pay == "aaa")

        main_cli(arrayOf("peer", "localhost:$PORT1", "send", "@!$PUB0"))
        val json2 = main_cli_assert(arrayOf(H1, "chain", "@!$PUB0", "get", "block", hash, "inline"))
        val blk2 = json2.jsonToBlock_()
        assert_(blk2.pay.crypt)

        main_cli(arrayOf("chain", "@!$PUB0", "post", "inline", "bbbb", S1)).let {
            assert_(!it.first && it.second.equals("! must be from owner"))
        }
    }
    @Test
    fun m06y_shared() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M60y/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M61y/", P1)) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M62y/", P2)) }
        Thread.sleep(200)

        main_cli(arrayOf("chains", "join", "\$xxx")).let {
            assert_(!it.first && it.second.equals("! expected shared key"))
        }

        val key1 = "password".toShared()
        val key2 = "xxxxxxxx".toShared()

        main_cli_assert(arrayOf(H0, "chains", "join", "\$xxx", key1))
        main_cli_assert(arrayOf(H1, "chains", "join", "\$xxx", key1))
        main_cli_assert(arrayOf(H2, "chains", "join", "\$xxx", key2))

        val hash = main_cli_assert(arrayOf("chain", "\$xxx", "post", "inline", "aaa"))

        File("/tmp/freechains/tests/M60y/chains/\$xxx/blocks/$hash.pay").readText().let {
            assert_(!it.equals("aaa"))
        }
        main_cli_assert(arrayOf("chain", "\$xxx", "get", "payload", hash, "inline")).let {
            assert_(it == "aaa")
        }

        main_cli_assert(arrayOf("peer", "localhost:$PORT1", "send", "\$xxx")).let {
            main_cli_assert(arrayOf(H1, "chain", "\$xxx", "get", "payload", hash, "inline")).let {
                assert_(it == "aaa")
            }
        }
        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT2", "send", "\$xxx")).let {
            assert_(it.equals("0 / 2"))
        }
    }

    @Test
    fun m07_genesis_fork() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M70/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M71/", P1)) }
        Thread.sleep(200)

        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))
        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "first-0", S0))
        main_cli(arrayOf(H1, "chains", "join", "#", PUB1))
        main_cli(arrayOf(H1, "chain", "#", "post", "inline", "first-1", S1))
        Thread.sleep(200)

        // H0: g <- f0
        // H1: g <- f1

        val r0 = main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "recv", "#"))
        val r1 = main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "#"))
        assert_(r0 == r1 && r0 == "0 / 2")

        val r00 = main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB0))
        val r11 = main_cli_assert(arrayOf(H1, "chain", "#", "reps", PUB1))
        val r10 = main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1))
        val r01 = main_cli_assert(arrayOf(H1, "chain", "#", "reps", PUB0))
        assert_(r00.toInt() == 29)
        assert_(r11.toInt() == 29)
        assert_(r10.toInt() == 0)
        assert_(r01.toInt() == 0)

        main_host(arrayOf(H0, "now", (getNow() + 1 * day).toString()))
        main_host(arrayOf(H1, "now", (getNow() + 1 * day).toString()))

        val x0 = main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB0))
        val x1 = main_cli_assert(arrayOf(H1, "chain", "#", "reps", PUB1))
        assert_(x0.toInt() == 31)
        assert_(x1.toInt() == 31)
    }

    @Test
    fun m08_likes() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M90/")) }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "#chat", PUB0))
        main_cli_assert(arrayOf("chain", "#chat", "post", "inline", "h1", S0))
        val h2 = main_cli_assert(arrayOf("chain", "#chat", "post", "inline", "h2", S1))
        main_cli_assert(arrayOf("chain", "#chat", "like", h2, S0))
        main_cli_assert(arrayOf("chain", "#chat", "like", h2, S0))
        assert_("1" == main_cli_assert(arrayOf("chain", "#chat", "reps", PUB1)))
        val h5 = main_cli_assert(arrayOf("chain", "#chat", "post", "inline", "h5", S1))
        main_cli_assert(arrayOf("chain", "#chat", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
            }
            assert_(str == h5)
        }
    }
    @Test
    fun m09_likes() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M90/")) }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "@$PUB0"))

        main_host(arrayOf(H0, "now", "0"))

        val h11 = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "h11", S0))
        val h22 = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "h22", S1))
        val h21 = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "h21", S0))

        // h0 -> h11 -> h21
        //          \-> h22

        main_cli_assert(arrayOf("chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
            }
            assert_(str.startsWith("2_"))
        }

        val l31 = main_cli_assert(arrayOf("chain", "@$PUB0", "like", h22, S0, "--why=l3")) // l3

        // h0 -> h11 -> h21
        //          \-> h22 -> l3

        main_cli_assert(arrayOf("chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 2)
                assert_(it.contains(l31) && it.contains(h21))
            }
        }
        assert_("0" == main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB1)))

        main_host(arrayOf(H0, "now", (3 * hour).toString()))
        val h41 = main_cli_assert(arrayOf("chain", "@$PUB0", "post", "inline", "41", S0))

        // h0 -> h11 -> h21 ---------\
        //          \-> h22 -- l31 -- h41

        main_host(arrayOf(H0, "now", (1 * day + 4 * hour).toString()))

        assert_(main_cli_assert(arrayOf("chain", "@$PUB0", "heads")).startsWith("4_"))
        assert_("2" == main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB0)))
        assert_("2" == main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB1)))

        // like myself
        main_cli_assert(arrayOf("chain", "@$PUB0", "like", h11, S0)).let {
            assert_(it == "like must not target itself")
        }

        val l51 = main_cli_assert(arrayOf("chain", "@$PUB0", "like", h22, S0, "--why=l5"))

        // h0 -> h11 -> h21 ---------\
        //          \-> h22 -- l31 -- h41 -- l51

        main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB0)).let {
            assert_(it == "1")
        }
        main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB1)).let {
            assert_(it == "3")
        }

        main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
            }
            assert_(str.contains("5_"))
        }

        // height is ~also 5~ 6
        val l62 = main_cli_assert(arrayOf("chain", "@$PUB0", "dislike", l51, "--why=l6", S1))

        // h0 -> h11 -> h21 ---------\
        //          \-> h22 -- l31 -- h41 -- l51 -- l62

        main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB1)).let {
            assert_(it == "2")
        }
        main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB0)).let {
            assert_(it == "0")
        }

        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M81/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf(H1, "chains", "join", "@$PUB0"))

        // I'm in the future, old posts will be refused
        main_host(arrayOf(H1, "now", Instant.now().toEpochMilli().toString()))

        val n1 = main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "send", "@$PUB0"))
        assert_(n1 == "0 / 7")

        main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "heads")).split(' ').let {
            assert_(it.size == 1)
            it.forEach { assert_(it.contains("6_")) }
        }

        // only very old
        // h0 -> h11 -> h21 ------xx\
        //          \-> h22 -- l3 xx h41 -- l51 -- l62

        main_host(arrayOf(H1, "now", "0"))
        val n2 = main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "recv", "@$PUB0"))
        assert_(n2 == "4 / 7") { n2 }
        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads")).split(' ').let {
            assert_(it.size==2 && it.contains(h21) && it.contains(l31))
        }

        // h0 -- h11 -- h21 ------xx\
        //          \-- h22 -- l3 xx h41 -- l51 -- l62

        // still the same
        main_host(arrayOf(H1, "now", "${2 * hour}"))
        main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "send", "@$PUB0")).let {
            assert_(it == "0 / 3")
        }

        // now ok
        main_host(arrayOf(H1, "now", "${1 * day + 4 * hour + 100}"))
        main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "send", "@$PUB0")).let {
            assert_(it == "3 / 3")
        }
        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads")).let {
            assert_(it.startsWith("6_"))
        }

        // h0 -- h11 -- h21 --------\
        //          \-- h22 -- l3 -- h41 -- l51 -- l62

        /*** TODO : did not check after this point ***/

        assert_("2" == main_cli_assert(arrayOf("chain", "@$PUB0", "reps", PUB1)))
        val h7 = main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "no rep", S1))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5
        //          \               /         \
        //           \- h22 <-------           l6 <- h7

        main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "recv", "@$PUB0")).let {
            assert_(it == "1 / 1")
        }
        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads")).let {
            assert_(it.startsWith("7_"))
        }

        main_cli(arrayOf(H1, "chain", "@$PUB0", "like", h7, S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5          l7
        //          \               /         \        /
        //           \- h22 <-------           l6 <- h7

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
                it.forEach {
                    assert_(it.startsWith("8_"))
                }
            }
        }

        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "@$PUB0")).let {
            assert_(it == "1 / 1")
        }

        // flush after 2h
        main_host(arrayOf(H0, "now", "${1 * day + 7 * hour}"))
        main_host(arrayOf(H1, "now", "${1 * day + 7 * hour}"))

        main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
                it.forEach {
                    assert_(it.startsWith("8_"))
                }
            }
        }
        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
                it.forEach {
                    assert_(it.startsWith("8_"))
                }
            }
        }

        // new post, no rep
        val h8 = main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "no sig"))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5          l7 <- h8
        //          \               /         \        /
        //           \- h22 <-------           l6 <- h7

        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "@$PUB0")).let {
            assert_(it.equals("1 / 1"))
        }

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "heads", "blocked")).let {
            assert_(it.startsWith("9_"))
        }

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "get", "payload", h8, "inline"))
            .let {
                assert_(it == "no sig")
            }

        // like post w/o pub
        main_cli(arrayOf(H1, "chain", "@$PUB0", "like", h8, S0))

        // h0 <- h11 <- h21 <- l3 <- h41 <- l5       l7       l9
        //          \               /         \    /         /
        //           \- h22 <-------           l6 <- h7 <- h8

        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "@$PUB0")).let {
            assert_(it == "1 / 1")
        }
        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "reps", PUB0)).let {
            assert_(it == "-2")
        }

        main_host(arrayOf(H1, "now", "${1 * day + 10 * hour}"))

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "reps", PUB0)).let {
            assert_(it == "-2")
        }
        main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", PUB0)).let {
            assert_(it == "-2")
        }

        val ln = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", h7))
        val x1 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", h11))
        val x2 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", h22))
        val x3 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", l51))
        val x4 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "reps", h8))
        println("$ln // $x1 // $x2 // $x3 // $x4")
        assert_(ln == "1" && x1 == "0" && x2 == "2" && x3 == "-1" && x4 == "1")
    }
    @Test
    fun m10_cons() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M100/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M101/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "@$PUB0"))
        main_cli(arrayOf(H1, "chains", "join", "@$PUB0"))
        main_host(arrayOf(H0, "now", "0"))
        main_host(arrayOf(H1, "now", "0"))

        val h1 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "h1", S0))
        val h2 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "h2", S0))
        val hx = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "post", "inline", "hx", S1))

        // h1 <- h2 (a) <- hx (r)

        val ps1 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "heads"))
        val rs1 = main_cli_assert(arrayOf(H0, "chain", "@$PUB0", "heads", "blocked"))
        assert_(!ps1.contains(h1) && ps1.contains(h2) && !ps1.contains(hx))
        assert_(!rs1.contains(h1) && !rs1.contains(h2) && rs1.contains(hx))

        main_cli(arrayOf(H1, "peer", "localhost:$PORT0", "recv", "@$PUB0"))

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h3", S0)).let {
            //assert_(it == "backs must be accepted")
        }

        // h1 <- h2 (a) <- hx (r)
        //             \<- h3

        main_host(arrayOf(H1, "now", "${3 * hour}"))

        val h4 = main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h4", S0))
        assert_(h4.startsWith("4_"))

        // h1 <- h2 (a) <- h3 <- h4
        //              \-- hx (r)

        main_cli(arrayOf(H0, "peer", "localhost:$PORT1", "send", "@$PUB0"))
        main_host(arrayOf(H1, "now", "${6 * hour}"))

        main_cli_assert(arrayOf(H1, "chain", "@$PUB0", "post", "inline", "h5")).let {
            assert_(it.startsWith("5_"))
        }
    }

    @Test
    fun m11_send_after_tine() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M100/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M101/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))
        main_cli(arrayOf(H1, "chains", "join", "#", PUB0))

        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h1", S0))
        val h2 = main_cli_assert(arrayOf(H0, "chain", "#", "post", "inline", "h2"))
        main_cli(arrayOf(H0, "chain", "#", "like", h2, S0))
        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h3"))

        main_cli(arrayOf(H0, "peer", "localhost:$PORT1", "send", "#"))

        // this all to test an internal assertion
    }
    @Test
    fun m12_state () {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M120/")) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))
        main_host(arrayOf(H0, "now", "0"))

        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M121/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf(H1, "chains", "join", "#", PUB0))
        main_host(arrayOf(H1, "now", "0"))

        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h1", S0))
        val h21 = main_cli_assert(arrayOf(H0, "chain", "#", "post", "inline", "h21"))
        val h22 = main_cli_assert(arrayOf(H0, "chain", "#", "post", "inline", "h22"))

        // h0 -- h1 -- h21
        //          -- h22

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let {
            assert_(it.startsWith("1_")) { it }
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.startsWith("2_")) { it }
        }

        main_cli(arrayOf(H0, "chain", "#", "like", h21, S0))

        // h0 -- h1 -- h21 -- l2
        //          -- h22

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
                it.forEach {
                    assert_(it.startsWith("3_"))
                }
            }
        }

        main_cli(arrayOf(H0, "chain", "#", "like", h22, S0))

        // h0 -- h1 -- h21 -- l2
        //          -- h22 -- l3

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let {
            it.split(' ').let {
                assert_(it.size == 2)
            }
        }
        assert_(main_cli_assert(arrayOf("chain", "#", "heads", "blocked")).isEmpty())

        main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "send", "#")).let {
            assert_(it.contains("5 / 5"))
        }

////////
        // all accepted
        main_host(arrayOf(H1, "now", "${3 * hour}"))

        // h0 -- h1 -- h21 -- l2
        //          -- h22 -- l3

        assert_(main_cli_assert(arrayOf(H1, "chain", "#", "heads", "blocked")).isEmpty())
        main_cli_assert(arrayOf(H1, "chain", "#", "heads")).let { str ->
            assert_(str.startsWith("3_"))
            str.split(' ').let {
                assert_(it.size == 2)
            }
        }

        // l4 dislikes h22
        val l4 = main_cli_assert(arrayOf(H0, "chain", "#", "dislike", h22, S0))

        // h0 -- h1 -- h21 -- l2 -- l4
        //         \-- h22 -- l3 --/

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1)
            }
            assert_(str.contains("4_"))
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        val h5x = main_cli_assert(arrayOf(H0, "chain", "#", "post", "inline", "h43"))

        // h0 -- h1 -- h21 -- l2 -- l4 -- h5x
        //         \-- h22 -- l3 --/

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it == h5x)
        }

////////

        main_host(arrayOf(H1, "now", "${1 * hour}"))
        val lx = main_cli_assert(arrayOf(H1, "chain", "#", "dislike", h22, S0))     // one is not enough
        val ly = main_cli_assert(arrayOf(H1, "chain", "#", "dislike", h22, S0))     // one is not enough
        main_host(arrayOf(H1, "now", "${4 * hour}"))
        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "#"))  // errors when merging

        // h0 -- h1 -- h21 -- l2 -- l4 -- h5x
        //         \-- h22 -- l3 --/  \-- lx -- ly

        // l4 dislikes h22 (reject it)
        // TODO: check if h22 contents are empty

        main_cli_assert(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf(H1, "chain", "#", "heads")).let {
            assert_(it.startsWith("5_"))
        }
    }
    @Test
    fun m13_reps () {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M13/")) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))
        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h1", S0))
        main_cli_assert(arrayOf(H0, "chain", "#", "post", "inline", "h2", S1)).let {
            main_cli(arrayOf(H0, S0, "chain", "#", "like", it))
        }

        // h0 <-- h1 <-- l2
        //            \- h2

        main_host(arrayOf(H0, "now", "${3 * hour}"))
        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "0")
        }

        main_host(arrayOf(H0, "now", "${25 * hour}"))
        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "2")
        }

        main_cli(arrayOf(H0, S1, "chain", "#", "post", "inline", "h3"))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3

        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "1")
        }

        main_host(arrayOf(H0, "now", "${50 * hour}"))
        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "3")
        }

        main_host(arrayOf(H0, "now", "${53 * hour}"))
        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h4", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4

        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "2")
        }

        main_host(arrayOf(H0, "now", "${78 * hour}"))
        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "4") { it }
        }

        main_cli(arrayOf(H0, "chain", "#", "post", "inline", "h5", S1))

        // h0 <-- h1 <-- l2
        //            \- h2 <-- h3 <-- h4 <-- h5

        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "3")
        }

        main_host(arrayOf(H0, "now", "${1000 * hour}"))
        main_cli_assert(arrayOf(H0, "chain", "#", "reps", PUB1)).let {
            assert_(it == "5")
        }
    }

    @Test
    fun m14_remove() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M140/")) }
        Thread.sleep(200)
        main_cli(arrayOf("chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))

        val h1  = main_cli_assert(arrayOf(H0, S0, "chain", "#", "post", "inline", "h0"))
        val h21 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "h21"))
        val h20 = main_cli_assert(arrayOf(H0, S0, "chain", "#", "post", "inline", "h20"))

        // h0 -- h1 -- h21
        //         \-- h20

        main_cli_assert(arrayOf(H0, S0, "chain", "#", "post", "inline", "h30"))

        // h0 -- h1 -- h21
        //         \-- h20 -- h30

        main_host(arrayOf(H0, "now", "${3 * hour}"))
        main_cli(arrayOf(H0, S0, "chain", "#", "post", "inline", "h40"))

        // h0 -- h1 -- h21
        //         \-- h20 -- h30 -- h40

        main_host(arrayOf(H0, "now", "${6 * hour}"))
        val l21 = main_cli_assert(arrayOf(H0, S0, "chain", "#", "like", h21, "--why=l21"))

        // h0 -- h1 -- h21 -- l21
        //         \-- h20 -- h30 -- h40

        main_host(arrayOf(H0, "now", "${9 * hour}"))

        val h51 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "h51"))

        // h0 -- h1 -- h21 -- l21 --------- h51
        //         \-- h20 -- h30 -- h40 --/

        val l60 = main_cli_assert(arrayOf(H0, S0, "chain", "#", "like", h51, "--why=l60"))

        // h0 -- h1 -- h21 -- l21 --------- h51 -- l60
        //         \-- h20 -- h30 -- h40 --/

        main_host(arrayOf(H0, "now", "${34 * hour}"))
        val h7 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "h7"))

        // h0 -- h1 -- h21 -- l21 --------- h51 -- l60 -- h7
        //         \-- h20 -- h30 -- h40 --/

        // removes h21 (wont remove anything)
        val lx = main_cli_assert(arrayOf(H0, S0, "chain", "#", "dislike", h21, "--why=dislike"))

        // h0 -- h1 -- h21 -- l21 --------- h51 -- l60 -- h7 -- lx
        //         \-- h20 -- h30 -- h40 --/

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let {
            assert_(!it.contains("2_"))
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        main_host(arrayOf(H0, "now", "${40 * hour}"))

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1) { it.size }
                it.forEach {
                    assert_(it.startsWith("8_"))
                }
            }
        }
    }
    @Test
    fun m15_rejected() {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M150/")) }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M151/", P1)) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))
        main_cli(arrayOf(H1, "chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))
        main_host(arrayOf(H1, "now", "0"))

        main_cli(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_cli(arrayOf(H0, S0, "chain", "#", "like", "--why=0@l2", h2))

        main_cli(arrayOf(H1, "peer", "localhost:$PORT0", "recv", "#"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2

        // HOST-1
        // h0 <- 0@h1 <- 1@h2 <- 0@l2

        // l3
        main_cli(arrayOf(H0, S0, "chain", "#", "dislike", h2, "--why=0@l3"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 0@l3-

        main_host(arrayOf(H0, "now", "${5 * hour}"))
        main_host(arrayOf(H1, "now", "${5 * hour}"))

        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1) { it.size }
                it.forEach { v -> assert_(v.startsWith("4_")) }
            }
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        main_cli_assert(arrayOf(H1, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1) { it.size }
                it.forEach { v -> assert_(v.startsWith("3_")) }
            }
        }
        main_cli_assert(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_host(arrayOf(H0, "now", "${25 * hour}"))
        main_host(arrayOf(H1, "now", "${25 * hour}"))

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        main_cli(arrayOf(H1, S1, "chain", "#", "post", "inline", "1@h3"))

        // HOST-0
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 0@l3-

        // HOST-1
        // h0 <- 0@h1 <- 1@h2 <- 0@l2 <- 1@h3

        main_cli_assert(arrayOf(H1, "chain", "#", "heads")).let { str ->
            str.split(' ').let {
                assert_(it.size == 1) { it.size }
                it.forEach { v -> assert_(v.startsWith("4_")) }
            }
        }

        // send H1 -> H0
        // ~1@h3 will be rejected b/c 1@h2 is rejected in H0~
        main_cli_assert(arrayOf(H1, "peer", "localhost:$PORT0", "send", "#")).let {
            assert_(it.contains("1 / 1"))
        }

        // l4: try again after like // like will be ignored b/c >24h
        main_cli(arrayOf(H0, S0, "chain", "#", "like", h2))

        // HOST-0
        // h0 <- 0@h1 <- 0@l2 <-- 0@l3- <- 0@l4
        //            <- 1@h2 <-\ 1@h3     /
        //                  \-------------/

        // HOST-1
        // h0 <- 0@h1 <- 0@l2 |
        //            <- 1@h2 | <- 1@h3

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf(H1, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
    }
    @Test
    fun m16_likes_fronts () {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M16/")) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))

        main_cli(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_cli(arrayOf(H0, S0, "chain", "#", "like", h2, "--why=0@l2"))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2
        //            <- 1@h2

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        main_host(arrayOf(H0, "now", "${3 * hour}"))

        // l4 dislikes h2: h2 should remain accepted b/c h2<-l3
        main_cli(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h3"))
        main_cli(arrayOf(H0, S0, "chain", "#", "dislike", h2, "--why=0@l3"))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2 \ 0@h3 -- 0@l3
        //            <- 1@h2  /

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let {
            assert_(it.startsWith("5_"))
        }
    }
    @Test
    fun m17_likes_day () {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M17/")) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))

        main_cli(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))
        main_cli(arrayOf(H0, S0, "chain", "#", "like", h2))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2
        //            <- 1@h2

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }

        main_host(arrayOf(H0, "now", "${25 * hour}"))

        // l4 dislikes h2: h2 should remain accepted b/c (l3-h2 > 24h)
        main_cli(arrayOf(H0, S0, "chain", "#", "dislike", h2))

        // HOST-0
        // h0 <- 0@h1 <-- 0@l2 <-- 0@h3 <- 0@l4
        //            <- 1@h2 <-/

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf(H0, "chain", "#", "heads")).let {
            assert_(it.startsWith("4_"))
        }
    }
    @Test
    fun m18_remove () {
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M18/")) }
        Thread.sleep(200)
        main_cli(arrayOf(H0, "chains", "join", "#", PUB0))

        main_host(arrayOf(H0, "now", "0"))

        val h1 = main_cli_assert(arrayOf(H0, S0, "chain", "#", "post", "inline", "0@h1"))
        val h2 = main_cli_assert(arrayOf(H0, S1, "chain", "#", "post", "inline", "1@h2"))

        // h0 <- 0@h1 <- 1@h2

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.startsWith("2_"))
        }

        main_cli(arrayOf(H0, "chain", "#", "remove", h1)).let { (ok,_) ->
            assert_(!ok) // "! can only remove blocked block")
        }

        main_cli_assert(arrayOf(H0, "chain", "#", "remove", h2)).let {
            assert_(it == "")
        }

        // h0 <- 0@h1

        main_cli_assert(arrayOf(H0, "chain", "#", "heads", "blocked")).let {
            assert_(it.isEmpty())
        }
        main_cli_assert(arrayOf("chain", "#", "heads")).let { list ->
            list.split(' ').toTypedArray().let {
                assert_(it.size == 1)
                assert_(it[0].startsWith("1_"))
            }
        }
    }

    @Test
    fun m19_sends () {
        val PVT = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
        val PUB = "3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322"
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M19-0/"))     }
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M19-1/", P1)) }
        Thread.sleep(500)
        main_cli_assert(arrayOf(H0, "chains", "join", "@!$PUB"))
        main_cli_assert(arrayOf(H1, "chains", "join", "@!$PUB"))

        val n = 100
        for (i in 1..n) {
            main_cli_assert(arrayOf(H0, "--sign=$PVT", "chain", "@!$PUB", "post", "inline", "$i"))
        }

        val ret = main_cli_assert(arrayOf(H0, "peer", "localhost:$PORT1", "send", "@!$PUB"))
        assert_(ret == "$n / $n")
    }

    @Test
    fun m20_posts_20 () {
        val PUB = "300C0F41FA37EDD0A812F88956EB005C68967ED4F9ADA8CDE7E64C030D4A2F39"
        val PVT = "8A0E17D9B3C29152A797D061C619CFBA829BC7325ED72831CC59C0A7148CA9F8300C0F41FA37EDD0A812F88956EB005C68967ED4F9ADA8CDE7E64C030D4A2F39"
        thread { main_host(arrayOf("start", "/tmp/freechains/tests/M20/")) }
        Thread.sleep(500)
        main_cli(arrayOf("chains", "join", "#crdt", PUB0))

        val n = 23
        for (i in 1..n) {
            main_cli_assert(arrayOf(H0, S0, "chain", "#crdt", "post", "inline", "Post $n"))
        }

        val ret = main_cli_assert(arrayOf(H0, "chain", "#crdt", "heads"))
        assert_(ret.startsWith("23_"))
    }
}
