package org.freechains.host

import org.freechains.common.*
import java.io.File

data class Host (
    val root: String,
    val port: Int,
    val chains: MutableMap<String,Chain> = mutableMapOf()
)

fun Host_load (dir: String, port: Int = PORT_8330) : Host {
    assert_(dir.startsWith("/") || dir.drop(1).startsWith(":\\")) { "path must be absolute" }
    val loc = Host(fsRoot + dir + "/", port)
    File(loc.root).let {
        if (!it.exists()) {
            it.mkdirs()
        }
    }
    File(loc.root + "/timestamp.txt").writeText(getNow().toString())
    return loc
}

// CHAINS

fun Host.chainsLoad (name: String) : Chain {
    val ret = if (this.chains[name] != null) this.chains[name]!! else {
        val file = File(this.root + "/chains/" + name + "/" + "chain")
        val chain = file.readText().fromJsonToChain()
        chain.root = this.root
        this.chains[name] = chain
        chain
    }
    ret.consensus_all()
    return ret
}

fun Host.chainsJoin (name: String, keys: List<HKey>) : Chain {
    val chain = Chain(this.root, name, keys).validate()
    val file = File(chain.path() + "/chain")
    assert_(!file.exists()) { "chain already exists: $chain" }
    chain.fsSave()

    val gen = Block(
        Immut(
            0,
            Payload(false, ""),
            null,
            emptySet()
        ),
        chain.genesis(),
        null,
        getNow()
    )
    chain.fsSaveBlock(gen, ByteArray(0))

    return file.readText().fromJsonToChain()
}

fun Host.chainsLeave (name: String) : Boolean {
    val chain = Chain(this.root, name, emptyList())
    val file = File(chain.path())
    return file.exists() && file.deleteRecursively()
}

fun Host.chainsList () : List<String> {
    return File(this.root + "/chains/").list().let {
        if (it == null) emptyList() else it.toList()
    }
}