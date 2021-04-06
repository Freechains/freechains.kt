package org.freechains.host

import org.freechains.common.*
import java.time.Instant

// TODO
const val T90D_rep    = 90*day          // consider last 90d for reputation
const val T120D_past  = 4*T90D_rep/3    // reject posts +120d in the past
const val T30M_future = 30*min          // refuse posts +30m in the future
const val T12h_new    = 12*hour         // -1 post younger than 12h
const val T24h_old    = 24*hour         // +1 post older than 24h

const val LK30_max     = 30
const val LK3_dislikes = 3

const val S128_pay = 128000             // 128 KBytes maximum size of payload

const val N16_blockeds = 16             // hold at most 16 blocked blocks locally

internal var NOW : Long? = null

fun setNow (t: Long) {
    NOW = Instant.now().toEpochMilli() - t
}

fun getNow () : Long {
    return Instant.now().toEpochMilli() - (if (NOW == null) 0 else NOW!!)
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}
