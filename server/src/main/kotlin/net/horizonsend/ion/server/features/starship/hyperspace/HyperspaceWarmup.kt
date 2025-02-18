package net.horizonsend.ion.server.features.starship.hyperspace

import net.horizonsend.ion.common.extensions.alertAction
import net.horizonsend.ion.common.extensions.informationAction
import net.horizonsend.ion.common.extensions.userErrorAction
import net.horizonsend.ion.server.IonServer
import net.horizonsend.ion.server.features.cache.PlayerCache
import net.horizonsend.ion.common.database.schema.nations.CapturableStation
import net.horizonsend.ion.server.features.starship.active.ActivePlayerStarship
import net.horizonsend.ion.server.features.starship.active.ActiveStarship
import net.horizonsend.ion.server.features.starship.subsystem.HyperdriveSubsystem
import org.bukkit.Location
import org.bukkit.scheduler.BukkitRunnable
import org.litote.kmongo.eq
import kotlin.math.max
import kotlin.math.min

class HyperspaceWarmup(
	val ship: ActiveStarship,
	var warmup: Int,
	val dest: Location,
	val drive: HyperdriveSubsystem,
	private val useFuel: Boolean
) :
	BukkitRunnable() {
	init {
		if (ship is ActivePlayerStarship) {
			warmup -= (
				max(
					min(
						CapturableStation.count(CapturableStation::nation eq PlayerCache[ship.pilot!!].nationOid).toInt(),
						6
					) - 2,
					0
				) * 1.5
				).toInt()
			warmup = max(warmup, 0)
		}

		runTaskTimer(IonServer, 20L, 20L)
	}

	private var seconds = 0

	override fun run() {
		seconds++
		ship.onlinePassengers.forEach { player ->
			player.informationAction(
				"Hyperdrive Warmup: $seconds/$warmup seconds"
			)
		}

		if (!drive.isIntact()) {
			ship.onlinePassengers.forEach { player ->
				player.alertAction(
					"Drive damaged! Jump failed!"
				)
			}
			cancel()
			return
		}

		if (MassShadows.find(ship.serverLevel.world, ship.centerOfMass.x.toDouble(), ship.centerOfMass.z.toDouble()) != null) {
			ship.onlinePassengers.forEach { player ->
				player.userErrorAction("Ship is within Gravity Well, jump cancelled")
			}
			cancel()
			return
		}

		if (seconds < warmup) {
			return
		}

		if (useFuel) {
			require(drive.hasFuel()) { "Hyperdrive doesn't have fuel!" }
			drive.useFuel()
		}

		ship.informationAction("Jumping")
		Hyperspace.completeJumpWarmup(this)
	}

	override fun cancel() {
		super.cancel()

		if (!Hyperspace.isWarmingUp(ship)) {
			return
		}

		Hyperspace.cancelJumpWarmup(this)
	}
}
