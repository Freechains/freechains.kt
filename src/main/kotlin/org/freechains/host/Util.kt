package org.freechains.host

import org.freechains.common.*
import java.time.Instant

typealias HKey = String

internal var NOW : Long? = null

///////////////////////////////////////////////////////////////////////////////

const val T30M_future = 30*min          // refuse posts +30m in the future
const val T12h_new    = 12*hour         // -1 post younger than 12h
const val T24h_old    = 24*hour         // +1 post older than 24h
const val T7d_fork    = 7*day
const val N100_fork   = 100

///////////////////////////////////////////////////////////////////////////////

const val LK30_max     = 30
const val LK3_dislikes = 3

const val S128_pay = 128000             // 128 KBytes maximum size of payload

const val N16_blockeds = 16             // hold at most 16 blocked blocks locally

///////////////////////////////////////////////////////////////////////////////

fun setNow (t: Long) {
    //NOW = t
    NOW = Instant.now().toEpochMilli() - t
}

fun getNow () : Long {
    //return NOW ?: 0
    return Instant.now().toEpochMilli() - (if (NOW == null) 0 else NOW!!)
}

fun String.nowToTime () : Long {
    return if (this == "now") getNow() else this.toLong()
}

///////////////////////////////////////////////////////////////////////////////

fun String.pvtToPub () : String {
    return this.substring(this.length/2)
}

///////////////////////////////////////////////////////////////////////////////

fun<T> Set<Set<T>>.intersectAll (): Set<T> {
    return this.fold(this.unionAll(), {x,y->x.intersect(y)})
}

fun<T> Set<Set<T>>.unionAll (): Set<T> {
    return this.fold(emptySet(), {x,y->x+y})
}
