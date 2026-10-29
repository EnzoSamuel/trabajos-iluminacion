package com.enzo.gastosexpress

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Widget redimensionable, con dos formas según el alto que le des:
 *  - chico (~4x2): atajos de gasto + barra del dólar (un toque cambia de tipo de dólar).
 *  - grande (pantalla completa): panel del dólar con selector de tipo, flecha verde/roja y tus dólares en pesos;
 *    abajo, categorías y un botón para anotar el gasto con el teclado normal del celular y enviarlo a la página.
 * (Android no permite campos de texto dentro de un widget: el monto se escribe en una ventanita.)
 */
class GastoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { actualizar(context, manager, it) }
        buscarDolar(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        actualizar(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACCION_DOLAR) {
            val d = intent.getStringExtra("d") ?: "refresh"
            when {
                d == "ciclo" -> DolarData.siguiente(context)
                d.startsWith("casa:") -> DolarData.setCasa(context, d.removePrefix("casa:"))
            }
            refresh(context) // se dibuja ya con lo guardado…
            val reciente = System.currentTimeMillis() - DolarData.leidoMs(context) < 30_000
            if (d == "refresh" || !reciente) buscarDolar(context, goAsync()) // …y se actualiza desde internet
            else goAsync().finish()
        } else super.onReceive(context, intent)
    }

    companion object {
        private const val ACCION_DOLAR = "com.enzo.gastosexpress.DOLAR_AHORA"
        private const val ALTO_GRANDE_DP = 300

        private val CATS = listOf("Comida", "Nafta", "Materiales", "Herramientas", "Viáticos", "Servicios", "Alquiler", "Otro")
        private val CAT_IDS = listOf(R.id.cat_0, R.id.cat_1, R.id.cat_2, R.id.cat_3, R.id.cat_4, R.id.cat_5, R.id.cat_6, R.id.cat_7)
        private val DOLAR_IDS = listOf(R.id.dc_0, R.id.dc_1, R.id.dc_2, R.id.dc_3, R.id.dc_4, R.id.dc_5, R.id.dc_6)
        private val ATAJOS = mapOf(
            R.id.btn_comida to "Comida", R.id.btn_nafta to "Nafta", R.id.btn_materiales to "Materiales",
            R.id.btn_otro to "Otro", R.id.btn_nuevo to "",
        )

        /** Autotest: infla y aplica las dos formas del widget. */
        fun probarVistas(ctx: Context): String {
            val padre = android.widget.FrameLayout(ctx)
            fun intentar(nombre: String, f: () -> RemoteViews) =
                try { f().apply(ctx, padre); "$nombre=ok" } catch (e: Exception) { "$nombre=ERROR ${e.javaClass.simpleName}: ${e.message}" }
            return listOf(intentar("chico") { chico(ctx) }, intentar("grande") { grande(ctx) }).joinToString(" ")
        }

        /** Autotest: dibuja el widget grande (360x600 dp) en un PNG dentro del almacenamiento privado de la app. */
        fun guardarVistaPrevia(ctx: Context): String = try {
            val d = ctx.resources.displayMetrics.density
            val w = (360 * d).toInt(); val h = (600 * d).toInt()
            val vista = grande(ctx).apply(ctx, android.widget.FrameLayout(ctx))
            vista.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            vista.layout(0, 0, w, h)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val lienzo = android.graphics.Canvas(bmp).also { it.drawColor(0xFF1B3A2A.toInt()) }
            vista.draw(lienzo)
            java.io.File(ctx.cacheDir, "vista_grande.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            "png=ok"
        } catch (e: Exception) { "png=ERROR ${e.message}" }

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            m.getAppWidgetIds(ComponentName(ctx, GastoWidget::class.java)).forEach { actualizar(ctx, m, it) }
        }

        /** Trae las cotizaciones en segundo plano y vuelve a dibujar el widget. */
        private fun buscarDolar(ctx: Context, pendiente: android.content.BroadcastReceiver.PendingResult) {
            Thread {
                try { DolarData.actualizar(ctx) } finally { refresh(ctx); pendiente.finish() }
            }.start()
        }

        private fun accionDolar(ctx: Context, d: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 7100 + d.hashCode(), Intent(ctx, GastoWidget::class.java).setAction(ACCION_DOLAR).putExtra("d", d),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** Abre la ventanita para anotar un gasto (categoría ya elegida, o vacía). */
        private fun anotar(ctx: Context, categoria: String, codigo: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, codigo, Intent(ctx, GastoActivity::class.java).putExtra("categoria", categoria)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** El título del widget abre el panel a pantalla completa de la app. */
        private fun abrirPanel(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 7200, Intent(ctx, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun actualizar(ctx: Context, m: AppWidgetManager, id: Int) {
            val altoDp = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            m.updateAppWidget(id, if (altoDp >= ALTO_GRANDE_DP) grande(ctx) else chico(ctx))
        }

        private fun chico(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_gastos)
            v.setOnClickPendingIntent(R.id.w_titulo, abrirPanel(ctx))
            v.setTextViewText(R.id.txt_hoy, "Hoy: " + pesos(Hoy.total(ctx)))
            v.setTextViewText(R.id.btn_dolar, DolarData.corto(ctx))
            v.setOnClickPendingIntent(R.id.btn_dolar, accionDolar(ctx, "ciclo")) // un toque: siguiente tipo de dólar
            ATAJOS.forEach { (viewId, c) -> v.setOnClickPendingIntent(viewId, anotar(ctx, c, viewId)) }
            return v
        }

        private fun grande(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_gastos_completo)
            v.setOnClickPendingIntent(R.id.w_titulo, abrirPanel(ctx))
            v.setTextViewText(R.id.f_hoy, "Hoy  ${pesos(Hoy.total(ctx))}   ·   Mes  ${pesos(Hoy.mes(ctx))}")

            // Dólar
            val sel = DolarData.casa(ctx)
            DolarData.CASAS.forEachIndexed { i, (casa, nombre) ->
                v.setTextViewText(DOLAR_IDS[i], nombre)
                v.setInt(DOLAR_IDS[i], "setBackgroundResource", if (casa == sel) R.drawable.chip_sel else R.drawable.chip_bg)
                v.setOnClickPendingIntent(DOLAR_IDS[i], accionDolar(ctx, "casa:$casa"))
            }
            v.setOnClickPendingIntent(R.id.dc_refresh, accionDolar(ctx, "refresh"))
            v.setTextViewText(R.id.d_valor, DolarData.valorGrande(ctx))
            v.setTextViewText(R.id.d_detalle, DolarData.detalle(ctx))
            v.setTextViewText(R.id.d_ahorro, DolarData.ahorro(ctx))
            v.setTextViewText(R.id.d_hora, DolarData.hora(ctx).let { if (it.isEmpty()) "" else "● act. $it" })

            // Gasto: cada categoría abre la ventanita con el teclado normal
            CAT_IDS.forEachIndexed { i, vid ->
                v.setTextViewText(vid, if (CATS[i] == "Herramientas") "Herram." else CATS[i])
                v.setOnClickPendingIntent(vid, anotar(ctx, CATS[i], 7300 + i))
            }
            v.setOnClickPendingIntent(R.id.btn_anotar, anotar(ctx, "", 7399))
            return v
        }
    }
}
