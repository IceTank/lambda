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

package com.lambda.module.modules.client

import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.BundleContentsComponent
import net.minecraft.component.type.ContainerComponent
import net.minecraft.item.ItemStack


object ServerFixes : Module(
	name = "2b2tFixes",
	tag = ModuleTag.CLIENT,
	description = "Fixes 2b2t specific issues"
) {
	@JvmStatic
	var bundleOrder by setting("Bundle Order", true, description = "Fixes the item order in bundles as 2b2t reverses them")

	@JvmStatic
	fun fixBundleOrder(stack: ItemStack?) {
		if (stack == null || stack.isEmpty) return  // Not applicable
		if (!isEnabled || !bundleOrder) return  // Not enabled

		// Recurse container type items
		if (stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
			//LOGGER.info("Fixing bundle contents");
			stack.get<BundleContentsComponent?>(DataComponentTypes.BUNDLE_CONTENTS)?.let {
				it.iterate().forEach { stack -> fixBundleOrder(stack) } // stream() returns copied Stacks
			}
		} else if (stack.contains(DataComponentTypes.CONTAINER)) {
			//LOGGER.info("Fixing container contents");
			stack.get<ContainerComponent?>(DataComponentTypes.CONTAINER)?.let {
				it.iterateNonEmpty().forEach { stack -> fixBundleOrder(stack) } // stream() returns copied Stacks
			}
		}

		if (!stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) return  // Not a bundle
		val contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS)
		if (contents == null || contents.isEmpty) return
		stack.set<BundleContentsComponent?>(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent(contents.stream().toList().reversed()))
	}
}