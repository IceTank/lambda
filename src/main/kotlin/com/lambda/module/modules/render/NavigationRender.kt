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

package com.lambda.module.modules.render

import com.lambda.graphics.mc.RenderBuilder
import com.lambda.graphics.mc.RenderBuilder.SDFShadow
import com.lambda.graphics.mc.RenderBuilder.SDFStyle
import com.lambda.graphics.mc.renderer.TickedRenderer.Companion.tickedRenderer
import com.lambda.module.Module
import com.lambda.module.modules.world.AdvancedBaritoneControl
import com.lambda.module.modules.world.AdvancedBaritoneControl.homePos
import com.lambda.module.tag.ModuleTag
import com.lambda.util.math.flooredBlockPos
import com.lambda.util.math.setAlpha
import com.lambda.util.math.times
import com.lambda.util.math.vec3d
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.awt.Color

object NavigationRender : Module(
	name = "NavigationRender",
	description = "Debug render for Lambda Navigation data",
	tag = ModuleTag.RENDER
) {
	val depthTest by setting("Depth Test", true, description = "Whether to render with depth test or not. Disable if you want to see renders through walls")
	val horizontalRange by setting("Horizontal Range", 10, 1..50, description = "Horizontal range to render light levels for")
	val verticalRange by setting("Vertical Range", 5, 1..50, description = "Vertical range to render light levels for")
	val distanceFromStart by setting("Distance From Start", false, description = "Display distance from start")

	init {
		tickedRenderer("LightLevels Ticked Renderer", { depthTest }) { safeContext ->
			val positions = hashSetOf<BlockPos>()

			with(safeContext) {
				buildPositions(positions, mc.gameRenderer.camera.pos.flooredBlockPos)
				positions.forEach { pos ->
					buildRender(pos)
				}
				buildHomeRender(homePos)
			}
		}
	}

	private fun buildPositions(collection: MutableCollection<BlockPos>, center: BlockPos) {
		(center.x - horizontalRange..center.x + horizontalRange).forEach { x ->
			(center.z - horizontalRange..center.z + horizontalRange).forEach { z ->
				(center.y - verticalRange..center.y + verticalRange).forEach { y ->
					collection.add(BlockPos(x, y, z))
				}
			}
		}
	}

	private fun RenderBuilder.buildRender(pos: BlockPos) {
		val renderVec = pos.vec3d
		val trueSize = (16 - 14) / 32.0
		val corner1 = renderVec.add(trueSize, 0.05, trueSize)
		val corner2 = renderVec.add(1.0 - trueSize, 0.05, trueSize)
		val corner3 = renderVec.add(1.0 - trueSize, 0.05, 1.0 - trueSize)
		val corner4 = renderVec.add(trueSize, 0.05, 1.0 - trueSize)
		val node = AdvancedBaritoneControl.closedSet.getOrDefault(pos.asLong(), null) ?: return
		val color = if (node.childNodes.isNotEmpty()) Color.ORANGE else Color.RED

		node.weakLinks.forEach { weakLink ->
			val weakLinkVec = weakLink.pos.vec3d.add(0.5, 0.5, 0.5)
			arrow(renderVec.add(0.5, 0.5, 0.5), weakLinkVec, Color.YELLOW, width = 0.02f)
		}

		filledQuad(corner1, corner2, corner3, corner4, color.setAlpha(0.2))
		polyline(listOf(corner1, corner2, corner3, corner4, corner1), color)

		node.parentNode?.let { parent ->
			val parentVec = parent.pos.toCenterPos()
			arrow(renderVec.add(0.5, 0.5, 0.5).offset(Direction.UP, 0.2), parentVec.offset(Direction.UP, 0.2), Color.GREEN, width = 0.02f)
		}
		if (distanceFromStart) {
			worldText(
				node.distanceFromStart.toString(),
				node.pos.toCenterPos().offset(Direction.UP, -0.2),
				style = SDFStyle(
					shadow = SDFShadow()
				),
				size = 0.2f
			)
		}
	}

	private fun RenderBuilder.arrow(start: Vec3d, end: Vec3d, color: Color, width: Float) {
		line(start, end, color, width)
		val dir = end.subtract(start).normalize()
		val perpendicular = Vec3d(-dir.z, 0.0, dir.x).normalize() * (width * 2)
		val arrowHead1 = end.subtract(dir * (width * 4)).add(perpendicular)
		val arrowHead2 = end.subtract(dir * (width * 4)).subtract(perpendicular)
		line(end, arrowHead1, color, width)
		line(end, arrowHead2, color, width)
	}

	private fun RenderBuilder.buildHomeRender(pos: BlockPos) {
		val renderVec = pos.vec3d
		val trueSize = (16 - 14) / 32.0
		val corner1 = renderVec.add(trueSize, 0.05, trueSize)
		val corner2 = renderVec.add(1.0 - trueSize, 0.05, trueSize)
		val corner3 = renderVec.add(1.0 - trueSize, 0.05, 1.0 - trueSize)
		val corner4 = renderVec.add(trueSize, 0.05, 1.0 - trueSize)
		val color = Color.GREEN

		filledQuad(corner1, corner2, corner3, corner4, color.setAlpha(0.2))
		polyline(listOf(corner1, corner2, corner3, corner4, corner1), color)
	}
}