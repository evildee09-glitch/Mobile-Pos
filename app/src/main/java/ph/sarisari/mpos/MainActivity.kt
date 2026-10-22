package ph.sarisari.mpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ph.sarisari.mpos.ui.PosRoot
import ph.sarisari.mpos.ui.PosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PosTheme { PosRoot() } }
    }
}
