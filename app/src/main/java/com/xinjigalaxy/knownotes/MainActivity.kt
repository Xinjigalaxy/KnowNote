package com.xinjigalaxy.knownotes

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.settings.AppLanguage
import com.xinjigalaxy.knownotes.data.settings.AppLocales
import com.xinjigalaxy.knownotes.data.settings.ThemeMode
import com.xinjigalaxy.knownotes.ui.KnowNoteRoot
import com.xinjigalaxy.knownotes.ui.theme.KnowNoteTheme

class MainActivity : ComponentActivity() {

    /**
     * API < 33 没有 per-app language API，只能在这里把语言包进 Context。
     * 33+ 交给系统 LocaleManager（见 AppLocales），这里不要重复包，否则会和系统设置打架。
     */
    override fun attachBaseContext(newBase: Context) {
        val language = AppLanguage.fromTag(UiPrefs(newBase).appLanguage())
        super.attachBaseContext(
            if (Build.VERSION.SDK_INT >= AppLocales.PER_APP_LANGUAGE_API) {
                newBase
            } else {
                AppLocales.wrap(newBase, language)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题设置是响应式的（AppSettings 持 StateFlow），在设置页改动会立刻换肤，
            // 不需要重建 Activity。
            val settings by (application as KnowNoteApp).container.settings.state
                .collectAsStateWithLifecycle()

            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            KnowNoteTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                fontScale = settings.fontScale,
                textColor = settings.textColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KnowNoteRoot()
                }
            }
        }
    }
}
