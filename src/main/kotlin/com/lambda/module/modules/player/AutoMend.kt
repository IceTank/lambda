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
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.SafeListener.Companion.listenOnce
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.inventory.InventoryRequest.Companion.inventoryRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.material.StackSelection.Companion.select
import com.lambda.interaction.material.StackSelection.Companion.selectStack
import com.lambda.interaction.material.container.ContainerManager.transfer
import com.lambda.interaction.material.container.containers.HotbarContainer
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafeAutomated
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.InputUtils.isSatisfied
import com.lambda.util.player.SlotUtils.hotbarSlots
import com.lambda.util.player.SlotUtils.hotbarStacks
import net.minecraft.client.option.KeyBinding
import net.minecraft.enchantment.Enchantments
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.PlayerScreenHandler
import net.minecraft.util.Hand


class AutoMend : Module(
	name = "AutoMend",
	description = "Automatically swaps items to the offhand to mend them with XP.",
	tag = ModuleTag.PLAYER
) {
	var offhand by setting("Offhand", true, description = "Switch repairable items to the offhand to mend them with XP.")
	var ignoreHeldItem by setting("Ignore Held Item", true, description = "Do not swap away the currently held item")

	var useXPKey by setting("Use XP", Bind(0, 0))

	init {
		listen<TickEvent.Pre> { event ->
			if (useXPKey.isSatisfied() && mc.currentScreen == null) {
				rotationRequest {
					pitch(45f)
				}.submit()
				val selection = selectStack { isItem(Items.EXPERIENCE_BOTTLE) }
				runSafeAutomated {
					var index = player.hotbarSlots.indexOfFirst { selection.filterStack(it.stack) }
					if (index < 0) {
						if (!selection.transfer(HotbarContainer)) return@listen
						index = player.hotbarStacks.indexOfFirst { selection.filterStack(it) }
					}
					HotbarRequest(index, this).submit()
					interaction.interactItem(player, Hand.MAIN_HAND)
				}
			}

			if (offhand && player.currentScreenHandler == player.playerScreenHandler && !player.offHandStack.needsMending) {
				selectStack {
					{ it.needsMending && (!ignoreHeldItem || it != player.mainHandStack) }
				}.filterSlots(player.playerScreenHandler.slots.subList(PlayerScreenHandler.INVENTORY_START, PlayerScreenHandler.HOTBAR_END))
					.getOrNull(0)?.let { slot ->
						inventoryRequest {
							swap(slot.id, 40)
						}.submit()
					}
			}
		}
	}

	private val ItemStack.hasMending: Boolean
		get() = getEnchantment(Enchantments.MENDING) > 0

	private val ItemStack.needsMending: Boolean
		get() = isDamageable && damage > 0 && hasMending
}