package com.enzo.gastosexpress

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Pantalla de la app: inicio de sesión (una sola vez) y estado del widget. */
class MainActivity : Activity() {
    private lateinit var estado: TextView
    private lateinit var form: View
    private lateinit var email: EditText
    private lateinit var pass: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnLogout: Button
    private lateinit var btnWidget: Button
    private lateinit var btnProbar: Button
    private lateinit var btnPanel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.txt_estado)
        form = findViewById(R.id.form_login)
        email = findViewById(R.id.in_email)
        pass = findViewById(R.id.in_pass)
        btnLogin = findViewById(R.id.btn_login)
        btnLogout = findViewById(R.id.btn_logout)
        btnWidget = findViewById(R.id.btn_widget)
        email.setText(Config.OWNER_EMAIL)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (intent.getBooleanExtra("selftest", false)) android.util.Log.i("GastosSelfTest", GastoWidget.probarVistas(this) + " " + GastoWidget.guardarVistaPrevia(this))
        btnPanel = findViewById(R.id.btn_panel)
        btnPanel.setOnClickListener { startActivity(Intent(this, PanelActivity::class.java)) }
        btnProbar = findViewById(R.id.btn_probar)
        btnProbar.setOnClickListener {
            Programar.probarDolar(this)
            Toast.makeText(this, "Mandando un aviso de prueba del dólar…", Toast.LENGTH_SHORT).show()
        }
        if (Session.hasSession(this)) Programar.dolar(this)

        btnLogin.setOnClickListener { login() }
        btnLogout.setOnClickListener { Session.clear(this); GastoWidget.refresh(this); pintar() }
        btnWidget.setOnClickListener { pedirWidget() }
        pintar()
    }

    private fun pintar() {
        val ok = Session.hasSession(this)
        form.visibility = if (ok) View.GONE else View.VISIBLE
        btnLogout.visibility = if (ok) View.VISIBLE else View.GONE
        btnWidget.visibility = if (ok) View.VISIBLE else View.GONE
        btnProbar.visibility = if (ok) View.VISIBLE else View.GONE
        btnPanel.visibility = if (ok) View.VISIBLE else View.GONE
        estado.text = if (ok) "Sesión iniciada como ${Session.email(this)}.\nAgregá el widget \"Gastos Express\" a tu pantalla de inicio."
        else "Iniciá sesión una sola vez para poder enviar gastos desde el widget."
    }

    private fun login() {
        val e = email.text.toString().trim()
        val p = pass.text.toString()
        if (e.isEmpty() || p.isEmpty()) { Toast.makeText(this, "Completá email y contraseña", Toast.LENGTH_SHORT).show(); return }
        btnLogin.isEnabled = false
        btnLogin.text = "Entrando…"
        Thread {
            val error = try { Api.signIn(applicationContext, e, p); null } catch (ex: ApiError) { ex.message } catch (ex: Exception) { "Sin conexión." }
            runOnUiThread {
                btnLogin.isEnabled = true
                btnLogin.text = "Iniciar sesión"
                pass.setText("")
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show() else { GastoWidget.refresh(this); Programar.dolar(this); pintar(); startActivity(Intent(this, PanelActivity::class.java)); finish() }
            }
        }.start()
    }

    private fun pedirWidget() {
        val m = AppWidgetManager.getInstance(this)
        if (Build.VERSION.SDK_INT >= 26 && m.isRequestPinAppWidgetSupported) {
            m.requestPinAppWidget(ComponentName(this, GastoWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Mantené apretada la pantalla de inicio > Widgets > Gastos Express", Toast.LENGTH_LONG).show()
        }
    }
}
