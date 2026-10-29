package ph.sarisari.mpos.domain

/** EAN-13 / UPC-A / EAN-8 bar patterns (pure Kotlin, no Android). Modules are '1' = bar, '0' = space. Other barcode types are not drawable here. */
object Ean {
    private val L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")
    private fun r(d: Int) = L[d].map { if (it == '0') '1' else '0' }.joinToString("")
    private fun g(d: Int) = r(d).reversed()

    /** Check digit for a code WITHOUT its last digit (7, 11 or 12 digits). */
    fun checkDigit(body: String): Int {
        var sum = 0; body.reversed().forEachIndexed { i, ch -> sum += (ch - '0') * (if (i % 2 == 0) 3 else 1) }
        return (10 - sum % 10) % 10
    }
    fun isValid(code: String) = code.isNotEmpty() && code.all { it.isDigit() } && code.length in setOf(8, 12, 13) && (code.last() - '0') == checkDigit(code.dropLast(1))

    /** Bar pattern, or null if the code is not a valid EAN-13 / UPC-A / EAN-8. */
    fun modules(code: String): String? {
        if (!isValid(code)) return null
        if (code.length == 8) {
            val d = code.map { it - '0' }
            return "101" + d.take(4).joinToString("") { L[it] } + "01010" + d.drop(4).joinToString("") { r(it) } + "101"
        }
        val c = if (code.length == 12) "0$code" else code; val d = c.map { it - '0' }
        val par = PARITY[d[0]]
        return "101" + (1..6).joinToString("") { i -> if (par[i - 1] == 'L') L[d[i]] else g(d[i]) } + "01010" + (7..12).joinToString("") { r(d[it]) } + "101"
    }

    /** In-store EAN-13 (prefix 200 is reserved by GS1 for store-internal use). */
    fun inStore(seq: Long): String { val body = "200" + seq.toString().padStart(9, '0'); return body + checkDigit(body) }
}
