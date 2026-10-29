package com.enzo.gastosexpress

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /** Suma al total de hoy / del mes solo si la fecha del gasto cae en hoy / en este mes. */
    fun sumar(c: Context, monto: Long, fechaMs: Long) {
        val hoy = LocalDate.now().toString()
        val dia = Instant.ofEpochMilli(fechaMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val e = p(c).edit()
        if (dia == hoy) e.putString("fecha", hoy).putLong("total", total(c) + monto)
        if (dia.take(7) == hoy.take(7)) e.putString("mesKey", hoy.take(7)).putLong("mesTotal", mes(c) + monto)
        e.apply()
    }
}

/** Últimos gastos enviados desde el widget. */
object Hist {
    private fun p(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)
    private val hora = DateTimeFormatter.ofPattern("HH:mm")
    private val dia = DateTimeFormatter.ofPattern("dd/MM")

    fun add(c: Context, cat: String, monto: Long, fechaMs: Long) {
        val f = Instant.ofEpochMilli(fechaMs).atZone(ZoneId.systemDefault())
        val cuando = if (f.toLocalDate() == LocalDate.now()) f.format(hora) else f.format(dia)
        val linea = "$cat · ${pesos(monto)} · $cuando"
        val todas = (listOf(linea) + lista(c)).take(5)
        p(c).edit().putString("items", todas.joinToString("\n")).apply()
    }

    fun lista(c: Context): List<String> =
        (p(c).getString("items", "") ?: "").split("\n").filter { it.isNotBlank() }
}

/** "2026-10-25" -> "25/10/2026" */
fun fechaCorta(iso: String): String = iso.split("-").let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else iso }

object Avisos {
    fun toast(c: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * [alta] = salta en pantalla (heads-up) con sonido/vibración; [persistente] = se queda en la barra
     * hasta que la saques vos (si no, se borra sola a los 8 s).
     */
    fun notificar(
        c: Context, id: Int, titulo: String, texto: String,
        canal: String = "gastos", canalNombre: String = "Gastos Express",
        alta: Boolean = false, persistente: Boolean = false,
    ) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(canal, canalNombre, if (alta) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val abrir = PendingIntent.getActivity(
            c, id, Intent(c, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(c, canal)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(if (alta) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (alta) NotificationCompat.DEFAULT_ALL else 0)
            .setContentIntent(abrir)
            .setAutoCancel(true)
        if (!persistente) b.setTimeoutAfter(8000)
        NotificationManagerCompat.from(c).notify(id, b.build())
    }
}

object Envio {
    /** Encola el envío en segundo plano. Si no hay internet, WorkManager reintenta solo. */
    fun enviar(ctx: Context, categoria: String, monto: Long, descripcion: String, fechaIso: String = "") {
        // Fecha elegida + hora actual; sin fecha = ahora. Se fija acá para que un reintento conserve la hora original.
        val ahora = System.currentTimeMillis()
        val fechaMs = if (fechaIso.isBlank()) ahora else
            LocalDate.parse(fechaIso).atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val req = OneTimeWorkRequestBuilder<GastoWorker>()
            .setInputData(workDataOf("categoria" to categoria, "monto" to monto, "descripcion" to descripcion, "fechaMs" to fechaMs))
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
        val fechaMs = inputData.getLong("fechaMs", System.currentTimeMillis())
        val nid = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val ctx = applicationContext
        try {
            val code = Api.postGasto(ctx, categoria, monto, descripcion, fechaMs)
            when {
                code in 200..299 -> {
                    Hoy.sumar(ctx, monto, fechaMs)
                    Hist.add(ctx, categoria, monto, fechaMs)
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
