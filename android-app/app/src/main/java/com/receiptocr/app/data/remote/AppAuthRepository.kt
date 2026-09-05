package com.receiptocr.app.data.remote

import android.content.Context
import com.receiptocr.app.model.UserProfile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val APP_API_BASE_URL = "https://receiptocr-api.somchai147258.workers.dev"
private const val AUTH_PREFS = "app_auth"

data class AppLoginResult(val user: UserProfile, val token: String)

object AppAuthRepository {
    fun login(context: Context, username: String, password: String): AppLoginResult {
        val c = URL("$APP_API_BASE_URL/api/app/login").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 7000
        c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { out ->
            out.write(JSONObject().put("username", username.trim()).put("password", password).toString().toByteArray())
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                throw IllegalStateException(if (err == "LOGIN_FAILED") "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง" else "เข้าสู่ระบบไม่สำเร็จ")
            }
            val o = JSONObject(body)
            val token = o.getString("token")
            val user = UserProfile(o.getString("employeeCode"), o.getString("fullName"), o.optString("username"))
            context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit()
                .putString("token", token)
                .putString("employeeCode", user.employeeCode)
                .putString("fullName", user.fullName)
                .putString("username", user.username)
                .commit()
            AppLoginResult(user, token)
        } finally {
            c.disconnect()
        }
    }

    fun token(context: Context): String =
        context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).getString("token", "").orEmpty()

    fun restoreUser(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        val employeeCode = prefs.getString("employeeCode", "").orEmpty()
        if (token.isBlank() || employeeCode.isBlank()) return null
        return UserProfile(
            employeeCode = employeeCode,
            fullName = prefs.getString("fullName", "").orEmpty().ifBlank { employeeCode },
            username = prefs.getString("username", "").orEmpty()
        )
    }

    fun logout(context: Context) {
        context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
