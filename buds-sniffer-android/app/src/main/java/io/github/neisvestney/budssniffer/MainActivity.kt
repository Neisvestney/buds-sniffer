package io.github.neisvestney.budssniffer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.neisvestney.budssniffer.probe.ProbeScreen
import io.github.neisvestney.budssniffer.ui.HomeScreen
import io.github.neisvestney.budssniffer.ui.theme.BudsSnifferTheme
import io.github.neisvestney.budssniffer.widget.WidgetPreviews
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { WidgetPreviews.publish(applicationContext) }
        setContent {
            BudsSnifferTheme {
                var probe by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = probe) { probe = false }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (probe) {
                        ProbeScreen(Modifier.padding(innerPadding))
                    } else {
                        HomeScreen(onOpenProbe = { probe = true }, modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
