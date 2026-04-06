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

package com.lambda.module.modules.player

import com.lambda.config.settings.complex.Bind
import com.lambda.config.settings.complex.KeybindSetting.Companion.onPress
import com.lambda.context.SafeContext
import com.lambda.event.events.ButtonEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.graphics.mc.renderer.ImmediateRenderer.Companion.immediateRenderer
import com.lambda.interaction.managers.rotating.Rotation
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.util.KeyCode
import com.lambda.util.extension.rotation
import com.lambda.util.math.flooredBlockPos
import com.lambda.util.world.raycast.RayCastUtils.orMiss
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import java.awt.Color

object AreaSelection : Module(
    name = "Area Selection",
    description = "Select and drag areas",
    tag = ModuleTag.PLAYER,
) {
    var pos1 by setting("Position 1", BlockPos.ORIGIN, "First corner of the selection area")
    var pos2 by setting("Position 2", BlockPos.ORIGIN, "Second corner of the selection area")
    private val resetSelectionBoxes by setting("Reset Selection Boxes", Bind.EMPTY, "Reset the positions of the area selection boxes to your current view")
        .onPress {
            val basePos = currentViewPosition().flooredBlockPos
            pos1 = basePos
            pos2 = basePos
        }

    private enum class HandleAxis(val direction: Vec3d, val color: Color) {
        POS_X(Vec3d(1.0, 0.0, 0.0), Color(255, 60, 60)),
        NEG_X(Vec3d(-1.0, 0.0, 0.0), Color(255, 60, 60)),
        POS_Y(Vec3d(0.0, 1.0, 0.0), Color(60, 255, 60)),
        NEG_Y(Vec3d(0.0, -1.0, 0.0), Color(60, 255, 60)),
        POS_Z(Vec3d(0.0, 0.0, 1.0), Color(60, 60, 255)),
        NEG_Z(Vec3d(0.0, 0.0, -1.0), Color(60, 60, 255));
    }

    private data class ArrowHandle(val axis: HandleAxis, val boxIndex: Int)

    private const val ARROW_LENGTH = 0.6
    private const val ARROW_THICKNESS = 0.06
    private const val ARROW_HEAD_SIZE = 0.12
    private const val ARROW_HEAD_LENGTH = 0.15

    private var hoveredHandle: ArrowHandle? = null
    private var draggingHandle: ArrowHandle? = null
    private var dragStartPos: Vec3d? = null
    private var dragStartBlockPos: BlockPos? = null

    private var hoveredBoxIndex: Int? = null
    private var selectedBoxIndex: Int? = null

    private fun SafeContext.currentViewPosition(): Vec3d = if (Freecam.isEnabled) Freecam.cameraPosition else player.eyePos

    private fun SafeContext.currentViewRotation(): Rotation = if (Freecam.isEnabled) Freecam.cameraRotation else player.rotation

    private fun arrowShaftBox(center: Vec3d, axis: HandleAxis): Box {
        val dir = axis.direction
        val halfThick = ARROW_THICKNESS / 2.0
        return when {
            dir.x != 0.0 -> {
                val startX = center.x
                val endX = center.x + dir.x * ARROW_LENGTH
                Box(
                    minOf(startX, endX), center.y - halfThick, center.z - halfThick,
                    maxOf(startX, endX), center.y + halfThick, center.z + halfThick
                )
            }
            dir.y != 0.0 -> {
                val startY = center.y
                val endY = center.y + dir.y * ARROW_LENGTH
                Box(
                    center.x - halfThick, minOf(startY, endY), center.z - halfThick,
                    center.x + halfThick, maxOf(startY, endY), center.z + halfThick
                )
            }
            else -> {
                val startZ = center.z
                val endZ = center.z + dir.z * ARROW_LENGTH
                Box(
                    center.x - halfThick, center.y - halfThick, minOf(startZ, endZ),
                    center.x + halfThick, center.y + halfThick, maxOf(startZ, endZ)
                )
            }
        }
    }

    private fun arrowHeadBox(center: Vec3d, axis: HandleAxis): Box {
        val dir = axis.direction
        val halfHead = ARROW_HEAD_SIZE / 2.0
        val tipStart = center.add(dir.multiply(ARROW_LENGTH))
        val tipEnd = tipStart.add(dir.multiply(ARROW_HEAD_LENGTH))
        return when {
            dir.x != 0.0 -> Box(
                minOf(tipStart.x, tipEnd.x), tipStart.y - halfHead, tipStart.z - halfHead,
                maxOf(tipStart.x, tipEnd.x), tipStart.y + halfHead, tipStart.z + halfHead
            )
            dir.y != 0.0 -> Box(
                tipStart.x - halfHead, minOf(tipStart.y, tipEnd.y), tipStart.z - halfHead,
                tipStart.x + halfHead, maxOf(tipStart.y, tipEnd.y), tipStart.z + halfHead
            )
            else -> Box(
                tipStart.x - halfHead, tipStart.y - halfHead, minOf(tipStart.z, tipEnd.z),
                tipStart.x + halfHead, tipStart.y + halfHead, maxOf(tipStart.z, tipEnd.z)
            )
        }
    }

    private fun faceCenterOf(selectionBox: Box, axis: HandleAxis): Vec3d {
        val cx = (selectionBox.minX + selectionBox.maxX) / 2.0
        val cy = (selectionBox.minY + selectionBox.maxY) / 2.0
        val cz = (selectionBox.minZ + selectionBox.maxZ) / 2.0
        return when (axis) {
            HandleAxis.POS_X -> Vec3d(selectionBox.maxX, cy, cz)
            HandleAxis.NEG_X -> Vec3d(selectionBox.minX, cy, cz)
            HandleAxis.POS_Y -> Vec3d(cx, selectionBox.maxY, cz)
            HandleAxis.NEG_Y -> Vec3d(cx, selectionBox.minY, cz)
            HandleAxis.POS_Z -> Vec3d(cx, cy, selectionBox.maxZ)
            HandleAxis.NEG_Z -> Vec3d(cx, cy, selectionBox.minZ)
        }
    }

    private fun arrowHitBox(selectionBox: Box, axis: HandleAxis): Box {
        val faceCenter = faceCenterOf(selectionBox, axis)
        val shaft = arrowShaftBox(faceCenter, axis)
        val head = arrowHeadBox(faceCenter, axis)
        return Box(
            minOf(shaft.minX, head.minX), minOf(shaft.minY, head.minY), minOf(shaft.minZ, head.minZ),
            maxOf(shaft.maxX, head.maxX), maxOf(shaft.maxY, head.maxY), maxOf(shaft.maxZ, head.maxZ)
        )
    }

    private fun projectOntoAxis(eye: Vec3d, lookDir: Vec3d, axisOrigin: Vec3d, axisDir: Vec3d): Vec3d {
        val w = eye.subtract(axisOrigin)
        val a = lookDir.dotProduct(lookDir)
        val b = lookDir.dotProduct(axisDir)
        val c = axisDir.dotProduct(axisDir)
        val d = lookDir.dotProduct(w)
        val e = axisDir.dotProduct(w)
        val denom = a * c - b * b
        if (denom < 1e-8) return axisOrigin
        val s = (a * e - b * d) / denom
        return axisOrigin.add(axisDir.multiply(s))
    }

    init {
        immediateRenderer("Area Selection") { safeContext ->
            val eye = safeContext.currentViewPosition()
            val lookDir = safeContext.currentViewRotation().vector

            val boxes = listOf(
                0 to Box.of(pos1.toCenterPos(), 1.0, 1.0, 1.0),
                1 to Box.of(pos2.toCenterPos(), 1.0, 1.0, 1.0)
            )
            val boxColors = listOf(
                Color(255, 0, 0, 50) to Color(255, 0, 0, 100),
                Color(0, 0, 255, 50) to Color(0, 0, 255, 100)
            )

            var closestHandle: ArrowHandle? = null
            var closestDist = Double.MAX_VALUE

            if (draggingHandle == null) {
                for ((boxIndex, selBox) in boxes) {
                    for (axis in HandleAxis.entries) {
                        val hitBox = arrowHitBox(selBox, axis)
                        val result = hitBox.raycast(eye, eye.add(lookDir.multiply(50.0)))
                        if (result.isPresent) {
                            val dist = result.get().squaredDistanceTo(eye)
                            if (dist < closestDist) {
                                closestDist = dist
                                closestHandle = ArrowHandle(axis, boxIndex)
                            }
                        }
                    }
                }
            }
            hoveredHandle = closestHandle

            if (closestHandle == null && draggingHandle == null) {
                var closestBoxDist = Double.MAX_VALUE
                var closestBoxIdx: Int? = null
                for ((boxIndex, selBox) in boxes) {
                    val result = selBox.raycast(eye, eye.add(lookDir.multiply(50.0)))
                    if (result.isPresent) {
                        val dist = result.get().squaredDistanceTo(eye)
                        if (dist < closestBoxDist) {
                            closestBoxDist = dist
                            closestBoxIdx = boxIndex
                        }
                    }
                }
                hoveredBoxIndex = closestBoxIdx
            } else {
                hoveredBoxIndex = null
            }

            for ((boxIndex, selBox) in boxes) {
                val (fill, outline) = boxColors[boxIndex]
                val isSelected = selectedBoxIndex == boxIndex
                val isHoveredBox = hoveredBoxIndex == boxIndex

                val boxFill = when {
                    isSelected -> Color(fill.red, fill.green, fill.blue, 100)
                    isHoveredBox -> Color(fill.red, fill.green, fill.blue, 80)
                    else -> fill
                }
                val boxOutline = when {
                    isSelected -> Color(255, 255, 0, 200)
                    isHoveredBox -> Color(255, 255, 255, 150)
                    else -> outline
                }

                box(selBox) {
                    colors(boxFill, boxOutline)
                }

                for (axis in HandleAxis.entries) {
                    val faceCenter = faceCenterOf(selBox, axis)
                    val handle = ArrowHandle(axis, boxIndex)
                    val isHovered = hoveredHandle == handle
                    val isDragging = draggingHandle == handle

                    val baseColor = axis.color
                    val shaftColor = when {
                        isDragging -> Color(255, 255, 100, 220)
                        isHovered -> Color(
                            minOf(baseColor.red + 80, 255),
                            minOf(baseColor.green + 80, 255),
                            minOf(baseColor.blue + 80, 255),
                            220
                        )
                        else -> Color(baseColor.red, baseColor.green, baseColor.blue, 160)
                    }
                    val headColor = when {
                        isDragging -> Color(255, 255, 50, 240)
                        isHovered -> Color(
                            minOf(baseColor.red + 100, 255),
                            minOf(baseColor.green + 100, 255),
                            minOf(baseColor.blue + 100, 255),
                            240
                        )
                        else -> Color(baseColor.red, baseColor.green, baseColor.blue, 200)
                    }

                    box(arrowShaftBox(faceCenter, axis)) { colors(shaftColor, shaftColor) }
                    box(arrowHeadBox(faceCenter, axis)) { colors(headColor, headColor) }
                }
            }

            val areaBox = Box(
                minOf(pos1.x, pos2.x).toDouble(),
                minOf(pos1.y, pos2.y).toDouble(),
                minOf(pos1.z, pos2.z).toDouble(),
                maxOf(pos1.x, pos2.x) + 1.0,
                maxOf(pos1.y, pos2.y) + 1.0,
                maxOf(pos1.z, pos2.z) + 1.0
            )
            box(areaBox) {
                hideFill()
                colors(Color(0, 0, 0, 0), Color(255, 255, 255, 180))
            }
        }

        listen<ButtonEvent.Mouse.Click> { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (event.isPressed) {
                    val hovered = hoveredHandle
                    if (hovered != null) {
                        draggingHandle = hovered
                        val startBlock = if (hovered.boxIndex == 0) pos1 else pos2
                        val faceCenter = faceCenterOf(Box.of(startBlock.toBottomCenterPos(), 1.0, 1.0, 1.0), hovered.axis)
                        dragStartPos = projectOntoAxis(currentViewPosition(), currentViewRotation().vector, faceCenter, hovered.axis.direction)
                        dragStartBlockPos = startBlock
                        event.cancel()
                        return@listen
                    }

                    val hoveredBox = hoveredBoxIndex
                    if (hoveredBox != null) {
                        selectedBoxIndex = if (selectedBoxIndex == hoveredBox) null else hoveredBox
                        event.cancel()
                        return@listen
                    }
                } else if (event.isReleased && draggingHandle != null) {
                    draggingHandle = null
                    dragStartPos = null
                    dragStartBlockPos = null
                    event.cancel()
                }
            }

            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.isPressed) {
                val selected = selectedBoxIndex ?: return@listen
                val hitResult = currentViewRotation().rayCast(100.0, currentViewPosition()).orMiss ?: return@listen
                if (hitResult.type == HitResult.Type.MISS) return@listen

                val targetPos = hitResult.pos.flooredBlockPos
                if (selected == 0) pos1 = targetPos else pos2 = targetPos
                event.cancel()
            }
        }

        listen<ButtonEvent.Keyboard.Press> { event ->
            if (event.keyCode == KeyCode.Escape.code && event.isPressed && selectedBoxIndex != null) {
                selectedBoxIndex = null
                event.cancel()
            }
        }

        listen<TickEvent.Pre> {
            val handle = draggingHandle ?: return@listen
            val startProjection = dragStartPos ?: return@listen
            val startBlock = dragStartBlockPos ?: return@listen

            if (GLFW.glfwGetMouseButton(mc.window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                draggingHandle = null
                dragStartPos = null
                dragStartBlockPos = null
                return@listen
            }

            val faceCenter = faceCenterOf(Box.of(startBlock.toBottomCenterPos(), 1.0, 1.0, 1.0), handle.axis)
            val currentProjection = projectOntoAxis(currentViewPosition(), currentViewRotation().vector, faceCenter, handle.axis.direction)

            val delta = currentProjection.subtract(startProjection)
            val axisDelta = handle.axis.direction.multiply(delta.dotProduct(handle.axis.direction))
            val newPos = startBlock.add(
                Math.round(axisDelta.x).toInt(),
                Math.round(axisDelta.y).toInt(),
                Math.round(axisDelta.z).toInt()
            )

            if (handle.boxIndex == 0) pos1 = newPos else pos2 = newPos
        }

        onDisable {
            draggingHandle = null
            dragStartPos = null
            dragStartBlockPos = null
            hoveredHandle = null
            hoveredBoxIndex = null
            selectedBoxIndex = null
        }
    }
}

