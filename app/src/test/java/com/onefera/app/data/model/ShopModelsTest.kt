package com.onefera.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopModelsTest {
    private fun summary(id: String, price: Int, mrp: Int = price) = ProductSummary(id = id, title = id, price = price, mrp = mrp)

    @Test
    fun `small carts pay delivery, larger ones ship free`() {
        val small = CartTotals.of(listOf(CartItem(summary("a", 200, 300), quantity = 2)))
        assertEquals(400, small.subtotal)
        assertEquals(Product.DELIVERY_FEE, small.deliveryFee)
        assertEquals(400 + Product.DELIVERY_FEE, small.total)
        assertEquals(200, small.savings)

        val big = CartTotals.of(listOf(CartItem(summary("b", 499))))
        assertEquals(0, big.deliveryFee)
        assertEquals(0, CartTotals.of(emptyList()).total)
    }

    @Test
    fun `cart keys separate variants of the same product`() {
        assertEquals("p-1", CartItem.keyFor("p-1", ""))
        assertEquals("p-1__uk-8", CartItem.keyFor("p-1", "UK 8"))
        assertTrue(CartItem.keyFor("p-1", "M") != CartItem.keyFor("p-1", "L"))
    }

    @Test
    fun `filter and sort the catalogue`() {
        val products = listOf(
            Product(id = "cheap", price = 300, rating = 4.6f, stock = 5, soldCount = 10, category = ProductCategory.Beauty),
            Product(id = "mid", price = 1_500, rating = 3.9f, stock = 0, soldCount = 90, category = ProductCategory.Beauty),
            Product(id = "pricey", price = 60_000, rating = 4.8f, stock = 2, soldCount = 40, category = ProductCategory.Mobiles),
        )
        assertEquals(listOf("mid", "pricey", "cheap"), ProductFilter().apply(products).map { it.id })
        assertEquals(listOf("cheap", "mid"), ProductFilter(category = ProductCategory.Beauty, sort = ProductSort.PriceLowHigh).apply(products).map { it.id })
        assertEquals(listOf("cheap"), ProductFilter(maxPrice = 1_000).apply(products).map { it.id })
        assertEquals(listOf("pricey", "cheap"), ProductFilter(inStockOnly = true, sort = ProductSort.Rating).apply(products).map { it.id })
        assertEquals(2, ProductFilter(maxPrice = 500, minRating = 4f).activeCount)
    }

    @Test
    fun `search keywords are lower-case prefixes`() {
        val keys = Product.keywordsFor("Apple AirPods Max")
        assertTrue("ap" in keys)
        assertTrue("airpods" in keys)
        assertFalse("a" in keys)
        assertFalse("Apple" in keys)
    }

    @Test
    fun `discounts and rupee formatting`() {
        assertEquals(25, Product(price = 750, mrp = 1_000).discountPercent)
        assertEquals(0, Product(price = 750, mrp = 0).discountPercent)
        assertEquals("₹1,23,456", formatRupees(123_456))
        assertEquals("₹499", formatRupees(499))
    }

    @Test
    fun `addresses need the essentials`() {
        val ok = Address("Asha", "9876543210", "12 MG Road", "", "Pune", "MH", "411001")
        assertTrue(ok.isComplete)
        assertFalse(ok.copy(phone = "98765").isComplete)
        assertFalse(ok.copy(pincode = "4110").isComplete)
    }
}
