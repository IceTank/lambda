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
import com.lambda.module.modules.debug.ChunkedRendererTest.updated
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafe
import it.unimi.dsi.fastutil.objects.ReferenceArraySet
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import java.awt.Color
import java.util.Collections
import kotlin.math.abs

object NoSpawnerDetector : Module(
	name = "NoSpawnerDetector",
	description = "Detects dungeon chests without spawners",
	tag = ModuleTag.WORLD
) {
	val dungeonGroundBlocks = ReferenceArraySet<Block>(arrayOf(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE))
	val detectedChests: MutableSet<BlockPos> = Collections.synchronizedSet<BlockPos>(mutableSetOf<BlockPos>())

	val esp by setting("ESP", true, description = "Highlight detected chests with a box")
	val espColor by setting("Color", Color(255, 0, 0, 100), description = "Color of detected chests") { esp }
	val tracer by setting("Tracer", true, description = "Draw a line from the player to detected chests")
	val tracerColor by setting("Tracer Color", Color(255, 0, 0, 100), description = "Color of tracers to detected chests") { tracer }
    val tracerWidth by setting("Tracer Width", 2f, 0.1f..10f, 0.1f, description = "Width of tracers to detected chests") { tracer }

	init {
		chunkedRenderer("ChunkedRendererNoSpawnerDetector", depthTest = { false }) { world, pos ->
			runSafe {
				if (updated) return@chunkedRenderer

				if (esp) {
					detectedChests.forEach {
						box(it) {
							allColors(espColor)
						}
					}
				}
			}
		}

		immediateRenderer("No Spawner Detector Immediate Renderer") {
			if (tracer) detectedChests.forEach {
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
			scanForChests(event.chunk)
		}

		listen<WorldEvent.ChunkEvent.Unload> { event ->
            detectedChests.removeIf {
				it.chunkPos == event.chunk.pos
            }
        }

		onDisable {
			detectedChests.clear()
		}
	}

	private fun SafeContext.scanForChests(chunk: WorldChunk) {
		chunk.blockEntities.forEach { (pos, entity) ->
			if (entity.type == BlockEntityType.CHEST) {
				if (isSpawnerNear(pos)) {
                    return@forEach // Skip if a spawner is nearby
                }
				if (touchesDungeonGround(pos)) {

				}
			}
		}
	}

	private fun SafeContext.isSpawnerNear(pos: BlockPos): Boolean {
		BlockPos.iterateOutwards(pos, 8, 4, 8).forEach { checkPos ->
            val blockEntity = mc.world?.getBlockEntity(checkPos)
            if (blockEntity?.type == BlockEntityType.MOB_SPAWNER) {
                return true
            }
        }
		return false
	}

	private fun SafeContext.touchesDungeonGround(pos: BlockPos): Boolean {
		for (dx in -1..1) {
            for (dz in -1..1) {
				if (dx == 0 && dz == 0) continue // Skip the chest block itself
	            if (abs(dx) + abs(dz) > 1) continue // Only check adjacent blocks (not diagonals)
                val block = mc.world?.getBlockState(pos.add(dx, 0, dz))?.block ?: continue
                if (block in dungeonGroundBlocks) return true
            }
        }
		return false
	}

	val BlockPos.chunkPos
	    get() = ChunkPos(this)
}