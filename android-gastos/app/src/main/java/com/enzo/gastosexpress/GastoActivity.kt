package com.enzo.gastosexpress

import android.app.Activity
import android.app.DatePickerDialog
import java.time.LocalDate
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/** Formulario emergente: Categoría, Monto y Descripción. Envía en segundo plano y se cierra. */
class GastoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.hasSession(this)) {
            Toast.makeText(this, "Primero iniciá sesión en la app Gastos Express", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_gasto)
        setFinishOnTouchOutside(true)

        val categoria = findViewById<EditText>(R.id.in_categoria)
        val monto = findViewById<EditText>(R.id.in_monto)
        val descripcion = findViewById<EditText>(R.id.in_descripcion)
        val cat = intent.getStringExtra("categoria") ?: ""
        categoria.setText(cat)
        (if (cat.isBlank()) categoria else monto).requestFocus()

        var fecha: LocalDate? = null // null = hoy, con la hora actual
        val btnFecha = findViewById<Button>(R.id.btn_fecha_form)
        btnFecha.setOnClickListener {
            val base = fecha ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, d ->
                val elegida = LocalDate.of(y, m + 1, d)
                fecha = if (elegida == LocalDate.now()) null else elegida
                btnFecha.text = fecha?.let { "Fecha: " + fechaCorta(it.toString()) } ?: "Fecha: hoy"
            }, base.year, base.monthValue - 1, base.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }

        findViewById<Button>(R.id.btn_cancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_guardar).setOnClickListener {
            val c = categoria.text.toString().trim().ifEmpty { "Otro" }
            val m = monto.text.toString().trim().toLongOrNull() ?: 0L
            if (m <= 0L) {
                monto.error = "Ingresá un monto"
                monto.requestFocus()
                return@setOnClickListener
            }
            Envio.enviar(this, c, m, descripcion.text.toString().trim(), fecha?.toString() ?: "")
            Toast.makeText(this, "Enviando gasto…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
