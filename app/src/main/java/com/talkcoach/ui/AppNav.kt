package com.talkcoach.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.talkcoach.TalkCoachApp
import com.talkcoach.ui.screens.AnalyzeScreen
import com.talkcoach.ui.screens.ContactScreen
import com.talkcoach.ui.screens.HomeScreen
import com.talkcoach.ui.screens.ImportScreen
import com.talkcoach.ui.screens.ResultScreen
import com.talkcoach.ui.screens.SettingsScreen

/**
 * 화면 라우팅. 공유 텍스트가 들어오면 어느 화면에서든 가져오기 화면으로 이동한다.
 * Screen routing; incoming shared text routes to the import screen from anywhere.
 *
 * @param app application (repository + pending import) / 앱 객체
 */
@Composable
fun AppNav(app: TalkCoachApp) {
    val nav = rememberNavController()                                  // Navigator / 내비게이터
    val pending by app.pendingImport.collectAsState()                  // Shared text / 공유 텍스트
    val repo = app.repo                                                // Repository / 저장소

    // Route to import whenever new shared text arrives / 공유 텍스트 도착 시 가져오기 화면으로
    LaunchedEffect(pending) {
        if (pending != null && nav.currentDestination?.route != "import") nav.navigate("import")
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repo = repo,
                onImportText = { app.pendingImport.value = it },          // Manual import / 직접 가져오기
                onOpenContact = { nav.navigate("contact/$it") },
                onSettings = { nav.navigate("settings") }
            )
        }
        composable("import") {
            ImportScreen(
                repo = repo,
                text = pending,
                onDone = { id ->
                    app.pendingImport.value = null                        // Clear queue / 대기열 비우기
                    nav.navigate("contact/$id") { popUpTo("home") }       // Go to profile / 프로필로 이동
                },
                onCancel = { app.pendingImport.value = null; nav.popBackStack("home", false) }
            )
        }
        composable("contact/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            val id = e.arguments!!.getLong("id")                          // Contact id / 상대 id
            ContactScreen(
                repo = repo, contactId = id,
                onBack = { nav.popBackStack() },
                onAnalyze = { nav.navigate("analyze/$id") },
                onOpenSession = { nav.navigate("result/$it") },
                onDeleted = { nav.popBackStack("home", false) }
            )
        }
        composable("analyze/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            val id = e.arguments!!.getLong("id")                          // Contact id / 상대 id
            AnalyzeScreen(
                repo = repo, contactId = id,
                onBack = { nav.popBackStack() },
                onDone = { sid -> nav.navigate("result/$sid") { popUpTo("contact/$id") } },
                onSettings = { nav.navigate("settings") }
            )
        }
        composable("result/{sid}", arguments = listOf(navArgument("sid") { type = NavType.LongType })) { e ->
            ResultScreen(repo = repo, sessionId = e.arguments!!.getLong("sid"), onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(repo = repo, onBack = { nav.popBackStack() })
        }
    }
}
