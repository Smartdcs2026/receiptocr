package com.receiptocr.app.util

import android.content.Context
import com.receiptocr.app.model.AppScreen
import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.model.WorkStatus
import org.json.JSONObject
import java.time.LocalDate

private const val UI_SESSION_PREFS = "ui_session"

/** เก็บหน้าที่กำลังทำและร้านปัจจุบันไว้ เพื่อให้กลับจากกล้องแล้วไม่หลุดออกจากงาน */
object AppUiSession {
    fun save(
        context: Context,
        screen: AppScreen,
        selectedDate: LocalDate,
        work: WorkItem?
    ) {
        val editor = context.getSharedPreferences(UI_SESSION_PREFS, Context.MODE_PRIVATE).edit()
            .putString("screen", screen.name)
            .putString("selectedDate", selectedDate.toString())
        if (work == null) editor.remove("work")
        else editor.putString("work", work.toJson().toString())
        editor.commit()
    }

    fun restoreScreen(context: Context, loggedIn: Boolean): AppScreen {
        if (!loggedIn) return AppScreen.LOGIN
        val raw = context.getSharedPreferences(UI_SESSION_PREFS, Context.MODE_PRIVATE)
            .getString("screen", AppScreen.HOME.name).orEmpty()
        return runCatching { AppScreen.valueOf(raw) }.getOrDefault(AppScreen.HOME)
    }

    fun restoreDate(context: Context): LocalDate {
        val raw = context.getSharedPreferences(UI_SESSION_PREFS, Context.MODE_PRIVATE)
            .getString("selectedDate", "").orEmpty()
        return runCatching { LocalDate.parse(raw) }.getOrDefault(LocalDate.now())
    }

    fun restoreWork(context: Context): WorkItem? {
        val raw = context.getSharedPreferences(UI_SESSION_PREFS, Context.MODE_PRIVATE)
            .getString("work", "").orEmpty()
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw).toWorkItem() }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(UI_SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PhotoCaptureRecovery.clear(context)
    }

    private fun WorkItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("brand", brand)
        .put("brandAbbr", brandAbbr)
        .put("businessType", businessType)
        .put("storeCode", storeCode)
        .put("storeName", storeName)
        .put("posCount", posCount)
        .put("openClose", openClose)
        .put("address", address)
        .put("storeFormat", storeFormat)
        .put("rank", rank)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("storeNote", storeNote)
        .put("receiptStoreId", receiptStoreId)
        .put("receiptStoreIdPending", receiptStoreIdPending)
        .put("reviewStatus", reviewStatus)
        .put("returnReason", returnReason)
        .put("planStatus", planStatus)
        .put("originWorkDate", originWorkDate)
        .put("movedToDate", movedToDate)
        .put("changeNote", changeNote)
        .put("status", status.name)

    private fun JSONObject.toWorkItem(): WorkItem = WorkItem(
        id = optInt("id"),
        brand = optString("brand"),
        brandAbbr = optString("brandAbbr"),
        businessType = optString("businessType"),
        storeCode = optString("storeCode"),
        storeName = optString("storeName"),
        posCount = optInt("posCount", 1).coerceAtLeast(1),
        openClose = optString("openClose"),
        address = optString("address"),
        storeFormat = optString("storeFormat"),
        rank = optString("rank"),
        latitude = optString("latitude"),
        longitude = optString("longitude"),
        storeNote = optString("storeNote"),
        receiptStoreId = optString("receiptStoreId"),
        receiptStoreIdPending = optBoolean("receiptStoreIdPending", false),
        reviewStatus = optString("reviewStatus"),
        returnReason = optString("returnReason"),
        planStatus = optString("planStatus", "ACTIVE"),
        originWorkDate = optString("originWorkDate"),
        movedToDate = optString("movedToDate"),
        changeNote = optString("changeNote"),
        status = runCatching { WorkStatus.valueOf(optString("status", WorkStatus.NOT_STARTED.name)) }
            .getOrDefault(WorkStatus.NOT_STARTED)
    )
}
