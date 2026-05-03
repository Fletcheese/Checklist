package com.example.checklist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.checklist.data.ChecklistRepository
import com.example.checklist.ui.ArchiveScreen
import com.example.checklist.ui.EditListScreen
import com.example.checklist.ui.EditTemplateScreen
import com.example.checklist.ui.HomeScreen
import com.example.checklist.ui.SchemasScreen
import com.example.checklist.ui.TemplatesScreen
import com.example.checklist.ui.theme.ChecklistTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private var intentState by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentState = intent
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                ChecklistRepository.initialize(context)
            }
            
            ChecklistTheme {
                ChecklistApp(intentState) { intentState = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentState = intent
    }
}

@Serializable object HomeRoute
@Serializable object TemplatesRoute
@Serializable object SchemasRoute
@Serializable object ArchiveRoute
@Serializable data class EditListRoute(val id: String, val isArchive: Boolean = false)
@Serializable data class EditTemplateRoute(val id: String)

enum class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val route: Any
) {
    HOME("Home", Icons.Default.Home, HomeRoute),
    TEMPLATES("Templates", Icons.Default.ListAlt, TemplatesRoute),
    SCHEMAS("Schemas", Icons.Default.Sort, SchemasRoute),
    ARCHIVE("Archive", Icons.Default.Archive, ArchiveRoute),
}

@Composable
fun ChecklistApp(intent: Intent? = null, onIntentHandled: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Handle widget navigation reactively
    LaunchedEffect(intent, ChecklistRepository.isInitialized) {
        if (ChecklistRepository.isInitialized && intent != null) {
            val instanceId = intent.getStringExtra("instanceId")
            if (instanceId != null) {
                onIntentHandled() // Clear the intent
                
                // Navigate to the specific list and clear the backstack to Home root
                navController.navigate(EditListRoute(id = instanceId, isArchive = false)) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = when (destination) {
                    TopLevelDestination.HOME -> currentDestination?.hasRoute<HomeRoute>() == true || 
                            (currentDestination?.hasRoute<EditListRoute>() == true && navBackStackEntry?.toRoute<EditListRoute>()?.isArchive == false)
                    TopLevelDestination.TEMPLATES -> currentDestination?.hasRoute<TemplatesRoute>() == true || 
                            currentDestination?.hasRoute<EditTemplateRoute>() == true
                    TopLevelDestination.SCHEMAS -> currentDestination?.hasRoute<SchemasRoute>() == true
                    TopLevelDestination.ARCHIVE -> currentDestination?.hasRoute<ArchiveRoute>() == true || 
                            (currentDestination?.hasRoute<EditListRoute>() == true && navBackStackEntry?.toRoute<EditListRoute>()?.isArchive == true)
                }

                item(
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = selected,
                    onClick = {
                        navController.navigate(destination.route) {
                            // Reset to root view on click by clearing backstack and NOT saving/restoring state
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    }
                )
            }
        }
    ) {
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> { 
                HomeScreen(onNavigateToEditList = { id -> navController.navigate(EditListRoute(id, isArchive = false)) }) 
            }
            composable<TemplatesRoute> { 
                TemplatesScreen(onNavigateToEditTemplate = { id -> navController.navigate(EditTemplateRoute(id)) }) 
            }
            composable<SchemasRoute> { SchemasScreen() }
            composable<ArchiveRoute> { 
                ArchiveScreen(onNavigateToEditList = { id -> navController.navigate(EditListRoute(id, isArchive = true)) }) 
            }
            composable<EditListRoute> { backStackEntry ->
                val route: EditListRoute = backStackEntry.toRoute()
                EditListScreen(instanceId = route.id, onBack = { navController.popBackStack() }) 
            }
            composable<EditTemplateRoute> { backStackEntry ->
                val route: EditTemplateRoute = backStackEntry.toRoute()
                EditTemplateScreen(templateId = route.id, onBack = { navController.popBackStack() }) 
            }
        }
    }
}
