package com.onefera.app.feature.main

import androidx.annotation.DrawableRes
import com.onefera.app.R

enum class MainTab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Home("Home", R.drawable.ic_home, R.drawable.ic_home_filled),
    Shop("Shop", R.drawable.ic_shop, R.drawable.ic_shop_filled),
    Search("Search", R.drawable.ic_search, R.drawable.ic_search),
    You("You", R.drawable.ic_person, R.drawable.ic_person_filled),
    Chats("Chats", R.drawable.ic_chat, R.drawable.ic_chat_filled),
    Near("Near", R.drawable.ic_near, R.drawable.ic_near_filled),
    Reels("Reels", R.drawable.ic_reels, R.drawable.ic_reels_filled),
}
