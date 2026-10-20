package app.mawja.android.discord

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscordFossIsolationTest {
    @Test
    fun `foss classpath excludes functional Discord implementation`() {
        val forbiddenClasses = listOf(
            "app.mawja.android.discord.DiscordTokenStore",
            "app.mawja.android.discord.DiscordAuthTokens",
            "app.mawja.android.discord.DiscordPlaybackSource",
            "app.mawja.android.discord.DiscordPresenceCoordinator",
            "app.mawja.android.discord.KizzyDiscordPresenceTransport",
            "app.mawja.android.discord.KizzyGatewayProtocol",
        )

        forbiddenClasses.forEach { className ->
            assertThat(runCatching { Class.forName(className) }.isFailure).isTrue()
        }
    }

    @Test
    fun `foss runtime reports Discord unavailable`() {
        assertThat(DiscordPresenceRuntime.settingsState.value.isAvailable).isFalse()
        assertThat(DiscordPresenceRuntime.settingsState.value.isEnabled).isFalse()
        assertThat(DiscordPresenceRuntime.settingsState.value.summary)
            .isEqualTo(DiscordSettingsSummary.UNAVAILABLE)
    }
}
