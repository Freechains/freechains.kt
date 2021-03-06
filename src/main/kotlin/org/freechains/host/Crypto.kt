package org.freechains.host

import org.freechains.common.*
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair

private const val len = "6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322".length

fun HKey.keyIsPrivate () : Boolean {
    return this.length == len
}

// GENERATE

fun String.toPwHash (): ByteArray {
    val inp = this.toByteArray()
    val out = ByteArray(32)                       // TODO: why 32?
    val salt = ByteArray(PwHash.ARGON2ID_SALTBYTES)     // all org.freechains.core.getZeros
    assert_(
            lazySodium.cryptoPwHash(
                    out, out.size, inp, inp.size, salt,
                    PwHash.OPSLIMIT_INTERACTIVE, PwHash.MEMLIMIT_INTERACTIVE, PwHash.Alg.getDefault()
            )
    )
    return out
}

fun String.toShared () : HKey {
    return Key.fromBytes(this.toPwHash()).asHexString
}

fun String.toPubPvt () : KeyPair {
    return lazySodium.cryptoSignSeedKeypair(this.toPwHash())
}

// ENCRYPT

fun ByteArray.encryptShared (shared: HKey) : ByteArray {
    val nonce = lazySodium.nonce(SecretBox.NONCEBYTES)
    val enc = ByteArray(SecretBox.MACBYTES + this.size)
    assert(lazySodium.cryptoSecretBoxEasy(enc, this,this.size.toLong(), nonce, Key.fromHexString(shared).asBytes))
    return nonce + enc

}

fun ByteArray.encryptPublic (pub: HKey) : ByteArray {
    val out = ByteArray(Box.SEALBYTES + this.size)
    val key0 = Key.fromHexString(pub).asBytes
    val key1 = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
    assert_(lazySodium.convertPublicKeyEd25519ToCurve25519(key1, key0))
    assert(lazySodium.cryptoBoxSeal(out, this, this.size.toLong(), key1))
    return out
}

// DECRYPT

fun ByteArray.decrypt (key: HKey) : ByteArray {
    return if (key.keyIsPrivate()) this.decryptPrivate(key) else this.decryptShared(key)
}

fun ByteArray.decryptShared (shared: HKey) : ByteArray {
    val nonce = this.copyOfRange(0, SecretBox.NONCEBYTES)
    val enc = this.copyOfRange(SecretBox.NONCEBYTES,this.size)
    val dec = ByteArray(enc.size-SecretBox.MACBYTES)
    assert(lazySodium.cryptoSecretBoxOpenEasy(dec, enc,enc.size.toLong(), nonce, Key.fromHexString(shared).asBytes))
    return dec
}

fun ByteArray.decryptPrivate (pvt: HKey) : ByteArray {
    val out = ByteArray(this.size - Box.SEALBYTES)
    val pub1 = Key.fromHexString(pvt.pvtToPub()).asBytes
    val pvt1 = Key.fromHexString(pvt).asBytes
    val pub2 = ByteArray(Box.CURVE25519XSALSA20POLY1305_PUBLICKEYBYTES)
    val pvt2 = ByteArray(Box.CURVE25519XSALSA20POLY1305_SECRETKEYBYTES)
    assert_(lazySodium.convertPublicKeyEd25519ToCurve25519(pub2, pub1))
    assert_(lazySodium.convertSecretKeyEd25519ToCurve25519(pvt2, pvt1))
    assert_(lazySodium.cryptoBoxSealOpen(out, this, this.size.toLong(), pub2, pvt2))
    return out
}