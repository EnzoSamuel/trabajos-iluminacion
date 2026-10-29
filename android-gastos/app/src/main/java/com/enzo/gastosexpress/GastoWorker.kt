package com.enzo.gastosexpress

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

fun pesos(n: Long): String =
    NumberFormat.getCurrencyInstance(Locale("es", "AR")).apply { maximumFractionDigits = 0 }.format(n)

/** Totales de hoy y del mes de lo cargado desde este celular (solo para mostrar en el widget). */
object Hoy {
    private fun p(c: Context) = c.getSharedPreferences("estado", Context.MODE_PRIVATE)

    fun total(c: Context): Long =
        if (p(c).getString("fecha", "") == LocalDate.now().toString()) p(c).getLong("total", 0L) else 0L

    fun mes(c: Context): Long =
        if (p(c).getString("mesKey", "") == LocalDate.now().toString().take(7)) p(c).getLong("mesTotal", 0L) else 0L

    fun sumar(c: Context, monto: Long) {
        val hoy = LocalDate.now().toString()
        p(c).edit()
            .putString("fecha", hoy).putLong("total", total(c) + monto)
            .putString("mesKey", hoy.take(7)).putLong("mesTotal", mes(c) + monto)
            .apply()
    }
}

/** Últimos gastos enviados desde el widget. */
object Hist {
    private fun p(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)
    private val hora = DateTimeFormatter.ofPattern("HH:mm")

    fun add(c: Context, cat: String, monto: Long) {
        val linea = "$cat · ${pesos(monto)} · ${LocalTime.now().format(hora)}"
        val todas = (listOf(linea) + lista(c)).take(5)
        p(c).edit().putString("items", todas.joinToString("\n")).apply()
    }

    fun lista(c: Context): List<String> =
        (p(c).getString("items", "") ?: "").split("\n").filter { it.isNotBlank() }
}

/** Borrador que se arma tocando el widget grande (categoría, monto y nota). */
object Borrador {
    private fun p(c: Context) = c.getSharedPreferences("borrador", Context.MODE_PRIVATE)
    var Context.cat: String
        get() = p(this).getString("cat", "") ?: ""
        set(v) { p(this).edit().putString("cat", v).apply() }
    var Context.monto: String
        get() = p(this).getString("monto", "") ?: ""
        set(v) { p(this).edit().putString("monto", v).apply() }
    var Context.nota: String
        get() = p(this).getString("nota", "") ?: ""
        set(v) { p(this).edit().putString("nota", v).apply() }

    fun limpiar(c: Context) = p(c).edit().clear().apply()
}

object Avisos {
    private const val CANAL = "gastos"

    fun toast(c: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    fun notificar(c: Context, id: Int, titulo: String, texto: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Gastos Express", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(c, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()
        NotificationManagerCompat.from(c).notify(id, n)
    }
}

object Envio {
    /** Encola el envío en segundo plano. Si no hay internet, WorkManager reintenta solo. */
    fun enviar(ctx: Context, categoria: String, monto: Long, descripcion: String) {
        val req = OneTimeWorkRequestBuilder<GastoWorker>()
            .setInputData(workDataOf("categoria" to categoria, "monto" to monto, "descripcion" to descripcion))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueue(req)
    }
}

class GastoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val categoria = inputData.getString("categoria") ?: "Otro"
        val monto = inputData.getLong("monto", 0L)
        val descripcion = inputData.getString("descripcion") ?: ""
        val nid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val ctx = applicationContext
        try {
            val code = Api.postGasto(ctx, categoria, monto, descripcion)
            when {
                code in 200..299 -> {
                    Hoy.sumar(ctx, monto)
                    Hist.add(ctx, categoria, monto)
                    GastoWidget.refresh(ctx)
                    Avisos.toast(ctx, "Gasto registrado con éxito")
                    Avisos.notificar(ctx, nid, "Gasto registrado con éxito", "$categoria · ${pesos(monto)}")
                    Result.success()
                }
                code == 401 || code == 403 -> {
                    Avisos.toast(ctx, "No se pudo registrar el gasto (sin permiso, HTTP $code)")
                    Avisos.notificar(ctx, nid, "No se pudo registrar el gasto",
                        "Sin permiso (HTTP $code). Revisá la sesión en la app y las reglas de Firestore.")
                    Result.failure()
                }
                runAttemptCount < 5 -> Result.retry()
                else -> {
                    Avisos.notificar(ctx, nid, "No se pudo registrar el gasto", "Error del servidor (HTTP $code).")
                    Result.failure()
                }
            }
        } catch (e: IOException) {
            if (runAttemptCount < 8) Result.retry() else {
                Avisos.notificar(ctx, nid, "Gasto sin enviar", "No hay conexión. Volvé a cargarlo.")
                Result.failure()
            }
        } catch (e: ApiError) {
            Avisos.notificar(ctx, nid, "No se pudo registrar el gasto", e.message ?: "Error")
            Result.failure()
        }
    }
}
