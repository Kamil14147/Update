package pl.kejmil.oledsender

enum class WidgetType(val wireType: String, val defaultPriority: Int) {
    CALL("call", 100),
    NAVIGATION("nav", 90),
    NOTIFICATION("notification", 80),
    MUSIC("music", 70),
    BATTERY("battery", 60),
    WEATHER("weather", 50),
    WIFI("wifi", 45),
    STORAGE("storage", 40),
    MEMORY("memory", 35),
    ALARM("alarm", 30),
    SYSTEM("system", 25),
    HOME("home", 10);

    companion object {
        val uiOrder = listOf(
            CALL,
            NAVIGATION,
            NOTIFICATION,
            MUSIC,
            BATTERY,
            WEATHER,
            WIFI,
            STORAGE,
            MEMORY,
            ALARM,
            SYSTEM,
            HOME
        )

        fun fromWireType(value: String): WidgetType? {
            return entries.firstOrNull { it.wireType == value }
        }
    }
}
