/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

/** A city people can pick when they would rather not share their location. */
data class IslamicCity(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val country: String,
    val lat: Double,
    val lng: Double,
) {
    fun name(arabic: Boolean): String = if (arabic) nameAr else nameEn
}

/** A small bundled list of major Arab and Muslim-majority cities (and large communities abroad). */
object IslamicCities {
    val all: List<IslamicCity> =
        listOf(
            IslamicCity("cairo", "Cairo", "القاهرة", "EG", 30.0444, 31.2357),
            IslamicCity("alexandria", "Alexandria", "الإسكندرية", "EG", 31.2001, 29.9187),
            IslamicCity("giza", "Giza", "الجيزة", "EG", 30.0131, 31.2089),
            IslamicCity("mansoura", "Mansoura", "المنصورة", "EG", 31.0409, 31.3785),
            IslamicCity("tanta", "Tanta", "طنطا", "EG", 30.7865, 31.0004),
            IslamicCity("zagazig", "Zagazig", "الزقازيق", "EG", 30.5877, 31.5020),
            IslamicCity("port_said", "Port Said", "بورسعيد", "EG", 31.2653, 32.3019),
            IslamicCity("ismailia", "Ismailia", "الإسماعيلية", "EG", 30.5965, 32.2715),
            IslamicCity("suez", "Suez", "السويس", "EG", 29.9668, 32.5498),
            IslamicCity("asyut", "Asyut", "أسيوط", "EG", 27.1783, 31.1859),
            IslamicCity("luxor", "Luxor", "الأقصر", "EG", 25.6872, 32.6396),
            IslamicCity("aswan", "Aswan", "أسوان", "EG", 24.0889, 32.8998),
            IslamicCity("makkah", "Makkah", "مكة المكرمة", "SA", 21.4225, 39.8262),
            IslamicCity("madinah", "Madinah", "المدينة المنورة", "SA", 24.4672, 39.6111),
            IslamicCity("riyadh", "Riyadh", "الرياض", "SA", 24.7136, 46.6753),
            IslamicCity("jeddah", "Jeddah", "جدة", "SA", 21.4858, 39.1925),
            IslamicCity("dammam", "Dammam", "الدمام", "SA", 26.4207, 50.0888),
            IslamicCity("dubai", "Dubai", "دبي", "AE", 25.2048, 55.2708),
            IslamicCity("abu_dhabi", "Abu Dhabi", "أبوظبي", "AE", 24.4539, 54.3773),
            IslamicCity("doha", "Doha", "الدوحة", "QA", 25.2854, 51.5310),
            IslamicCity("kuwait", "Kuwait City", "مدينة الكويت", "KW", 29.3759, 47.9774),
            IslamicCity("manama", "Manama", "المنامة", "BH", 26.2285, 50.5860),
            IslamicCity("muscat", "Muscat", "مسقط", "OM", 23.5880, 58.3829),
            IslamicCity("sanaa", "Sanaa", "صنعاء", "YE", 15.3694, 44.1910),
            IslamicCity("amman", "Amman", "عمّان", "JO", 31.9539, 35.9106),
            IslamicCity("jerusalem", "Jerusalem", "القدس", "PS", 31.7683, 35.2137),
            IslamicCity("gaza", "Gaza", "غزة", "PS", 31.5017, 34.4668),
            IslamicCity("beirut", "Beirut", "بيروت", "LB", 33.8938, 35.5018),
            IslamicCity("damascus", "Damascus", "دمشق", "SY", 33.5138, 36.2765),
            IslamicCity("baghdad", "Baghdad", "بغداد", "IQ", 33.3152, 44.3661),
            IslamicCity("khartoum", "Khartoum", "الخرطوم", "SD", 15.5007, 32.5599),
            IslamicCity("tripoli", "Tripoli", "طرابلس", "LY", 32.8872, 13.1913),
            IslamicCity("tunis", "Tunis", "تونس", "TN", 36.8065, 10.1815),
            IslamicCity("algiers", "Algiers", "الجزائر", "DZ", 36.7538, 3.0588),
            IslamicCity("casablanca", "Casablanca", "الدار البيضاء", "MA", 33.5731, -7.5898),
            IslamicCity("rabat", "Rabat", "الرباط", "MA", 34.0209, -6.8416),
            IslamicCity("nouakchott", "Nouakchott", "نواكشوط", "MR", 18.0735, -15.9582),
            IslamicCity("istanbul", "Istanbul", "إسطنبول", "TR", 41.0082, 28.9784),
            IslamicCity("tehran", "Tehran", "طهران", "IR", 35.6892, 51.3890),
            IslamicCity("karachi", "Karachi", "كراتشي", "PK", 24.8607, 67.0011),
            IslamicCity("lahore", "Lahore", "لاهور", "PK", 31.5204, 74.3587),
            IslamicCity("dhaka", "Dhaka", "دكا", "BD", 23.8103, 90.4125),
            IslamicCity("kuala_lumpur", "Kuala Lumpur", "كوالالمبور", "MY", 3.1390, 101.6869),
            IslamicCity("jakarta", "Jakarta", "جاكرتا", "ID", -6.2088, 106.8456),
            IslamicCity("london", "London", "لندن", "GB", 51.5074, -0.1278),
            IslamicCity("paris", "Paris", "باريس", "FR", 48.8566, 2.3522),
            IslamicCity("berlin", "Berlin", "برلين", "DE", 52.5200, 13.4050),
            IslamicCity("new_york", "New York", "نيويورك", "US", 40.7128, -74.0060),
            IslamicCity("toronto", "Toronto", "تورونتو", "CA", 43.6532, -79.3832),
        )

    fun byId(id: String?): IslamicCity? = all.firstOrNull { it.id == id }

    /** Cities whose English or Arabic name contains [query], ignoring case and Arabic hamza/ta-marbuta forms. */
    fun search(query: String): List<IslamicCity> {
        val q = fold(query.trim())
        if (q.isEmpty()) return all
        return all.filter { fold(it.nameEn).contains(q) || fold(it.nameAr).contains(q) }
    }

    /** The bundled city closest to a point, for labelling a device location. */
    fun nearest(
        lat: Double,
        lng: Double,
    ): IslamicCity = all.minBy { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }

    internal fun fold(s: String): String =
        s
            .lowercase()
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace("\u0651", "")
}
