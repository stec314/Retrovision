package dev.retrovision.core.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator in "world units": the whole world is 0..1 on both axes, y grows southwards (XYZ tiles). */
object WebMercator {
    const val MAX_LAT = 85.05112878

    fun x(lon: Double) = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0
    }
    fun lon(x: Double) = x * 360.0 - 180.0
    fun lat(y: Double) = atan(kotlin.math.sinh(PI * (1 - 2 * y))) * 180.0 / PI

    /** Ground metres per world unit at a latitude (Earth circumference scaled by cos(lat)). */
    fun metresPerUnit(lat: Double) = 40_075_016.686 * kotlin.math.cos(lat * PI / 180.0)

}
