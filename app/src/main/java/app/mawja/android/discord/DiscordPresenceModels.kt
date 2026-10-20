package app.mawja.android.discord

enum class DiscordConnectionState {
    UNAVAILABLE,
    DISCONNECTED,
    LINKING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface DiscordLinkResult {
    data object Success : DiscordLinkResult
    data class Failure(val message: String) : DiscordLinkResult
}
