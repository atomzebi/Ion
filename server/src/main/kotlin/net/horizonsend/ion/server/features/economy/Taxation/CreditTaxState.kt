package net.horizonsend.ion.server.features.economy.Taxation

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.horizonsend.ion.common.utils.configuration.Configuration
import net.horizonsend.ion.server.IonServer
import java.io.File

@Serializable
data class CreditTaxState(
    val lastRunAtEpochMillis: Long = 0L,
    val nextRunAtEpochMillis: Long = 0L
)

object CreditTaxStateStore {
    private val stateDirectory: File get() = IonServer.dataFolder.resolve("state")
    private val stateFile: File get() = stateDirectory.resolve("creditTax.json")
    private val json get() = Configuration.getJsonSerializer()

    fun load(): CreditTaxState? {
        if (!stateFile.exists()) return null
        return json.decodeFromString(stateFile.readText())
    }

    fun create(now: Long, frequencyMillis: Long): CreditTaxState {
        return CreditTaxState(
            lastRunAtEpochMillis = 0L,
            nextRunAtEpochMillis = now + frequencyMillis
        )
    }

    fun save(state: CreditTaxState) {
        stateDirectory.mkdirs()
        stateFile.writeText(json.encodeToString(state))
    }

    fun path(): String = stateFile.path
}
