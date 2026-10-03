package com.onefera.app.data.firebase

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import com.onefera.app.data.seller.ListingDraft
import com.onefera.app.data.seller.SellerRepository
import com.onefera.app.data.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seller mode on Firestore:
 * - listings are `products/{productId}` documents with `sellerId == uid` (rules check the account is in Seller mode)
 * - photos go to Cloud Storage under `products/{uid}/{productId}/`
 * - orders containing the seller's items are found through `orders.sellerIds`
 * - order status changes go through the `updateOrderStatus` Cloud Function, which also notifies the buyer
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreSellerRepository @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val media: MediaProcessor,
) : SellerRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance("asia-south1") }
    private val products get() = db.collection("products")

    override fun myListings(): Flow<List<Product>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            products.whereEqualTo("sellerId", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(200)
                .snapshotFlow().map { s -> s.documents.map { it.toProduct() } }
        }
    }

    override fun listing(productId: String): Flow<Product?> =
        products.document(productId).snapshotFlow().map { if (it.exists()) it.toProduct() else null }

    override suspend fun saveListing(draft: ListingDraft, onProgress: (Float) -> Unit): Result<Product> = runFriendly {
        val uid = auth.currentUid()
        draft.problem()?.let { throw UserFacingException(it) }
        val profile = users.observeProfile(uid).first() ?: throw UserFacingException("Finish setting up your profile first.")
        val ref = draft.id?.let { products.document(it) } ?: products.document()
        val uploaded = draft.newImages.mapIndexed { i, uri ->
            val prepared = media.prepareImage(uri, maxSide = 1200)
            try {
                val path = "products/$uid/${ref.id}/${System.currentTimeMillis()}_$i.jpg"
                val storageRef = storage.reference.child(path)
                storageRef.putFile(android.net.Uri.fromFile(prepared.file), StorageMetadata.Builder().setContentType("image/jpeg").build())
                    .addOnProgressListener { snap ->
                        val part = snap.bytesTransferred.toFloat() / snap.totalByteCount.coerceAtLeast(1)
                        onProgress(0.9f * (i + part) / draft.newImages.size)
                    }
                    .await()
                storageRef.downloadUrl.await().toString()
            } finally {
                media.cleanUp(prepared)
            }
        }
        val title = draft.title.trim()
        val brand = draft.brand.trim().ifEmpty { profile.displayName }
        val fields = mapOf(
            "title" to title,
            "brand" to brand,
            "description" to draft.description.trim(),
            "category" to draft.category.name,
            "price" to draft.price,
            "mrp" to (draft.mrp.takeIf { it >= draft.price } ?: draft.price),
            "images" to draft.images + uploaded,
            "highlights" to draft.highlights.map { it.trim() }.filter { it.isNotEmpty() }.take(6),
            "variants" to draft.variants.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12),
            "stock" to draft.stock,
            "sellerId" to uid,
            "seller" to mapOf(
                "uid" to uid,
                "displayName" to profile.displayName,
                "username" to profile.username,
                "avatarUrl" to profile.avatarUrl,
                "verified" to profile.verified,
                "isPrivate" to profile.isPrivate,
            ),
            "isDrop" to false,
            "keywords" to Product.keywordsFor("$title $brand ${draft.category.label}"),
        )
        if (draft.id == null) {
            ref.set(fields + mapOf("rating" to 0, "ratingCount" to 0, "soldCount" to 0, "createdAt" to FieldValue.serverTimestamp())).await()
        } else {
            ref.update(fields).await()
        }
        onProgress(1f)
        ref.get().await().toProduct()
    }

    override suspend fun deleteListing(productId: String): Result<Unit> = runFriendly {
        auth.currentUid()
        products.document(productId).delete().await()
        Unit
    }

    override suspend fun setStock(productId: String, stock: Int): Result<Unit> = runFriendly {
        auth.currentUid()
        if (stock !in 0..ListingDraft.MAX_STOCK) throw UserFacingException("Stock must be between 0 and ${ListingDraft.MAX_STOCK}.")
        products.document(productId).update("stock", stock).await()
        Unit
    }

    override fun sellerOrders(): Flow<List<Order>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            db.collection("orders").whereArrayContains("sellerIds", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .snapshotFlow().map { s -> s.documents.map { it.toOrder() }.filter { it.status != OrderStatus.PendingPayment } }
        }
    }

    override fun sellerOrder(orderId: String): Flow<Order?> =
        db.collection("orders").document(orderId).snapshotFlow().map { if (it.exists()) it.toOrder() else null }

    override suspend fun advanceOrder(orderId: String): Result<Unit> = runFriendly {
        auth.currentUid()
        try {
            functions.getHttpsCallable("updateOrderStatus").call(mapOf("orderId" to orderId)).await()
        } catch (e: FirebaseFunctionsException) {
            throw UserFacingException(e.message ?: "Couldn't update this order.", e)
        }
        Unit
    }
}
