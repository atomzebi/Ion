package net.horizonsend.ion.server.miscellaneous.registrations

import net.horizonsend.ion.common.IonComponent
import net.horizonsend.ion.common.database.DBManager
import net.horizonsend.ion.common.utils.redisaction.RedisActions
import net.horizonsend.ion.server.features.cache.Caches
import net.horizonsend.ion.server.features.sidebar.Sidebar
import net.horizonsend.ion.server.features.spacestations.SpaceStations
import net.horizonsend.ion.server.features.misc.CombatNPCs
import net.horizonsend.ion.server.features.misc.PacketHandler
import net.horizonsend.ion.server.features.client.whereisit.mod.ModNetworking
import net.horizonsend.ion.server.features.chat.ChannelSelections
import net.horizonsend.ion.server.features.economy.bazaar.Bazaars
import net.horizonsend.ion.server.features.economy.bazaar.Merchants
import net.horizonsend.ion.server.features.economy.cargotrade.CrateRestrictions
import net.horizonsend.ion.server.features.economy.cargotrade.ShipmentBalancing
import net.horizonsend.ion.server.features.economy.cargotrade.ShipmentGenerator
import net.horizonsend.ion.server.features.economy.cargotrade.ShipmentManager
import net.horizonsend.ion.server.features.economy.city.CityNPCs
import net.horizonsend.ion.server.features.economy.city.TradeCities
import net.horizonsend.ion.server.features.economy.collectors.CollectionMissions
import net.horizonsend.ion.server.features.economy.collectors.Collectors
import net.horizonsend.ion.server.features.gear.Gear
import net.horizonsend.ion.server.features.hyperspace.HyperspaceBeacons
import net.horizonsend.ion.server.features.machine.AreaShields
import net.horizonsend.ion.server.features.machine.PowerMachines
import net.horizonsend.ion.server.features.misc.*
import net.horizonsend.ion.server.features.multiblock.Multiblocks
import net.horizonsend.ion.server.features.nations.NationsBalancing
import net.horizonsend.ion.server.features.nations.NationsMap
import net.horizonsend.ion.server.features.nations.NationsMasterTasks
import net.horizonsend.ion.server.features.nations.StationSieges
import net.horizonsend.ion.server.features.nations.region.Regions
import net.horizonsend.ion.server.features.progression.Levels
import net.horizonsend.ion.server.features.progression.PlayerXPLevelCache
import net.horizonsend.ion.server.features.progression.SLXP
import net.horizonsend.ion.server.features.progression.ShipKillXP
import net.horizonsend.ion.server.features.space.*
import net.horizonsend.ion.server.features.starship.*
import net.horizonsend.ion.server.features.starship.active.ActiveStarshipMechanics
import net.horizonsend.ion.server.features.starship.active.ActiveStarships
import net.horizonsend.ion.server.features.starship.control.StarshipControl
import net.horizonsend.ion.server.features.starship.control.StarshipCruising
import net.horizonsend.ion.server.features.starship.factory.StarshipFactories
import net.horizonsend.ion.server.features.starship.hyperspace.Hyperspace
import net.horizonsend.ion.server.features.starship.hyperspace.HyperspaceMap
import net.horizonsend.ion.server.features.starship.subsystem.shield.StarshipShields
import net.horizonsend.ion.server.features.transport.Extractors
import net.horizonsend.ion.server.features.transport.TransportConfig
import net.horizonsend.ion.server.features.transport.Wires
import net.horizonsend.ion.server.features.transport.pipe.Pipes
import net.horizonsend.ion.server.features.transport.pipe.filter.Filters
import net.horizonsend.ion.server.features.tutorial.TutorialManager
import net.horizonsend.ion.server.miscellaneous.registrations.legacy.CustomRecipes
import net.horizonsend.ion.server.miscellaneous.utils.Notify

val components: List<IonComponent> = listOf(
	GameplayTweaks,
	DBManager,
	RedisActions,
	Caches,
	Notify,
	Shuttles,

	PlayerXPLevelCache,
	Levels,
	SLXP,

	CombatNPCs,

	CustomRecipes,
	Crafting,

	SpaceWorlds,
	Space,
	Orbits,

	SpaceMechanics,

	NationsBalancing,
	Regions,

	StationSieges,

	Multiblocks,
	PowerMachines,
	AreaShields,

	TransportConfig.Companion,
	Extractors,
	Pipes,
	Filters,
	Wires,

	Gear,

	TradeCities,

	CollectionMissions,

	CrateRestrictions,

	ShipmentBalancing,
	ShipmentGenerator,
	ShipmentManager,

	Bazaars,
	Merchants,

	Hyperspace,
	HyperspaceBeacons,
	DeactivatedPlayerStarships,
	ActiveStarships,
	ActiveStarshipMechanics,
	PilotedStarships,
	StarshipDetection,
	StarshipComputers,
	StarshipControl,
	StarshipShields,
	StarshipCruising,
	Hangars,
	StarshipFactories,
	TutorialManager,
	Interdiction,
	StarshipDealers,
	ShipKillXP,
	Decomposers,
	ChannelSelections,

	DutyModeMonitor,

	SpaceStations,
	Sidebar,
	PacketHandler,
	ModNetworking,

	SpaceMap,
	NationsMap,
	HyperspaceMap,
	HyperspaceBeacons,
	Collectors,
	CityNPCs,
	AreaShields,
	NationsMasterTasks
)
