package com.tianxian.fruit

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.ui.TianXianApp

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15/16 targetSdk 35+ defaults to edge-to-edge.
        // Keep the system status bar visible and use dark icons on a light bar.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        db = AppDatabase(applicationContext)
        setContent { TianXianApp(db) }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }
}
