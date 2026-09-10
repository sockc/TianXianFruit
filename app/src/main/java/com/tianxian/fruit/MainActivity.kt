package com.tianxian.fruit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.ui.TianXianApp

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase(applicationContext)
        setContent { TianXianApp(db) }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }
}
