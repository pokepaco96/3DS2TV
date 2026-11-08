package com.threedstwo.tv.core.layout

/**
 * 2D affine transform:  x' = a*x + b*y + c ;  y' = d*x + e*y + f.
 * Same element order as android.graphics.Matrix values (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y).
 */
data class Affine(
    val a: Float = 1f, val b: Float = 0f, val c: Float = 0f,
    val d: Float = 0f, val e: Float = 1f, val f: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + b * y + c
    fun mapY(x: Float, y: Float) = d * x + e * y + f

    /** Returns the transform that applies `this` first and then [next]. */
    fun then(next: Affine) = Affine(
        a = next.a * a + next.b * d,
        b = next.a * b + next.b * e,
        c = next.a * c + next.b * f + next.c,
        d = next.d * a + next.e * d,
        e = next.d * b + next.e * e,
        f = next.d * c + next.e * f + next.f,
    )

    fun inverse(): Affine {
        val det = a * e - b * d
        require(det != 0f) { "singular transform" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    /** Applies only the linear part (for direction vectors). */
    fun mapVectorX(x: Float, y: Float) = a * x + b * y
    fun mapVectorY(x: Float, y: Float) = d * x + e * y

    /** 9 values in android.graphics.Matrix order. */
    fun toMatrixValues(out: FloatArray = FloatArray(9)): FloatArray {
        out[0] = a; out[1] = b; out[2] = c
        out[3] = d; out[4] = e; out[5] = f
        out[6] = 0f; out[7] = 0f; out[8] = 1f
        return out
    }

    companion object {
        val IDENTITY = Affine()
        fun translate(tx: Float, ty: Float) = Affine(c = tx, f = ty)
        fun scale(sx: Float, sy: Float) = Affine(a = sx, e = sy)
    }
}
