/*
 * Copyright 2026 Lambda
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.lambda.module.modules.world

import com.lambda.context.SafeContext
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.graphics.mc.renderer.ChunkedRenderer.Companion.chunkedRenderer
import com.lambda.graphics.mc.renderer.ImmediateRenderer.Companion.immediateRenderer
import com.lambda.graphics.mc.renderer.RendererUtils.worldToScreenNormalized
import com.lambda.module.Module
import com.lambda.module.modules.world.NoSpawnerDetector.dungeonGroundBlocks
import com.lambda.module.tag.ModuleTag
import com.lambda.sound.SoundManager
import com.lambda.threading.runSafe
import com.lambda.util.Communication.info
import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import com.lambda.util.world.toBlockPos
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

object DeactivatedSpawnerDetector : Module(
	name = "DeactivatedSpawnerDetector",
	description = "Detects spawners that have torches placed on them to deactivate them",
	tag = ModuleTag.WORLD
) {
	val detectedSpawners: ConcurrentHashMap.KeySetView<BlockPos, Boolean> = ConcurrentHashMap.newKeySet<BlockPos>()

	val lightThreshold by setting("Light Threshold", 1, 1..15, 1, description = "Minimum light level to consider a spawner deactivated")
		.onValueChange { from, to -> detectedSpawners.clear(); rescanLoadedChunks() }
	val onlyDungeon by setting("Only Dungeon Spawners", true, description = "Only consider spawners on top of cobblestone")
		.onValueChange { from, to -> detectedSpawners.clear(); rescanLoadedChunks() }
	val esp by setting("ESP", true, description = "Highlight detected spawners with a box")
	val espColor by setting("Color", Color(255, 0, 0, 100), description = "Color of detected spawners") { esp }
	val tracer by setting("Tracer", true, description = "Draw a line from the player to detected spawners")
	val tracerColor by setting("Tracer Color", Color(255, 0, 0, 100), description = "Color of tracers to detected spawners") { tracer }
	val tracerWidth by setting("Tracer Width", 0.004f, 0.001f..0.010f, 0.001f, description = "Width of tracers to detected spawners") { tracer }
	val notification by setting("Notification", mutableSetOf<Notification>(), mutableSetOf<Notification>(Notification.Sound))

	val chunkedRenderer = chunkedRenderer("Chunked Renderer Deactivated Spawner Detector", depthTest = { false }) { world, pos ->
		if (esp) {
			runSafe {
				if (pos.toBlockPos() in detectedSpawners) {
					box(pos.toBlockPos()) {
						allColors(espColor)
					}
				}
			}
		}
	}

	init {
		immediateRenderer("Immediate Renderer Deactivated Spawner Detector") {
			if (tracer) detectedSpawners.forEach {
				val endPoint = worldToScreenNormalized(it.toCenterPos()) ?: return@forEach
				screenLineGradient(
					0.5f, 0.5f,
					tracerColor,
					endPoint.x, endPoint.y,
					tracerColor,
					tracerWidth
				)
			}
		}

		listen<WorldEvent.ChunkEvent.Load> { event ->
			onChunkLoad(event.chunk.pos)
		}

		listen<WorldEvent.ChunkEvent.Unload> { event ->
			deleteResultsInChunk(event.chunk.pos)
		}

		onEnable {
			rescanLoadedChunks()
		}

		onDisable {
			detectedSpawners.clear()
		}
	}

	private fun SafeContext.rescanLoadedChunks() {
		for (i in 0 until world.chunkManager.chunks.chunks.length()) {
			try {
				val chunk = world.chunkManager.chunks.chunks.get(i) ?: continue
				scanForSpawners(chunk)
			} catch (_: Exception) {
				// Exception? too bad
			}
		}

		chunkedRenderer.rebuild()
	}

	private fun SafeContext.deleteResultsInChunk(chunkPos: ChunkPos) {
		detectedSpawners.removeIf { it.chunkPos == chunkPos }
		chunkedRenderer.rebuildChunk(chunkPos.x, chunkPos.z)
	}

	private fun SafeContext.onChunkLoad(chunkPos: ChunkPos) {
		for (dx in -1..1) {
			for (dz in -1..1) {
				val neighborPos = ChunkPos(chunkPos.x + dx, chunkPos.z + dz)

				if (hasNeighborsLoaded(neighborPos)) {
					val neighborChunk = world.getChunk(neighborPos.x, neighborPos.z) ?: continue
					scanForSpawners(neighborChunk)
				}
			}
		}
	}

	private fun SafeContext.hasNeighborsLoaded(chunkPos: ChunkPos): Boolean {
		for (dx in -1..1) {
			for (dz in -1..1) {
				val neighborPos = ChunkPos(chunkPos.x + dx, chunkPos.z + dz)
				if (!world.chunkManager.isChunkLoaded(neighborPos.x, neighborPos.z)) return false
			}
		}
		return true
	}

	private fun SafeContext.scanForSpawners(chunk: WorldChunk) {
		deleteResultsInChunk(chunk.pos)
		chunk.blockEntities.forEach { (pos, entity) ->
			if (entity.type == BlockEntityType.MOB_SPAWNER) {
				if (onlyDungeon && world.getBlockState(pos.down()).block !in dungeonGroundBlocks) return@forEach

				if (hasLight(pos)) {
					detectedSpawners.add(pos)
					Notification.Coordinates.ifActive {
						info("Light up spawner detected at ${pos.x}, ${pos.y}, ${pos.z}")
					}
					Notification.Sound.ifActive {
						SoundManager.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP)
					}
				}
			}
		}
		chunkedRenderer.rebuildChunk(chunk.pos.x, chunk.pos.z)
	}

	private fun SafeContext.hasLight(pos: BlockPos): Boolean {
		for (dx in -1..1) {
			for (dy in -1..1) {
				for (dz in -1..1) {
					val checkPos = pos.add(dx, dy, dz)
					if (world.getLightLevel(checkPos) > lightThreshold) return true
				}
			}
		}
		return false
	}

	val BlockPos.chunkPos
		get() = ChunkPos(this)

	enum class Notification(override val displayName: String, override val description: String) : NamedEnum, Describable {
		Sound("Sound", "Play a sound when a spawner is detected"),
		Coordinates("Coordinates", "Logs coordinates of detected spawners in chat");

		fun isActive() = notification.contains(this)

		fun ifActive(action: () -> Unit) {
			if (isActive()) action()
		}
	}
}