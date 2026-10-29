package com.enzo.gastosexpress

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Cotizaciones que muestra el widget: se guardan en el celular para poder dibujarlas al instante
 * y se refrescan con un toque o cada ~15 min (DolarWorker).
 */
object DolarData {
    val CASAS = listOf(
        "blue" to "Blue", "oficial" to "Oficial", "bolsa" to "MEP", "contadoconliqui" to "CCL",
        "cripto" to "Cripto", "mayorista" to "Mayorista", "tarjeta" to "Tarjeta",
    )
    const val VERDE = 0xFF4FD699.toInt()
    const val ROJO = 0xFFFF6A55.toInt()
    const val GRIS = 0xFF8497A7.toInt()
    const val BLANCO = 0xFFEAF2F8.toInt()

    data class Cot(val compra: Double, val venta: Double)

    private fun p(c: Context) = c.getSharedPreferences("dolarw", Context.MODE_PRIVATE)
    fun nombre(casa: String) = CASAS.firstOrNull { it.first == casa }?.second ?: casa
    fun casa(c: Context): String = p(c).getString("sel", "blue") ?: "blue"
    fun setCasa(c: Context, casa: String) = p(c).edit().putString("sel", casa).apply()
    fun siguiente(c: Context) {
        val i = CASAS.indexOfFirst { it.first == casa(c) }
        setCasa(c, CASAS[(i + 1) % CASAS.size].first)
    }
    fun hora(c: Context): String = p(c).getString("hora", "") ?: ""
    fun leidoMs(c: Context): Long = p(c).getLong("leido", 0L)
    fun hayError(c: Context): Boolean = p(c).getString("estado", "") == "error"
    fun usd(c: Context): Double = p(c).getString("usd", "0")?.toDoubleOrNull() ?: 0.0

    private fun leer(s: String?): Cot? = s?.split("|")?.takeIf { it.size >= 2 }?.let {
        val a = it[0].toDoubleOrNull(); val b = it[1].toDoubleOrNull()
        if (a != null && b != null) Cot(a, b) else null
    }
    fun actual(c: Context, casa: String): Cot? = leer(p(c).getString("c_$casa", null))
    fun ayer(c: Context, casa: String): Cot? = leer(p(c).getString("y_$casa", null))

    /** Bloqueante: llamar desde un hilo. Trae todas las cotizaciones, el cierre de ayer y tus dólares. */
    fun actualizar(c: Context) {
        try {
            val arr = Api.dolares()
            val e = p(c).edit()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                e.putString("c_${j.getString("casa")}", "${j.optDouble("compra")}|${j.optDouble("venta")}")
            }
            e.putString("hora", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                .putLong("leido", System.currentTimeMillis()).putString("estado", "ok").apply()
        } catch (ex: Exception) {
            p(c).edit().putString("estado", "error").apply()
            return
        }
        val hoy = LocalDate.now().toString()
        for ((casa, _) in CASAS) {
            if (p(c).getString("yd_$casa", "") == hoy) continue
            try {
                var dia = LocalDate.now().minusDays(1)
                for (intento in 0 until 5) {
                    val j = Api.dolarDia(casa, dia)
                    if (j != null) {
                        p(c).edit().putString("y_$casa", "${j.optDouble("compra")}|${j.optDouble("venta")}").putString("yd_$casa", hoy).apply()
                        break
                    }
                    dia = dia.minusDays(1)
                }
            } catch (ex: Exception) { /* sin cierre de ayer: se muestra sin flecha */ }
        }
        try {
            if (Session.hasSession(c)) {
                val u = Api.getDoc(c, "ajustes/ahorro_usd")?.optJSONObject("fields")?.fnum("usd") ?: 0.0
                p(c).edit().putString("usd", u.toString()).apply()
            }
        } catch (ex: Exception) { /* sin sesión o sin permiso */ }
    }

    // ---------- Textos con flecha verde/roja ----------
    private fun SpannableStringBuilder.color(txt: String, col: Int, negrita: Boolean = false): SpannableStringBuilder {
        val ini = length
        append(txt)
        setSpan(ForegroundColorSpan(col), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (negrita) setSpan(StyleSpan(Typeface.BOLD), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    private fun pct(c: Context, casa: String): Double? {
        val a = actual(c, casa) ?: return null
        val y = ayer(c, casa) ?: return null
        return if (y.compra > 0) (a.compra / y.compra - 1) * 100 else null
    }
    private fun flecha(p: Double?): Pair<String, Int> = when {
        p == null || abs(p) < 0.05 -> "■" to GRIS
        p > 0 -> "▲" to VERDE
        else -> "▼" to ROJO
    }
    private fun fmtPct(p: Double) = String.format(java.util.Locale("es", "AR"), "%+.1f%%", p)

    /** Una línea para el widget chico: "▲ Blue  $ 1.545 / $ 1.565". */
    fun corto(c: Context): CharSequence {
        val casa = casa(c); val a = actual(c, casa)
        if (a == null) return if (hayError(c)) "Sin conexión · tocá para reintentar" else "Dólar ${nombre(casa)}: tocá para ver"
        val (f, col) = flecha(pct(c, casa))
        return SpannableStringBuilder().color(f, col, true).append("  ").color(nombre(casa), BLANCO, true)
            .append("  ${pesos(a.compra.toLong())} / ${pesos(a.venta.toLong())}")
    }

    /** Número grande del panel completo: "▲ $ 1.545". */
    fun valorGrande(c: Context): CharSequence {
        val casa = casa(c); val a = actual(c, casa) ?: return if (hayError(c)) "Sin conexión" else "Cargando…"
        val (f, col) = flecha(pct(c, casa))
        return SpannableStringBuilder().color(f, col, true).append(" ").append(pesos(a.compra.toLong()))
    }

    /** "compra · venta $ 1.565 · ▲ +0,6% vs ayer". */
    fun detalle(c: Context): CharSequence {
        val casa = casa(c); val a = actual(c, casa) ?: return if (hayError(c)) "Tocá ↻ para reintentar" else ""
        val b = SpannableStringBuilder().append("${nombre(casa)} · venta ${pesos(a.venta.toLong())}")
        val p = pct(c, casa)
        if (p != null) {
            val (f, col) = flecha(p)
            b.append("  ·  ").color("$f ${fmtPct(p)}", col, true).append(" vs ayer")
        }
        return b
    }

    /** "Tus US$ 550 valen $ 849.750 (▲ $ 5.500 vs ayer)". */
    fun ahorro(c: Context): CharSequence {
        val casa = casa(c); val u = usd(c); val a = actual(c, casa) ?: return ""
        if (u <= 0.0) return "Cargá tus dólares en la web"
        val b = SpannableStringBuilder().append("Tus US$ ${u.toLong()} valen ${pesos((u * a.compra).toLong())}")
        val y = ayer(c, casa)
        if (y != null) {
            val dif = u * (a.compra - y.compra)
            if (abs(dif) >= 1.0) {
                val (f, col) = flecha(dif)
                b.append("  ").color("$f ${pesos(abs(dif).toLong())}", col, true).append(" vs ayer")
            }
        }
        return b
    }
}
