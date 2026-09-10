package com.tianxian.fruit.model

data class Purchase(
    val id:Int = 0,
    val date:String,
    val fruitName:String,
    val unit:String,
    val quantity:Double,
    val totalCost:Double
){
    fun unitPrice():Double {
        if(quantity == 0.0) return 0.0
        return totalCost / quantity
    }
}