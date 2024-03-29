package org.freechains.cli

import org.freechains.common.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket

val help = """
freechains $VERSION

Usage:
    freechains chains join  <chain> [<key>...]
    freechains chains leave <chain>
    freechains chains list
    freechains chains listen
    
    freechains chain <name> genesis
    freechains chain <name> heads [blocked]
    freechains chain <name> get (block | payload) <hash> [file <path>]
    freechains chain <name> post (inline | file | -) [<path_or_text>]
    freechains chain <name> (like | dislike) <hash>
    freechains chain <name> reps <hash_or_pub>
    freechains chain <name> consensus
    freechains chain <name> listen
    
    freechains peer <addr:port> ping
    freechains peer <addr:port> chains
    freechains peer <addr:port> (send | recv) <chain>

    freechains keys (shared | pubpvt) <passphrase>
    
Options:
    --help              [none]            displays this help
    --version           [none]            displays software version
    --host=<addr:port>  [all]             sets host address and port to connect [default: localhost:$PORT_8330]
    --port=<port>       [all]             sets host port to connect [default: $PORT_8330]
    --sign=<pvt>        [post|(dis)like]  signs post with given private key
    --encrypt           [post]            encrypts post with public key (only in public identity chains)
    --decrypt=<pvt>     [get]             decrypts post with private key (only in public identity chains)
    --why=<text>        [(dis)like]       explains reason for the like

More Information:

    http://www.freechains.org/

    Please report bugs at <http://github.com/Freechains/README/>.
"""

fun main (args: Array<String>) {
    main_ { main_cli(args) }
}

fun main_cli_assert (args: Array<String>) : String {
    return main_assert_ { main_cli(args) }
}

fun main_cli (args: Array<String>) : Pair<Boolean,String> {
    return main_catch_("freechains", VERSION, help, args) { cmds,opts ->
        val (addr, port_) = (opts["--host"] ?: "localhost:$PORT_8330").to_Addr_Port()
        val port = opts["--port"]?.toInt() ?: port_
        val socket = Socket(addr, port)
        //socket.soTimeout = 0
        socket.soTimeout = TIMEOUT
        val writer = DataOutputStream(socket.getOutputStream()!!)
        val reader = DataInputStream(socket.getInputStream()!!)

        //println("+++ $cmds")
        @Suppress("UNREACHABLE_CODE")
        val ret: String = when (cmds[0]) {
            "keys" -> {
                assert_(cmds.size == 3) { "invalid number of arguments" }
                writer.writeLineX("$PRE keys ${cmds[1]}")
                assert_(!cmds[2].contains('\n')) { "invalid password" }
                writer.writeLineX(cmds[2])
                reader.readLineX()
            }
            "peer" -> {
                assert_(cmds.size in 3..4) { "invalid number of arguments" }
                val remote = cmds[1]
                when (cmds[2]) {
                    "ping" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE peer $remote ping")
                        reader.readLineX()
                    }
                    "chains" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE peer $remote chains")
                        reader.readLineX()
                    }
                    "send" -> {
                        assert_(cmds.size == 4) { "invalid number of arguments" }
                        writer.writeLineX("$PRE peer $remote send ${cmds[3]}")
                        reader.readLineX()
                    }
                    "recv" -> {
                        assert_(cmds.size == 4) { "invalid number of arguments" }
                        writer.writeLineX("$PRE peer $remote recv ${cmds[3]}")
                        reader.readLineX()
                    }
                    else -> "!"
                }
            }
            "chains" -> {
                assert_(cmds.size >= 2) { "invalid number of arguments" }
                when (cmds[1]) {
                    "list" -> {
                        assert_(cmds.size == 2) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chains list")
                        reader.readLineX()
                    }
                    "leave" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chains leave ${cmds[2]}")
                        reader.readLineX()
                    }
                    "join" -> {
                        assert_(cmds.size >= 3) { "invalid number of arguments" }
                        if (cmds.size > 3) {
                            writer.writeLineX("$PRE chains join " + cmds[2] + " " + cmds.drop(3).joinToString(" "))
                        } else {
                            writer.writeLineX("$PRE chains join " + cmds[2])
                        }
                        reader.readLineX()
                    }
                    "listen" -> {
                        assert_(cmds.size == 2) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chains listen")
                        while (true) {
                            val n_name = reader.readLineX()
                            println(n_name)
                        }
                        "!"
                    }
                    else -> "!"
                }
            }
            "chain" -> {
                assert_(cmds.size >= 3) { "invalid number of arguments" }
                val chain = cmds[1]

                fun like(lk: String): String {
                    assert_(cmds.size == 4) { "invalid number of arguments" }
                    assert_(opts["--sign"] is String) { "expected `--sign`" }
                    val (len, pay) = opts["--why"].let {
                        if (it == null) {
                            Pair(0, "")
                        } else {
                            Pair(it.length.toString(), it)
                        }
                    }
                    writer.writeLineX("$PRE chain $chain like $lk ${cmds[3]} ${opts["--sign"]} $len")
                    writer.writeLineX(pay)
                    return reader.readLineX()
                }

                when (cmds[2]) {
                    "genesis" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chain $chain genesis")
                        reader.readLineX()
                    }
                    "heads" -> {
                        assert_(cmds.size <= 4) { "invalid number of arguments" }
                        val blocked = when (cmds.size) {
                            3 -> ""
                            4 -> { assert(cmds[3]=="blocked") ; " blocked" }
                            else -> error("impossible case")
                        }
                        writer.writeLineX("$PRE chain $chain heads" + blocked)
                        reader.readLineX()
                    }
                    "get" -> {
                        assert_(cmds.size >= 5) { "invalid number of arguments" }
                        val decrypt = opts["--decrypt"].toString() // null or pvtkey

                        writer.writeLineX("$PRE chain $chain get ${cmds[3]} ${cmds[4]} $decrypt")
                        val len = reader.readLineX()
                        if (len.startsWith('!')) {
                            len
                        } else {
                            val bs = reader.readNBytesX(len.toInt())
                            if (cmds.size == 5) {
                                bs.toString(Charsets.UTF_8)
                            } else {
                                assert(cmds[5] == "file")
                                File(cmds[6]).writeBytes(bs)
                                ""
                            }
                        }
                    }
                    "post" -> {
                        assert_(cmds.size in 4..5) { "invalid number of arguments" }
                        val sign = opts["--sign"] ?: "anon"
                        val encrypt = opts.containsKey("--encrypt").toString() // null (false) or empty (true)

                        val pay = when (cmds[3]) {
                            "inline" -> cmds[4].toByteArray()
                            "file"   -> File(cmds[4]).readBytes()
                            "-"      -> DataInputStream(System.`in`).readAllBytesX()
                            else     -> error("impossible case")
                        }
                        writer.writeLineX("$PRE chain $chain post $sign $encrypt ${pay.size}")
                        writer.write(pay)

                        reader.readLineX()
                    }
                    "consensus" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chain $chain consensus")
                        reader.readLineX()
                    }
                    "reps" -> {
                        assert_(cmds.size == 4) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chain $chain reps ${cmds[3]}")
                        reader.readLineX()
                    }
                    "like" -> like("1")
                    "dislike" -> like("-1")
                    "listen" -> {
                        assert_(cmds.size == 3) { "invalid number of arguments" }
                        writer.writeLineX("$PRE chain $chain listen")
                        while (true) {
                            val n = reader.readLineX()
                            println(n)
                        }
                        "!"
                    }
                    else -> "!"
                }
            }
            else -> "!"
        }
        when {
            ret.startsWith("!") -> Pair(false, ret)
            else -> Pair(true, ret)
        }
    }
}
