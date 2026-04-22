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

import baritone.api.pathing.goals.GoalBlock
import baritone.api.pathing.goals.GoalComposite
import com.lambda.config.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.applyEdits
import com.lambda.context.AutomatedSafeContext
import com.lambda.context.SafeContext
import com.lambda.event.events.PlayerEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.BaritoneManager
import com.lambda.interaction.construction.simulation.context.BuildContext
import com.lambda.interaction.managers.breaking.BreakRequest.Companion.breakRequest
import com.lambda.module.Module
import com.lambda.module.modules.player.AreaSelection.pos1
import com.lambda.module.modules.player.AreaSelection.pos2
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafeAutomated
import com.lambda.util.Communication.info
import com.lambda.util.Timer
import com.lambda.util.math.dist
import com.lambda.util.math.distSq
import fi.dy.masa.litematica.data.DataManager
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

object AdvancedBaritoneControl : Module(
	name = "AdvanceBaritoneControl",
	description = "Module to direct Baritone to do things like mining and building using a simple planing algorithm",
	tag = ModuleTag.WORLD
) {
	var homePos by setting("Home Position", BlockPos.ORIGIN, description = "Position the pathfinder should always be able to return to")
	val maxDistance by setting("Max Distance", 10, 1..50, description = "Maximum distance from home position to grow the graph to. Higher values allow for bigger graphs.")
	val logTimeTake by setting("Log Time Take", false, description = "Whether to log the time it takes to update the graph when blocks are changed")
	val edgeRemappingForCost by setting("Edge Cost remapping", true, description = "Redirects node connections to more optimal paths when")
//	val edgeSumBalancing by setting("Edge Sum Balancing", true, description = "Balances incoming connections to a node to make the graph more uniform.")
	val areaSelectionMode by setting("Area Selection", AreaSelection.Baritone, description = "Selection Method to use")
	val clearInSlices by setting("Clear By Slices", false, description = "Clear blocks by vertical slices")
	val sliceThickness by setting("Slice Thickness", 3, 1..10, description = "Thickness of vertical slices when clearing by slices")

	val closedSet = Long2ObjectOpenHashMap<Node>()
	val queue = ArrayDeque<Node>()

	var clearJob: ClearAreaJob? = null

	init {
		setDefaultAutomationConfig {
			applyEdits {
				hideAllGroupsExcept(breakConfig, hotbarConfig)
			}
		}

		listen<PlayerEvent.Interact.Block> { event ->
			if (player.mainHandStack.item == Items.DIAMOND_SHOVEL) {
				val node = closedSet.getOrDefault(event.blockHitResult.blockPos.asLong(), null)
					?: closedSet.getOrDefault(event.blockHitResult.blockPos.offset(event.blockHitResult.side, 1).asLong(), null) ?: return@listen
				redirectNodeConnections(node)

				return@listen
			}
			if (player.mainHandStack.item == Items.FEATHER) {
				val hitPos = event.blockHitResult.blockPos.add(event.blockHitResult.side.vector)
				homePos = hitPos
				val homeNode = Node(homePos, 0, null)
				queue.clear()
				closedSet.clear()
				queue.add(homeNode)
				closedSet.put(homeNode.pos.asLong(), homeNode)
				expandNode(homeNode)
			}
		}

		listen<WorldEvent.BlockUpdate.Client> { event ->
			handleBlockUpdate(event.pos)
		}

		listen<TickEvent.Pre> {
			runSafeAutomated {
				clearJob?.tick()
				if (clearJob?.done == true) {
					info("Finished clearing area")
					clearJob = null
				}
			}
		}

		onDisable {
			BaritoneManager.cancel()
			clearJob = null
		}
	}

	fun clearArea(): ClearAreaJob {
		val job = ClearAreaJob()
		clearJob = job
		return job
	}

	/**
	 * Removes a node from the graph and returns all connected child nodes
	 */
	private fun SafeContext.removeNode(node: Node): List<Node> {
		closedSet.remove(node.pos.asLong())
		queue.remove(node)
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
					for (y in -2..2) {
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
					for (y in -1..2) {
						if (abs(x) > 0 && abs(z) > 0) continue // no diagonals

						val checkPos = BlockPos(pos.x + x, pos.y + y, pos.z + z)
						if (checkPos.asLong() in closedSet) {
							val node = closedSet[checkPos.asLong()] ?: continue
							if (node.isBlockValid()) {
								//							info("Node at $checkPos is now valid, growing back")
								expandNode(node)
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
		val childQueue: MutableSet<Node> = mutableSetOf()
		val nodesToGrowBack = mutableSetOf<Node>()
		childQueue.addAll(start.childNodes)
		removeNode(start)

		while (childQueue.isNotEmpty()) {
			val node = childQueue.first()
			childQueue.remove(node)

			// parentNode == null is root/home node
			node.parentNode?.takeIf { it.pos.asLong() !in closedSet }?.let {
				removeNode(node)
				node.weakLinks.forEach { weakLink ->
					if (weakLink.pos.asLong() in closedSet) {
						nodesToGrowBack.add(weakLink)
					}
				}
				childQueue.addAll(node.childNodes)
			}
		}
		for (node in nodesToGrowBack) {
			if (node.isBlockValid() && node.pos.asLong() in closedSet) {
				//				info("Node at ${node.pos} is now valid, growing back")
				expandNode(node)
			}
		}
	}

	private fun SafeContext.expandNode(start: Node) {
		if (start.distanceFromStart > maxDistance) return
		closedSet.put(start.pos.asLong(), start)
		queue.add(start)
		while (true) {
			val node = queue.removeFirstOrNull() ?: break
			node.neighbors().forEach { neighbor ->
				if (neighbor.asLong() !in closedSet) {
					val newNode = Node(neighbor, node.distanceFromStart + 1, node)
					queue.addLast(newNode)
					closedSet.put(newNode.pos.asLong(), newNode)
					node.childNodes.add(newNode)
				}
			}
			node.neighbors().forEach { neighbor ->
				closedSet.getOrDefault(neighbor.asLong(), null)
					?.takeIf { it !in node.childNodes && it != node.parentNode }
					?.let { existingNode ->
						existingNode.weakLinks.add(node)
						node.weakLinks.add(existingNode)
					}
			}
			if (edgeRemappingForCost) {
				val visited = mutableSetOf<Node>()
				val queue = ArrayDeque<Node>()
				queue.add(node)
				while (true) {
					val node = queue.removeFirstOrNull() ?: break
					visited.add(node)
					redirectNodeConnections(node)
						.filter { it !in visited }
						.forEach { if (it !in queue) queue.add(it) }
				}
			}
		}
	}

	/**
	 * Redirects a nodes parent node to a weak link if the weak link node has a shorter distance from the start
	 *
	 * If a shorter node (betterNode) path exists:
	 *
	 * The current node gets it's parent node set to the betterNode
	 * The betterNode node gets it's weak link to the current node removed
	 * The betterNode node gets the current node added as a child
	 *
	 * The old parent node gets the current node removed as a child
	 * The old parent node gets the current node added as a weak link
	 *
	 * The current node gets the old parent node added as a weak link
	 * The current node gets the betterNode removed as a weak link
	 *
	 * The current node gets it's distance from start updated to the betterNode distance from start + 1
	 *
	 * Returns a list of affected nodes. Affected nodes might have an incorrect distance from start until updated.
	 */
	private fun SafeContext.redirectNodeConnections(node: Node): List<Node> {
		val parentNode = node.parentNode ?: return emptyList()

		val affectedNodes = mutableListOf<Node>()
		if (parentNode.distanceFromStart < node.distanceFromStart - 1) {
			node.distanceFromStart = parentNode.distanceFromStart + 1
			affectedNodes.addAll(node.childNodes)
		}
		var betterNode: Node? = null
		for (weakLink in node.weakLinks) {
			if (weakLink.distanceFromStart < parentNode.distanceFromStart) {
				if ((betterNode?.distanceFromStart ?: Int.MAX_VALUE) < weakLink.distanceFromStart) {
					continue
				}
				betterNode = weakLink
			}
		}
		if (betterNode != null) {
			// Redirect to better node
			node.parentNode = betterNode
			betterNode.weakLinks.remove(node)
			betterNode.childNodes.add(node)

			parentNode.childNodes.remove(node)
			parentNode.weakLinks.add(node)

			node.weakLinks.add(parentNode)
			node.weakLinks.remove(betterNode)

			node.distanceFromStart = betterNode.distanceFromStart + 1
			affectedNodes.addAll(node.weakLinks)
		}
		return affectedNodes
	}

	class Node(val pos: BlockPos, var distanceFromStart: Int, var parentNode: Node?, val childNodes: MutableSet<Node> = mutableSetOf(), val weakLinks: MutableSet<Node> = mutableSetOf()) {
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
				return world.getBlockState(pos.down()).isSolidBlock(world, pos.down())
						&& world.getBlockState(pos).isAir
						&& world.getBlockState(pos.up()).isAir
			}
		}

		context(safeContext: SafeContext)
		fun neighbors(): List<BlockPos> {
			val list = mutableListOf<BlockPos>()
			// same y level forward
			for (x in -1..1) {
				for (z in -1..1) {
					for (y in -1..1) {
						if (x == 0 && z == 0) continue // skip self and vertical neighbors
						if (abs(x) > 0 && abs(z) > 0) continue // no diagonals

						val pos = BlockPos(pos.x + x, pos.y + y, pos.z + z)
						if (isBlockValid(pos)) {
							list.add(pos)
						}
					}
				}
			}
			return list
		}
	}

	private fun SafeContext.isStandingOnBlock(pos: BlockPos): Boolean {
		val playerBox = player.boundingBox.expand(-0.2, 1.0, -0.2)
		val blockBox = Box(pos)
		return playerBox.intersects(blockBox)
	}

	private fun inArea(pos: BlockPos): Boolean {
		val area = getArea() ?: return false
		return area.first.x <= pos.x && pos.x <= area.second.x
				&& area.first.y <= pos.y && pos.y <= area.second.y
				&& area.first.z <= pos.z && pos.z <= area.second.z
	}

	private fun getArea(): Pair<BlockPos, BlockPos>? {
		return when (areaSelectionMode) {
			AreaSelection.AreaSelector -> {
				val min = BlockPos(
					minOf(pos1.x, pos2.x),
					minOf(pos1.y, pos2.y),
					minOf(pos1.z, pos2.z)
				)
				val max = BlockPos(
					maxOf(pos1.x, pos2.x) + 1,
					maxOf(pos1.y, pos2.y) + 1,
					maxOf(pos1.z, pos2.z) + 1
				)
				min to max
			}
			AreaSelection.Baritone -> {
				val selection = BaritoneManager.selections().firstOrNull()?: return null
				selection.min() to selection.max()
			}
			AreaSelection.SchematicPlacement -> {
				if (!Printer.litematicaAvailable()) {
					return null
				}
				val placement = DataManager.getSchematicPlacementManager()?.allSchematicsPlacements?.firstOrNull {
					it.isEnabled
				} ?: return null
				placement.eclosingBox?.let {
					val pos1 = it.pos1
					val pos2 = it.pos2
					if (pos1 != null && pos2 != null) {
						val min = BlockPos(minOf(pos1.x, pos2.x), minOf(pos1.y, pos2.y), minOf(pos1.z, pos2.z))
						val max = BlockPos(maxOf(pos1.x, pos2.x) + 1, maxOf(pos1.y, pos2.y) + 1, maxOf(pos1.z, pos2.z) + 1)
						min to max
					} else {
						null
					}
				}
			}
		}
	}


	class ClearAreaJob {
		private val pendingActions = ConcurrentLinkedQueue<BuildContext>()
		var done = false
		var lastPos: BlockPos = BlockPos.ORIGIN
		val timeoutTimer = Timer()

		val slicesCleared = mutableListOf<BlockPos>()

		/**
		 * Ticks the clear area job
		 *
		 * @return true if the job is done, false otherwise
		 */
		context(automatedSafeContext: AutomatedSafeContext)
		fun tick(): Boolean {
			if (done) return true

			with(automatedSafeContext) {
				val deadEndNodesNear = deadEndNodesAroundMe(4f)
					.filter { !isStandingOnBlock(it.pos) }
				if (deadEndNodesNear.isNotEmpty()) {
					if (player.blockPos != lastPos) {
						timeoutTimer.reset()
						lastPos = player.blockPos
					}
					if (!timeoutTimer.timePassed(10.seconds)) {
						breakRequest(deadEndNodesNear.map { it.pos.down() }, pendingActions)
							?.submit()
						return false
					}
				}
			}
			if (BaritoneManager.isActive) {
				return false
			}

			val (deadEnds, all) = automatedSafeContext.getDeadenedNodes()
			val nodesToClear = if (deadEnds.isNotEmpty()) {
				deadEnds.sortedWith(Comparator.comparingInt { node -> node.pos.distSq(automatedSafeContext.player.blockPos) })
			} else {
				all.sortedWith(Comparator.comparingInt { node -> node.pos.distSq(automatedSafeContext.player.blockPos) }).reversed()
			}
			if (nodesToClear.isEmpty()) {
				val area = getArea()
				if (clearInSlices && area != null) {
					val maxSlice = slicesCleared.maxOfOrNull { it.x } ?: min(area.first.x, area.second.x)
					if (maxSlice > max(area.first.x, area.second.x)) {
						done = true
						return true
					}
					automatedSafeContext.info("Moving to next slice to clear")
					repeat(sliceThickness) { i ->
						slicesCleared.add(BlockPos(maxSlice + i, 0, 0))
					}
					return false
				}
				done = true
				return true
			}

			val parentNodes = nodesToClear.mapNotNull { it.parentNode }
			if (parentNodes.isEmpty()) {
				done = true
				return true
			}
			val goals = parentNodes.map { GoalBlock(it.pos) }.toTypedArray()
			val compGoal = GoalComposite(*goals)

			BaritoneManager.setGoalAndPath(compGoal)
			return false
		}

		fun SafeContext.deadEndNodesAroundMe(range: Float): List<Node> {
			return BlockPos.iterateOutwards(player.blockPos, 5, 2, 5)
				.mapNotNull { closedSet.getOrDefault(it.asLong(), null) }
				.filter { it.pos.dist(player.eyePos) < range }
				.applyIf(clearInSlices) {
					filter { inSlice(it.pos.x) }
				}
				.filter { it.childNodes.isEmpty() }
		}

		/**
		 * Checks if a given x coordinates lays within the current slice being cleared. Only relevant if clearInSlices is true and an area is selected.
		 */
		fun inSlice(x: Int): Boolean {
			if (!clearInSlices) return true

			val area = getArea() ?: return true
			val currentSlice = if (slicesCleared.isEmpty()) min(area.first.x, area.second.x)
			else slicesCleared.maxOf { it.x } + 1
			return x >= currentSlice && x < currentSlice + sliceThickness
		}

		fun SafeContext.getDeadenedNodes(): Pair<MutableList<Node>, MutableList<Node>> {
			val deadEndNodes = mutableListOf<Node>()
			val allNodes = mutableListOf<Node>()
			if (getArea() == null) return deadEndNodes to allNodes

			for (node in closedSet.values) {
				if (node.parentNode == null) continue
				if (!inArea(node.pos.down())) continue
				if (!inSlice(node.pos.x)) continue

				if (node.isBlockValid()) {
					allNodes.add(node)
					if (node.childNodes.isEmpty()) deadEndNodes.add(node)
				}
			}
			return deadEndNodes to allNodes
		}
	}

	inline fun <T> T.applyIf(condition: Boolean, block: T.() -> T): T {
		return if (condition) block() else this
	}

	enum class AreaSelection {
		Baritone,
		AreaSelector,
		SchematicPlacement
	}
}