package org.freechains.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

typealias Addr_Port = Pair<String,Int>

const val MAJOR    = 0
const val MINOR    = 8
const val REVISION = 5
const val VERSION  = "v$MAJOR.$MINOR.$REVISION"
const val PRE      = "FC $VERSION"

const val PORT_8330 = 8330 //8888

///////////////////////////////////////////////////////////////////////////////

inline fun assert_ (value: Boolean, lazyMessage: () -> Any = {"Assertion failed"}) {
    if (!value) {
        val message = lazyMessage()
        throw AssertionError(message)
    }
}

///////////////////////////////////////////////////////////////////////////////

fun main_ (f: ()->Pair<Boolean,String>) {
    f().let { (ok,msg) ->
        if (ok) {
            if (msg.isNotEmpty()) {
                println(msg)
            }
        } else {
            System.err.println(msg)
            exitProcess(1)
        }
    }
}

fun main_assert_ (f: ()->Pair<Boolean,String>) : String {
    return f().let { (ok,msg) ->
        assert_(ok) { msg }
        msg
    }
}

fun main_catch_ (
        app: String, version: String, help: String, args: Array<String>,
        f: (List<String>,Map<String,String?>)->Pair<Boolean,String>
): Pair<Boolean,String> {
    val full = "$app ${args.joinToString(" ")}"

    return try {
        val (cmds, opts) = args.cmds_opts()
        when {
            opts.containsKey("--help")    -> Pair(true,  help)
            opts.containsKey("--version") -> Pair(true,  version)
            cmds.size == 0                -> Pair(false, help)
            else                          -> f(cmds, opts)
        }
    } catch (e: AssertionError) {
        return when {
            e.message.equals("Assertion failed") -> Pair(false, "! TODO - $e - ${e.message} - $full")
            e.message!!.startsWith('!') -> Pair(false, e.message!!)
            else -> Pair(false, "! " + e.message!!)
        }
    } catch (e: java.io.EOFException) {
        return Pair(false, "! connection closed")
    } catch (e: ConnectException) {
        assert_(e.message == "Connection refused (Connection refused)")
        return Pair(false, "! connection refused")
    } catch (e: SocketTimeoutException) {
        return Pair(false, "! connection timeout")
    } catch (e: UnknownHostException) {
        return Pair(false, "! invalid host")
    } catch (e: Throwable) {
        //System.err.println(e.stackTrace.contentToString())
        return Pair(false, "! TODO - $e - ${e.message} - $full")
    }
}

///////////////////////////////////////////////////////////////////////////////

fun DataInputStream.readLineX () : String {
    val ret = mutableListOf<Byte>()
    while (true) {
        val c = this.readByte()
        if (c == '\r'.toByte()) {
            assert_(this.readByte() == '\n'.toByte())
            break
        }
        if (c == '\n'.toByte()) {
            break
        }
        ret.add(c)
    }
    val str = ret.toByteArray().toString(Charsets.UTF_8)
    assert_(!str.startsWith('!')) { str }
    return str
}

fun DataOutputStream.writeLineX (v: String) {
    this.writeBytes(v)
    this.writeByte('\n'.toInt())
}


/*
fun DataInputStream.readNBytesX (len: Int): ByteArray {
    return this.readNBytes(len)
}

fun DataInputStream.readAllBytesX (): ByteArray {
    return this.readAllBytes()
}

//@Throws(IOException::class)
fun DataInputStream.readAllBytesX(): ByteArray {
    return this.readNBytesX(2147483647)
}
*/

//@Throws(IOException::class)
fun DataInputStream.readNBytesX(len: Int): ByteArray {
    return if (len < 0) {
        throw IllegalArgumentException("len < 0")
    } else {
        var bufs: MutableList<ByteArray?>? = null
        var result: ByteArray? = null
        var total = 0
        var remaining = len
        var n: Int = 0
        do {
            val buf = ByteArray(Math.min(remaining, 8192))
            var nread: Int
            nread = 0
            while (this.read(
                    buf,
                    nread,
                    Math.min(buf.size - nread, remaining)
                ).also({ n = it }) > 0
            ) {
                nread += n
                remaining -= n
            }
            if (nread > 0) {
                if (2147483639 - total < nread) {
                    throw OutOfMemoryError("Required array size too large")
                }
                total += nread
                if (result == null) {
                    result = buf
                } else {
                    if (bufs == null) {
                        bufs = ArrayList()
                        bufs.add(result)
                    }
                    bufs.add(buf)
                }
            }
        } while (n >= 0 && remaining > 0)
        if (bufs == null) {
            if (result == null) {
                ByteArray(0)
            } else {
                if (result.size == total) result else Arrays.copyOf(result, total)
            }
        } else {
            result = ByteArray(total)
            var offset = 0
            remaining = total
            var count: Int
            val var12: Iterator<*> = bufs.iterator()
            while (var12.hasNext()) {
                val b = var12.next() as ByteArray
                count = Math.min(b.size, remaining)
                System.arraycopy(b, 0, result, offset, count)
                offset += count
                remaining -= count
            }
            result
        }
    }
}

///////////////////////////////////////////////////////////////////////////////

fun Array<String>.cmds_opts () : Pair<List<String>,Map<String,String?>> {
    val cmds = this.filter { !it.startsWith("--") }
    val opts = this
            .filter { it.startsWith("--") }
            .map {
                if (it.contains('=')) {
                    val (k,v) = Regex("(--.*)=(.*)").find(it)!!.destructured
                    Pair(k,v)
                } else {
                    Pair(it, null)
                }
            }
            .toMap()
    return Pair(cmds,opts)
}

///////////////////////////////////////////////////////////////////////////////

fun String.to_Addr_Port () : Addr_Port {
    val lst = this.split(":")
    return when (lst.size) {
        0    -> Addr_Port("localhost", PORT_8330)
        1    -> Addr_Port(lst[0],      PORT_8330)
        else -> Addr_Port(lst[0],      lst[1].toInt())
    }
}

/*
fun Addr_Port.from_Addr_Port () : String {
    return "$first:$second"
}
*/

///////////////////////////////////////////////////////////////////////////////

fun<T> Set<Set<T>>.intersectAll (): Set<T> {
    return this.fold(this.unionAll(), {x,y->x.intersect(y)})
}

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}
