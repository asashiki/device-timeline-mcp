package com.devicetimeline.agent

data class HeartRateSample(val time: String, val bpm: Long)
data class StepsSample(val startTime: String, val endTime: String, val count: Long)
data class SleepSession(val startTime: String, val endTime: String, val title: String?)
data class CaloriesSample(val startTime: String, val endTime: String, val kcal: Double)
data class SpO2Sample(val time: String, val percentage: Double)
data class DistanceSample(val startTime: String, val endTime: String, val meters: Double)
data class ExerciseSession(val startTime: String, val endTime: String, val type: Int, val title: String?)
data class BloodPressureSample(val time: String, val systolic: Double, val diastolic: Double)
data class TemperatureSample(val time: String, val celsius: Double)
data class RespiratoryRateSample(val time: String, val rpm: Double)
data class BloodGlucoseSample(val time: String, val mmolPerL: Double)
data class WeightSample(val time: String, val kg: Double)
data class HeightSample(val time: String, val meters: Double)

data class HealthSnapshot(
    val heartRate: List<HeartRateSample>? = null,
    val steps: List<StepsSample>? = null,
    val sleep: List<SleepSession>? = null,
    val calories: List<CaloriesSample>? = null,
    val spo2: List<SpO2Sample>? = null,
    val distance: List<DistanceSample>? = null,
    val exercise: List<ExerciseSession>? = null,
    val bloodPressure: List<BloodPressureSample>? = null,
    val temperature: List<TemperatureSample>? = null,
    val respiratoryRate: List<RespiratoryRateSample>? = null,
    val bloodGlucose: List<BloodGlucoseSample>? = null,
    val weight: List<WeightSample>? = null,
    val height: List<HeightSample>? = null
)
