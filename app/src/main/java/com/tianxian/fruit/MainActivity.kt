package com.tianxian.fruit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Dashboard()
            }
        }
    }
}

@Composable
fun Dashboard() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Text(
            "天鲜果业经营助手",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("📍 当前摊位")
                Text("未设置")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("今日营业额")
                Text("¥0")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("今日利润")
                Text("¥0")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("今日客户")
                Text("0人")
                Text("新客 0   老客 0")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text("🏆 排行榜")
        Text("营业额排行")
        Text("利润排行")
        Text("客户排行")
    }
}