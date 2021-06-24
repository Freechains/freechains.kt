package org.freechains.common

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

val lazySodium: LazySodiumJava = LazySodiumJava(SodiumJava())

val fsRoot = "/"