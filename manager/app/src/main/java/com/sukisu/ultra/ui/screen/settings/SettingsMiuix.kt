package com.sukisu.ultra.ui.screen.settings

import com.sukisu.ultra.backend.BackendCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.sukisu.ultra.ui.component.miuix.AppCard
import com.sukisu.ultra.ui.component.miuix.AppScaffold
import com.sukisu.ultra.ui.component.profile.TemporaryRootPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import com.sukisu.ultra.ui.component.Storefront
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.screen.flash.FlashIt
import com.sukisu.ultra.ui.util.LkmSelection
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.KsuIsValid
import com.sukisu.ultra.ui.component.dialog.rememberLoadingDialog
import com.sukisu.ultra.ui.component.miuix.SendLogDialog
import com.sukisu.ultra.ui.component.uninstalldialog.UninstallDialog
import com.sukisu.ultra.ui.theme.LocalBackgroundImagePath
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.theme.backgroundAwareCardColors
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.customText
import com.sukisu.ultra.ui.util.CustomTextKey
import com.sukisu.ultra.ui.util.LocaleHelper
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import com.sukisu.ultra.ui.viewmodel.MainPageRequest
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    sectionRequest: MainPageRequest? = null,
    onSectionConsumed: (Long) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val anchorRequesters = remember { mutableMapOf<String, BringIntoViewRequester>() }
    fun requester(anchor: String): BringIntoViewRequester =
        anchorRequesters.getOrPut(anchor) { BringIntoViewRequester() }

    LaunchedEffect(sectionRequest?.token) {
        val request = sectionRequest ?: return@LaunchedEffect
        delay(120)
        val anchor = SettingsSection.normalize(request.section)
        val target = anchorRequesters[SettingsSection.cardFor(anchor)]
            ?: anchorRequesters[SettingsSection.GENERAL]
        target?.bringIntoView()
        onSectionConsumed(request.token)
    }
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val hasCustomBackground = LocalBackgroundImagePath.current.isNotEmpty()
    val barColor = if (blurActive || hasCustomBackground) Color.Transparent else colorScheme.surface
    val loadingDialog = rememberLoadingDialog()
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val bootPatchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            navigator.push(
                Route.Flash(FlashIt.FlashBoot(boot = uri, lkm = LkmSelection.KmiNone, ota = false))
            )
        }
    }

    AppScaffold(
        containerColor = if (hasCustomBackground) Color.Transparent else colorScheme.surface,
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = Color.Transparent,
                    title = customText(CustomTextKey.PAGE_SETTINGS),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    AppCard(
                        modifier = Modifier
                            .bringIntoViewRequester(requester(SettingsSection.GENERAL))
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                        colors = backgroundAwareCardColors(),
                    ) {
                        OverlayDropdownPreference(
                            title = customText(CustomTextKey.SETTING_UI_MODE),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            startAction = {
                                Icon(
                                    Icons.Rounded.DisplaySettings,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = customText(CustomTextKey.SETTING_UI_MODE),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onSelectedIndexChange = actions.onSetUiModeIndex
                        )
                        val languageSystemLabel = stringResource(id = R.string.settings_language_system)
                        val languageTags = remember { listOf(LocaleHelper.SYSTEM) + LocaleHelper.SUPPORTED_TAGS }
                        val languageNames = remember(languageSystemLabel) {
                            languageTags.map { if (it.isEmpty()) languageSystemLabel else LocaleHelper.displayName(it) }
                        }
                        OverlayDropdownPreference(
                            title = customText(CustomTextKey.SETTING_LANGUAGE),
                            summary = stringResource(id = R.string.settings_language_summary),
                            items = languageNames,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Language,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = customText(CustomTextKey.SETTING_LANGUAGE),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = languageTags.indexOf(uiState.appLanguage).coerceAtLeast(0),
                            onSelectedIndexChange = { index -> actions.onSetLanguage(languageTags[index]) }
                        )
                        val themeTitle = customText(CustomTextKey.PAGE_APPEARANCE)
                        ArrowPreference(
                            title = themeTitle,
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = themeTitle,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTheme
                        )
                        val themeStoreTitle = customText(CustomTextKey.PAGE_THEME_STORE)
                        ArrowPreference(
                            title = themeStoreTitle,
                            summary = stringResource(id = R.string.theme_store_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Storefront,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = themeStoreTitle,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenThemeStore
                        )

                    }

                    KsuIsValid {
                        if (BackendCompat.supportsKernelExtensions) {
                        AppCard(
                            modifier = Modifier
                                .bringIntoViewRequester(requester(SettingsSection.EXTENSIONS))
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            colors = backgroundAwareCardColors(),
                        ) {
                            val extensionsTitle = customText(CustomTextKey.PAGE_EXTENSIONS)
                            ArrowPreference(
                                title = extensionsTitle,
                                summary = stringResource(R.string.extension_section_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Memory,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = extensionsTitle,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = actions.onOpenExtensions
                            )
                        }
                        }
                        val toolsTitle = customText(CustomTextKey.PAGE_TOOLS)
                        if (BackendCompat.supportsTools) {
                        AppCard(
                            modifier = Modifier
                                .bringIntoViewRequester(requester(SettingsSection.TOOLS))
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            colors = backgroundAwareCardColors(),
                        ) {
                            ArrowPreference(
                                title = toolsTitle,
                                summary = stringResource(id = R.string.settings_tools_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeveloperMode,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = toolsTitle,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    actions.onOpenTools()
                                }
                            )
                        }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(requester(SettingsSection.TEMPORARY_ROOT))
                        ) {
                            TemporaryRootPreferences()
                        }
                    }

                    KsuIsValid {
                        val suCompatModeItems = listOf(
                            stringResource(id = R.string.settings_mode_enable_by_default),
                            stringResource(id = R.string.settings_mode_disable_until_reboot),
                            stringResource(id = R.string.settings_mode_disable_always),
                        )


                        AppCard(
                            modifier = Modifier
                                .bringIntoViewRequester(requester(SettingsSection.MODULE_BEHAVIOUR))
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            colors = backgroundAwareCardColors(),
                        ) {
                            SwitchPreference(
                                title = customText(CustomTextKey.SETTING_AUTO_DELETE_MODULE_ZIP),
                                summary = stringResource(id = R.string.settings_auto_delete_module_zip_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = customText(CustomTextKey.SETTING_AUTO_DELETE_MODULE_ZIP),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.autoDeleteModuleZip,
                                onCheckedChange = actions.onSetAutoDeleteModuleZip
                            )
                            SwitchPreference(
                                title = customText(CustomTextKey.SETTING_AUTO_DELETE_UPDATE_APK),
                                summary = stringResource(id = R.string.settings_auto_delete_update_apk_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = customText(CustomTextKey.SETTING_AUTO_DELETE_UPDATE_APK),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.autoDeleteUpdateApk,
                                onCheckedChange = actions.onSetAutoDeleteUpdateApk
                            )
                        }
                    }

                    if (uiState.isLkmMode) {
                        AppCard(
                            modifier = Modifier
                                .bringIntoViewRequester(requester(SettingsSection.UNINSTALL))
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                            colors = backgroundAwareCardColors(),
                        ) {
                            val uninstall = customText(CustomTextKey.SETTING_UNINSTALL)
                            ArrowPreference(
                                title = uninstall,
                                enabled = !uiState.isLateLoadMode,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = uninstall,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUninstallDialog.value = true },
                            )
                            UninstallDialog(
                                show = showUninstallDialog.value,
                                onDismissRequest = { showUninstallDialog.value = false }
                            )
                        }
                    }

                    AppCard(
                        modifier = Modifier
                            .bringIntoViewRequester(requester(SettingsSection.DIAGNOSTICS))
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        colors = backgroundAwareCardColors(),
                    ) {
                        if (BackendCompat.supportsBootPatch) {
                            val bootPatch = stringResource(R.string.settings_boot_patch)
                            ArrowPreference(
                                title = bootPatch,
                                summary = stringResource(R.string.settings_boot_patch_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Memory,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = bootPatch,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    bootPatchLauncher.launch(
                                        Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "application/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            putExtra(
                                                Intent.EXTRA_MIME_TYPES,
                                                arrayOf("application/octet-stream", "application/zip")
                                            )
                                        }
                                    )
                                },
                            )
                        }
                        ArrowPreference(
                            title = customText(CustomTextKey.SETTING_SEND_LOG),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = customText(CustomTextKey.SETTING_SEND_LOG),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = customText(CustomTextKey.PAGE_ABOUT)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}
