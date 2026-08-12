package com.stt.benchmark.ui

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stt.benchmark.summary.CodexAuthViewModel
import com.stt.benchmark.ui.library.LibraryRoute
import com.stt.benchmark.ui.onboarding.OnboardingPreferences
import com.stt.benchmark.ui.onboarding.OnboardingScreen
import com.stt.benchmark.ui.recording.RecordingRoute
import com.stt.benchmark.ui.recording.RecordingViewModel
import com.stt.benchmark.ui.settings.SettingsRoute
import com.stt.benchmark.ui.transcription.TranscriptionRoute
import com.stt.benchmark.ui.theme.SttBenchmarkTheme

internal enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    RECORDING("recording", "녹음", Icons.Default.Mic),
    TRANSCRIPTION("transcription", "전사", Icons.Default.GraphicEq),
    LIBRARY("library", "보관함", Icons.Default.Inventory2),
    SETTINGS("settings", "설정", Icons.Default.Settings),
}

@Composable
fun LongSttApp(
    sttViewModel: SttViewModel,
    codexAuthViewModel: CodexAuthViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { OnboardingPreferences(context.applicationContext) }
    var onboardingComplete by remember { mutableStateOf(preferences.isComplete()) }
    val darkTheme = isSystemInDarkTheme()
    val recordingViewModel: RecordingViewModel = viewModel()
    val recordingState by recordingViewModel.uiState.collectAsStateWithLifecycle()

    SttBenchmarkTheme(darkTheme = darkTheme) {
        if (!onboardingComplete) {
            SystemBarAppearance(darkBackground = true)
            OnboardingScreen(
                onComplete = {
                    preferences.setComplete(true)
                    onboardingComplete = true
                },
                modifier = modifier,
            )
            return@SttBenchmarkTheme
        }

        SystemBarAppearance(darkBackground = darkTheme)
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val showNavigationLabels = shouldShowNavigationLabels(LocalDensity.current.fontScale)
        var pendingResultType by rememberSaveable { mutableStateOf("") }
        var pendingResultId by rememberSaveable { mutableStateOf("") }
        val pendingCompletedResult = CompletedResultTarget.restore(pendingResultType, pendingResultId)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    AppDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            alwaysShowLabel = showNavigationLabels,
                            onClick = {
                                val restoreDestinationState = shouldRestoreTopLevelState(destination)
                                if (destination == AppDestination.LIBRARY) {
                                    // Saved top-level destinations may restore without re-running
                                    // the route LaunchedEffect, so refresh on every explicit tap.
                                    sttViewModel.refreshLibraries()
                                    codexAuthViewModel.refreshSummaryEntries()
                                }
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        // Library is a live archive view. Recreate it instead of
                                        // restoring a stale saved entry or an old detail dialog.
                                        // Recording is the start destination, so restoring state
                                        // there can revive a previously saved child tab instead.
                                        saveState = restoreDestinationState
                                    }
                                    launchSingleTop = true
                                    restoreState = restoreDestinationState
                                }
                            },
                            icon = {
                                Box {
                                    Icon(
                                        destination.icon,
                                        contentDescription = buildString {
                                            append(destination.label)
                                            if (
                                                destination == AppDestination.RECORDING &&
                                                recordingState.isRecorderActive
                                            ) append(", 녹음 진행 중")
                                        },
                                    )
                                    if (
                                        destination == AppDestination.RECORDING &&
                                        recordingState.isRecorderActive
                                    ) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error),
                                        )
                                    }
                                }
                            },
                            // Four Korean labels cannot retain a safe visual separation at 200%
                            // font scale. Icon descriptions still expose the destination to
                            // TalkBack while the visible label is intentionally compacted.
                            label = if (showNavigationLabels) ({ Text(destination.label) }) else null,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = AppDestination.RECORDING.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                composable(AppDestination.RECORDING.route) {
                    RecordingRoute(
                        viewModel = recordingViewModel,
                        onOpenTranscription = {
                            navController.navigate(AppDestination.TRANSCRIPTION.route) {
                                launchSingleTop = true
                            }
                        },
                        onTranscribeRecording = { sessionId, allowPartial ->
                            sttViewModel.startRecordingGroupTranscription(sessionId, allowPartial)
                            navController.navigate(AppDestination.TRANSCRIPTION.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(AppDestination.TRANSCRIPTION.route) {
                    TranscriptionRoute(
                        viewModel = sttViewModel,
                        onOpenSettings = {
                            navController.navigate(AppDestination.SETTINGS.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenCompletedResult = { target ->
                            pendingResultType = target.type.name
                            pendingResultId = target.id
                            sttViewModel.refreshLibraries()
                            navController.navigate(AppDestination.LIBRARY.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = false
                                }
                                launchSingleTop = true
                                restoreState = false
                            }
                        },
                    )
                }
                composable(AppDestination.LIBRARY.route) {
                    LaunchedEffect(Unit) { sttViewModel.refreshLibraries() }
                    LibraryRoute(
                        viewModel = sttViewModel,
                        codexAuthViewModel = codexAuthViewModel,
                        onOpenTranscription = {
                            navController.navigate(AppDestination.TRANSCRIPTION.route) {
                                launchSingleTop = true
                            }
                        },
                        initialCompletedResult = pendingCompletedResult,
                        onInitialCompletedResultHandled = {
                            pendingResultType = ""
                            pendingResultId = ""
                        },
                    )
                }
                composable(AppDestination.SETTINGS.route) {
                    SettingsRoute(
                        viewModel = sttViewModel,
                        codexAuthViewModel = codexAuthViewModel,
                        onReplayOnboarding = {
                            preferences.setComplete(false)
                            onboardingComplete = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * The live archive is always recreated, while the graph start destination must be revealed
 * directly. Restoring state for the start destination can restore a saved child back stack and
 * leave the user on the tab they were trying to leave.
 */
internal fun shouldRestoreTopLevelState(destination: AppDestination): Boolean = when (destination) {
    AppDestination.RECORDING,
    AppDestination.LIBRARY,
    -> false

    AppDestination.TRANSCRIPTION,
    AppDestination.SETTINGS,
    -> true
}

@Composable
private fun SystemBarAppearance(darkBackground: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        // enableEdgeToEdge()와 함께 route 배경이 system bar 뒤까지 이어지도록 하되,
        // icon 대비는 현재 theme/onboarding 배경을 유일한 기준으로 유지한다.
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = usesLightSystemBarIcons(darkBackground)
            isAppearanceLightNavigationBars = usesLightSystemBarIcons(darkBackground)
        }
    }
}

/** Pure contrast policy kept testable independently from an Android Window. */
internal fun usesLightSystemBarIcons(darkBackground: Boolean): Boolean = !darkBackground

/** Retain visible labels through the verified 130% layout, then prevent bottom-nav overlap. */
internal fun shouldShowNavigationLabels(fontScale: Float): Boolean = fontScale <= 1.3f
