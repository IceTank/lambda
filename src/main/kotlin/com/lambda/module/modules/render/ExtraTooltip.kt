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

import com.lambda.context.SafeContext
import com.lambda.module.Module
import com.lambda.module.modules.render.ExtraTooltip.Calculator.fractionRemaining
import com.lambda.module.modules.render.ExtraTooltip.Calculator.timeRemaining
import com.lambda.module.modules.render.ExtraTooltip.Util.formatTimePercent
import com.lambda.module.modules.render.ExtraTooltip.Util.getShulkerContentTimeRemaining
import com.lambda.module.tag.ModuleTag
import com.lambda.threading.runSafe
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.MapIdComponent
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.item.Item.TooltipContext
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.text.Text
import net.minecraft.util.Formatting


object ExtraTooltip : Module(
	name = "ExtraTooltip",
	description = "Adds extra info to item tooltips.",
	tag = ModuleTag.RENDER
) {
	val elytraTime by setting("Elytra Time", true)
	val oldShulkers by setting("Old Shulkers", true)
	val repairCost by setting("Repair Cost", true)
	val showMapId by setting("Show Map ID", true)

	init {
		ItemTooltipCallback.EVENT.register(this::onTooltip)
	}

	private fun onTooltip(itemStack: ItemStack, context: TooltipContext, type: TooltipType, lines: MutableList<Text>) {
		if (!isEnabled) return

		runSafe {
			if (elytraTime) {
				if (itemStack.item === Items.ELYTRA) {
					lines.add(1, Text.literal(formatTimePercent(itemStack, "[M]m [S]s", "[TIME] remaining ([%]%)")).formatted(Formatting.GREEN))
				}
				if (ContainerPreview.isShulkerBox(itemStack)) {
					getShulkerContentTimeRemaining(itemStack)?.let { timeRemaining ->
						lines.add(1, timeRemaining)
					}
				}
			}

			if (oldShulkers) {
				if (ContainerPreview.isShulkerBox(itemStack) && isOldShulker(itemStack)) {
					lines.add(1, Text.literal("1.12.2 Unopened").styled { style -> style.withColor(Formatting.GRAY) })
				}
			}

			if (repairCost) {
				val repairCost = getRepairCost(itemStack)
				if (repairCost > 0) {
					lines.add(Text.literal("Repair Cost: $repairCost").styled { style -> style.withColor(Formatting.YELLOW) })
				}
			}

			if (showMapId && itemStack.item === Items.FILLED_MAP) {
				val component = itemStack.get<MapIdComponent?>(DataComponentTypes.MAP_ID)
				val mapId = component?.id()?.toString() ?: "Unknown"
				lines.add(Text.literal("Map ID: $mapId").styled { style -> style.withColor(Formatting.GRAY) })
			}
		}
	}

	private fun getRepairCost(itemStack: ItemStack): Int {
		return itemStack.get(DataComponentTypes.REPAIR_COST) ?: 0
	}

	fun isOldShulker(itemStack: ItemStack): Boolean {
		if (itemStack.isEmpty) return false
		return try {
			itemStack.get(DataComponentTypes.BLOCK_ENTITY_DATA)?.contains("CustomName") ?: false
		} catch (_: Exception) {
			false
		}
	}

	object Util {
		fun SafeContext.formatTimePercent(item: ItemStack, format: String, timeFormat: String): String {
			val timeLeft = formatTime(timeRemaining(item), timeFormat)
			val percent = (fractionRemaining(item) * 100.0).toInt()

			return format
				.replace("\\[TIME]".toRegex(), timeLeft)
				.replace("\\[%]".toRegex(), percent.toString())
		}

		fun SafeContext.getShulkerContentTimeRemaining(itemStack: ItemStack): Text? {
			val timeRemaining = itemStack.shulkerBoxContents.stream()
				.filter { item: ItemStack -> item.item === Items.ELYTRA }
				.mapToInt { item: ItemStack -> timeRemaining(item) }
				.sum()
			if (timeRemaining == 0) return null
			return Text.literal(formatLongTime(timeRemaining)).formatted(Formatting.GREEN)
		}

		/**
		 * Formats the time in seconds to a string
		 *
		 * @param time   time in seconds
		 * @param format format string
		 * @return formatted time string
		 */
		fun formatTime(time: Int, format: String): String {
			return format
				.replace("\\[M]".toRegex(), (time / 60).toString())
				.replace("\\[S]".toRegex(), (time % 60).toString())
		}

		fun formatLongTime(time: Int): String {
			val days = time / 86400
			val hours = (time % 86400) / 3600
			val minutes = (time % 3600) / 60
			val seconds = time % 60

			val sb = StringBuilder()
			if (days > 0) {
				sb.append(days).append("d ")
			}
			if (hours > 0) {
				sb.append(hours).append("h ")
			}
			return sb.append(minutes).append("m ").append(seconds).append("s").toString()
		}

//		fun SafeContext.findElytra(player: PlayerEntity): Optional<ItemStack?> {
//			val chestPlate: ItemStack = player.getInventory().getArmorStack(EquipmentSlot.CHEST.getEntitySlotId())
//
//			if (chestPlate.item === Items.ELYTRA) {
//				return Optional.of<ItemStack?>(chestPlate)
//			}
//
//			return Optional.empty<ItemStack?>()
//		}
	}

	object Calculator {
		/**
		 * Calculates the time remaining in seconds for the given item
		 *
		 * @param item  item to calculate time remaining for
		 * @return time remaining in seconds
		 */
		fun SafeContext.timeRemaining(item: ItemStack): Int {
			val unbreaking = getUnbreakingLevel(item)
			return ((item.maxDamage - item.damage) * (unbreaking + 1)) - 1
		}

		fun SafeContext.fractionRemaining(item: ItemStack): Float {
			val unbreaking = getUnbreakingLevel(item)
			val timeRemaining = timeRemaining(item)
			val totalTime = (item.maxDamage * (unbreaking + 1)) - 1

			return timeRemaining.toFloat() / totalTime.toFloat()
		}

		fun SafeContext.getUnbreakingLevel(item: ItemStack): Int {
			try {
				val entry: RegistryEntry<Enchantment?> = world.registryManager.getOrThrow<Enchantment?>(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.UNBREAKING.value).orElseThrow()
				return EnchantmentHelper.getLevel(entry, item)
			} catch (_: Exception) {
				return 0
			}
		}
	}
}