package com.receiptocr.app.data.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import com.receiptocr.app.model.WorkItem
import java.util.Locale

private const val STORE_LOCATION_PREFS = "store_location_overrides"

data class CapturedStoreLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAt: Long
) {
    val latitudeText: String get() = String.format(Locale.US, "%.7f", latitude)
    val longitudeText: String get() = String.format(Locale.US, "%.7f", longitude)
}

object StoreLocationRepository {
    private fun key(work: WorkItem): String = "${work.brand.trim()}|${work.storeCode.trim()}"

    fun load(context: Context, work: WorkItem): CapturedStoreLocation? {
        val p = context.getSharedPreferences(STORE_LOCATION_PREFS, Context.MODE_PRIVATE)
        val prefix = key(work)
        if (!p.contains("$prefix.lat") || !p.contains("$prefix.lng")) return null
        return CapturedStoreLocation(
            latitude = Double.fromBits(p.getLong("$prefix.lat", 0L)),
            longitude = Double.fromBits(p.getLong("$prefix.lng", 0L)),
            accuracyMeters = p.getFloat("$prefix.acc", 0f),
            capturedAt = p.getLong("$prefix.at", 0L)
        )
    }

    fun save(context: Context, work: WorkItem, location: CapturedStoreLocation) {
        val prefix = key(work)
        context.getSharedPreferences(STORE_LOCATION_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("$prefix.lat", location.latitude.toBits())
            .putLong("$prefix.lng", location.longitude.toBits())
            .putFloat("$prefix.acc", location.accuracyMeters)
            .putLong("$prefix.at", location.capturedAt)
            .apply()
    }

    fun applySaved(context: Context, work: WorkItem): WorkItem {
        val location = load(context, work) ?: return work
        return work.copy(latitude = location.latitudeText, longitude = location.longitudeText)
    }

    fun captureCurrent(context: Context, callback: (Result<CapturedStoreLocation>) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            callback(Result.failure(IllegalStateException("อนุญาตตำแหน่งก่อนใช้งาน")))
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            callback(Result.failure(IllegalStateException("เปิดตำแหน่งในโทรศัพท์แล้วลองอีกครั้ง")))
            return
        }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            CancellationSignal(),
            ContextCompat.getMainExecutor(context),
            Consumer { location ->
                if (location == null) callback(Result.failure(IllegalStateException("ยังหาตำแหน่งไม่ได้ กรุณาลองอีกครั้ง")))
                else callback(Result.success(CapturedStoreLocation(location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())))
            }
        )
    }
}
