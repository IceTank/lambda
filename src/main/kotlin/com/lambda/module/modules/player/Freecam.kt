/*
 * Copyright 2025 Lambda
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

import com.lambda.Lambda.mc
import com.lambda.config.settings.complex.Bind
import com.lambda.config.settings.complex.KeybindSetting.Companion.onPress
import com.lambda.event.events.ButtonEvent
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.PlayerEvent
import com.lambda.event.events.RenderEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.graphics.mc.renderer.ImmediateRenderer.Companion.immediateRenderer
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.Rotation
import com.lambda.interaction.managers.rotating.RotationConfig
import com.lambda.interaction.managers.rotating.RotationMode
import com.lambda.interaction.managers.rotating.visibilty.lookAt
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafeAutomated
import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import com.lambda.util.extension.rotation
import com.lambda.util.math.flooredBlockPos
import com.lambda.util.math.interpolate
import com.lambda.util.math.plus
import com.lambda.util.math.times
import com.lambda.util.player.MovementUtils.calcMoveRad
import com.lambda.util.player.MovementUtils.cancel
import com.lambda.util.player.MovementUtils.handledByBaritone
import com.lambda.util.player.MovementUtils.isInputting
import com.lambda.util.player.MovementUtils.movementVector
import com.lambda.util.player.MovementUtils.newMovementInput
import com.lambda.util.player.MovementUtils.roundedForward
import com.lambda.util.player.MovementUtils.roundedStrafing
import com.lambda.util.player.MovementUtils.verticalMovement
import com.lambda.util.world.raycast.RayCastUtils.orMiss
import net.minecraft.client.option.Perspective
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import java.awt.Color

object Freecam : Module(
    name = "Freecam",
    description = "Move your camera freely",
    tag = ModuleTag.RENDER,
    autoDisable = true,
) {
    private val speed by setting("Speed", 0.5, 0.1..1.0, 0.1, "Freecam movement speed", unit = "m/s")
    private val sprint by setting("Sprint Multiplier", 3.0, 0.1..10.0, 0.1, description = "Set below 1.0 to fly slower on sprint.")
    private val reach by setting("Reach", 10.0, 1.0..100.0, 1.0, "Freecam reach distance")
    private val rotateMode by setting("Rotate Mode", FreecamRotationMode.None, "Rotation mode")
        .onValueChange { _, it -> if (it == FreecamRotationMode.LookAtTarget) mc.crosshairTarget = BlockHitResult.createMissed(Vec3d.ZERO, Direction.UP, BlockPos.ORIGIN) }
    private val relative by setting("Relative", false, "Moves freecam relative to player position")
        .onValueChange { _, it -> if (it) lastPlayerPosition = player.pos }
    private val keepYLevel by setting("Keep Y Level", false, "Don't change the camera y-level on player movement") { relative }
    private val resetSelectionBoxes by setting("Reset Selection Boxes", Bind.EMPTY, "Reset the positions of the area selection boxes to your current position").onPress {
        pos1 = position.flooredBlockPos
        pos2 = position.flooredBlockPos
    }

    override val rotationConfig = RotationConfig.Instant(RotationMode.Lock)

    private var lastPerspective = Perspective.FIRST_PERSON
    private var lastPlayerPosition: Vec3d = Vec3d.ZERO
    private var prevPosition: Vec3d = Vec3d.ZERO
    private var position: Vec3d = Vec3d.ZERO
    private val lerpPos: Vec3d
        get() {
            val tickProgress = mc.gameRenderer.camera.lastTickProgress
            return prevPosition.interpolate(tickProgress, position)
        }

    private var rotation: Rotation = Rotation.ZERO
    private var velocity: Vec3d = Vec3d.ZERO

    private var pos1 = BlockPos.ORIGIN
    private var pos2 = BlockPos.ORIGIN

    // Arrow handle drag state
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

    /** Build the shaft box for an arrow extending from the center of a face */
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

    /** Build the arrowhead box at the tip of an arrow */
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

    /** Get the face center of a selection box for a given axis direction */
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

    /** Get the full hitbox for an arrow handle (shaft + head combined) */
    private fun arrowHitBox(selectionBox: Box, axis: HandleAxis): Box {
        val faceCenter = faceCenterOf(selectionBox, axis)
        val shaft = arrowShaftBox(faceCenter, axis)
        val head = arrowHeadBox(faceCenter, axis)
        return Box(
            minOf(shaft.minX, head.minX), minOf(shaft.minY, head.minY), minOf(shaft.minZ, head.minZ),
            maxOf(shaft.maxX, head.maxX), maxOf(shaft.maxY, head.maxY), maxOf(shaft.maxZ, head.maxZ)
        )
    }

    /** Project eye ray onto the drag axis and return the closest point on axis */
    private fun projectOntoAxis(eye: Vec3d, lookDir: Vec3d, axisOrigin: Vec3d, axisDir: Vec3d): Vec3d {
        // Find closest point on the axis line to the look ray
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

    @JvmStatic
    fun updateCam() {
        mc.gameRenderer.apply {
            camera.setRotation(rotation.yawF, rotation.pitchF)
            camera.setPos(lerpPos.x, lerpPos.y, lerpPos.z)
        }
    }

    /**
     * @see net.minecraft.entity.Entity.changeLookDirection
     */
    private const val SENSITIVITY_FACTOR = 0.15

    init {
        immediateRenderer("Freecam Area Selection") {
            val boxes = listOf(
                0 to Box.of(pos1.toBottomCenterPos(), 1.0, 1.0, 1.0),
                1 to Box.of(pos2.toBottomCenterPos(), 1.0, 1.0, 1.0)
            )
            val boxColors = listOf(
                Color(255, 0, 0, 50) to Color(255, 0, 0, 100),
                Color(0, 0, 255, 50) to Color(0, 0, 255, 100)
            )

            // Raycast from freecam eye to find hovered arrow handle
            val eye = lerpPos
            val lookDir = rotation.vector
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

            // Raycast against selection boxes to detect hovered box (only when not hovering an arrow and not dragging)
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

            // Render selection boxes
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

                // Render arrow handles on each face
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

                    // Draw shaft
                    val shaft = arrowShaftBox(faceCenter, axis)
                    box(shaft) {
                        colors(shaftColor, shaftColor)
                    }

                    // Draw arrowhead (larger box at the tip)
                    val head = arrowHeadBox(faceCenter, axis)
                    box(head) {
                        colors(headColor, headColor)
                    }
                }
            }

            // Render the area selection outline between the two boxes
            val areaBox = Box(
                minOf(pos1.x, pos2.x).toDouble(),
                minOf(pos1.y, pos2.y).toDouble() - 0.5,
                minOf(pos1.z, pos2.z).toDouble(),
                maxOf(pos1.x, pos2.x) + 1.0,
                maxOf(pos1.y, pos2.y) + .5,
                maxOf(pos1.z, pos2.z) + 1.0
            )
            box(areaBox) {
                hideFill()
                colors(Color(0, 0, 0, 0), Color(255, 255, 255, 180))
            }
        }

        // Handle mouse clicks for arrow dragging, box selection, and box placement
        listen<ButtonEvent.Mouse.Click> { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (event.isPressed) {
                    // If hovering an arrow handle, start dragging it
                    val hovered = hoveredHandle
                    if (hovered != null) {
                        draggingHandle = hovered
                        val selBox = if (hovered.boxIndex == 0)
                            Box.of(pos1.toBottomCenterPos(), 1.0, 1.0, 1.0)
                        else
                            Box.of(pos2.toBottomCenterPos(), 1.0, 1.0, 1.0)
                        val faceCenter = faceCenterOf(selBox, hovered.axis)
                        dragStartPos = projectOntoAxis(lerpPos, rotation.vector, faceCenter, hovered.axis.direction)
                        dragStartBlockPos = if (hovered.boxIndex == 0) pos1 else pos2
                        event.cancel()
                        return@listen
                    }

                    // If hovering a selection box, select/deselect it
                    val hoveredBox = hoveredBoxIndex
                    if (hoveredBox != null) {
                        selectedBoxIndex = if (selectedBoxIndex == hoveredBox) null else hoveredBox
                        event.cancel()
                        return@listen
                    }
                } else if (event.isReleased) {
                    if (draggingHandle != null) {
                        draggingHandle = null
                        dragStartPos = null
                        dragStartBlockPos = null
                        event.cancel()
                    }
                }
            }

            // Right-click places the selected box at the crosshair raycast location
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.isPressed) {
                val selIdx = selectedBoxIndex ?: return@listen
                val hitResult = rotation.rayCast(100.0, lerpPos).orMiss ?: return@listen
                if (hitResult.type == HitResult.Type.MISS) return@listen

                val targetPos = hitResult.pos.flooredBlockPos
                if (selIdx == 0) pos1 = targetPos else pos2 = targetPos
                event.cancel()
            }
        }

        // ESC clears the selected box
        listen<ButtonEvent.Keyboard.Press> { event ->
            if (event.keyCode == GLFW.GLFW_KEY_ESCAPE && event.isPressed && selectedBoxIndex != null) {
                selectedBoxIndex = null
                event.cancel()
            }
        }

        // Handle dragging: update pos on each tick while dragging
        listen<TickEvent.Pre> {
            val handle = draggingHandle ?: return@listen
            val startProjection = dragStartPos ?: return@listen
            val startBlock = dragStartBlockPos ?: return@listen

            // Check if mouse is still held
            if (GLFW.glfwGetMouseButton(mc.window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                draggingHandle = null
                dragStartPos = null
                dragStartBlockPos = null
                return@listen
            }

            val selBox = if (handle.boxIndex == 0)
                Box.of(startBlock.toBottomCenterPos(), 1.0, 1.0, 1.0)
            else
                Box.of(startBlock.toBottomCenterPos(), 1.0, 1.0, 1.0)
            val faceCenter = faceCenterOf(selBox, handle.axis)
            val currentProjection = projectOntoAxis(lerpPos, rotation.vector, faceCenter, handle.axis.direction)

            val delta = currentProjection.subtract(startProjection)
            // Project delta onto the axis direction
            val axisDelta = handle.axis.direction.multiply(delta.dotProduct(handle.axis.direction))
            val newPos = startBlock.add(
                Math.round(axisDelta.x).toInt(),
                Math.round(axisDelta.y).toInt(),
                Math.round(axisDelta.z).toInt()
            )

            if (handle.boxIndex == 0) pos1 = newPos else pos2 = newPos
        }

        onEnable {
            lastPerspective = mc.options.perspective
            position = player.eyePos
            rotation = player.rotation
            velocity = Vec3d.ZERO
            lastPlayerPosition = player.pos
        }

        onDisable {
            mc.options.perspective = lastPerspective
        }

        listen<TickEvent.Pre> {
            when (rotateMode) {
                FreecamRotationMode.None -> return@listen
                FreecamRotationMode.KeepRotation -> rotationRequest { rotation(rotation) }.submit()
                FreecamRotationMode.LookAtTarget ->
                    mc.crosshairTarget?.let {
                        runSafeAutomated {
                            rotationRequest { rotation(lookAt(it.pos)) }.submit()
                        }
                    }
            }
        }

        listen<PlayerEvent.ChangeLookDirection> {
            rotation = rotation.withDelta(
                it.deltaYaw * SENSITIVITY_FACTOR,
                it.deltaPitch * SENSITIVITY_FACTOR
            )
            it.cancel()
        }

        listen<MovementEvent.InputUpdate> { event ->
            mc.options.perspective = Perspective.FIRST_PERSON

            // Don't block baritone from working
            if (!event.input.handledByBaritone) {
                // Reset actual input
                event.input.cancel()
            }

            // Create new input for freecam
            val input = newMovementInput(assumeBaritone = false, slowdownCheck = false)
            val sprintModifier = if (mc.options.sprintKey.isPressed) sprint else 1.0
            val moveDir = calcMoveRad(rotation.yawF, input.roundedForward, input.roundedStrafing)
            var moveVec = movementVector(moveDir, input.verticalMovement) * speed * sprintModifier
            if (!input.isInputting) moveVec *= Vec3d(0.0, 1.0, 0.0)

            // Apply movement
            velocity += moveVec
            velocity *= 0.6

            // Update position
            prevPosition = position
            position += velocity

            if (relative) {
                val delta = player.pos.subtract(lastPlayerPosition)
                position += if (keepYLevel) Vec3d(delta.x, 0.0, delta.z) else delta
                lastPlayerPosition = player.pos
            }
        }

        listen<RenderEvent.UpdateTarget>({ 1 }) { event -> // Higher priority then RotationManager to run before RotationManager modifies mc.crosshairTarget
            mc.crosshairTarget = rotation
                .rayCast(reach, lerpPos)
                .orMiss // Can't be null (otherwise mc will spam "Null returned as 'hitResult', this shouldn't happen!")

            mc.crosshairTarget?.let { if (it.type != HitResult.Type.MISS) event.cancel() }
        }
    }

    enum class FreecamRotationMode(override val displayName: String, override val description: String) : NamedEnum, Describable {
        None("None", "No rotation changes"),
        LookAtTarget("Look At Target", "Look at the block or entity under your crosshair"),
        KeepRotation("Keep Rotation", "Look in the same direction as the camera");
    }
}
