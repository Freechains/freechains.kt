package org.freechains.host

import org.freechains.common.*
import com.goterl.lazysodium.exceptions.SodiumException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.net.*
import java.util.*
import kotlin.collections.HashSet
import kotlin.concurrent.thread

class Daemon (loc_: Host) {
    private val listenLists = mutableMapOf<String,MutableSet<DataOutputStream>>()
    private val server = ServerSocket(loc_.port)
    private val loc = loc_

    // must use "plain-by-value" b/c of different refs in different connections of the same node
    private fun getLock (chain: String) : String {
        return (loc.root+chain).intern()
    }

    private fun chainsLoadSync (name: String) : Chain {
        return synchronized(this.getLock(name)) {
            val chain = loc.chainsLoad(name)    // TODO: remove synchronized??? check git history
            //chain.consensus()
            chain
        }
    }

    fun daemon () {
        //System.err.println("local start: $host")
        while (true) {
            try {
                val remote = server.accept()
                //remote.soTimeout = 0
                remote.soTimeout = TIMEOUT
                System.err.println("remote connect: $loc <- ${remote.inetAddress.hostAddress}")
                thread {
                    try {
                        handle(remote)
                    } catch (e: Throwable) {
                        System.err.println(
                            e.message ?: e.toString()
                        )
                        System.err.println(e.stackTrace.contentToString())
                    }
                    remote.close()
                }
            } catch (e: SocketException) {
                assert_(e.message == "Socket closed")
                break
            }
        }
    }

    private fun signal (chain: String, n: Int) {
        val has1 = synchronized (listenLists) { listenLists.containsKey(chain) }
        if (has1) {
            val wrs = synchronized (listenLists) { listenLists[chain]!!.toList() }
            for (wr in wrs) {
                try {
                    wr.writeLineX(n.toString())
                } catch (e: Throwable) {
                    synchronized (listenLists) { listenLists[chain]!!.remove(wr) }
                }
            }
        }
        val has2 = synchronized (listenLists) { listenLists.containsKey("*") }
        if (has2) {
            val wrs = synchronized (listenLists) { listenLists["*"]!!.toList() }
            for (wr in wrs) {
                try {
                    wr.writeLineX(n.toString() + " " + chain)
                } catch (e: Throwable) {
                    synchronized (listenLists) { listenLists["*"]!!.remove(wr) }
                }
            }
        }
    }

