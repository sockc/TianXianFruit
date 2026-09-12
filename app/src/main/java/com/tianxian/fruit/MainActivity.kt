package com.tianxian.fruit

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.tianxian.fruit.data.AppDatabase
import com.tianxian.fruit.sync.CloudSyncManager
import com.tianxian.fruit.sync.LedgerBook
import com.tianxian.fruit.sync.LedgerManager
import com.tianxian.fruit.ui.TianXianApp

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var ledgerManager:
        LedgerManager
    private lateinit var cloudSyncManager:
        CloudSyncManager
    private lateinit var currentBook:
        LedgerBook

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        WindowCompat
            .setDecorFitsSystemWindows(
                window,
                true
            )
        window.statusBarColor =
            Color.WHITE
        WindowCompat
            .getInsetsController(
                window,
                window.decorView
            )
            .isAppearanceLightStatusBars =
            true

        ledgerManager =
            LedgerManager(
                applicationContext
            )

        currentBook =
            ledgerManager.currentBook()

        cloudSyncManager =
            CloudSyncManager(
                applicationContext,
                ledgerManager
            )

        db =
            AppDatabase(
                context =
                    applicationContext,
                dbFileName =
                    currentBook.databaseName,
                ledgerId =
                    currentBook.id,
                ledgerName =
                    currentBook.name,
                deviceId =
                    ledgerManager.deviceId,
                deviceName =
                    ledgerManager.deviceName
            )

        setContent {
            TianXianApp(
                db = db,
                ledgerManager =
                    ledgerManager,
                cloudSyncManager =
                    cloudSyncManager,
                currentBook =
                    currentBook,
                onSwitchBook = {
                    bookId ->
                    if (
                        bookId !=
                        currentBook.id &&
                        ledgerManager
                            .setCurrentBook(
                                bookId
                            )
                    ) {
                        recreate()
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            ::cloudSyncManager.isInitialized &&
            ::currentBook.isInitialized
        ) {
            cloudSyncManager
                .scheduleAutoSync(
                    currentBook
                )
        }
    }

    override fun onDestroy() {
        if (
            ::cloudSyncManager.isInitialized
        ) {
            cloudSyncManager.shutdown()
        }

        if (::db.isInitialized) {
            db.close()
        }

        super.onDestroy()
    }
}
