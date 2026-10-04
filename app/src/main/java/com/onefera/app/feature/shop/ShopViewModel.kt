package com.onefera.app.feature.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.ProductFilter
import com.onefera.app.data.model.ProductSort
import com.onefera.app.data.shop.ShopRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class ShopUiState(
    val loading: Boolean = true,
    val drop: Product? = null,
    val trending: List<Product> = emptyList(),
    val topPicks: List<Product> = emptyList(),
    val results: List<Product> = emptyList(),
    val filter: ProductFilter = ProductFilter(),
    val wishlist: Set<String> = emptySet(),
    val cartCount: Int = 0,
)

@HiltViewModel
class ShopViewModel @Inject constructor(private val shop: ShopRepository) : ViewModel() {
    private val filter = MutableStateFlow(ProductFilter())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<ShopUiState> = combine(shop.catalogue(), filter, shop.wishlistIds(), shop.cart()) { products, f, wished, cart ->
        val inStock = products.filter { it.inStock }
        ShopUiState(
            loading = false,
            drop = dropOfTheDay(products),
            trending = inStock.sortedByDescending { it.soldCount }.take(10),
            topPicks = inStock.filter { it.ratingCount >= 20 }.sortedByDescending { it.rating }.take(10),
            results = f.apply(products),
            filter = f,
            wishlist = wished,
            cartCount = cart.sumOf { it.quantity },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShopUiState())

    fun onCategory(category: ProductCategory?) = filter.update { it.copy(category = if (it.category == category) null else category) }
    fun onSort(sort: ProductSort) = filter.update { it.copy(sort = sort) }
    fun onFilter(value: ProductFilter) = filter.update { value.copy(category = it.category, sort = it.sort) }
    fun clearFilters() = filter.update { ProductFilter(category = it.category, sort = it.sort) }

    fun toggleWish(product: Product) {
        val wished = product.id !in state.value.wishlist
        viewModelScope.launch {
            shop.setWishlisted(product, wished)
                .onSuccess { if (wished) _messages.send("Saved to your wishlist 💜") }
                .onFailure { _messages.send(it.message ?: "Couldn't update your wishlist.") }
        }
    }

    companion object {
        /** Drops rotate daily: today's pick is stable for everyone, based on the day of the year. */
        fun dropOfTheDay(products: List<Product>): Product? {
            val drops = products.filter { it.isDrop && it.inStock }.sortedBy { it.id }.ifEmpty { return products.firstOrNull { it.inStock } }
            val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            return drops[day % drops.size]
        }

        /** Millis until local midnight, when the next drop goes live. */
        fun millisUntilNextDrop(now: Long = System.currentTimeMillis()): Long {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return c.timeInMillis - now
        }
    }
}
