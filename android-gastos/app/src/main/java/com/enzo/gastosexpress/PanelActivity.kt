package com.enzo.gastosexpress

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Panel a pantalla completa (no depende del launcher): dólar en vivo con flecha verde/roja y selector de
 * tipo, tus dólares en pesos, y el formulario de gasto con teclado normal. Se refresca solo cada 20 s
 * mientras está abierto.
 */
class PanelActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val categorias = listOf("Comida", "Nafta", "Materiales", "Herramientas", "Viáticos", "Servicios", "Alquiler", "Otro")
    private var categoria = ""
    private var fecha: LocalDate? = null
    private var ultimoValor: Double? = null
    private var buscando = false

    private lateinit var valor: TextView
    private lateinit var detalle: TextView
    private lateinit var tick: TextView
    private lateinit var ahorro: TextView
    private lateinit var hora: TextView
    private lateinit var dolarChips: LinearLayout
    private lateinit var catChips: LinearLayout
    private lateinit var monto: EditText
    private lateinit var nota: EditText
    private lateinit var btnFecha: Button
    private lateinit var hoy: TextView

    private val refresco = object : Runnable {
        override fun run() { buscar(); h.postDelayed(this, 20_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.hasSession(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_panel)
        val raiz = findViewById<View>(R.id.panel_root)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        valor = findViewById(R.id.p_valor); detalle = findViewById(R.id.p_detalle); tick = findViewById(R.id.p_tick)
        ahorro = findViewById(R.id.p_ahorro); hora = findViewById(R.id.p_hora); hoy = findViewById(R.id.p_hoy)
        dolarChips = findViewById(R.id.p_dolar_chips); catChips = findViewById(R.id.p_cat_chips)
        monto = findViewById(R.id.p_monto); nota = findViewById(R.id.p_nota)
        btnFecha = findViewById(R.id.p_fecha)
        findViewById<TextView>(R.id.p_ajustes).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        btnFecha.setOnClickListener { elegirFecha() }
        findViewById<Button>(R.id.p_enviar).setOnClickListener { enviar() }
        intent.getStringExtra("categoria")?.takeIf { it.isNotEmpty() }?.let { categoria = it }

        pintarCategorias()
        pintarDolar()
    }

    override fun onResume() {
        super.onResume()
        if (Session.hasSession(this)) {
            Programar.dolar(this)
            h.post(refresco)
        }
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(refresco)
    }

    /** Pide las cotizaciones en segundo plano y vuelve a pintar; marca si cambió desde la lectura anterior. */
    private fun buscar() {
        if (buscando) return
        buscando = true
        val casa = DolarData.casa(this)
        Thread {
            val antes = DolarData.actual(this, casa)?.compra
            DolarData.actualizar(this)
            runOnUiThread {
                buscando = false
                val ahora = DolarData.actual(this, DolarData.casa(this))?.compra
                if (antes != null && ahora != null && DolarData.casa(this) == casa && abs(ahora - antes) > 0.001) mostrarTick(ahora - antes)
                pintarDolar()
                GastoWidget.refresh(this)
            }
        }.start()
    }

    private fun mostrarTick(dif: Double) {
        val col = if (dif > 0) DolarData.VERDE else DolarData.ROJO
        val t = SpannableStringBuilder("${if (dif > 0) "▲" else "▼"} ${if (dif > 0) "+" else "−"}${pesos(abs(dif).toLong())} desde la lectura anterior")
        t.setSpan(ForegroundColorSpan(col), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tick.text = t
        tick.alpha = 1f
        tick.animate().alpha(0.35f).setDuration(6000).start()
    }

    private fun pintarDolar() {
        valor.text = DolarData.valorGrande(this)
        detalle.text = DolarData.detalle(this)
        ahorro.text = DolarData.ahorro(this)
        hoy.text = "Hoy " + pesos(Hoy.total(this))
        hora.text = if (DolarData.hayError(this)) "sin conexión" else DolarData.hora(this).let { if (it.isEmpty()) "…" else "● actualizado $it" }
        val sel = DolarData.casa(this)
        val filas = DolarData.CASAS.chunked(4)
        dolarChips.removeAllViews()
        filas.forEach { fila ->
            val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.forEach { (casa, nombre) ->
                ll.addView(chip(nombre, casa == sel) {
                    DolarData.setCasa(this, casa)
                    tick.text = ""
                    pintarDolar()
                    buscar()
                })
            }
            // relleno para que la última fila no estire los botones
            repeat(4 - fila.size) { ll.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            dolarChips.addView(ll)
        }
    }

    private fun pintarCategorias() {
        catChips.removeAllViews()
        categorias.chunked(4).forEach { fila ->
            val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fila.forEach { c ->
                ll.addView(chip(c, c == categoria) { categoria = if (categoria == c) "" else c; pintarCategorias() })
            }
            catChips.addView(ll)
        }
    }

    private fun chip(texto: String, sel: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = texto
        setTextColor(DolarData.BLANCO)
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        setBackgroundResource(if (sel) R.drawable.chip_sel else R.drawable.chip_bg)
        layoutParams = LinearLayout.LayoutParams(0, (40 * resources.displayMetrics.density).toInt(), 1f).apply {
            setMargins(3, 3, 3, 3)
        }
        setOnClickListener { onClick() }
    }

    private fun elegirFecha() {
        val base = fecha ?: LocalDate.now()
        DatePickerDialog(this, { _, y, m, d ->
            val e = LocalDate.of(y, m + 1, d)
            fecha = if (e == LocalDate.now()) null else e
            btnFecha.text = fecha?.let { "Fecha: " + fechaCorta(it.toString()) } ?: "Fecha: hoy"
        }, base.year, base.monthValue - 1, base.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    private fun enviar() {
        val m = monto.text.toString().trim().toLongOrNull() ?: 0L
        if (m <= 0L) { monto.error = "Ingresá un monto"; monto.requestFocus(); return }
        Envio.enviar(this, categoria.ifEmpty { "Otro" }, m, nota.text.toString().trim(), fecha?.toString() ?: "")
        Toast.makeText(this, "Enviando gasto…", Toast.LENGTH_SHORT).show()
        monto.setText(""); nota.setText(""); categoria = ""; fecha = null
        btnFecha.text = "Fecha: hoy"
        pintarCategorias()
        h.postDelayed({ pintarDolar() }, 4000)
    }
}
