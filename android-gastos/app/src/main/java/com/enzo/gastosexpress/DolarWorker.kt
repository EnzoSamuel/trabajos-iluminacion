package com.enzo.gastosexpress

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal fun JSONObject.fnum(n: String): Double? =
    optJSONObject(n)?.let { it.optString("doubleValue", it.optString("integerValue", "")).toDoubleOrNull() }
internal fun JSONObject.fstr(n: String): String? = optJSONObject(n)?.optString("stringValue")?.takeIf { it.isNotEmpty() }
internal fun JSONObject.fbool(n: String): Boolean? = optJSONObject(n)?.let { if (it.has("booleanValue")) it.optBoolean("booleanValue") else null }

object Programar {
    /** Revisa el dólar cada ~15 min (el mínimo que permite Android). Sobrevive a reinicios del celular. */
    fun dolar(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<DolarWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork("dolar", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Dispara un aviso ahora mismo para probar (ignora el umbral). */
    fun probarDolar(ctx: Context) {
        WorkManager.getInstance(ctx.applicationContext)
            .enqueue(OneTimeWorkRequestBuilder<DolarWorker>().setInputData(workDataOf("forzar" to true)).build())
    }
}

/**
 * Avisa cuando el dólar sube o baja más que el umbral elegido en la web (ajustes/alerta),
 * contando cuánto cambia el valor de tus dólares (ajustes/ahorro_usd).
 */
class DolarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        // Mantiene fresco el widget (cotizaciones, cierre de ayer y tus dólares) aunque no haya aviso que mandar.
        DolarData.actualizar(ctx)
        GastoWidget.refresh(ctx)
        val forzar = inputData.getBoolean("forzar", false)
        var activa = true
        var casa = "blue"
        var tipo = "compra"
        var umbral = 1.0
        var usdAhorro = 0.0
        try {
            if (Session.hasSession(ctx)) {
                Api.getDoc(ctx, "ajustes/alerta")?.optJSONObject("fields")?.let { f ->
                    activa = f.fbool("activa") ?: true
                    casa = f.fstr("casa") ?: casa
                    tipo = f.fstr("tipo") ?: tipo
                    umbral = f.fnum("umbralPct") ?: umbral
                }
                Api.getDoc(ctx, "ajustes/ahorro_usd")?.optJSONObject("fields")?.let { usdAhorro = it.fnum("usd") ?: 0.0 }
            }
        } catch (e: Exception) {
            // Sin sesión o sin permiso: se usan los valores por defecto (dólar blue, 1 %).
        }
        if (!activa && !forzar) return@withContext Result.success()

        val ahora = try { Api.dolar(casa).optDouble(tipo, 0.0) } catch (e: Exception) { return@withContext Result.success() }
        if (ahora <= 0.0) return@withContext Result.success()

        val prefs = ctx.getSharedPreferences("dolar", Context.MODE_PRIVATE)
        val key = "ref_${casa}_$tipo"
        val ref = prefs.getString(key, null)?.toDoubleOrNull() ?: 0.0
        if (ref <= 0.0) prefs.edit().putString(key, ahora.toString()).apply() // primera lectura: queda de referencia
        val pct = if (ref > 0.0) (ahora / ref - 1) * 100 else 0.0
        if (!forzar && abs(pct) < umbral) return@withContext Result.success()

        val nombre = casa.replaceFirstChar { it.uppercase() }.let { if (casa == "bolsa") "MEP" else if (casa == "contadoconliqui") "CCL" else it }
        val sube = pct >= 0
        val signo = String.format(java.util.Locale("es", "AR"), "%+.1f%%", pct)
        val titulo = if (forzar && abs(pct) < umbral) "Dólar $nombre ($tipo): ${pesos(ahora.toLong())}"
        else "${if (sube) "▲ Subió" else "▼ Bajó"} el dólar $nombre $signo"
        val dif = usdAhorro * (ahora - (if (ref > 0.0) ref else ahora))
        val texto = buildString {
            if (ref > 0.0) append("${pesos(ref.toLong())} → ${pesos(ahora.toLong())}. ") else append("Hoy: ${pesos(ahora.toLong())}. ")
            if (usdAhorro > 0.0) {
                if (abs(dif) >= 1.0) append("Tus US$ ${usdAhorro.toLong()} ${if (sube) "valen" else "ahora valen"} ${pesos(abs(dif).toLong())} ${if (sube) "más" else "menos"}.")
                else append("Tus US$ ${usdAhorro.toLong()} valen ${pesos((usdAhorro * ahora).toLong())}.")
            }
        }
        // Canal nuevo con importancia alta: salta en pantalla y se queda hasta que la saques.
        ctx.getSystemService(Context.NOTIFICATION_SERVICE).let { (it as android.app.NotificationManager).deleteNotificationChannel("dolar") }
        Avisos.notificar(ctx, 7000 + (casa.hashCode() and 0x3ff), titulo, texto, "dolar_alertas", "Aviso del dólar", alta = true, persistente = true)
        prefs.edit().putString(key, ahora.toString()).apply()
        Result.success()
    }
}
