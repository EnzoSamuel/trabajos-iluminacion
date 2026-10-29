package com.enzo.gastosexpress

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.enzo.gastosexpress.Borrador.cat
import com.enzo.gastosexpress.Borrador.monto
import com.enzo.gastosexpress.Borrador.nota

/**
 * Widget redimensionable. Tamaño chico (4x2): atajos que abren el formulario emergente.
 * Tamaño grande (ocupa la pantalla): categorías + teclado numérico + enviar, todo dentro del widget.
 * (Android no permite campos de texto en widgets, por eso el monto se arma con teclas.)
 */
class GastoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { actualizar(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        actualizar(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACCION) {
            manejar(context, intent.getStringExtra("k") ?: "")
            refresh(context)
        } else super.onReceive(context, intent)
    }

    companion object {
        private const val ACCION = "com.enzo.gastosexpress.TECLA"
        private const val ALTO_GRANDE_DP = 260

        private val CATS = listOf("Comida", "Nafta", "Materiales", "Herramientas", "Viáticos", "Servicios", "Alquiler", "Otro")
        private val CAT_IDS = listOf(R.id.cat_0, R.id.cat_1, R.id.cat_2, R.id.cat_3, R.id.cat_4, R.id.cat_5, R.id.cat_6, R.id.cat_7)
        private val TECLAS = mapOf(
            R.id.key_0 to "0", R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3", R.id.key_4 to "4",
            R.id.key_5 to "5", R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9",
            R.id.key_000 to "000", R.id.key_del to "DEL",
        )
        private val ATAJOS = mapOf(
            R.id.btn_comida to "Comida", R.id.btn_nafta to "Nafta", R.id.btn_materiales to "Materiales",
            R.id.btn_otro to "Otro", R.id.btn_nuevo to "",
        )

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            m.getAppWidgetIds(ComponentName(ctx, GastoWidget::class.java)).forEach { actualizar(ctx, m, it) }
        }

        private fun manejar(ctx: Context, k: String) {
            when {
                k.length == 1 && k[0].isDigit() -> {
                    val n = ctx.monto
                    if (n.length < 9 && !(n.isEmpty() && k == "0")) ctx.monto = n + k
                }
                k == "000" -> if (ctx.monto.isNotEmpty() && ctx.monto.length <= 6) ctx.monto = ctx.monto + "000"
                k == "DEL" -> ctx.monto = ctx.monto.dropLast(1)
                k == "CLEAR" -> Borrador.limpiar(ctx)
                k.startsWith("CAT:") -> { val c = k.removePrefix("CAT:"); ctx.cat = if (ctx.cat == c) "" else c }
                k == "SEND" -> enviar(ctx)
            }
        }

        private fun enviar(ctx: Context) {
            if (!Session.hasSession(ctx)) {
                Avisos.toast(ctx, "Primero iniciá sesión en la app Gastos Express")
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val m = ctx.monto.toLongOrNull() ?: 0L
            if (m <= 0L) { Avisos.toast(ctx, "Ingresá un monto con el teclado"); return }
            Envio.enviar(ctx, ctx.cat.ifEmpty { "Otro" }, m, ctx.nota)
            Avisos.toast(ctx, "Enviando gasto…")
            Borrador.limpiar(ctx)
        }

        private fun tecla(ctx: Context, k: String): PendingIntent {
            val i = Intent(ctx, GastoWidget::class.java).setAction(ACCION).putExtra("k", k)
            return PendingIntent.getBroadcast(ctx, k.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun actualizar(ctx: Context, m: AppWidgetManager, id: Int) {
            val altoDp = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val v = if (altoDp >= ALTO_GRANDE_DP) grande(ctx) else chico(ctx)
            m.updateAppWidget(id, v)
        }

        private fun chico(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_gastos)
            v.setTextViewText(R.id.txt_hoy, "Hoy: " + pesos(Hoy.total(ctx)))
            ATAJOS.forEach { (viewId, c) ->
                val i = Intent(ctx, GastoActivity::class.java)
                    .putExtra("categoria", c)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(viewId, PendingIntent.getActivity(
                    ctx, viewId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return v
        }

        private fun grande(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_gastos_grande)
            v.setTextViewText(R.id.f_hoy, "Hoy  " + pesos(Hoy.total(ctx)))
            v.setTextViewText(R.id.f_mes, "Mes  " + pesos(Hoy.mes(ctx)))

            val n = ctx.monto.toLongOrNull() ?: 0L
            v.setTextViewText(R.id.f_monto, pesos(n))
            v.setTextViewText(R.id.f_cat, "Categoría: " + ctx.cat.ifEmpty { "elegí una" })
            v.setTextViewText(R.id.btn_nota, if (ctx.nota.isEmpty()) "+ Nota" else "Nota: " + ctx.nota.take(14))
            v.setOnClickPendingIntent(R.id.f_monto, tecla(ctx, "CLEAR"))

            CAT_IDS.forEachIndexed { i, vid ->
                v.setTextViewText(vid, CATS[i])
                v.setInt(vid, "setBackgroundResource", if (ctx.cat == CATS[i]) R.drawable.chip_sel else R.drawable.chip_bg)
                v.setOnClickPendingIntent(vid, tecla(ctx, "CAT:" + CATS[i]))
            }
            TECLAS.forEach { (vid, k) -> v.setOnClickPendingIntent(vid, tecla(ctx, k)) }
            v.setOnClickPendingIntent(R.id.btn_send, tecla(ctx, "SEND"))
            v.setOnClickPendingIntent(R.id.btn_nota, PendingIntent.getActivity(
                ctx, 7001, Intent(ctx, NotaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val h = Hist.lista(ctx)
            v.setTextViewText(R.id.hist_0, h.getOrNull(0) ?: "Todavía no cargaste gastos")
            v.setTextViewText(R.id.hist_1, h.getOrNull(1) ?: "")
            v.setTextViewText(R.id.hist_2, h.getOrNull(2) ?: "")
            return v
        }
    }
}
