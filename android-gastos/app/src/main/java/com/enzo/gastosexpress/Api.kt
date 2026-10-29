package com.enzo.gastosexpress

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** Datos públicos del proyecto Firebase (los mismos que usa la web). */
object Config {
    const val API_KEY = "AIzaSyCo-COK-hq72MPXowd07sHSmNC6q4bJIR0"
    const val PROJECT_ID = "trabajos-iluminacion"
    const val OWNER_EMAIL = "enzoenzoca07@gmail.com"
}

/** Sesión guardada en el almacenamiento privado de la app. Nunca se guarda la contraseña. */
object Session {
    private fun p(c: Context) = c.getSharedPreferences("sesion", Context.MODE_PRIVATE)

    fun hasSession(c: Context) = !p(c).getString("refresh", null).isNullOrEmpty()
    fun email(c: Context): String = p(c).getString("email", "") ?: ""
    fun refresh(c: Context): String = p(c).getString("refresh", "") ?: ""
    fun idToken(c: Context): String = p(c).getString("id", "") ?: ""
    fun expiresAt(c: Context): Long = p(c).getLong("exp", 0L)

    fun save(c: Context, email: String?, id: String, refresh: String, expiresInSec: Long) {
        p(c).edit().apply {
            if (email != null) putString("email", email)
            putString("id", id)
            putString("refresh", refresh)
            putLong("exp", System.currentTimeMillis() + expiresInSec * 1000)
        }.apply()
    }

    fun clear(c: Context) = p(c).edit().clear().apply()
}

class ApiError(val code: Int, message: String) : Exception(message)

object Api {
    private const val SIGN_IN =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${Config.API_KEY}"
    private const val REFRESH =
        "https://securetoken.googleapis.com/v1/token?key=${Config.API_KEY}"
    private const val GASTOS =
        "https://firestore.googleapis.com/v1/projects/${Config.PROJECT_ID}/databases/(default)/documents/gastos"

    private fun post(url: String, body: String, contentType: String, bearer: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Inicio de sesión con la cuenta de Firebase. Solo se acepta el email del dueño. */
    fun signIn(ctx: Context, email: String, password: String) {
        if (!email.equals(Config.OWNER_EMAIL, ignoreCase = true)) throw ApiError(403, "Esta cuenta no tiene acceso.")
        val body = JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString()
        val (code, text) = post(SIGN_IN, body, "application/json")
        if (code !in 200..299) throw ApiError(code, "Email o contraseña incorrectos.")
        val j = JSONObject(text)
        Session.save(ctx, email.lowercase(), j.getString("idToken"), j.getString("refreshToken"), j.getString("expiresIn").toLong())
    }

    private fun refreshToken(ctx: Context): String {
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(Session.refresh(ctx), "UTF-8")
        val (code, text) = post(REFRESH, form, "application/x-www-form-urlencoded")
        if (code !in 200..299) throw ApiError(401, "La sesión venció. Abrí la app e iniciá sesión de nuevo.")
        val j = JSONObject(text)
        Session.save(ctx, null, j.getString("id_token"), j.getString("refresh_token"), j.getString("expires_in").toLong())
        return j.getString("id_token")
    }

    private fun token(ctx: Context): String =
        if (Session.expiresAt(ctx) - 60_000 > System.currentTimeMillis() && Session.idToken(ctx).isNotEmpty())
            Session.idToken(ctx) else refreshToken(ctx)

    private fun get(url: String, bearer: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Lee un documento de Firestore (ej. "ajustes/alerta"). Devuelve null si no existe. */
    fun getDoc(ctx: Context, path: String): JSONObject? {
        val url = "https://firestore.googleapis.com/v1/projects/${Config.PROJECT_ID}/databases/(default)/documents/$path"
        var (code, text) = get(url, token(ctx))
        if (code == 401) { val r = get(url, refreshToken(ctx)); code = r.first; text = r.second }
        if (code == 404) return null
        if (code !in 200..299) throw ApiError(code, "No se pudo leer $path (HTTP $code)")
        return JSONObject(text)
    }

    /** Todas las cotizaciones del día (blue, oficial, bolsa, contadoconliqui, cripto, mayorista, tarjeta). */
    fun dolares(): org.json.JSONArray {
        val (code, text) = get("https://dolarapi.com/v1/dolares", null)
        if (code !in 200..299) throw ApiError(code, "Cotizaciones no disponibles (HTTP $code)")
        return org.json.JSONArray(text)
    }

    /** Cierre de un tipo de dólar en una fecha puntual. Devuelve null si ese día no hay dato. */
    fun dolarDia(casa: String, dia: java.time.LocalDate): JSONObject? {
        val (code, text) = get(
            "https://api.argentinadatos.com/v1/cotizaciones/dolares/$casa/${dia.year}/${"%02d".format(dia.monthValue)}/${"%02d".format(dia.dayOfMonth)}", null)
        return if (code in 200..299) JSONObject(text) else null
    }

    /** Cotización pública de un tipo de dólar (blue, oficial, bolsa, ...). */
    fun dolar(casa: String): JSONObject {
        val (code, text) = get("https://dolarapi.com/v1/dolares/$casa", null)
        if (code !in 200..299) throw ApiError(code, "Cotización no disponible (HTTP $code)")
        return JSONObject(text)
    }

    /** Crea un documento en la colección "gastos". No toca ninguna otra colección. Devuelve el código HTTP. */
    fun postGasto(ctx: Context, categoria: String, monto: Long, descripcion: String, fechaMs: Long): Int {
        val now = System.currentTimeMillis()
        val doc = JSONObject().put("fields", JSONObject()
            .put("categoria", JSONObject().put("stringValue", categoria))
            .put("monto", JSONObject().put("doubleValue", monto.toDouble()))
            .put("descripcion", JSONObject().put("stringValue", descripcion))
            .put("fecha", JSONObject().put("timestampValue", Instant.ofEpochMilli(fechaMs).toString()))
            .put("creadoEn", JSONObject().put("integerValue", now.toString()))
            .put("origen", JSONObject().put("stringValue", "android"))).toString()
        var code = post(GASTOS, doc, "application/json", token(ctx)).first
        if (code == 401) code = post(GASTOS, doc, "application/json", refreshToken(ctx)).first // token vencido: renovar y reintentar una vez
        return code
    }
}
