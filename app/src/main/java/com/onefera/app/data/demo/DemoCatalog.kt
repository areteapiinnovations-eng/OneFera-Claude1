package com.onefera.app.data.demo

import android.content.Context
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.toSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sample catalogue in `assets/catalog/products.json`. The same file seeds Firestore
 * (`firebase/scripts/seed-products.mjs`), so demo mode and a fresh Firebase project match.
 */
@Singleton
class DemoCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val products: List<Product> by lazy {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val sellers = DemoSeed.creators.associateBy { it.uid }
        val now = System.currentTimeMillis()
        json.decodeFromString(ListSerializer(Product.serializer()), text).mapIndexed { index, p ->
            p.copy(
                seller = sellers[p.sellerId]?.toSummary() ?: p.seller,
                // Spread "listed" times so the Newest sort has something to show.
                createdAt = now - index * 3 * 60 * 60 * 1000L,
            )
        }
    }

    val byId: Map<String, Product> by lazy { products.associateBy { it.id } }

    private companion object {
        const val ASSET = "catalog/products.json"
    }
}
