package org.freechains.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

typealias Hash = String

enum class Head_State {
    INVALID, BLOCKED, LINKED, ALL
}

@Serializable
data class Like (
    val n    : Int,     // +1: like, -1: dislike
    val hash : Hash     // target post hash
)

@Serializable
data class Signature (
    val hash : String,    // signature
    val pub  : HKey       // of pubkey (if "", assumes pub of chain)
)

@Serializable
data class Payload (            // payload metadata
    val crypt   : Boolean,      // is encrypted (method depends on chain)
    val hash    : Hash          // hash of contents
)

@Serializable
data class Immut (
    val time    : Long,         // author's timestamp
    val pay     : Payload,      // payload metadata
    val like    : Like?,        // point to liked post
    val backs   : Set<Hash>     // back links (happened-before blocks)
)

@Serializable
data class Block_Get (          // only used in "freechains ... get block ...
    val hash   : Hash,
    val time   : Long,
    val pay    : Payload,
    val like   : Like?,
    val sign   : Signature?,
    val backs  : Set<Hash>
)

@Serializable
data class Block (
    val immut   : Immut,        // things to hash
    val hash    : Hash,         // hash of immut
    val sign    : Signature?
)

fun Immut.toJson (): String {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.encodeToString(Immut.serializer(), this)
}

fun Block.toJson (): String {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.encodeToString(Block.serializer(), this)
}

fun Block_Get.toJson (): String {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.encodeToString(Block_Get.serializer(), this)
}

fun String.jsonToBlock (): Block {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.decodeFromString(Block.serializer(), this)
}

fun String.jsonToBlock_ (): Block_Get {
    //@OptIn(UnstableDefault::class)
    val json = Json { prettyPrint=true }
    return json.decodeFromString(Block_Get.serializer(), this)
}

fun Hash.hashSplit () : Pair<Int,String> {
    val (height, hash) = this.split("_")
    return Pair(height.toInt(), hash)
}

fun Hash.toHeight () : Int {
    return this.hashSplit().first
}

fun Hash.hashIsBlock () : Boolean {
    return this.contains('_')   // otherwise is pubkey
}

fun Block.isFrom (pub: HKey) : Boolean {
    return (this.sign!=null && this.sign.pub==pub)
}
