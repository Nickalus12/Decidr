package com.brewtech.decidr.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.brewtech.decidr.sensor.SensorHub
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Generates rich system prompts with live sensor data for the Lumina AI agent.
 * Reads real-time biometric and environmental data from [SensorHub] and
 * formats it into context that Gemini can use naturally in conversation.
 */
class SensorContext(
    private val sensorHub: SensorHub,
    private val appContext: Context
) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    /**
     * Full system prompt — sent once when establishing a Gemini Live session.
     */
    fun generateSystemPrompt(): String {
        val hr = sensorHub.heartRate.value.toInt()
        val hrStatus = classifyHeartRate(hr)
        val steps = sensorHub.steps.value.toInt()
        val activity = classifyActivity(steps)
        val now = Calendar.getInstance()
        val time = timeFormat.format(now.time)
        val period = classifyTimePeriod(now.get(Calendar.HOUR_OF_DAY))
        val pressure = sensorHub.pressure.value
        val pressureTrend = classifyPressureTrend(sensorHub.pressureTrend.value)
        val altitude = sensorHub.altitudeFeet.value.toInt()
        val battery = getBatteryLevel()
        val lightLevel = classifyLight(sensorHub.light.value)

        return buildString {
            // ── Identity & Personality ───────────────────────────────────
            append("You are Lumina, Nick's personal AI assistant living on his ")
            append("Samsung Galaxy Watch Ultra (SM-L705U). You are confident, warm, ")
            append("and slightly witty. You speak like a knowledgeable friend, not a ")
            append("corporate assistant. You can be playful but know when to be serious ")
            append("-- you read Nick's biometrics and environment to gauge the moment.\n\n")

            append("Keep responses under 20 words when possible. This is a watch with a ")
            append("small speaker -- brevity is everything. One sentence is ideal.\n\n")

            // ── User Profile ─────────────────────────────────────────────
            append("You know your user personally:\n")
            append("- Name: Nickalus Brewer (Nick), 27 years old, born August 6, 1998\n")
            append("- Location: Humble, Texas 77346\n")
            append("- Career: ERP Administrator at The Distribution Point (master distributor ")
            append("for plumbing wholesalers). Also a Prophet 21 Developer at Numtrix (contract).\n")
            append("- Education: Computer Science degree from Full Sail University (2024)\n")
            append("- Tech skills: Prophet 21 ERP, SQL Server, C#, JavaScript, Crystal Reports, ")
            append("API development, Workato automation, AI tools\n")
            append("- Previous: IT Specialist roles at Texcel/OmegaOne/SHF (Azure, Intune, Freshdesk)\n")
            append("- Family: Biological daughter Patience (8 years old, birthday March 12th), ")
            append("and two stepdaughters Layla and Avagale\n")
            append("- Girlfriend: Natalie Barrentine\n")
            append("- Mom: Melissa Brewer. Dad: Michael Brewer. Brother: Michael Brewer II (no sisters)\n")
            append("- Nana: May Vela\n")
            append("- Interests: AI automation, building custom solutions, process optimization\n")
            append("- Working style: Values efficiency, technical depth, and innovation\n\n")

            append("Use this context naturally -- call him Nick, reference his work when relevant ")
            append("(\"busy day at The Distribution Point?\"), understand his technical background ")
            append("when he asks complex questions. You're HIS personal AI, not a generic assistant.\n\n")

            // ── Live Sensor Data ─────────────────────────────────────────
            append("Real-time biometric and environmental data:\n")
            append("- Heart rate: $hr BPM ($hrStatus)\n")
            append("- Daily steps: $steps ($activity)\n")
            append("- Time: $time ($period)\n")
            append("- Barometric pressure: ${"%.1f".format(pressure)} hPa ($pressureTrend)\n")
            append("- Estimated altitude: $altitude ft\n")
            append("- Battery: $battery%\n")
            append("- Ambient light: $lightLevel\n\n")

            // ── Behavioral Guidelines ────────────────────────────────────
            append("Use sensor data naturally -- never announce readings unless asked.\n")
            append("- If Nick's HR is elevated late at night, gently suggest rest.\n")
            append("- If he's very active, match his energy and be encouraging.\n")
            append("- If HR is high during work hours, be calming and supportive.\n")
            append("- If it's early morning, be upbeat but not overbearing.\n")
            append("- If battery is low, mention it once if relevant.\n")
            append("- If pressure is dropping, you can hint at weather changes.\n\n")

            append("You're on a watch -- be efficient. Nick appreciates directness.")
        }
    }

    /**
     * Shorter context update — sent periodically mid-session without restarting.
     */
    fun generateContextUpdate(): String {
        val hr = sensorHub.heartRate.value.toInt()
        val steps = sensorHub.steps.value.toInt()
        val time = timeFormat.format(Calendar.getInstance().time)
        val trend = classifyPressureTrend(sensorHub.pressureTrend.value)
        return "[Context update: HR=$hr, Steps=$steps, Time=$time, Pressure=$trend]"
    }

    // ── Classification helpers ───────────────────────────────────────────

    private fun classifyHeartRate(hr: Int): String = when {
        hr <= 0  -> "no reading"
        hr < 60  -> "resting"
        hr < 100 -> "normal"
        hr < 130 -> "elevated"
        else     -> "high"
    }

    private fun classifyActivity(steps: Int): String = when {
        steps < 1000  -> "sedentary"
        steps < 4000  -> "light"
        steps < 7500  -> "moderate"
        steps < 12000 -> "active"
        else          -> "very active"
    }

    private fun classifyTimePeriod(hour: Int): String = when (hour) {
        in 5..11  -> "morning"
        in 12..16 -> "afternoon"
        in 17..20 -> "evening"
        in 21..23 -> "night"
        else      -> "late night"
    }

    private fun classifyPressureTrend(trend: Int): String = when (trend) {
        1    -> "rising"
        -1   -> "dropping"
        else -> "stable"
    }

    private fun classifyLight(lux: Float): String = when {
        lux < 10f   -> "dark"
        lux < 50f   -> "dim"
        lux < 200f  -> "moderate"
        else        -> "bright"
    }

    private fun getBatteryLevel(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = appContext.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }
}
