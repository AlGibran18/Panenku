package id.panenku.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import id.panenku.app.navigation.PanenkuApp
import id.panenku.app.ui.theme.PanenkuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PanenkuTheme { PanenkuApp() } }
    }
}
