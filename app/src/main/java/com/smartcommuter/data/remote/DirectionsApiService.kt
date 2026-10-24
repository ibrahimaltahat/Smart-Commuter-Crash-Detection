package com.smartcommuter.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// --- نماذج بيانات OSRM المجانية (ترجع نفس صيغة Polyline الخاصة بجوجل) ---
data class OsrmRouteResponseDto(
    @SerializedName("code") val code: String,
    @SerializedName("routes") val routes: List<OsrmRouteDto>?
)

data class OsrmRouteDto(
    @SerializedName("geometry") val encodedPolyline: String, // خط المسار المشفر
    @SerializedName("distance") val distanceMeters: Double,  // المسافة بالمتر
    @SerializedName("duration") val durationSeconds: Double  // الوقت بالثواني
)

interface DirectionsApiService {

    /**
     * جلب مسار القيادة الحقيقي مجاناً بدون أي مفتاح API
     * ملاحظة: OSRM يستقبل الإحداثيات بترتيب: "lng1,lat1;lng2,lat2"
     */
    @GET("route/v1/driving/{coordinates}")
    suspend fun getFreeDrivingRoute(
        @Path("coordinates", encoded = true) coordinates: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "polyline"
    ): OsrmRouteResponseDto

    companion object {
        private const val BASE_URL = "https://router.project-osrm.org/"

        fun create(): DirectionsApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(DirectionsApiService::class.java)
        }
    }
}