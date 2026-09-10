package com.tianxian.fruit.model

data class DailySession(
    val date:String,
    val store:String,
    val wechat:Double,
    val alipay:Double,
    val cash:Double,
    val expense:Double,
    val purchaseCost:Double,
    val customer:Int,
    val newCustomer:Int,
    val oldCustomer:Int
){
    fun revenue():Double = wechat + alipay + cash

    fun profit():Double =
        revenue() - purchaseCost - expense
}