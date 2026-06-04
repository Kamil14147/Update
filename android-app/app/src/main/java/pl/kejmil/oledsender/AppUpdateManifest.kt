package pl.kejmil.oledsender

import org.json.JSONObject

data class AppUpdateManifest(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val changelog: String,
    val sizeBytes: Long,
    val sha256: String,
    val required: Boolean
) {
    companion object {
        fun fromJson(text: String): AppUpdateManifest {
            val json = JSONObject(text)
            return AppUpdateManifest(
                packageName = json.getString("packageName"),
                name = json.optString("name", "KESP32"),
                versionName = json.getString("versionName"),
                versionCode = json.getLong("versionCode"),
                apkUrl = json.getString("apkUrl"),
                changelog = json.optString("changelog", ""),
                sizeBytes = json.getLong("sizeBytes"),
                sha256 = json.getString("sha256").lowercase(),
                required = json.optBoolean("required", false)
            )
        }
    }
}