    private fun handle (client: Socket) {
        val reader = DataInputStream(client.getInputStream()!!)
        val writer = DataOutputStream(client.getOutputStream()!!)
        val ln = reader.readLineX()

        try {
            val (v1,v2,_,cmds_) =
                Regex("FC v(\\d+)\\.(\\d+)\\.(\\d+) (.*)").find(ln)!!.destructured
            assert_(MAJOR == v1.toInt() && MINOR >= v2.toInt()) { "incompatible versions" }
            val cmds = cmds_.split(' ')

            if (!client.inetAddress!!.toString().equals("/127.0.0.1")) {
                assert_(
                cmds[0].equals("_peer_") && (
                        cmds[1].equals("_send_") || cmds[1].equals("_recv_") ||
                                cmds[1].equals("_ping_") || cmds[1].equals("_chains_")
                        )
                ) {
                    "invalid remote address"
                }
            }

            when (cmds[0]) {
                "host" -> when (cmds[1]) {
                    "stop" -> {
                        writer.writeLineX("true")
                        server.close()
                        System.err.println("host stop: $loc")
                    }
                    "path" -> {
                        writer.writeLineX(loc.root)
                        System.err.println("host path: ${loc.root}")
                    }
                    "now" -> {
                        if (cmds.size == 3) {
                            val tmp = cmds[2].toLong()
                            setNow(tmp)
                        }
                        val now = getNow()
                        writer.writeLineX(now.toString())
                        System.err.println("host now: $now")
                    }
                }
                "peer" -> {
                    val remote = cmds[1]
                    fun peer(): Pair<DataInputStream, DataOutputStream> {
                        val s = remote.to_Addr_Port().let {
                            Socket(it.first, it.second)
                        }
                        val r = DataInputStream(s.getInputStream()!!)
                        val w = DataOutputStream(s.getOutputStream()!!)
                        return Pair(r, w)
                    }
                    when (cmds[2]) {
                        "ping" -> {
                            val (r, w) = peer()
                            val now = getNow()
                            w.writeLineX("$PRE _peer_ _ping_")
                            val ret = r.readLineX().let {
                                if (it == "true") {
                                    (getNow() - now).toString()
                                } else {
                                    ""
                                }
                            }
                            writer.writeLineX(ret)
                            System.err.println("peer ping: $ret")
                        }
                        "chains" -> {
                            val (r, w) = peer()
                            w.writeLineX("$PRE _peer_ _chains_")
                            val ret = r.readLineX()
                            writer.writeLineX(ret)
                            System.err.println("peer chains")
                        }
                        "send" -> {
                            val chain = cmds[3]
                            val (r, w) = peer()
                            w.writeLineX("$PRE _peer_ _recv_ $chain")
                            val (nmin, nmax) = peerSend(r, w, chain)
                            System.err.println("peer send: $chain: ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                        }
                        "recv" -> {
                            val chain = cmds[3]
                            val (r, w) = peer()
                            w.writeLineX("$PRE _peer_ _send_ $chain")
                            val (nmin, nmax) = peerRecv(r, w, chain)
                            System.err.println("peer recv: $chain: ($nmin/$nmax)")
                            writer.writeLineX("$nmin / $nmax")
                            if (nmin > 0) {
                                thread {
                                    signal(chain, nmin)
                                }
                            }
                        }
                    }
                }
                "_peer_" -> {
                    when (cmds[1]) {
                        "_ping_" -> {
                            writer.writeLineX("true")
                            System.err.println("_peer_ _ping_")
                        }
                        "_chains_" -> {
                            val ret = loc.chainsList().joinToString(" ")
                            writer.writeLineX(ret)
                            System.err.println("_peer_ _chains_: $ret")
                        }
                        "_send_" -> {
                            val chain = cmds[2]
                            val (nmin, nmax) = peerSend(reader, writer, chain)
                            System.err.println("_peer_ _send_: $chain: ($nmin/$nmax)")
                        }
                        "_recv_" -> {
                            val name = cmds[2]
                            val (nmin, nmax) = peerRecv(reader, writer, name)
                            System.err.println("_peer_ _recv_: $name: ($nmin/$nmax)")
                            if (nmin > 0) {
                                thread {
                                    signal(name, nmin)
                                }
                            }
                        }
                    }
                }
                "keys" -> when (cmds[1]) {
                    "shared" -> {
                        val pass = reader.readLineX()
                        writer.writeLineX(pass.toShared())
                    }
                    "pubpvt" -> {
                        val pass = reader.readLineX()
                        val keys = pass.toPubPvt()
                        writer.writeLineX(
                            keys.publicKey.asHexString + ' ' +
                                    keys.secretKey.asHexString
                        )
                    }
                }
                "chains" -> when (cmds[1]) {
                    "join" -> {
                        val name = cmds[2]
                        val chain = synchronized (getLock(name)) {
                            loc.chainsJoin(name, cmds.drop(3))
                        }
                        writer.writeLineX(chain.hash)
                        System.err.println("chains join: $name (${chain.hash})")
                    }
                    "leave" -> {
                        val name = cmds[2]
                        val ret = loc.chainsLeave(name)
                        writer.writeLineX(ret.toString())
                        System.err.println("chains leave: $name -> $ret")
                    }
                    "list" -> {
                        val ret = loc.chainsList().joinToString(" ")
                        writer.writeLineX(ret)
                        System.err.println("chains list: $ret")
                    }
                    "listen" -> {
                        client.soTimeout = 0
                        synchronized(listenLists) {
                            if (!listenLists.containsKey("*")) {
                                listenLists["*"] = mutableSetOf()
                            }
                            listenLists["*"]!!.add(writer)
                        }
                        while (true) {
                            Thread.sleep(Long.MAX_VALUE);   // keeps this connection alive
                        }
                    }
                }
                "chain" -> {
                    val name = cmds[1]
                    when (cmds[2]) {
                        "listen" -> {
                            client.soTimeout = 0
                            synchronized(listenLists) {
                                if (!listenLists.containsKey(name)) {
                                    listenLists[name] = mutableSetOf()
                                }
                                listenLists[name]!!.add(writer)
                            }
                            while (true) {
                                Thread.sleep(Long.MAX_VALUE);   // keeps this connection alive
                            }
                        }
                        else -> {
                            val chain = this.chainsLoadSync(name)
                            when (cmds[2]) {
                                "genesis" -> {
                                    val hash = chain.genesis()
                                    writer.writeLineX(hash)
                                    System.err.println("chain genesis: $hash")
                                }
                                "heads" -> {
                                    val heads = when (cmds.size) {
                                        3 -> chain.heads(Head_State.LINKED)
                                        4 -> { assert(cmds[3]=="blocked") ; chain.heads(Head_State.BLOCKED) }
                                        else -> error("impossible case")
                                    }.joinToString(" ")
                                    writer.writeLineX(heads)
                                    //System.err.println("chain heads: $heads")
                                    System.err.println("chain heads: ...")
                                }
                                "consensus" -> {
                                    val ret = chain.cons.joinToString(" ")
                                    writer.writeLineX(ret)
                                    System.err.println("chain consensus: ...")
                                }
                                "get" -> {
                                    val hash = cmds[4]
                                    val decrypt= if (cmds[5] == "null") null else cmds[5]
                                    try {
                                        when (cmds[3]) {
                                            "block" -> {
                                                val blk = chain.fsLoadBlock(hash)
                                                val blk_ = Block_Get (
                                                    blk.hash, blk.immut.time,
                                                    blk.immut.pay, blk.immut.like, blk.sign,
                                                    blk.immut.backs
                                                )
                                                val ret = blk_.toJson()
                                                writer.writeLineX(ret.length.toString())
                                                writer.writeBytes(ret)
                                            }
                                            "payload" -> {
                                                val ret = if (chain.isRevoked(chain.fsLoadBlock(hash))) {
                                                    ByteArray(0)
                                                } else {
                                                    chain.fsLoadPayCrypt(hash,decrypt)
                                                }
                                                writer.writeLineX(ret.size.toString())
                                                writer.write(ret)
                                            }
                                            else -> error("impossible case")
                                        }
                                    } catch (e: FileNotFoundException) {
                                        writer.writeLineX("! block not found")
                                    }
                                    //writer.writeLineX("\n")
                                    System.err.println("chain get: $hash")
                                }
                                "reps" -> {
                                    val ref = cmds[3]
                                    val likes =
                                        if (ref.hashIsBlock()) {
                                            val (pos, neg) = chain.repsPost(ref)
                                            pos - neg
                                        } else {
                                            chain.reps.getZ(ref)
                                        }
                                    writer.writeLineX(likes.toString())
                                    System.err.println("chain reps: $likes")
                                }

                                // all others need "synchronized"
                                // they affect the chain in the disk, which is shared across connections

                                "post" -> {
                                    val sign = cmds[3]
                                    val len = cmds[5].toInt()
                                    val pay = reader.readNBytesX(len)
                                    var ret: String
                                    try {
                                        synchronized(getLock(chain.name)) {
                                            ret = chain.blockNew (
                                                if (sign == "anon") null else sign,
                                                null,
                                                pay,
                                                cmds[4].toBoolean(),
                                                null
                                            )
                                        }
                                        thread {
                                            signal(name, 1)
                                        }
                                    } catch (e: Throwable) {
                                        System.err.println(e.stackTrace.contentToString())
                                        ret = "! " + e.message!!
                                    }
                                    writer.writeLineX(ret)
                                    System.err.println("chain post: $ret")
                                }
                                "like" -> {
                                    val pay = reader.readNBytesX(cmds[6].toInt())
                                    var ret: String
                                    try {
                                        synchronized(getLock(chain.name)) {
                                            ret = chain.blockNew (
                                                cmds[5],
                                                Like(cmds[3].toInt(), cmds[4]),
                                                pay,
                                                false,
                                                null
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        System.err.println(e.stackTrace.contentToString())
                                        ret = e.message!!
                                    }
                                    writer.writeLineX(ret)
                                    thread {
                                        signal(name, 1)
                                    }
                                    System.err.println("chain like: $ret")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: AssertionError) {
            writer.writeLineX("! " + e.message!!)
        } catch (e: ConnectException) {
            assert_(e.message == "Connection refused (Connection refused)")
            writer.writeLineX("! connection refused")
        } catch (e: SodiumException) {
            writer.writeLineX("! " + e.message!!)
        } catch (e: FileNotFoundException) {
            writer.writeLineX("! chain does not exist")
        } catch (e: SocketException) {
            System.err.println("! connection closed")
            //writer.writeLineX("! connection closed")
        } catch (e: SocketTimeoutException) {
            System.err.println("! connection timeout")
            //writer.writeLineX("! connection timeout")
        } catch (e: Throwable) {
            System.err.println(e.stackTrace.contentToString())
            writer.writeLineX("! TODO - $e - ${e.message}")
        }
    }

    fun peerSend (reader: DataInputStream, writer: DataOutputStream, chain_: String) : Pair<Int,Int> {
        // - receives most recent timestamp
        // - DFS in heads
        //   - asks if contains hash
        //   - aborts path if reaches timestamp+24h
        //   - pushes into toSend
        // - sends toSend

        val chain = this.chainsLoadSync(chain_)
        val visited = HashSet<Hash>()
        var nmin    = 0
        var nmax    = 0

        // for each local head
        val heads = chain.heads(Head_State.LINKED) + chain.heads(Head_State.BLOCKED)
        val nout = heads.size
        writer.writeLineX(nout.toString())                              // 1
        for (head in heads) {
            val pending = ArrayDeque<Hash>()
            pending.push(head)

            val toSend = mutableSetOf<Hash>()

            // for each head path of blocks
            while (pending.isNotEmpty()) {
                val hash = pending.pop()
                if (visited.contains(hash)) {
                    continue
                }
                visited.add(hash)

                val blk = chain.fsLoadBlock(hash)

                //println(">>> $hash")
                writer.writeLineX(hash)                             // 2: asks if contains hash
                val has = reader.readLineX().toBoolean()   // 3: receives yes or no
                if (has) {
                    continue                             // already has: finishes subpath
                }

                // sends this one and visits children
                toSend.add(hash)
                for (back in blk.immut.backs) {
                    pending.push(back)
                }
            }

            writer.writeLineX("")                     // 4: will start sending nodes
            writer.writeLineX(toSend.size.toString())    // 5: how many
            val nin = toSend.size
            val sorted = toSend.sortedWith(compareBy{it.toHeight()})
            for (hash in sorted) {
                val out = chain.fsLoadBlock(hash)
                val json = out.toJson()
                writer.writeLineX(json.length.toString()) // 6
                writer.writeBytes(json)
                val pay = if (chain.isRevoked(out)) ByteArray(0) else chain.fsLoadPayRaw(hash)
                writer.writeLineX(pay.size.toString())
                writer.write(pay)
                writer.writeLineX("")
                reader.readLineX()                          // 6,5
            }
            val nin2 = reader.readLineX().toInt()    // 7: how many blocks again
            assert_(nin >= nin2)
            nmin += nin2
            nmax += nin
        }
        val nout2 = reader.readLineX().toInt()       // 8: how many heads again
        assert_(nout == nout2)

        return Pair(nmin,nmax)
    }

    fun peerRecv (reader: DataInputStream, writer: DataOutputStream, chain_: String) : Pair<Int,Int> {
        // - sends most recent timestamp
        // - answers if contains each host
        // - receives all

        val chain = this.chainsLoadSync(chain_)
        var nmax = 0
        var nmin = 0

        // list of received revoked blocks (empty payloads)
        // will check if are really revoked
        val revokeds = mutableListOf<Block>()

        // for each remote head
        val nout = reader.readLineX().toInt()        // 1
        for (i in 1..nout) {
            // for each head path of blocks
            while (true) {
                val hash = reader.readLineX()   // 2: receives hash in the path
                //println("<<< $hash")
                if (hash.isEmpty()) {                   // 4
                    break                               // nothing else to answer
                } else {
                    //println(chain.fsExistsBlock(hash).toString())
                    writer.writeLineX(chain.fsExistsBlock(hash).toString())   // 3: have or not block
                }
            }

            // receive blocks
            val nin = reader.readLineX().toInt()    // 5
            nmax += nin
            var nin2 = 0

            xxx@for (j in 1..nin) {
                try {
                    val len1 = reader.readLineX().toInt() // 6
                    val blk = reader.readNBytesX(len1).toString(Charsets.UTF_8).jsonToBlock().copy() //local=getNow()
                    val len2 = reader.readLineX().toInt()
                    if (chain.fromOwner(blk) || chain.name.startsWith('$')) {
                        // ok
                    } else {
                        assert_(len2 <= S128_pay) { "post is too large" }
                    }
                    val pay = reader.readNBytesX(len2)
                    reader.readLineX()
                    writer.writeLineX("")               // 6,5

                    // reject peers with different keys
                    if (chain.name.startsWith('$')) {
                        pay.decrypt(chain.keys[0])  // throws exception if fails
                    }

                    synchronized(getLock(chain.name)) {
                        assert_(chain.heads(Head_State.BLOCKED).size <= N16_blockeds) { "too many blocked blocks" }
                        chain.fsSaveBlock(blk,pay)
                    }
                    if (pay.size==0 && blk.immut.pay.hash!="".calcHash()) {
                        revokeds.add(blk)
                    } // else: payload is really an empty string

                    nmin++
                    nin2++
                } catch (e: Throwable) {
                    System.err.println(e.message)
                    System.err.println(e.stackTrace.contentToString())
                }
            }
            writer.writeLineX(nin2.toString())             // 7
        }
        writer.writeLineX(nout.toString())                // 8

        for (blk in revokeds) {
            assert_(chain.isRevoked(blk)) {
                "bug found: expected revoked state"
            }
        }

        return Pair(nmin,nmax)
    }
}
