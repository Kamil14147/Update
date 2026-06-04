package pl.kejmil.oledsender

import org.json.JSONObject

data class FirmwareManifest(
    val device: String,
    val name: String,
    val version: String,
    val firmwareUrl: String,
    val changelog: String,
    val sizeBytes: Long,
    val sha256: String,
    val required: Boolean
) {
    companion object {
        const val DEVICE_ID = "kejmil-oled-esp32"

        fun fromJson(text: String): FirmwareManifest {
            val json = JSONObject(text)
            return FirmwareManifest(
                device = json.getString("device"),
                name = json.optString("name", "Kejmil OLED"),
                version = json.getString("version"),
                firmwareUrl = json.getString("firmwareUrl"),
                changelog = json.optString("changelog", ""),
                sizeBytes = json.getLong("sizeBytes"),
                sha256 = json.getString("sha256").lowercase(),
                required = json.optBoolean("required", false)
            )
        }
    }
}
