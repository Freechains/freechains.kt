package org.freechains.common

import java.io.DataInputStream

val fsRoot = "/"

fun DataInputStream.readNBytesX (len: Int): ByteArray {
    return this.readNBytes(len)
}

fun DataInputStream.readAllBytesX (): ByteArray {
    return this.readAllBytes()
}
