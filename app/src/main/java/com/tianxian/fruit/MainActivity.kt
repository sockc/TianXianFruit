package com.tianxian.fruit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*

class MainActivity: ComponentActivity(){
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(20.dp)){
                    Text("天鲜果业经营助手")
                    Text("今日营业额 ¥0")
                    Text("今日利润 ¥0")
                    Text("客户 0")
                }
            }
        }
    }
}
