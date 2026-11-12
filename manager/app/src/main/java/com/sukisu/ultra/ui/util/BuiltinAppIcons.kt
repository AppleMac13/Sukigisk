package com.sukisu.ultra.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.MainActivity

@Immutable
data class BuiltinAppIcon(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int,
    @param:DrawableRes val foregroundRes: Int,
    
    val aliasClassName: String?,
)


object BuiltinAppIcons {
    const val DEFAULT_ID = "default"
    const val KERNELSU_ID = "kernelsu"
    const val LOGO_ID = "sukisu_logo"
    const val ZAKO_ID = "zako"
    const val ARIS_ID = "aris"
    const val ARONA_ID = "arona"
    const val SHIROKO_ID = "shiroko"
    const val KEY_ID = "key"
    const val HINA_ID = "hina"
    const val PLANA_ID = "plana"
    const val HOSHINO_ID = "hoshino"
    const val SLEEPYGPT_ID = "sleepy_gpt"

    private const val TAG = "BuiltinAppIcons"
    private val mainActivityName = MainActivity::class.java.name

    val all: List<BuiltinAppIcon> = listOf(
        BuiltinAppIcon(
            id = DEFAULT_ID,
            nameRes = R.string.app_icon_builtin_default,
            iconRes = R.mipmap.ic_launcher_key,
            foregroundRes = R.drawable.ic_launcher_foreground_key,
            aliasClassName = mainActivityName + "AliasDefault",
        ),
        BuiltinAppIcon(
            id = KERNELSU_ID,
            nameRes = R.string.app_icon_builtin_kernelsu,
            iconRes = R.mipmap.ic_launcher_alt,
            foregroundRes = R.drawable.ic_launcher_foreground_alt,
            aliasClassName = mainActivityName + "Alias",
        ),
        BuiltinAppIcon(
            id = LOGO_ID,
            nameRes = R.string.app_icon_builtin_logo,
            iconRes = R.mipmap.ic_launcher_logo,
            foregroundRes = R.drawable.ic_launcher_foreground,
            aliasClassName = mainActivityName + "AliasLogo",
        ),
        BuiltinAppIcon(
            id = ZAKO_ID,
            nameRes = R.string.app_icon_builtin_zako,
            iconRes = R.mipmap.ic_launcher_zako,
            foregroundRes = R.drawable.ic_launcher_foreground_zako,
            aliasClassName = mainActivityName + "AliasZako",
        ),
        BuiltinAppIcon(
            id = ARIS_ID,
            nameRes = R.string.app_icon_builtin_aris,
            iconRes = R.mipmap.ic_launcher_aris,
            foregroundRes = R.drawable.ic_launcher_foreground_aris,
            aliasClassName = mainActivityName + "AliasAris",
        ),
        BuiltinAppIcon(
            id = ARONA_ID,
            nameRes = R.string.app_icon_builtin_arona,
            iconRes = R.mipmap.ic_launcher_arona,
            foregroundRes = R.drawable.ic_launcher_foreground_arona,
            aliasClassName = mainActivityName + "AliasArona",
        ),
        BuiltinAppIcon(
            id = SHIROKO_ID,
            nameRes = R.string.app_icon_builtin_shiroko,
            iconRes = R.mipmap.ic_launcher_shiroko,
            foregroundRes = R.drawable.ic_launcher_foreground_shiroko,
            aliasClassName = mainActivityName + "AliasShiroko",
        ),
        BuiltinAppIcon(
            id = HINA_ID,
            nameRes = R.string.app_icon_builtin_hina,
            iconRes = R.mipmap.ic_launcher_hina,
            foregroundRes = R.drawable.ic_launcher_foreground_hina,
            aliasClassName = mainActivityName + "AliasHina",
        ),
        BuiltinAppIcon(
            id = PLANA_ID,
            nameRes = R.string.app_icon_builtin_plana,
            iconRes = R.mipmap.ic_launcher_plana,
            foregroundRes = R.drawable.ic_launcher_foreground_plana,
            aliasClassName = mainActivityName + "AliasPlana",
        ),
        BuiltinAppIcon(
            id = HOSHINO_ID,
            nameRes = R.string.app_icon_builtin_hoshino,
            iconRes = R.mipmap.ic_launcher_hoshino,
            foregroundRes = R.drawable.ic_launcher_foreground_hoshino,
            aliasClassName = mainActivityName + "AliasHoshino",
        ),
        BuiltinAppIcon(
            id = SLEEPYGPT_ID,
            nameRes = R.string.app_icon_builtin_sleepy_gpt,
            iconRes = R.mipmap.ic_launcher_sleepy_gpt,
            foregroundRes = R.drawable.ic_launcher_foreground_sleepy_gpt,
            aliasClassName = mainActivityName + "AliasSleepyGpt",
        ),
    )

    fun find(id: String?): BuiltinAppIcon = all.firstOrNull { it.id == id } ?: all.first()

    
    fun apply(
        context: Context,
        icon: BuiltinAppIcon,
        dynamicColor: Boolean = false,
    ) {
        val pm = context.packageManager
        val main = ComponentName(context, mainActivityName)
        val selectedClass = componentClassName(icon, dynamicColor)
        val target = ComponentName(context, selectedClass)

        setEnabled(pm, main, true)
        setEnabled(pm, target, true)
        allAliasClassNames()
            .filter { it != selectedClass }
            .forEach { setEnabled(pm, ComponentName(context, it), false) }
    }

    private fun componentClassName(
        icon: BuiltinAppIcon,
        dynamicColor: Boolean,
    ): String {
        if (dynamicColor) {
            return icon.aliasClassName ?: (mainActivityName + "AliasDefault")
        }
        return mainActivityName + "Alias" + aliasSuffix(icon) + "Static"
    }

    private fun allAliasClassNames(): List<String> = buildList {
        all.forEach { icon ->
            val base = mainActivityName + "Alias" + aliasSuffix(icon)
            add(icon.aliasClassName ?: (mainActivityName + "AliasDefault"))
            add(base + "Static")
        }
    }

    private fun aliasSuffix(icon: BuiltinAppIcon): String = when (icon.id) {
        DEFAULT_ID -> "Default"
        KERNELSU_ID -> ""
        LOGO_ID -> "Logo"
        ZAKO_ID -> "Zako"
        ARIS_ID -> "Aris"
        ARONA_ID -> "Arona"
        SHIROKO_ID -> "Shiroko"
        KEY_ID -> "Key"
        HINA_ID -> "Hina"
        PLANA_ID -> "Plana"
        HOSHINO_ID -> "Hoshino"
        SLEEPYGPT_ID -> "SleepyGpt"
        else -> "Default"
    }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        try {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot change the enabled state of $component", t)
        }
    }
}
