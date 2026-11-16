package app.roadmaxxing

import androidx.compose.runtime.Composable
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.main.MainScreen

/** Root composable shared by Android and iOS. The platform must start Koin before this runs. */
@Composable
fun App() {
    RoadmaxxingTheme {
        MainScreen()
    }
}
