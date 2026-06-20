package com.devicetimeline.agent

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.reflect.KClass

class HealthConnectReader(context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    val healthConnectClient: HealthConnectClient get() = client

    suspend fun readSnapshot(from: Instant, to: Instant): HealthSnapshot = coroutineScope {
        Log.i(TAG, "Reading health data from $from to $to")
        val timeRange = TimeRangeFilter.between(from, to)

        val heartRate = async { tryRead("heart_rate") { readHeartRate(timeRange) } }
        val steps = async { tryRead("steps") { readSteps(timeRange) } }
        val sleep = async { tryRead("sleep") { readSleep(timeRange) } }
        val calories = async { tryRead("calories") { readCalories(timeRange) } }
        val spo2 = async { tryRead("spo2") { readSpO2(timeRange) } }
        val distance = async { tryRead("distance") { readDistance(timeRange) } }
        val exercise = async { tryRead("exercise") { readExercise(timeRange) } }
        val bloodPressure = async { tryRead("blood_pressure") { readBloodPressure(timeRange) } }
        val temperature = async { tryRead("temperature") { readTemperature(timeRange) } }
        val respiratoryRate = async { tryRead("respiratory_rate") { readRespiratoryRate(timeRange) } }
        val bloodGlucose = async { tryRead("blood_glucose") { readBloodGlucose(timeRange) } }
        val weight = async { tryRead("weight") { readWeight(timeRange) } }
        val height = async { tryRead("height") { readHeight(timeRange) } }

        val snapshot = HealthSnapshot(
            heartRate = heartRate.await(),
            steps = steps.await(),
            sleep = sleep.await(),
            calories = calories.await(),
            spo2 = spo2.await(),
            distance = distance.await(),
            exercise = exercise.await(),
            bloodPressure = bloodPressure.await(),
            temperature = temperature.await(),
            respiratoryRate = respiratoryRate.await(),
            bloodGlucose = bloodGlucose.await(),
            weight = weight.await(),
            height = height.await()
        )

        val totalRecords = listOfNotNull(
            snapshot.heartRate, snapshot.steps, snapshot.sleep, snapshot.calories,
            snapshot.spo2, snapshot.distance, snapshot.exercise, snapshot.bloodPressure,
            snapshot.temperature, snapshot.respiratoryRate, snapshot.bloodGlucose,
            snapshot.weight, snapshot.height
        ).sumOf { it.size }
        Log.i(TAG, "Health data read complete: $totalRecords total records")

        snapshot
    }

    /**
     * Generic paginated read: fetches all pages of records for a given type,
     * maps each record to a domain model, and returns the full list.
     */
    private suspend fun <R : Record, T> readAllPages(
        recordType: KClass<R>,
        timeRange: TimeRangeFilter,
        mapper: (R) -> List<T>
    ): List<T>? {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        var pageCount = 0

        do {
            val request = ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = timeRange,
                pageToken = pageToken
            )
            val response = client.readRecords(request)
            for (record in response.records) {
                all.addAll(mapper(record))
            }
            pageCount++
            pageToken = response.pageToken
            if (pageToken != null) {
                Log.d(TAG, "[${recordType.simpleName}] page $pageCount done (${all.size} records so far), fetching next page...")
            }
        } while (pageToken != null)

        if (pageCount > 1) {
            Log.i(TAG, "[${recordType.simpleName}] fetched $pageCount pages total")
        }

        return all.ifEmpty { null }
    }

    private suspend fun readHeartRate(timeRange: TimeRangeFilter): List<HeartRateSample>? {
        return readAllPages(HeartRateRecord::class, timeRange) { record ->
            record.samples.map { sample ->
                HeartRateSample(
                    time = sample.time.toString(),
                    bpm = sample.beatsPerMinute
                )
            }
        }
    }

    private suspend fun readSteps(timeRange: TimeRangeFilter): List<StepsSample>? {
        return readAllPages(StepsRecord::class, timeRange) { record ->
            listOf(StepsSample(
                startTime = record.startTime.toString(),
                endTime = record.endTime.toString(),
                count = record.count
            ))
        }
    }

    private suspend fun readSleep(timeRange: TimeRangeFilter): List<SleepSession>? {
        return readAllPages(SleepSessionRecord::class, timeRange) { record ->
            listOf(SleepSession(
                startTime = record.startTime.toString(),
                endTime = record.endTime.toString(),
                title = record.title
            ))
        }
    }

    private suspend fun readCalories(timeRange: TimeRangeFilter): List<CaloriesSample>? {
        return readAllPages(TotalCaloriesBurnedRecord::class, timeRange) { record ->
            listOf(CaloriesSample(
                startTime = record.startTime.toString(),
                endTime = record.endTime.toString(),
                kcal = record.energy.inKilocalories
            ))
        }
    }

    private suspend fun readSpO2(timeRange: TimeRangeFilter): List<SpO2Sample>? {
        return readAllPages(OxygenSaturationRecord::class, timeRange) { record ->
            listOf(SpO2Sample(
                time = record.time.toString(),
                percentage = record.percentage.value
            ))
        }
    }

    private suspend fun readDistance(timeRange: TimeRangeFilter): List<DistanceSample>? {
        return readAllPages(DistanceRecord::class, timeRange) { record ->
            listOf(DistanceSample(
                startTime = record.startTime.toString(),
                endTime = record.endTime.toString(),
                meters = record.distance.inMeters
            ))
        }
    }

    private suspend fun readExercise(timeRange: TimeRangeFilter): List<ExerciseSession>? {
        return readAllPages(ExerciseSessionRecord::class, timeRange) { record ->
            listOf(ExerciseSession(
                startTime = record.startTime.toString(),
                endTime = record.endTime.toString(),
                type = record.exerciseType,
                title = record.title
            ))
        }
    }

    private suspend fun readBloodPressure(timeRange: TimeRangeFilter): List<BloodPressureSample>? {
        return readAllPages(BloodPressureRecord::class, timeRange) { record ->
            listOf(BloodPressureSample(
                time = record.time.toString(),
                systolic = record.systolic.inMillimetersOfMercury,
                diastolic = record.diastolic.inMillimetersOfMercury
            ))
        }
    }

    private suspend fun readTemperature(timeRange: TimeRangeFilter): List<TemperatureSample>? {
        return readAllPages(BodyTemperatureRecord::class, timeRange) { record ->
            listOf(TemperatureSample(
                time = record.time.toString(),
                celsius = record.temperature.inCelsius
            ))
        }
    }

    private suspend fun readRespiratoryRate(timeRange: TimeRangeFilter): List<RespiratoryRateSample>? {
        return readAllPages(RespiratoryRateRecord::class, timeRange) { record ->
            listOf(RespiratoryRateSample(
                time = record.time.toString(),
                rpm = record.rate
            ))
        }
    }

    private suspend fun readBloodGlucose(timeRange: TimeRangeFilter): List<BloodGlucoseSample>? {
        return readAllPages(BloodGlucoseRecord::class, timeRange) { record ->
            listOf(BloodGlucoseSample(
                time = record.time.toString(),
                mmolPerL = record.level.inMillimolesPerLiter
            ))
        }
    }

    private suspend fun readWeight(timeRange: TimeRangeFilter): List<WeightSample>? {
        return readAllPages(WeightRecord::class, timeRange) { record ->
            listOf(WeightSample(
                time = record.time.toString(),
                kg = record.weight.inKilograms
            ))
        }
    }

    private suspend fun readHeight(timeRange: TimeRangeFilter): List<HeightSample>? {
        return readAllPages(HeightRecord::class, timeRange) { record ->
            listOf(HeightSample(
                time = record.time.toString(),
                meters = record.height.inMeters
            ))
        }
    }

    private suspend fun <T> tryRead(typeName: String, block: suspend () -> T?): T? {
        return try {
            val result = block()
            if (result == null) {
                Log.d(TAG, "[$typeName] no data found")
            } else if (result is List<*>) {
                Log.i(TAG, "[$typeName] ${result.size} records read")
            }
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "[$typeName] permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "[$typeName] read failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "HealthConnectReader"

        fun isAvailable(context: Context): Int =
            HealthConnectClient.getSdkStatus(context)
    }
}
