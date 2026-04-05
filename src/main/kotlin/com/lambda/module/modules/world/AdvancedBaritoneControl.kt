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
import com.lambda.event.events.PlayerEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.util.Communication.info
import it.unimi.dsi.fastutil.longs.*
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos
import kotlin.math.abs
import kotlin.time.measureTime

object AdvancedBaritoneControl : Module(
	name = "AdvanceBaritoneControl",
	description = "Module to direct Baritone to do things like mining and building using a simple planing algorithm",
	tag = ModuleTag.WORLD
) {
	val homePos by setting("Home Position", BlockPos.ORIGIN, description = "Position the pathfinder should always be able to return to")
	val maxDistance by setting("Max Distance", 10, 1..50, description = "Maximum distance from home position to grow the graph to. Higher values allow for bigger graphs.")
	val logTimeTake by setting("Log Time Take", false, description = "Whether to log the time it takes to update the graph when blocks are changed")

	val closedSet = Long2ObjectArrayMap<Node>()
	val openSet = mutableSetOf<Node>()

	init {
		listen<PlayerEvent.Interact.Block> { event ->
			if (player.mainHandStack.item == Items.DIAMOND_SHOVEL) {
				val node = closedSet.getOrDefault(event.blockHitResult.blockPos.offset(event.blockHitResult.side).asLong(), null) ?: return@listen
				info("Children: ${node.childNodes.size} Weak Links: ${node.weakLinks.size} Distance from home: ${node.distanceFromStart}")

				val targetNode = closedSet.getOrDefault(event.blockHitResult.blockPos.asLong(), null) ?: return@listen
				info("Block node valid: ${targetNode.isBlockValid()}")
				handleBlockUpdate(event.blockHitResult.blockPos)

				return@listen
			}
			if (player.mainHandStack.item == Items.FEATHER) {
				val homeNode = Node(homePos, 0, null)
				openSet.clear()
				closedSet.clear()
				openSet.add(homeNode)
				closedSet.put(homeNode.pos.asLong(), homeNode)
				grow(homePos, homeNode)
			}
		}

		listen<WorldEvent.BlockUpdate.Client> { event ->
			handleBlockUpdate(event.pos)
		}
	}

	/**
	 * Removes a node from the graph and returns all connected child nodes
	 */
	private fun SafeContext.removeNode(node: Node): List<Node> {
		closedSet.remove(node.pos.asLong())
		openSet.remove(node)
		node.parentNode?.childNodes?.remove(node)

		node.neighbors().forEach { neighbor ->
			val existingNode = closedSet.getOrDefault(neighbor.asLong(), null) ?: return@forEach
			existingNode.weakLinks.remove(node)
			existingNode.childNodes.remove(node)
		}

		return node.childNodes.toList()
	}

	private fun SafeContext.handleBlockUpdate(pos: BlockPos) {
		val timeTaken = measureTime {
			for (x in -1..1) {
				for (z in -1..1) {
					for (y in 0..1) {
						if (abs(x) > 0 && abs(z) > 0) continue // no diagonals

						val checkPos = BlockPos(pos.x + x, pos.y + y, pos.z + z)
						val node = closedSet.getOrDefault(checkPos.asLong(), null) ?: continue
						if (!node.isBlockValid()) {
							removeAndPruneNodes(node)
						}
					}
				}
			}
			// Grow back
			for (x in -1..1) {
				for (z in -1..1) {
					for (y in 0..1) {
						if (abs(x) > 0 && abs(z) > 0) continue // no diagonals

						val checkPos = BlockPos(pos.x + x, pos.y + y, pos.z + z)
						if (checkPos.asLong() in closedSet) {
							val node = closedSet[checkPos.asLong()] ?: continue
							if (node.isBlockValid()) {
								//							info("Node at $checkPos is now valid, growing back")
								grow(homePos, node)
							}
						}
					}
				}
			}
		}
		if (logTimeTake) {
			info("Graph update took ${timeTaken.inWholeMicroseconds} microseconds")
		}
	}

	/**
	 * Junction nodes are nodes that have more than 2 connections
	 */
	private fun isNodeJunction(node: Node): Boolean {
		return node.childNodes.isNotEmpty()
	}

	/**
	 * Removes nodes that are no longer valid from the open and closed set.
	 * A node is no longer valid if the parent node no longer exists in the closed set.
	 * Expects the start node to already be removed from the closed set
	 */
	private fun SafeContext.removeAndPruneNodes(start: Node) {
		val openSet0: MutableSet<Node> = mutableSetOf()
		val nodesToGrowBack = mutableSetOf<Node>()
		openSet0.addAll(start.childNodes)
		removeNode(start)

		while (openSet0.isNotEmpty()) {
			val node = openSet0.first()
			openSet0.remove(node)

			// parentNode == null is root/home node
			if (node.parentNode != null && node.parentNode.pos.asLong() !in closedSet) {
				removeNode(node)
				node.weakLinks.forEach { weakLink ->
					if (weakLink.pos.asLong() in closedSet) {
						nodesToGrowBack.add(weakLink)
					}
				}
				openSet0.addAll(node.childNodes)
			}
		}
		for (node in nodesToGrowBack) {
			if (node.isBlockValid() && node.pos.asLong() in closedSet) {
//				info("Node at ${node.pos} is now valid, growing back")
				grow(homePos, node)
			}
		}
	}

	private fun SafeContext.grow(origin: BlockPos, node: Node, depth: Int = 0) {
		if (node.pos.getManhattanDistance(origin) > maxDistance) return

		node.neighbors().forEach { neighbor ->
			if (neighbor.asLong() !in closedSet) {
				val newNode = Node(neighbor, node.distanceFromStart + 1, node)
				openSet.add(newNode)
				closedSet.put(newNode.pos.asLong(), newNode)
				node.childNodes.add(newNode)
				grow(origin, newNode, depth + 1)
			}
		}
		node.neighbors().forEach { neighbor ->
			val existingNode = closedSet.getOrDefault(neighbor.asLong(), null)
			if (existingNode != null && existingNode !in node.childNodes && existingNode != node.parentNode) {
				existingNode.weakLinks.add(node)
				node.weakLinks.add(existingNode)
				return@forEach
			}
		}
	}

	class Node(val pos: BlockPos, val distanceFromStart: Int, val parentNode: Node?, val childNodes: MutableSet<Node> = mutableSetOf(), val weakLinks: MutableSet<Node> = mutableSetOf()) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Node) return false
			return pos == other.pos
		}

		override fun hashCode(): Int {
			return pos.hashCode()
		}

		context(safeContext: SafeContext)
		fun isBlockValid(blockPos: BlockPos? = null): Boolean {
			val pos = blockPos ?: this.pos
			with(safeContext) {
				return world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) && world.getBlockState(pos).isAir
			}
		}

		context(safeContext: SafeContext)
		fun neighbors(): List<BlockPos> {
			val list = mutableListOf<BlockPos>()
			// same y level forward
			for (x in -1..1) {
				for (z in -1..1) {
					if (x == 0 && z == 0) continue
					if (abs(x) > 0 && abs(z) > 0) continue // no diagonals

					val pos = BlockPos(pos.x + x, pos.y, pos.z + z)
					if (isBlockValid(pos)) {
						list.add(BlockPos(pos))
					}
				}
			}
			return list
		}
	}
}