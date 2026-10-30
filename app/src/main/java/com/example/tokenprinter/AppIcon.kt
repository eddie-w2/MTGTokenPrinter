// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 eddie-w2 and Token Printer contributors

package com.example.tokenprinter

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The launcher icons the app ships with. [alias] is the matching activity-alias in the manifest. */
enum class AppIcon(val label: String, val alias: String, val bg: Int, val fg: Int) {
    Themed("Themed", "com.example.tokenprinter.IconThemed", R.color.ic_bg_themed, R.drawable.ic_fg_themed),
    Color("Color", "com.example.tokenprinter.IconColor", R.color.ic_bg_color, R.drawable.ic_fg_color),
}

object AppIcons {
    private fun enabled(ctx: Context, icon: AppIcon): Boolean {
        val state = ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx, icon.alias))
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.Themed // the manifest default
            else -> false
        }
    }

    fun current(ctx: Context): AppIcon =
        if (enabled(ctx, AppIcon.Color) && !enabled(ctx, AppIcon.Themed)) AppIcon.Color else AppIcon.Themed

    /** Enables the chosen alias first, then disables the others, without restarting the app. */
    fun set(ctx: Context, icon: AppIcon) {
        val pm = ctx.packageManager
        pm.setComponentEnabledSetting(
            ComponentName(ctx, icon.alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIcon.values().filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(
                ComponentName(ctx, it.alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
