package com.enzo.gastosexpress

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import com.enzo.gastosexpress.Borrador.nota

/** Diálogo mínimo para escribir la descripción del gasto que se está armando en el widget. */
class NotaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nota)
        setFinishOnTouchOutside(true)
        val campo = findViewById<EditText>(R.id.in_nota)
        campo.setText(nota)
        campo.setSelection(campo.text.length)
        findViewById<Button>(R.id.btn_nota_ok).setOnClickListener {
            nota = campo.text.toString().trim()
            GastoWidget.refresh(this)
            finish()
        }
    }
}
