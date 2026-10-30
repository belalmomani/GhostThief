package com.ghostthief.game.game

import com.ghostthief.game.R
import com.ghostthief.game.logic.Progress
import com.ghostthief.game.logic.VehicleRules

/**
 * مواصفات رسم المركبة. الصور من الورقة التي زوّدتَني بها (كلها تنظر لليسار)، والمركبة الافتراضية مرسومة برمجياً (تنظر لليمين).
 * widthFactor: عرضها نسبةً لعرض السيارة الأساسية. groundFrac: موضع أسفل العجلات من ارتفاع الصورة.
 * blue/red: مواضع وميض الأضواء كنسب من الصورة (null = بلا وميض). liftFrac: ارتفاع التحليق (للمروحية).
 */
/** كيف يُنقل الحرامي: مركبة مغلقة (يختفي الركاب داخلها)، أو دراجة (سائق وراكب ظاهران)، أو مروحية (طيار والحرامي معلّق بحبل). */
enum class Carry { HIDDEN, RIDE, HELI }

data class VehicleSpec(
    val id: String,
    val drawable: Int,
    val nameRes: Int,
    val facesRight: Boolean,
    val widthFactor: Float,
    val groundFrac: Float,
    val blue: Pair<Float, Float>? = null,
    val red: Pair<Float, Float>? = null,
    val liftFrac: Float = 0f,
    val carry: Carry = Carry.HIDDEN,
    /** موضع جلوس السائق/الطيار والراكب (نسب من الصورة). */
    val driverSeat: Pair<Float, Float>? = null,
    val passengerSeat: Pair<Float, Float>? = null
)

object Vehicles {
    val all: List<VehicleSpec> = listOf(
        VehicleSpec("default", R.drawable.police_car, R.string.veh_default, true, 1.0f, 216f / 230f, 249f / 440f to 0.126f, 195f / 440f to 0.126f),
        VehicleSpec("sedan", R.drawable.veh_sedan, R.string.veh_sedan, false, 0.90f, 0.983f, 0.52f to 0.08f, 0.68f to 0.08f),
        VehicleSpec("suv", R.drawable.veh_suv, R.string.veh_suv, false, 0.95f, 0.988f, 0.452f to 0.076f, 0.605f to 0.064f),
        VehicleSpec("classic", R.drawable.veh_classic, R.string.veh_classic, false, 0.90f, 0.988f, 0.47f to 0.07f, 0.613f to 0.066f),
        VehicleSpec("pickup", R.drawable.veh_pickup, R.string.veh_pickup, false, 1.00f, 0.988f, 0.47f to 0.07f, 0.595f to 0.063f),
        VehicleSpec("van", R.drawable.veh_van, R.string.veh_van, false, 1.00f, 0.989f, 0.45f to 0.05f, 0.559f to 0.05f),
        VehicleSpec("moto", R.drawable.veh_moto, R.string.veh_moto, false, 0.62f, 0.989f, carry = Carry.RIDE, driverSeat = 0.47f to 0.40f, passengerSeat = 0.70f to 0.38f),
        VehicleSpec("atv", R.drawable.veh_atv, R.string.veh_atv, false, 0.70f, 0.987f, 0.471f to 0.079f, 0.613f to 0.08f, carry = Carry.RIDE, driverSeat = 0.50f to 0.38f, passengerSeat = 0.72f to 0.36f),
        VehicleSpec("sport", R.drawable.veh_sport, R.string.veh_sport, false, 0.90f, 0.986f, 0.50f to 0.085f, 0.629f to 0.08f),
        VehicleSpec("heli", R.drawable.veh_heli, R.string.veh_heli, false, 1.10f, 0.984f, liftFrac = 0.60f, carry = Carry.HELI, driverSeat = 0.30f to 0.60f)
    )

    fun byId(id: String): VehicleSpec = all.find { it.id == id } ?: all[0]

    /** المركبة المختارة إن كانت مفتوحة، وإلا الأساسية (مثلاً بعد إعادة ضبط التقدم). */
    fun effective(id: String, p: Progress): VehicleSpec =
        if (VehicleRules.isUnlocked(id, p)) byId(id) else all[0]
}
